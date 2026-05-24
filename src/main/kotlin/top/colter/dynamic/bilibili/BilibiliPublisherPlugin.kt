package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.config.save
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.SubscribeRepository
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.logger
import kotlin.time.Duration.Companion.milliseconds

public class BilibiliPublisherPlugin() : PlatformPublisherPlugin {
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

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPlatformGateway
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var cursorStore: BilibiliCursorStore
    private lateinit var detectTask: TaskDefinition

    @Volatile
    private var subscriptions: Map<Publisher, List<Subscriber>> = emptyMap()

    @Volatile
    private var lastSubscriptionRefreshAt: Long = 0

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
            val started = startDetectionTask()
            logger.info {
                "pluginId=$pluginId action=start result=started loginAccount=${loginResult.account?.name ?: loginResult.account?.userId ?: "unknown"} taskStarted=$started"
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
            platform = platformId,
            userId = snapshot.userId,
            type = PublisherType.USER,
            name = snapshot.name,
            official = snapshot.official,
            state = 1,
            face = LazyImage(snapshot.faceUrl),
            header = snapshot.headerUrl?.let(::LazyImage),
            pendant = snapshot.pendantUrl?.let(::LazyImage),
        )
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        return pollService.queryFollowState(userId)
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        return pollService.followPublisher(userId)
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val result = pollService.loginByCookie(cookie)
        persistCookiesIfLoggedIn(result)
        if (result.status == PublisherLoginStatus.SUCCESS) {
            startDetectionTask()
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
            startDetectionTask()
        }
        return result
    }

    private suspend fun detectAndPublish() {
        refreshSubscriptions(force = false)
        if (subscriptions.isEmpty()) return

        val start = System.currentTimeMillis()
        val followedDynamics = runCatching { pollService.fetchSubscribedLatest(config.fetchLimit) }
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
            val uid = publisher.userId?.toLongOrNull() ?: return@publisherLoop
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
                    if (cursor.lastSeenDynamicId == dynamicId || cursor.recentDynamicIds.contains(dynamicId)) {
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

    private fun refreshSubscriptions(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSubscriptionRefreshAt < config.subscriptionRefreshIntervalMs) return

        subscriptions = SubscribeRepository.findActivePublishersWithSubscribersByPlatform(platformId)
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
}
