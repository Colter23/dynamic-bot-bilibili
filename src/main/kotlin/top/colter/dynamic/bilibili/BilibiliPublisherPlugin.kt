package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.config.save
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.hasSeen
import top.colter.dynamic.core.data.replayLowerBoundAtEpochSeconds
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.logger
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

public class BilibiliPublisherPlugin() : PlatformPublisherPlugin, DynamicLinkResolver {
    private val pluginId: String = "bilibili-publisher"
    private val detectTaskId: String = "bilibili-detect"

    override val platformId: String = "bilibili"

    private var loadConfig: (String) -> BilibiliPublisherConfig = { pluginId ->
        DefaultConfigService.loadOrCreate(pluginId) { BilibiliPublisherConfig() }
    }
    private var serviceFactory: (Long) -> BilibiliPlatformGateway = { requestIntervalMs ->
        BilibiliPollService(requestIntervalMs)
    }
    private var cursorStoreFactory: () -> BilibiliCursorStore = { DatabaseBilibiliCursorStore() }
    private var saveConfig: (String, BilibiliPublisherConfig) -> Unit = { id, config ->
        DefaultConfigService.save(id, config)
    }
    private var taskScheduler: TaskScheduler = TaskScheduler()

    private val followGroupMutex: Mutex = Mutex()

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPlatformGateway
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var cursorStore: BilibiliCursorStore
    private lateinit var detectTask: TaskDefinition

    @Volatile
    private var subscriptions: Map<Publisher, List<Subscriber>> = emptyMap()

    @Volatile
    private var lastSubscriptionRefreshAt: Long = 0

    @Volatile
    private var followGroupId: Long? = null

    internal constructor(
        loadConfig: (String) -> BilibiliPublisherConfig,
        serviceFactory: (Long) -> BilibiliPlatformGateway,
        cursorStoreFactory: () -> BilibiliCursorStore,
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler = TaskScheduler(),
    ) : this() {
        this.loadConfig = loadConfig
        this.serviceFactory = serviceFactory
        this.cursorStoreFactory = cursorStoreFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(
        PublisherLoginMethod.COOKIE,
        PublisherLoginMethod.QR_CODE,
    )

    override fun init() {
        config = loadConfig(pluginId)
        pollService = serviceFactory(config.requestIntervalMs)
        mapper = BilibiliDynamicMapper()
        cursorStore = cursorStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalMs.milliseconds, runImmediately = true),
            action = {
                detectAndPublish()
            },
        )

