package top.colter.dynamic.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PlatformCapability
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.data.hasSeen
import top.colter.dynamic.core.data.replayLowerBoundAtEpochSeconds
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.link.DynamicLinkResolver
import top.colter.dynamic.core.link.ParsedDynamicLink
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

private val logger = loggerFor<BilibiliPublisherPlugin>()

public class BilibiliPublisherPlugin() :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherFollowPlugin,
    PublisherLoginProvider,
    DynamicLinkResolver,
    ConfigurablePlugin<BilibiliPublisherConfig> {
    private var pluginId: String = "bilibili-publisher"
    private val detectTaskId: String = "bilibili-detect"

    override val platformId: PlatformId = PlatformId.of("bilibili")

    override val configId: String
        get() = pluginId
    override val configName: String = "Bilibili 动态源"
    override val configDescription: String = "Bilibili 轮询与登录配置。"
    override val configClass = BilibiliPublisherConfig::class
    override val configFormSpec = BilibiliPublisherConfigForm.spec

    private var loadConfig: (String) -> BilibiliPublisherConfig = { pluginId ->
        error("插件配置服务尚未初始化：$pluginId")
    }
    private var useContextConfigService: Boolean = true
    private var serviceFactory: (Long) -> BilibiliPlatformGateway = { requestIntervalMs ->
        BilibiliPollService(requestIntervalMs)
    }
    private var cursorStoreFactory: () -> BilibiliCursorStore = {
        error("Bilibili 游标存储尚未初始化")
    }
    private var liveStatusStoreFactory: () -> BilibiliLiveStatusStore = {
        error("Bilibili 直播状态存储尚未初始化")
    }
    private var saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> }
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var sourceUpdatePublisher: SourceUpdatePublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private var useContextTaskScheduler: Boolean = true
    private var useContextStateStores: Boolean = true

    private val followGroupMutex: Mutex = Mutex()
    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPlatformGateway
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var cursorStore: BilibiliCursorStore
    private lateinit var liveStatusStore: BilibiliLiveStatusStore
    private lateinit var detectTask: TaskDefinition

    @Volatile
    private var dynamicPublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var livePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var followGroupId: Long? = null

    @Volatile
    private var pendingDetection: Boolean = false

    internal constructor(
        loadConfig: (String) -> BilibiliPublisherConfig,
        serviceFactory: (Long) -> BilibiliPlatformGateway,
        cursorStoreFactory: () -> BilibiliCursorStore,
        liveStatusStoreFactory: () -> BilibiliLiveStatusStore,
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
    ) : this() {
        this.loadConfig = loadConfig
        this.useContextConfigService = false
        this.serviceFactory = serviceFactory
        this.cursorStoreFactory = cursorStoreFactory
        this.liveStatusStoreFactory = liveStatusStoreFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        this.useContextTaskScheduler = false
        this.useContextStateStores = false
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(
        PublisherLoginMethod.COOKIE,
        PublisherLoginMethod.QR_CODE,
    )

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        sourceUpdatePublisher = context.sourceUpdatePublisher
        subscriptionQueryService = context.subscriptionQueryService
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextStateStores) {
            cursorStoreFactory = { SourceStateBilibiliCursorStore(context.sourceStateStore) }
            liveStatusStoreFactory = { SourceStateBilibiliLiveStatusStore(context.sourceStateStore) }
        }
        if (useContextConfigService) {
            loadConfig = { id -> context.configService.loadOrCreate(id) { BilibiliPublisherConfig() } }
            saveConfig = { id, next -> context.configService.save(id, next) }
        }
        config = loadConfig(pluginId)
        pollService = serviceFactory(config.requestIntervalMs)
        mapper = BilibiliDynamicMapper()
        cursorStore = cursorStoreFactory()
        liveStatusStore = liveStatusStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalMs.milliseconds, runImmediately = true),
            action = {
                detectAndPublish()
            },
        )

        importStoredCookies()
        loadActivePublishers()
        logger.info { "Bilibili 配置已加载：pluginId=$pluginId" }
    }

    override suspend fun onStart() {
        val loginResult = pollService.checkLoginState()
        if (loginResult.status == PublisherLoginStatus.SUCCESS) {
            val taskStarted = bootstrapLoggedInState(allowReplay = true)
            logger.info {
                "Bilibili 轮询已就绪：账号=${loginResult.account?.name ?: loginResult.account?.userId ?: "未知"}，任务新启动=$taskStarted"
            }
        } else {
            logger.warn {
                "Bilibili 轮询未启动：登录状态=${loginResult.status}，原因=${loginResult.message}"
            }
        }
    }

    override suspend fun onStop() {
        taskScheduler.stop(detectTaskId)
        logger.info { "Bilibili 轮询已停止" }
    }

    override fun currentConfig(): BilibiliPublisherConfig {
        return if (::config.isInitialized) config else loadConfig(pluginId)
    }

    override fun applyConfig(next: BilibiliPublisherConfig): ConfigApplyResult {
        BilibiliPublisherConfigForm.validate(next)
        val previous = currentConfig()
        val changed = previous != next
        if (!changed) {
            return ConfigApplyResult(changed = false, message = "Bilibili 配置未变化")
        }

        config = next
        if (previous.followGroupName != next.followGroupName) {
            followGroupId = null
        }

        val restartTargets = if (
            previous.pollingIntervalMs != next.pollingIntervalMs ||
            previous.requestIntervalMs != next.requestIntervalMs ||
            previous.cookiesJson != next.cookiesJson
        ) {
            listOf("Bilibili 插件")
        } else {
            emptyList()
        }
        return ConfigApplyResult(
            changed = true,
            restartRequired = restartTargets.isNotEmpty(),
            restartTargets = restartTargets,
            message = if (restartTargets.isEmpty()) {
                "Bilibili 配置已保存并生效"
            } else {
                "Bilibili 配置已保存；需要重启 Bilibili 插件以重建轮询服务"
            },
        )
    }

    override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
        val snapshot = pollService.fetchPublisherSnapshot(userId) ?: return null
        return PublisherInfo(
            key = PublisherKey.of(platformId.value, PublisherKind.USER, snapshot.userId),
            name = snapshot.name,
            official = snapshot.official,
            state = EntityState.ACTIVE,
            avatar = MediaRef(snapshot.faceUrl, MediaKind.AVATAR),
            banner = snapshot.headerUrl?.let { MediaRef(it, MediaKind.COVER) },
            pendant = snapshot.pendantUrl?.let { MediaRef(it, MediaKind.AVATAR) },
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

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val groupId = findExistingFollowGroupId()
            ?: return skipAutoUnfollow(userId, "未配置或未找到 Bot 关注分组")

        val relation = runCatching {
            pollService.fetchFollowRelation(userId)
        }.onFailure {
            logger.warn(it) {
                "Bilibili 自动取消关注关系查询失败：uid=$userId"
            }
        }.getOrNull() ?: return skipAutoUnfollow(userId, "关注关系查询失败")

        if (!relation.following) {
            return skipAutoUnfollow(userId, "当前账号未关注该 UP 主")
        }
        if (relation.tagIds != setOf(groupId)) {
            return skipAutoUnfollow(userId, "UP 主不只属于 Bot 关注分组：tagIds=${relation.tagIds}，botGroupId=$groupId")
        }

        val result = runCatching {
            pollService.unfollowPublisher(userId)
        }.onFailure {
            logger.warn(it) {
                "Bilibili 自动取消关注失败：uid=$userId，groupId=$groupId"
            }
        }.getOrElse { error ->
            return FollowActionResult(
                FollowActionStatus.FAILED,
                error.message ?: "Bilibili 自动取消关注失败",
            )
        }

        if (result.status == FollowActionStatus.FOLLOWED) {
            logger.info {
                "Bilibili 自动取消关注完成：uid=$userId，groupId=$groupId"
            }
        } else {
            logger.warn {
                "Bilibili 自动取消关注未完成：uid=$userId，status=${result.status}，message=${result.message}"
            }
        }
        return result
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return pollService.checkLoginState()
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return

        when (event.changeType) {
            SubscriptionChangeType.SUBSCRIBED -> handleSubscribed(event)
            SubscriptionChangeType.UNSUBSCRIBED -> handleUnsubscribed(event)
        }
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
                "Bilibili 短链解析失败：url=$normalizedInput"
            }
        }.getOrNull() ?: return null

        return parseDirectDynamicLink(expanded)?.copy(sourceUrl = normalizedInput)
    }

    override suspend fun resolveDynamicLink(parsedLink: ParsedDynamicLink): DynamicLinkResolution {
        if (parsedLink.platformId != platformId) {
            return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "不支持的平台：${parsedLink.platformId.value}",
            )
        }

        val source = runCatching { pollService.fetchDynamicDetail(parsedLink.updateId) }
            .getOrElse { error ->
                return DynamicLinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "获取 Bilibili 动态详情失败",
                    cause = error,
                )
            }
            ?: return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "未找到 Bilibili 动态：${parsedLink.updateId}",
            )

        val dynamic = mapper.map(source, fallbackPublisher())
            ?: return DynamicLinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "Bilibili 动态映射失败：${parsedLink.updateId}",
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
        if (!detectMutex.tryLock()) {
            pendingDetection = true
            logger.debug { "Bilibili 检测仍在执行，本轮已标记为补跑" }
            return
        }

        try {
            do {
                pendingDetection = false
                detectAndPublishLocked()
            } while (pendingDetection)
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun detectAndPublishLocked() {
        loadActivePublishers(logSummary = false)
        val dynamicPublisherSnapshot = dynamicPublishers
        val livePublisherSnapshot = livePublishers
        if (dynamicPublisherSnapshot.isEmpty() && livePublisherSnapshot.isEmpty()) {
            logger.debug { "Bilibili 检测跳过：没有活跃订阅发布者" }
            return
        }

        if (dynamicPublisherSnapshot.isNotEmpty()) {
            val start = System.currentTimeMillis()
            val followedDynamics = runCatching {
                pollService.fetchNewDynamicPage(1).items.take(config.fetchLimit.coerceAtLeast(0))
            }
                .onFailure {
                    logger.warn(it) {
                        "Bilibili 动态轮询失败"
                    }
                }
                .getOrElse { emptyList() }
            val latency = System.currentTimeMillis() - start
            val dynamicsByPublisher = followedDynamics
                .groupBy { it.mid }
                .mapValues { (_, dynamics) ->
                    dynamics.sortedWith(compareByDescending<BiliDynamic> { it.time }.thenByDescending { it.id })
                }

            dynamicPublisherSnapshot.values.forEach publisherLoop@{ publisher ->
                val publisherId = publisher.id
                val uid = publisher.externalId.toLongOrNull() ?: return@publisherLoop
                val publisherDynamics = dynamicsByPublisher[uid].orEmpty()
                if (publisherDynamics.isEmpty()) return@publisherLoop

                val initialCursor = cursorStore.get(publisherId)
                if (initialCursor == null) {
                    val latestDynamic = publisherDynamics.first()
                    cursorStore.markSeen(publisherId, latestDynamic.id.toString(), latestDynamic.time)
                    logger.debug {
                        "Bilibili 动态游标已初始化：publisherId=$publisherId，latencyMs=$latency"
                    }
                    return@publisherLoop
                }

                var cursor: SourceCursor = initialCursor
                publisherDynamics
                    .asReversed()
                    .forEach dynamicLoop@{ raw ->
                        val dynamicId = raw.id.toString()
                        if (raw.time <= cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) {
                            return@dynamicLoop
                        }

                        val dynamic = mapper.map(raw, publisher) ?: return@dynamicLoop
                        logger.info {
                            "Bilibili 检测到新动态：publisher=${publisher.displayLabel()}，uid=$uid，dynamicId=$dynamicId，time=${raw.time}"
                        }
                        if (publishSourceUpdate(dynamic)) {
                            cursor = cursorStore.markSeen(publisherId, dynamicId, raw.time)
                        }
                    }
            }
        }
        detectLiveStatusChanges(livePublisherSnapshot)
    }

    private suspend fun bootstrapLoggedInState(allowReplay: Boolean): Boolean {
        loadActivePublishers()
        runCatching { ensureFollowGroupInitialized() }
            .onFailure {
                logger.warn(it) {
                    "Bilibili 关注分组初始化失败"
                }
            }
        if (config.replayWindowHours > 0 && allowReplay) {
            runCatching { replayMissingDynamics() }
                .onFailure {
                    logger.warn {
                        "Bilibili 历史动态补发未完成：${it.message ?: "未知错误"}"
                    }
                    logger.debug(it) {
                        "Bilibili 历史动态补发异常详情"
                    }
                }
        } else if (!taskScheduler.isRunning(detectTaskId)) {
            runCatching { warmUpExistingCursors() }
                .onFailure {
                    logger.warn(it) {
                        "Bilibili 动态游标预热失败"
                    }
                }
        }
        runCatching { warmUpLiveStatuses() }
            .onFailure {
                logger.warn(it) {
                    "Bilibili 直播状态预热失败"
                }
            }
        return startDetectionTask()
    }

    private suspend fun warmUpExistingCursors() {
        val publisherSnapshot = dynamicPublishers
        if (publisherSnapshot.isEmpty()) return

        val followedDynamics = runCatching { pollService.fetchNewDynamicPage(1).items.take(config.fetchLimit.coerceAtLeast(0)) }
            .onFailure {
                logger.warn(it) {
                    "Bilibili 动态游标预热拉取失败"
                }
            }
            .getOrElse { emptyList() }
        if (followedDynamics.isEmpty()) return

        val dynamicsByPublisher = followedDynamics
            .groupBy { it.mid }
            .mapValues { (_, dynamics) ->
                dynamics.sortedWith(compareByDescending<BiliDynamic> { it.time }.thenByDescending { it.id })
            }

        publisherSnapshot.values.forEach publisherLoop@{ publisher ->
            val publisherId = publisher.id
            val uid = publisher.externalId.toLongOrNull() ?: return@publisherLoop
            if (cursorStore.get(publisherId) == null) return@publisherLoop

            val publisherDynamics = dynamicsByPublisher[uid].orEmpty()
            if (publisherDynamics.isEmpty()) return@publisherLoop

            var cursor = cursorStore.get(publisherId) ?: return@publisherLoop
            publisherDynamics
                .asReversed()
                .forEach dynamicLoop@{ raw ->
                    val dynamicId = raw.id.toString()
                    if (raw.time <= cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) {
                        return@dynamicLoop
                    }
                    cursor = cursorStore.markSeen(publisherId, dynamicId, raw.time)
                }
        }
    }

    private suspend fun warmUpLiveStatuses() {
        if (!config.liveDetectionEnabled) return
        val publisherSnapshot = livePublishers
        if (publisherSnapshot.isEmpty()) return

        val now = System.currentTimeMillis() / 1000
        val liveFetch = fetchLiveSnapshotsByUid(publisherSnapshot.values)
        publisherSnapshot.values.forEach { publisher ->
            val uid = publisher.externalId.toLongOrNull() ?: return@forEach
            if (uid in liveFetch.unavailableUids) return@forEach
            val snapshot = liveFetch.snapshotsByUid[uid] ?: return@forEach
            val previous = liveStatusStore.get(publisher.id)
            val current = buildLiveState(
                publisher = publisher,
                snapshot = snapshot,
                previous = previous,
                observedAt = now,
            )
            liveStatusStore.save(current)
        }
    }

    private suspend fun detectLiveStatusChanges(publisherSnapshot: Map<Int, Publisher>) {
        if (!config.liveDetectionEnabled || publisherSnapshot.isEmpty()) return

        val now = System.currentTimeMillis() / 1000
        val liveFetch = fetchLiveSnapshotsByUid(publisherSnapshot.values)
        publisherSnapshot.values.forEach { publisher ->
            val uid = publisher.externalId.toLongOrNull() ?: return@forEach
            if (uid in liveFetch.unavailableUids) return@forEach
            val snapshot = liveFetch.snapshotsByUid[uid] ?: return@forEach
            val previous = liveStatusStore.get(publisher.id)
            val current = buildLiveState(
                publisher = publisher,
                snapshot = snapshot,
                previous = previous,
                observedAt = now,
            )
            val update = buildLiveUpdate(publisher, previous, current, now)
            if (update != null) {
                logger.info {
                    "Bilibili 检测到直播状态变化：publisher=${publisher.displayLabel()}，event=${update.eventType.value}，roomId=${current.roomId}"
                }
            }
            if (update == null || publishSourceUpdate(update)) {
                liveStatusStore.save(current)
            }
        }
    }

    private suspend fun fetchLiveSnapshotsByUid(publishers: Collection<Publisher>): LiveSnapshotFetchResult {
        val uids = publishers
            .mapNotNull { it.externalId.toLongOrNull() }
            .distinct()
        if (uids.isEmpty()) return LiveSnapshotFetchResult()

        val batchSize = config.liveStatusBatchSize.coerceAtLeast(1)
        val result = linkedMapOf<Long, BilibiliLiveSnapshot>()
        val unavailable = linkedSetOf<Long>()
        uids.chunked(batchSize).forEach { batch ->
            val snapshots = runCatching {
                pollService.fetchLiveStatusBatch(batch)
            }.onFailure {
                logger.warn(it) {
                    "Bilibili 直播状态拉取失败：uids=$batch"
                }
                unavailable.addAll(batch)
            }.getOrNull() ?: return@forEach
            val returnedUids = linkedSetOf<Long>()
            val requestedUids = batch.toSet()
            snapshots.forEach { snapshot ->
                val uid = snapshot.userId.toLongOrNull() ?: return@forEach
                if (uid !in requestedUids) return@forEach
                result[uid] = snapshot
                returnedUids.add(uid)
            }
            val missingUids = batch.filterNot { it in returnedUids }
            if (missingUids.isNotEmpty()) {
                unavailable.addAll(missingUids)
                logger.debug {
                    "Bilibili 直播状态返回缺失，已保留旧状态：uids=$missingUids"
                }
            }
        }
        return LiveSnapshotFetchResult(
            snapshotsByUid = result,
            unavailableUids = unavailable,
        )
    }

    private fun buildLiveState(
        publisher: Publisher,
        snapshot: BilibiliLiveSnapshot?,
        previous: PublisherLiveStatus?,
        observedAt: Long,
    ): PublisherLiveStatus {
        val status = snapshot?.status ?: LiveStatus.CLOSE
        val roomId = snapshot?.roomId?.takeIf { it.isNotBlank() }
            ?: previous?.roomId
            ?: ""
        val title = snapshot?.title?.takeIf { it.isNotBlank() }
            ?: previous?.title
            ?: publisher.name
        val cover = snapshot?.coverUrl?.let { MediaRef(it, MediaKind.COVER) } ?: previous?.cover
        val area = snapshot?.area?.takeIf { it.isNotBlank() } ?: previous?.area
        val startedAt = if (status == LiveStatus.OPEN) {
            snapshot?.startedAtEpochSeconds
                ?: previous?.takeIf { it.status == LiveStatus.OPEN }?.startedAtEpochSeconds
                ?: observedAt
        } else {
            previous?.startedAtEpochSeconds ?: snapshot?.startedAtEpochSeconds
        }

        return PublisherLiveStatus(
            publisherId = publisher.id,
            roomId = roomId,
            status = status,
            title = title,
            cover = cover,
            area = area,
            startedAtEpochSeconds = startedAt,
            lastObservedAtEpochSeconds = observedAt,
        )
    }

    private fun buildLiveUpdate(
        publisher: Publisher,
        previous: PublisherLiveStatus?,
        current: PublisherLiveStatus,
        observedAt: Long,
    ): SourceUpdate? {
        if (previous == null) return null

        val previousOpen = previous.status == LiveStatus.OPEN
        val currentOpen = current.status == LiveStatus.OPEN
        if (previousOpen == currentOpen) return null

        val eventType = if (currentOpen) SourceEventType.LIVE_STARTED else SourceEventType.LIVE_ENDED
        val startedAt = if (eventType == SourceEventType.LIVE_STARTED) {
            current.startedAtEpochSeconds ?: observedAt
        } else {
            previous.startedAtEpochSeconds ?: current.startedAtEpochSeconds
        }
        val endedAt = if (eventType == SourceEventType.LIVE_ENDED) observedAt else null
        val eventTime = when (eventType) {
            SourceEventType.LIVE_STARTED -> startedAt ?: observedAt
            SourceEventType.LIVE_ENDED -> endedAt ?: observedAt
            else -> observedAt
        }
        val roomId = current.roomId.ifBlank { previous.roomId }
        val title = current.title.ifBlank { previous.title }

        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = eventType,
                externalId = "$roomId:$eventTime",
            ),
            publisher = publisher.toInfo(),
            occurredAtEpochSeconds = eventTime,
            observedAtEpochSeconds = observedAt,
            link = liveLink(roomId),
            payload = LivePayload(
                roomId = roomId,
                title = title,
                area = current.area ?: previous.area,
                cover = current.cover ?: previous.cover,
                status = current.status,
                previousStatus = previous.status,
                startedAtEpochSeconds = startedAt,
                endedAtEpochSeconds = endedAt,
            ),
        )
    }

    private suspend fun replayMissingDynamics() {
        if (config.replayWindowHours <= 0) return
        val publisherSnapshot = dynamicPublishers
        if (publisherSnapshot.isEmpty()) return

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val targets = publisherSnapshot.values.mapNotNull { publisher ->
            val userId = publisher.externalId.toLongOrNull() ?: return@mapNotNull null
            val cursor = cursorStore.get(publisher.id) ?: return@mapNotNull null
            val lowerBound = cursor.replayLowerBoundAtEpochSeconds(config.replayWindowHours, nowEpochSeconds) ?: return@mapNotNull null
            ReplayTarget(
                publisher = publisher,
                userId = userId,
                cursor = cursor,
                lowerBound = lowerBound,
            )
        }
        if (targets.isEmpty()) return

        val globalLowerBound = targets.minOf { it.lowerBound }
        val collectedDynamics = mutableListOf<BiliDynamic>()

        var page = 1
        while (true) {
            val pageFetch = runCatching { pollService.fetchNewDynamicPage(page) }
            if (pageFetch.isFailure) {
                val error = pageFetch.exceptionOrNull()
                if (collectedDynamics.isEmpty()) {
                    throw error ?: IllegalStateException("Bilibili 历史动态分页拉取失败")
                }
                logger.warn {
                    "Bilibili 历史动态分页提前停止：page=$page，已收集=${collectedDynamics.size} 条，将继续补发已收集动态；原因=${error?.message ?: "未知错误"}"
                }
                break
            }

            val pageResult = pageFetch.getOrThrow()
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
                if (raw.time <= cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) return@dynamicLoop

                val dynamic = mapper.map(raw, target.publisher) ?: return@dynamicLoop
                logger.info {
                    "Bilibili 补发历史动态：publisher=${target.publisher.displayLabel()}，uid=${target.userId}，dynamicId=$dynamicId，time=${raw.time}"
                }
                if (publishSourceUpdate(dynamic)) {
                    cursor = cursorStore.markSeen(target.publisher.id, dynamicId, raw.time)
                }
            }
        }
    }

    private suspend fun publishSourceUpdate(update: SourceUpdate): Boolean {
        logger.info {
            "Bilibili 提交来源更新到主项目：event=${update.eventType.value}，update=${update.key.stableValue()}，publisher=${update.publisher.displayLabel()}"
        }
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = pluginId,
                update = update,
            ),
        )
        if (result.accepted) {
            logger.info {
                "Bilibili 来源更新已进入主项目：update=${update.key.stableValue()}，结果=${result.message}"
            }
        } else {
            logger.warn {
                "Bilibili 来源更新发布失败，游标暂不推进：update=${update.key.stableValue()}，原因=${result.message}"
            }
        }
        return result.accepted
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
                "加入 Bilibili 关注分组失败：uid=$userId，groupId=$groupId"
            }
        }
    }

    private suspend fun ensureFollowGroupId(): Long? {
        val groupName = normalizedFollowGroupName() ?: return null
        followGroupId?.let { return it }

        return followGroupMutex.withLock {
            followGroupId?.let { return@withLock it }
            val resolved = resolveFollowGroupId(groupName, createIfMissing = true)
            followGroupId = resolved
            resolved
        }
    }

    private suspend fun findExistingFollowGroupId(): Long? {
        val groupName = normalizedFollowGroupName() ?: return null
        followGroupId?.let { return it }

        return followGroupMutex.withLock {
            followGroupId?.let { return@withLock it }
            val resolved = resolveFollowGroupId(groupName, createIfMissing = false)
            followGroupId = resolved
            resolved
        }
    }

    private suspend fun resolveFollowGroupId(groupName: String, createIfMissing: Boolean): Long? {
        val existingGroups = runCatching {
            pollService.fetchFollowGroups()
        }.onFailure {
            logger.warn(it) {
                "读取 Bilibili 关注分组失败：name=$groupName"
            }
        }.getOrNull() ?: return null

        existingGroups.firstOrNull { it.name == groupName }?.let { matched ->
            logger.debug {
                "复用 Bilibili 关注分组：name=$groupName，groupId=${matched.tid}"
            }
            return matched.tid
        }

        if (!createIfMissing) {
            logger.info {
                "Bilibili 关注分组未找到，已跳过创建：name=$groupName"
            }
            return null
        }

        runCatching {
            pollService.createFollowGroup(groupName)
        }.onFailure {
            logger.warn(it) {
                "创建 Bilibili 关注分组失败：name=$groupName"
            }
        }

        val refreshedGroups = runCatching {
            pollService.fetchFollowGroups()
        }.onFailure {
            logger.warn(it) {
                "重新读取 Bilibili 关注分组失败：name=$groupName"
            }
        }.getOrNull() ?: return null

        val createdGroup = refreshedGroups.firstOrNull { it.name == groupName }
        if (createdGroup == null) {
            logger.warn {
                "Bilibili 关注分组未找到：name=$groupName"
            }
            return null
        }

        logger.info {
            "Bilibili 关注分组已创建：name=$groupName，groupId=${createdGroup.tid}"
        }
        return createdGroup.tid
    }

    private fun normalizedFollowGroupName(): String? {
        return config.followGroupName.trim().takeIf { it.isNotBlank() }
    }

    private fun skipAutoUnfollow(userId: String, reason: String): FollowActionResult {
        logger.info {
            "Bilibili 自动取消关注已跳过：uid=$userId，原因=$reason"
        }
        return FollowActionResult(
            FollowActionStatus.FAILED,
            "已跳过自动取消关注：$reason",
        )
    }

    private suspend fun ensureLiveBaseline(publisher: Publisher) {
        if (!config.liveDetectionEnabled || liveStatusStore.get(publisher.id) != null) return
        val uid = publisher.externalId.toLongOrNull() ?: return
        val now = System.currentTimeMillis() / 1000
        val liveFetch = fetchLiveSnapshotsByUid(listOf(publisher))
        if (uid in liveFetch.unavailableUids) return
        val snapshot = liveFetch.snapshotsByUid[uid] ?: return
        liveStatusStore.save(
            buildLiveState(
                publisher = publisher,
                snapshot = snapshot,
                previous = null,
                observedAt = now,
            )
        )
    }

    private suspend fun handleSubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
            return
        }

        val interests = applyPublisherSnapshot(snapshot)
        if (interests.becameDynamicPresent && cursorStore.get(publisherId) == null) {
            cursorStore.ensureBaseline(publisherId, event.subscription.createdAtEpochSeconds)
        }

        if (taskScheduler.isRunning(detectTaskId) && interests.hasAnyInterest) {
            if (interests.becameLivePresent) {
                ensureLiveBaseline(snapshot.publisher)
            }
            detectAndPublish()
        }
    }

    private fun handleUnsubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): PublisherInterests {
        val publisherId = snapshot.publisher.id
        val hasDynamic = snapshot.hasEnabledEvent(SubscriptionEventKind.DYNAMIC)
        val hasLive = snapshot.hasLiveEventSubscription()
        return synchronized(publisherLock) {
            val wasDynamicPresent = dynamicPublishers.containsKey(publisherId)
            val wasLivePresent = livePublishers.containsKey(publisherId)
            dynamicPublishers = if (hasDynamic) {
                dynamicPublishers + (publisherId to snapshot.publisher)
            } else {
                dynamicPublishers - publisherId
            }
            livePublishers = if (hasLive) {
                livePublishers + (publisherId to snapshot.publisher)
            } else {
                livePublishers - publisherId
            }
            PublisherInterests(
                hasDynamic = hasDynamic,
                hasLive = hasLive,
                becameDynamicPresent = hasDynamic && !wasDynamicPresent,
                becameLivePresent = hasLive && !wasLivePresent,
            )
        }
    }

    private fun removePublisherFromSnapshots(publisherId: Int) {
        synchronized(publisherLock) {
            dynamicPublishers = dynamicPublishers - publisherId
            livePublishers = livePublishers - publisherId
        }
    }

    private fun loadActivePublishers(logSummary: Boolean = true) {
        val snapshots = subscriptionQueryService.findActivePublishersWithSubscribersBySourcePlatform(platformId.value)
        val loadedDynamic = snapshots
            .filter { it.hasEnabledEvent(SubscriptionEventKind.DYNAMIC) }
            .map { it.publisher }
            .associateBy { it.id }
        val loadedLive = snapshots
            .filter { it.hasLiveEventSubscription() }
            .map { it.publisher }
            .associateBy { it.id }
        synchronized(publisherLock) {
            dynamicPublishers = loadedDynamic
            livePublishers = loadedLive
        }
        if (logSummary) {
            logger.info {
                "Bilibili 订阅发布者已加载：动态=${loadedDynamic.size}，直播=${loadedLive.size}"
            }
        }
    }

    private suspend fun importStoredCookies() {
        if (config.cookiesJson.isBlank()) return
        runCatching {
            pollService.importCookiesJson(config.cookiesJson)
        }.onFailure {
            logger.warn(it) { "导入 Bilibili 登录信息失败" }
        }
    }

    private fun persistCookiesIfLoggedIn(result: PublisherLoginResult) {
        if (result.status != PublisherLoginStatus.SUCCESS) return
        config = config.copy(cookiesJson = compactJson(pollService.exportCookiesJson()))
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "保存 Bilibili 登录信息失败" }
        }
    }

    private fun compactJson(json: String): String {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return ""

        val compact = StringBuilder(trimmed.length)
        var inString = false
        var escaping = false

        trimmed.forEach { char ->
            if (inString) {
                compact.append(char)
                when {
                    escaping -> escaping = false
                    char == '\\' -> escaping = true
                    char == '"' -> inString = false
                }
            } else {
                when {
                    char == '"' -> {
                        inString = true
                        compact.append(char)
                    }
                    !char.isWhitespace() -> compact.append(char)
                }
            }
        }

        return compact.toString()
    }

    private fun startDetectionTask(): Boolean {
        val started = taskScheduler.start(detectTask)
        if (started) {
            logger.info { "Bilibili 检测任务已启动：taskId=$detectTaskId" }
        } else {
            logger.debug { "Bilibili 检测任务已在运行：taskId=$detectTaskId" }
        }
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
            updateId = dynamicId,
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

    private fun liveLink(roomId: String): String {
        return if (roomId.isBlank()) BILIBILI_LIVE_HOME else "$BILIBILI_LIVE_HOME/$roomId"
    }

    private fun fallbackPublisher(): Publisher {
        return Publisher(
            id = 0,
            key = PublisherKey.of(platformId.value, PublisherKind.USER, "unknown"),
            name = "",
            avatar = MediaRef("", MediaKind.AVATAR),
            createTime = 0,
            createUser = 0,
        )
    }

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun top.colter.dynamic.core.data.PublisherInfo.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun PublisherSubscribers.hasLiveEventSubscription(): Boolean {
        return hasEnabledEvent(SubscriptionEventKind.LIVE_STARTED) ||
            hasEnabledEvent(SubscriptionEventKind.LIVE_ENDED)
    }

    private fun PublisherSubscribers.hasEnabledEvent(kind: SubscriptionEventKind): Boolean {
        return subscriptions.any { item ->
            item.subscription.state == EntityState.ACTIVE &&
                item.subscriber.state == EntityState.ACTIVE &&
                kind in item.subscription.policy.enabledEvents
        }
    }

    private data class PublisherInterests(
        val hasDynamic: Boolean,
        val hasLive: Boolean,
        val becameDynamicPresent: Boolean,
        val becameLivePresent: Boolean,
    ) {
        val hasAnyInterest: Boolean
            get() = hasDynamic || hasLive
    }

    private data class ReplayTarget(
        val publisher: Publisher,
        val userId: Long,
        val cursor: SourceCursor,
        val lowerBound: Long,
    )

    private data class LiveSnapshotFetchResult(
        val snapshotsByUid: Map<Long, BilibiliLiveSnapshot> = emptyMap(),
        val unavailableUids: Set<Long> = emptySet(),
    )

    private companion object {
        private const val BILIBILI_DYNAMIC_HOME: String = "https://t.bilibili.com"
        private const val BILIBILI_HOME: String = "https://www.bilibili.com"
        private const val BILIBILI_LIVE_HOME: String = "https://live.bilibili.com"
        private val BILIBILI_PLATFORM: PlatformDescriptor = PlatformDescriptor.of(
            id = "bilibili",
            displayName = "Bilibili",
            homepageUri = BILIBILI_HOME,
            iconUri = "$BILIBILI_HOME/favicon.ico",
            capabilities = setOf(PlatformCapability.PUBLISHER_SOURCE, PlatformCapability.LINK_RESOLVER),
        )
    }
}
