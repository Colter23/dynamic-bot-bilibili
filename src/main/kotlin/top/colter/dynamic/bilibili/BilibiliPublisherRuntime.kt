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
import top.colter.dynamic.core.event.NoopSystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.link.LinkVideoDownloader
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.plugin.FollowActionResult
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
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

private val logger = loggerFor<BilibiliPublisherRuntime>()

internal class BilibiliPublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLookupPlugin,
    PublisherFollowPlugin,
    PublisherLoginProvider,
    LinkResolver,
    LinkVideoDownloader,
    ConfigurablePlugin<BilibiliPublisherConfig> {
    private var pluginId: String = "bilibili-publisher"
    private val detectTaskId: String = "bilibili-detect"

    override val platformId: PlatformId = PlatformId.of("bilibili")
    override val platformDescriptor: PlatformDescriptor = BILIBILI_PLATFORM

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
    private var notificationPublisher: SystemNotificationPublisher = NoopSystemNotificationPublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private var useContextTaskScheduler: Boolean = true
    private var useContextStateStores: Boolean = true

    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()
    private val startupBootstrapLock: Any = Any()

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPlatformGateway
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var linkResolver: BilibiliLinkResolver
    private lateinit var followService: BilibiliFollowService
    private lateinit var requestFailureHandler: BilibiliRequestFailureHandler
    private lateinit var cursorStore: BilibiliCursorStore
    private lateinit var liveStatusStore: BilibiliLiveStatusStore
    private lateinit var detectTask: TaskDefinition

    @Volatile
    private var pollServiceClosed: Boolean = false

    @Volatile
    private var dynamicPublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var livePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var pendingDetection: Boolean = false

    private var startupFollowGroupPending: Boolean = false
    private var startupReplayPending: Boolean = false
    private var startupCursorWarmupPending: Boolean = false
    private var startupLiveWarmupPending: Boolean = false

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
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        sourceUpdatePublisher = context.sourceUpdatePublisher
        notificationPublisher = context.notificationPublisher
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
        rebuildPollService()
        mapper = BilibiliDynamicMapper()
        requestFailureHandler = BilibiliRequestFailureHandler(
            configProvider = { config },
            notificationPublisher = notificationPublisher,
        )
        linkResolver = BilibiliLinkResolver(
            platformId = platformId,
            configProvider = { config },
            gatewayProvider = { pollService },
            mapper = mapper,
            publisherInfoResolver = ::fetchPublisherInfo,
            requestFailureHandler = requestFailureHandler,
        )
        followService = BilibiliFollowService(
            configProvider = { config },
            gatewayProvider = { pollService },
            requestFailureHandler = requestFailureHandler,
        )
        cursorStore = cursorStoreFactory()
        liveStatusStore = liveStatusStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            name = "Bilibili 动态检测",
            description = "按配置间隔检测已订阅发布者的新动态、开播和下播事件，并发布到主项目。",
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalSeconds.seconds, runImmediately = true),
            action = {
                detectAndPublish()
            },
        )

        importStoredCookies()
        loadActivePublishers()
        logger.info {
            "Bilibili 配置已加载：pluginId=$pluginId，直播检测=${if (config.liveDetectionEnabled) "启用" else "关闭"}"
        }
    }

    override suspend fun onStart() {
        ensurePollServiceReady()
        val loginResult = pollService.checkLoginState()
        if (loginResult.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("登录状态检查")
            val taskStarted = bootstrapLoggedInState(allowReplay = true)
            logger.info {
                "Bilibili 轮询已就绪：账号=${loginResult.account?.name ?: loginResult.account?.userId ?: "未知"}，任务新启动=$taskStarted"
            }
        } else {
            logger.warn {
                "Bilibili 轮询未启动：登录状态=${loginResult.status}，原因=${loginResult.message}"
            }
            if (config.cookiesJson.isNotBlank()) {
                publishNotification(
                    SystemNotificationPublishRequest(
                        type = "bilibili.start_login_failed",
                        severity = SystemNotificationSeverity.WARN,
                        title = "Bilibili 登录检查失败",
                        content = "Bilibili 插件启动时检测到登录状态不可用，轮询未启动。请重新登录或更新 Cookie。",
                        dedupeKey = "bilibili.start_login_failed:$pluginId",
                        details = mapOf(
                            "status" to loginResult.status.name,
                            "message" to loginResult.message,
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun onStop() {
        taskScheduler.stop(detectTaskId)
        closePollService("stop")
        logger.info { "Bilibili 轮询已停止" }
    }

    override suspend fun onUnload() {
        closePollService("unload")
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
            if (::followService.isInitialized) {
                followService.resetFollowGroupCache()
            }
        }

        val restartTargets = if (
            previous.pollingIntervalSeconds != next.pollingIntervalSeconds ||
            previous.requestIntervalSeconds != next.requestIntervalSeconds ||
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
        ensurePollServiceReady()
        val snapshot = runBilibiliRequest("发布者资料查询 uid=$userId") {
            pollService.fetchPublisherSnapshot(userId)
        }.getOrNull() ?: return null
        return PublisherInfo(
            key = PublisherKey.of(platformId.value, PublisherKind.USER, snapshot.userId),
            name = snapshot.name,
            avatarBadgeKey = snapshot.avatarBadgeKey,
            state = EntityState.ACTIVE,
            avatar = MediaRef(snapshot.faceUrl, MediaKind.AVATAR),
            banner = snapshot.headerUrl?.let { MediaRef(it, MediaKind.COVER) },
            pendant = snapshot.pendantUrl?.let { MediaRef(it, MediaKind.AVATAR) },
        )
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        ensurePollServiceReady()
        return followService.queryFollowState(userId)
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        ensurePollServiceReady()
        return followService.followPublisher(userId)
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        ensurePollServiceReady()
        return followService.unfollowPublisher(userId)
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        ensurePollServiceReady()
        return pollService.checkLoginState()
    }

    override suspend fun exportCookie(): String? {
        val cookiesJson = if (::pollService.isInitialized && !pollServiceClosed) {
            pollService.exportCookiesJson()
        } else {
            currentConfig().cookiesJson
        }
        return compactJson(cookiesJson).takeIf { it.isNotBlank() && it != "[]" }
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return

        when (event.changeType) {
            SubscriptionChangeType.SUBSCRIBED -> handleSubscribed(event)
            SubscriptionChangeType.UPDATED -> handleSubscribed(event)
            SubscriptionChangeType.UNSUBSCRIBED -> handleUnsubscribed(event)
        }
    }

    override fun matchesLink(inputUrl: String): Boolean {
        return linkResolver.matchesLink(inputUrl)
    }

    override suspend fun parseLink(inputUrl: String): ParsedLink? {
        ensurePollServiceReady()
        return linkResolver.parseLink(inputUrl)
    }

    override suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        ensurePollServiceReady()
        return linkResolver.resolveLink(parsedLink)
    }

    override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        ensurePollServiceReady()
        return requestFailureHandler.run("视频下载 id=${request.parsedLink.targetId}") {
            pollService.downloadVideoLink(request)
        }.getOrThrow()
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        ensurePollServiceReady()
        val result = pollService.loginByCookie(cookie)
        persistCookiesIfLoggedIn(result)
        if (result.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("Cookie 登录")
            bootstrapLoggedInState(allowReplay = !taskScheduler.isRunning(detectTaskId))
        }
        return result
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        ensurePollServiceReady()
        val result = pollService.loginByQrCode(onQrCode, onStatusChanged)
        persistCookiesIfLoggedIn(result)
        if (result.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("二维码登录")
            bootstrapLoggedInState(allowReplay = !taskScheduler.isRunning(detectTaskId))
        }
        return result
    }

    private suspend fun detectAndPublish() {
        if (::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()) {
            logger.debug { "Bilibili 检测跳过：登录状态失效，轮询请求已暂停" }
            return
        }
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
            persistRuntimeCookiesIfChanged()
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun <T> runBilibiliRequest(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return requestFailureHandler.run(operation, block)
    }

    private suspend fun detectAndPublishLocked() {
        loadActivePublishers(logSummary = false)
        val startupLiveWarmupAttempted = runStartupBootstrapIfPending()
        val dynamicPublisherSnapshot = dynamicPublishers
        val livePublisherSnapshot = livePublishers
        if (dynamicPublisherSnapshot.isEmpty() && livePublisherSnapshot.isEmpty()) {
            logger.debug { "Bilibili 检测跳过：没有活跃订阅发布者" }
            return
        }

        if (dynamicPublisherSnapshot.isNotEmpty()) {
            val start = System.currentTimeMillis()
            // 全局动态流混合了所有关注 UP 主，单页可能放不下突发新动态，需按已有游标下界翻页。
            val pollLowerBound = dynamicPublisherSnapshot.values
                .mapNotNull { cursorStore.get(it.id)?.lastSeenAtEpochSeconds }
                .minOrNull()
            val followedDynamics = collectPolledDynamics(pollLowerBound)
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
                for (raw in publisherDynamics.asReversed()) {
                    val dynamicId = raw.id.toString()
                    if (raw.time < cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) {
                        continue
                    }

                    val dynamic = mapper.map(raw, publisher) ?: continue
                    logger.info {
                        "Bilibili 检测到新动态：publisher=${publisher.displayLabel()}，uid=$uid，dynamicId=$dynamicId，time=${raw.time}"
                    }
                    if (publishSourceUpdate(dynamic)) {
                        cursor = cursorStore.markSeen(publisherId, dynamicId, raw.time)
                    } else {
                        logger.warn {
                            "Bilibili 动态发布失败，已停止该发布者本轮后续处理，游标暂不越过失败动态：dynamicId=$dynamicId"
                        }
                        return@publisherLoop
                    }
                }
            }
        }
        if (requestFailureHandler.isPollingPaused() || startupLiveWarmupAttempted) return
        detectLiveStatusChanges(livePublisherSnapshot)
    }

    // 按页拉取全局动态流，直到翻过 lowerBound（已有游标的最早时间）或无更多页/到达页数上限。
    // lowerBound 为 null（全部发布者均无游标）时只取首页，仅用于初始化游标。
    private suspend fun collectPolledDynamics(lowerBound: Long?): List<BiliDynamic> {
        val collected = mutableListOf<BiliDynamic>()
        var page = 1
        while (page <= MAX_POLL_PAGES) {
            val pageFetch = runBilibiliRequest("动态轮询 page=$page") {
                pollService.fetchNewDynamicPage(page)
            }
            if (pageFetch.isFailure) {
                if (collected.isEmpty()) return emptyList()
                logger.warn {
                    "Bilibili 动态轮询分页提前停止：page=$page，已收集=${collected.size} 条；原因=${pageFetch.exceptionOrNull()?.message ?: "未知错误"}"
                }
                break
            }
            val pageResult = pageFetch.getOrThrow()
            val items = pageResult.items
            if (items.isEmpty()) break
            collected.addAll(items)

            if (lowerBound == null) break
            val oldestTime = items.minOf { it.time }
            if (!pageResult.hasMore || oldestTime < lowerBound) break
            page += 1
        }
        return collected
    }

    private suspend fun bootstrapLoggedInState(allowReplay: Boolean): Boolean {
        loadActivePublishers()
        scheduleStartupBootstrap(allowReplay)
        return startDetectionTask()
    }

    private fun scheduleStartupBootstrap(allowReplay: Boolean) {
        synchronized(startupBootstrapLock) {
            startupFollowGroupPending = true
            if (config.replayWindowMinutes > 0 && allowReplay) {
                startupReplayPending = true
                startupCursorWarmupPending = false
            } else if (!taskScheduler.isRunning(detectTaskId)) {
                startupCursorWarmupPending = true
            }
            startupLiveWarmupPending = true
        }
    }

    private fun takeStartupBootstrapPlan(): StartupBootstrapPlan {
        return synchronized(startupBootstrapLock) {
            StartupBootstrapPlan(
                initializeFollowGroup = startupFollowGroupPending,
                replayMissingDynamics = startupReplayPending,
                warmUpExistingCursors = startupCursorWarmupPending,
                warmUpLiveStatuses = startupLiveWarmupPending,
            ).also {
                startupFollowGroupPending = false
                startupReplayPending = false
                startupCursorWarmupPending = false
                startupLiveWarmupPending = false
            }
        }
    }

    private suspend fun runStartupBootstrapIfPending(): Boolean {
        val plan = takeStartupBootstrapPlan()
        if (!plan.hasWork) return false

        if (plan.initializeFollowGroup) {
            runCatching { followService.ensureFollowGroupInitialized() }
                .onFailure {
                    logger.warn(it) {
                        "Bilibili 关注分组初始化失败"
                    }
                }
        }
        if (plan.replayMissingDynamics) {
            runCatching { replayMissingDynamics() }
                .onFailure {
                    logger.warn {
                        "Bilibili 历史动态补发未完成：${it.message ?: "未知错误"}"
                    }
                    logger.debug(it) {
                        "Bilibili 历史动态补发异常详情"
                    }
                }
        } else if (plan.warmUpExistingCursors) {
            runCatching { warmUpExistingCursors() }
                .onFailure {
                    logger.warn(it) {
                        "Bilibili 动态游标预热失败"
                    }
                }
        }
        if (plan.warmUpLiveStatuses) {
            runCatching { warmUpLiveStatuses() }
                .onFailure {
                    logger.warn(it) {
                        "Bilibili 直播状态预热失败"
                    }
                }
        }
        return plan.warmUpLiveStatuses
    }

    private suspend fun warmUpExistingCursors() {
        val publisherSnapshot = dynamicPublishers
        if (publisherSnapshot.isEmpty()) return

        val followedDynamics = runBilibiliRequest("动态游标预热") { pollService.fetchNewDynamicPage(1).items }
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
                    if (raw.time < cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) {
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
            if (requestFailureHandler.isPollingPaused()) {
                unavailable.addAll(batch)
                return@forEach
            }
            val snapshots = runBilibiliRequest("直播状态拉取 uids=$batch") {
                pollService.fetchLiveStatusBatch(batch)
            }.onFailure {
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
        val rawStatus = snapshot?.status ?: LiveStatus.CLOSE
        // ROUND（轮播）是过渡态：直播中切轮播不代表下播，仅 CLOSE 才结束直播。
        // 把 ROUND 归一为"延续上一已解析状态"——直播中→ROUND 视为仍在播，冷启动/已下播→ROUND 视为未播。
        // 由于存储的 status 始终是 OPEN/CLOSE，可消除 OPEN↔ROUND 抖动产生的虚假开播/下播。
        val status = when (rawStatus) {
            LiveStatus.OPEN -> LiveStatus.OPEN
            LiveStatus.CLOSE -> LiveStatus.CLOSE
            LiveStatus.ROUND -> if (previous?.status == LiveStatus.OPEN) LiveStatus.OPEN else LiveStatus.CLOSE
        }
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
        if (config.replayWindowMinutes <= 0) return
        val publisherSnapshot = dynamicPublishers
        if (publisherSnapshot.isEmpty()) return

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val targets = publisherSnapshot.values.mapNotNull { publisher ->
            val userId = publisher.externalId.toLongOrNull() ?: return@mapNotNull null
            val cursor = cursorStore.get(publisher.id) ?: return@mapNotNull null
            val lowerBound = cursor.replayLowerBoundAtEpochSeconds(config.replayWindowMinutes, nowEpochSeconds) ?: return@mapNotNull null
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
            val pageFetch = runBilibiliRequest("历史动态补发 page=$page") {
                pollService.fetchNewDynamicPage(page)
            }
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

        var replayedCount = 0
        targets.forEach targetLoop@{ target ->
            var cursor = target.cursor
            dynamicsByPublisher[target.userId].orEmpty().forEach dynamicLoop@{ raw ->
                if (raw.time < target.lowerBound) return@dynamicLoop
                val dynamicId = raw.id.toString()
                if (raw.time < cursor.lastSeenAtEpochSeconds || cursor.hasSeen(dynamicId)) return@dynamicLoop

                val dynamic = mapper.map(raw, target.publisher) ?: return@dynamicLoop
                logger.debug {
                    "Bilibili 补发历史动态：publisher=${target.publisher.displayLabel()}，uid=${target.userId}，dynamicId=$dynamicId，time=${raw.time}"
                }
                if (publishSourceUpdate(dynamic)) {
                    cursor = cursorStore.markSeen(target.publisher.id, dynamicId, raw.time)
                    replayedCount += 1
                } else {
                    logger.warn {
                        "Bilibili 历史动态补发失败，已停止该发布者本轮补发，游标暂不越过失败动态：dynamicId=$dynamicId"
                    }
                    return@targetLoop
                }
            }
        }
        if (replayedCount > 0) {
            logger.info {
                "Bilibili 历史动态补发完成：发布者=${targets.size}，动态=$replayedCount"
            }
        }
    }

    private suspend fun publishSourceUpdate(update: SourceUpdate): Boolean {
        logger.debug {
            "Bilibili 提交来源更新到主项目：event=${update.eventType.value}，update=${update.key.stableValue()}，publisher=${update.publisher.displayLabel()}"
        }
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = pluginId,
                update = update,
            ),
        )
        if (result.accepted) {
            logger.debug {
                "Bilibili 来源更新已进入主项目：update=${update.key.stableValue()}，结果=${result.message}"
            }
        } else {
            logger.warn {
                "Bilibili 来源更新发布失败，游标暂不推进：update=${update.key.stableValue()}，原因=${result.message}"
            }
        }
        return result.accepted
    }

    private suspend fun publishNotification(request: SystemNotificationPublishRequest) {
        runCatching { notificationPublisher.publish(request) }
            .onFailure {
                logger.warn(it) { "Bilibili 系统通知发布失败：type=${request.type}" }
            }
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
            cursorStore.evict(publisherId)
            liveStatusStore.evict(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): PublisherInterests {
        val publisherId = snapshot.publisher.id
        val hasDynamic = snapshot.hasEnabledEvent(SubscriptionEventKind.DYNAMIC)
        val hasLive = snapshot.hasLiveEventSubscription()
        val interests = synchronized(publisherLock) {
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
        if (!interests.hasDynamic) cursorStore.evict(publisherId)
        if (!interests.hasLive) liveStatusStore.evict(publisherId)
        return interests
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

    private suspend fun ensurePollServiceReady() {
        if (::pollService.isInitialized && !pollServiceClosed) return
        rebuildPollService()
        importStoredCookies()
    }

    private fun rebuildPollService() {
        pollService = serviceFactory(secondsToMillis(config.requestIntervalSeconds, minimumMillis = 0))
        pollServiceClosed = false
    }

    private fun closePollService(reason: String) {
        if (!::pollService.isInitialized || pollServiceClosed) return
        runCatching { persistRuntimeCookiesIfChanged() }
            .onFailure {
                logger.warn(it) { "关闭 Bilibili 客户端前回存 Cookie 失败：reason=$reason" }
            }
        runCatching { pollService.close() }
            .onFailure {
                logger.warn(it) { "关闭 Bilibili 客户端失败：reason=$reason" }
            }
        pollServiceClosed = true
        logger.debug { "Bilibili 客户端已关闭：reason=$reason" }
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

    // BiliClient 在轮询期间可能刷新内存 Cookie（wbi/bili_ticket/风控轮换）。
    // 若不回存，重启后会用过期 Cookie 重新登录。仅在导出值发生变化时写回，避免无谓落盘。
    private fun persistRuntimeCookiesIfChanged() {
        if (!::pollService.isInitialized) return
        val latest = compactJson(pollService.exportCookiesJson())
        if (latest.isBlank() || latest == "[]") return
        if (latest == compactJson(config.cookiesJson)) return
        config = config.copy(cookiesJson = latest)
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "回存 Bilibili 运行期 Cookie 失败" }
        }
        logger.debug { "Bilibili 运行期 Cookie 已回存配置" }
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

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun top.colter.dynamic.core.data.PublisherInfo.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private fun liveLink(roomId: String): String {
        return if (roomId.isBlank()) BILIBILI_LIVE_HOME else "$BILIBILI_LIVE_HOME/$roomId"
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

    private data class StartupBootstrapPlan(
        val initializeFollowGroup: Boolean,
        val replayMissingDynamics: Boolean,
        val warmUpExistingCursors: Boolean,
        val warmUpLiveStatuses: Boolean,
    ) {
        val hasWork: Boolean
            get() = initializeFollowGroup || replayMissingDynamics || warmUpExistingCursors || warmUpLiveStatuses
    }

    private companion object {
        private const val BILIBILI_HOME: String = "https://www.bilibili.com"
        private const val BILIBILI_LIVE_HOME: String = "https://live.bilibili.com"
        private const val MAX_POLL_PAGES: Int = 5
        private val BILIBILI_PLATFORM: PlatformDescriptor = PlatformDescriptor.of(
            id = "bilibili",
            displayName = "Bilibili",
            homepageUri = BILIBILI_HOME,
            iconUri = "$BILIBILI_HOME/favicon.ico",
            capabilities = setOf(
                PlatformCapability.PUBLISHER_SOURCE,
                PlatformCapability.LIVE_SOURCE,
                PlatformCapability.LINK_RESOLVER,
            ),
        )
    }
}