        importStoredCookies()
        refreshSubscriptions(force = true)
        logger.info {
            "pluginId=$pluginId action=init configPath=${DefaultConfigService.resolvePath(pluginId).toAbsolutePath()}"
        }
    }

    override fun start() {
        val loginResult = runBlocking { pollService.checkLoginState() }
        if (loginResult.status == PublisherLoginStatus.SUCCESS) {
            val taskStarted = runBlocking { bootstrapLoggedInState(allowReplay = true) }
            logger.info {
                "pluginId=$pluginId action=start result=started loginAccount=${loginResult.account?.name ?: loginResult.account?.userId ?: "unknown"} taskStarted=$taskStarted"
            }
        } else {
            logger.warn {
                "pluginId=$pluginId action=start result=skipped reason=${loginResult.status.name.lowercase()} message=${loginResult.message}"
            }
        }
    }

    override fun stop() {
        runBlocking {
            taskScheduler.stop(detectTaskId)
        }
        logger.info { "pluginId=$pluginId action=stop" }
    }

    override fun cleanup() {
        logger.info { "pluginId=$pluginId action=cleanup" }
    }

    override suspend fun fetchPublisherProfile(userId: String): PublisherProfile? {
        val snapshot = pollService.fetchPublisherProfile(userId) ?: return null
        return PublisherProfile(
            platformId = platformId,
            externalId = snapshot.userId,
            type = PublisherType.USER,
            name = snapshot.name,
            official = snapshot.official,
            state = EntityState.ACTIVE,
            face = LazyImage(snapshot.faceUrl),
            header = snapshot.headerUrl?.let(::LazyImage),
            pendant = snapshot.pendantUrl?.let(::LazyImage),
        )
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        return pollService.queryFollowState(userId)
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        val result = pollService.followPublisher(userId)
        if (result.status == FollowActionStatus.FOLLOWED || result.status == FollowActionStatus.ALREADY_FOLLOWING) {
            addPublisherToFollowGroup(userId)
        }
        return result
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return pollService.checkLoginState()
    }

    override suspend fun parseDynamicLink(inputUrl: String): ParsedDynamicLink? {
        val normalizedInput = inputUrl.trim().trimUrlPunctuation()
        if (normalizedInput.isBlank()) return null

        parseDirectDynamicLink(normalizedInput)?.let { return it }
        if (!isBilibiliShortUrl(normalizedInput)) return null

        val expanded = runCatching {
            pollService.expandShortUrl(normalizedInput, config.shortUrlResolveTimeoutMs)
        }.onFailure {
            logger.warn(it) {
                "pluginId=$pluginId action=expand_short_url result=failed url=$normalizedInput"
            }
        }.getOrNull() ?: return null

        return parseDirectDynamicLink(expanded)?.copy(sourceUrl = normalizedInput)
    }

    override suspend fun resolveDynamicLink(parsedLink: ParsedDynamicLink): DynamicLinkResolution {
        if (!parsedLink.platformId.equals(platformId, ignoreCase = true)) {
            return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "unsupported platform: ${parsedLink.platformId}",
            )
        }

        val source = runCatching { pollService.fetchDynamicDetail(parsedLink.dynamicId) }
            .getOrElse { error ->
                return DynamicLinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "failed to fetch Bilibili dynamic detail",
                    cause = error,
                )
            }
            ?: return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "Bilibili dynamic not found: ${parsedLink.dynamicId}",
            )

        val dynamic = mapper.map(source, fallbackPublisher())
            ?: return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "failed to map Bilibili dynamic: ${parsedLink.dynamicId}",
            )

        return DynamicLinkResolution.Success(parsedLink, dynamic)
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val result = pollService.loginByCookie(cookie)
        persistCookiesIfLoggedIn(result)
        if (result.status == PublisherLoginStatus.SUCCESS) {
            bootstrapLoggedInState(allowReplay = !taskScheduler.isRunning(detectTaskId))
        }
        return result
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        val result = pollService.loginByQrCode(onQrCode, onStatusChanged)
        persistCookiesIfLoggedIn(result)
        if (result.status == PublisherLoginStatus.SUCCESS) {
            bootstrapLoggedInState(allowReplay = !taskScheduler.isRunning(detectTaskId))
        }
        return result
    }

    private suspend fun detectAndPublish() {
        refreshSubscriptions(force = false)
        if (subscriptions.isEmpty()) return

        val start = System.currentTimeMillis()
        val followedDynamics = runCatching { pollService.fetchNewDynamicPage(1).items.take(config.fetchLimit.coerceAtLeast(0)) }
            .onFailure {
                logger.error(it) {
                    "pluginId=$pluginId action=poll result=failed"
                }
            }
            .getOrElse { emptyList() }
        val latency = System.currentTimeMillis() - start
        val dynamicsByPublisher = followedDynamics
            .groupBy { it.mid }
            .mapValues { (_, dynamics) ->
                dynamics.sortedWith(compareByDescending<BiliDynamic> { it.time }.thenByDescending { it.id })
            }

        subscriptions.forEach publisherLoop@{ (publisher, subscribers) ->
            val publisherId = publisher.id.toString()
            val uid = publisher.externalId.toLongOrNull() ?: return@publisherLoop
            val publisherDynamics = dynamicsByPublisher[uid].orEmpty()
            if (publisherDynamics.isEmpty()) return@publisherLoop

            val initialCursor = cursorStore.get(publisherId)
            if (initialCursor == null) {
                val latestDynamic = publisherDynamics.first()
                cursorStore.markSeen(publisherId, latestDynamic.id.toString(), latestDynamic.time)
                logger.info {
                    "pluginId=$pluginId publisherId=$publisherId action=bootstrap result=initialized latencyMs=$latency"
                }
                return@publisherLoop
            }

            var cursor: PublisherCursor = initialCursor
            publisherDynamics
                .asReversed()
                .forEach dynamicLoop@{ raw ->
                    val dynamicId = raw.id.toString()
                    if (cursor.hasSeen(dynamicId)) {
                        return@dynamicLoop
                    }

                    val dynamic = mapper.map(raw, publisher) ?: return@dynamicLoop
                    subscribers.forEach { subscriber ->
                        DynamicEvent(source = pluginId, target = subscriber, dynamic = dynamic).broadcast()
                    }
                    cursor = cursorStore.markSeen(publisherId, dynamicId, raw.time)
                }
        }
    }

    private suspend fun bootstrapLoggedInState(allowReplay: Boolean): Boolean {
        refreshSubscriptions(force = true)
        runCatching { ensureFollowGroupInitialized() }
            .onFailure {
                logger.warn(it) {
                    "pluginId=$pluginId action=follow_group_init result=failed"
                }
            }
        if (config.replayWindowHours > 0 && allowReplay) {
            runCatching { replayMissingDynamics() }
                .onFailure {
                    logger.warn(it) {
                        "pluginId=$pluginId action=replay result=failed"
                    }
                }
        } else if (!taskScheduler.isRunning(detectTaskId)) {
            runCatching { warmUpExistingCursors() }
                .onFailure {
                    logger.warn(it) {
                        "pluginId=$pluginId action=bootstrap_warmup result=failed"
                    }
                }
        }
        return startDetectionTask()
    }

    private suspend fun warmUpExistingCursors() {
        if (subscriptions.isEmpty()) return

        val followedDynamics = runCatching { pollService.fetchNewDynamicPage(1).items.take(config.fetchLimit.coerceAtLeast(0)) }
            .onFailure {
                logger.error(it) {
                    "pluginId=$pluginId action=bootstrap_warmup_poll result=failed"
                }
            }
            .getOrElse { emptyList() }
        if (followedDynamics.isEmpty()) return

        val dynamicsByPublisher = followedDynamics
            .groupBy { it.mid }
            .mapValues { (_, dynamics) ->
                dynamics.sortedWith(compareByDescending<BiliDynamic> { it.time }.thenByDescending { it.id })
            }

        subscriptions.forEach publisherLoop@{ (publisher, _) ->
            val publisherId = publisher.id.toString()
            val uid = publisher.externalId.toLongOrNull() ?: return@publisherLoop
            if (cursorStore.get(publisherId) == null) return@publisherLoop

            val publisherDynamics = dynamicsByPublisher[uid].orEmpty()
            if (publisherDynamics.isEmpty()) return@publisherLoop

            var cursor = cursorStore.get(publisherId) ?: return@publisherLoop
            publisherDynamics
                .asReversed()
                .forEach dynamicLoop@{ raw ->
                    val dynamicId = raw.id.toString()
                    if (cursor.hasSeen(dynamicId)) {
                        return@dynamicLoop
                    }
                    cursor = cursorStore.markSeen(publisherId, dynamicId, raw.time)
                }
        }
    }

    private suspend fun replayMissingDynamics() {
        if (config.replayWindowHours <= 0) return
        if (subscriptions.isEmpty()) return

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val targets = subscriptions.mapNotNull { (publisher, subscribers) ->
            val userId = publisher.externalId.toLongOrNull() ?: return@mapNotNull null
            val cursor = cursorStore.get(publisher.id.toString()) ?: return@mapNotNull null
            val lowerBound = cursor.replayLowerBoundAtEpochSeconds(config.replayWindowHours, nowEpochSeconds) ?: return@mapNotNull null
            ReplayTarget(
                publisher = publisher,
                userId = userId,
                subscribers = subscribers,
                cursor = cursor,
                lowerBound = lowerBound,
            )
        }
        if (targets.isEmpty()) return

        val globalLowerBound = targets.minOf { it.lowerBound }
        val collectedDynamics = mutableListOf<BiliDynamic>()

        var page = 1
        while (true) {
            val pageResult = pollService.fetchNewDynamicPage(page)
            val items = pageResult.items
            if (items.isEmpty()) break

            collectedDynamics.addAll(items.filter { it.time >= globalLowerBound })

            val oldestTime = items.minOf { it.time }
            if (!pageResult.hasMore || oldestTime < globalLowerBound) {
                break
            }
            page += 1
        }

        if (collectedDynamics.isEmpty()) return

        val dynamicsByPublisher = collectedDynamics
            .groupBy { it.mid }
            .mapValues { (_, dynamics) ->
                dynamics.sortedWith(compareBy<BiliDynamic> { it.time }.thenBy { it.id })
            }

        targets.forEach { target ->
            var cursor = target.cursor
            dynamicsByPublisher[target.userId].orEmpty().forEach dynamicLoop@{ raw ->
                if (raw.time < target.lowerBound) return@dynamicLoop
                val dynamicId = raw.id.toString()
                if (cursor.hasSeen(dynamicId)) return@dynamicLoop

                val dynamic = mapper.map(raw, target.publisher) ?: return@dynamicLoop
                target.subscribers.forEach { subscriber ->
                    DynamicEvent(source = pluginId, target = subscriber, dynamic = dynamic).broadcast()
                }
                cursor = cursorStore.markSeen(target.publisher.id.toString(), dynamicId, raw.time)
            }
        }
    }

    private suspend fun ensureFollowGroupInitialized() {
        ensureFollowGroupId()
    }

    private suspend fun addPublisherToFollowGroup(userId: String) {
        val groupId = ensureFollowGroupId() ?: return
        val uid = userId.toLongOrNull() ?: return
        runCatching {
            pollService.addUsersToFollowGroup(listOf(uid), listOf(groupId))
        }.onFailure {
            logger.warn(it) {
                "pluginId=$pluginId action=follow_group_add result=failed userId=$userId groupId=$groupId"
            }
        }
    }

    private suspend fun ensureFollowGroupId(): Long? {
        val groupName = normalizedFollowGroupName() ?: return null
        followGroupId?.let { return it }

        return followGroupMutex.withLock {
            followGroupId?.let { return@withLock it }
            val resolved = resolveFollowGroupId(groupName)
            followGroupId = resolved
            resolved
        }
    }

    private suspend fun resolveFollowGroupId(groupName: String): Long? {
        val existingGroups = runCatching {
            pollService.fetchFollowGroups()
        }.onFailure {
            logger.warn(it) {
                "pluginId=$pluginId action=follow_group_fetch result=failed groupName=$groupName"
            }
        }.getOrNull() ?: return null

        existingGroups.firstOrNull { it.name == groupName }?.let { matched ->
            logger.info {
                "pluginId=$pluginId action=follow_group_resolve result=found groupName=$groupName groupId=${matched.tid}"
            }
            return matched.tid
        }

        runCatching {
            pollService.createFollowGroup(groupName)
        }.onFailure {
            logger.warn(it) {
                "pluginId=$pluginId action=follow_group_create result=failed groupName=$groupName"
            }
        }

        val refreshedGroups = runCatching {
            pollService.fetchFollowGroups()
        }.onFailure {
            logger.warn(it) {
                "pluginId=$pluginId action=follow_group_reload result=failed groupName=$groupName"
            }
        }.getOrNull() ?: return null

        val createdGroup = refreshedGroups.firstOrNull { it.name == groupName }
        if (createdGroup == null) {
            logger.warn {
                "pluginId=$pluginId action=follow_group_resolve result=missing groupName=$groupName"
            }
            return null
        }

        logger.info {
            "pluginId=$pluginId action=follow_group_resolve result=created groupName=$groupName groupId=${createdGroup.tid}"
        }
        return createdGroup.tid
    }

    private fun normalizedFollowGroupName(): String? {
        return config.followGroupName.trim().takeIf { it.isNotBlank() }
    }

    private fun refreshSubscriptions(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSubscriptionRefreshAt < config.subscriptionRefreshIntervalMs) return

        subscriptions = SubscriptionRepository.findActivePublishersWithSubscribersBySourcePlatform(platformId)
        lastSubscriptionRefreshAt = now
        logger.info {
            "pluginId=$pluginId action=refresh_subscription publisherSize=${subscriptions.size}"
        }
    }

    private fun importStoredCookies() {
        if (config.cookiesJson.isBlank()) return
        runCatching {
            runBlocking {
                pollService.importCookiesJson(config.cookiesJson)
            }
        }.onFailure {
            logger.warn(it) { "pluginId=$pluginId action=import_stored_cookies result=failed" }
        }
    }

    private fun persistCookiesIfLoggedIn(result: PublisherLoginResult) {
        if (result.status != PublisherLoginStatus.SUCCESS) return
        config = config.copy(cookiesJson = pollService.exportCookiesJson())
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "pluginId=$pluginId action=save_config result=failed" }
        }
    }

    private fun startDetectionTask(): Boolean {
        val started = taskScheduler.start(detectTask)
        logger.info { "pluginId=$pluginId action=start_detection_task started=$started" }
        return started
    }

    private fun parseDirectDynamicLink(inputUrl: String): ParsedDynamicLink? {
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        val pathSegments = uri.path
            ?.split("/")
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val dynamicId = when (host) {
            "t.bilibili.com" -> pathSegments.firstOrNull()
            "www.bilibili.com",
            "m.bilibili.com" -> when (pathSegments.firstOrNull()) {
                "opus" -> pathSegments.getOrNull(1)
                "dynamic" -> pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }?.takeIf { it.all { char -> char.isDigit() } }
            ?: return null

        return ParsedDynamicLink(
            platformId = platformId,
            dynamicId = dynamicId,
            normalizedUrl = dynamicLink(dynamicId),
            sourceUrl = inputUrl,
        )
    }

    private fun isBilibiliShortUrl(inputUrl: String): Boolean {
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        return host == "b23.tv" || host.endsWith(".b23.tv")
    }

    private fun String.trimUrlPunctuation(): String {
        return trim().trimEnd(
            '.',
            ',',
            ';',
            ':',
            '!',
            '?',
            ')',
            ']',
            '}',
            '>',
            '。',
            '，',
            '；',
            '：',
            '！',
            '？',
            '）',
            '】',
            '》',
        )
    }

    private fun dynamicLink(dynamicId: String): String {
        return "$BILIBILI_DYNAMIC_HOME/$dynamicId"
    }

    private fun fallbackPublisher(): Publisher {
        return Publisher(
            id = 0,
            platformId = platformId,
            type = PublisherType.USER,
            externalId = "",
            name = "",
            face = LazyImage(""),
            createTime = 0,
            createUser = 0,
        )
    }

    private data class ReplayTarget(
        val publisher: Publisher,
        val userId: Long,
        val subscribers: List<Subscriber>,
        val cursor: PublisherCursor,
        val lowerBound: Long,
    )

    private companion object {
        private const val BILIBILI_DYNAMIC_HOME: String = "https://t.bilibili.com"
    }
}
