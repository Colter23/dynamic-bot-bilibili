package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.config.save
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.PublisherType
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
import top.colter.dynamic.core.task.IntervalTask
import top.colter.dynamic.core.task.TaskEngine
import top.colter.dynamic.core.task.TaskRegistry
import top.colter.dynamic.core.tools.logger

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
    private var cursorStoreFactory: (String) -> CursorStore = { path -> CursorStore(path) }
    private var saveConfig: (String, BilibiliPublisherConfig) -> Unit = { id, config ->
        DefaultConfigService.save(id, config)
    }

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPlatformGateway
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var cursorStore: CursorStore
    private lateinit var detectTask: IntervalTask

    @Volatile
    private var subscriptions: Map<Publisher, List<top.colter.dynamic.core.data.Subscriber>> = emptyMap()

    @Volatile
    private var lastSubscriptionRefreshAt: Long = 0

    internal constructor(
        loadConfig: (String) -> BilibiliPublisherConfig,
        serviceFactory: (Long) -> BilibiliPlatformGateway,
        cursorStoreFactory: (String) -> CursorStore,
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
    ) : this() {
        this.loadConfig = loadConfig
        this.serviceFactory = serviceFactory
        this.cursorStoreFactory = cursorStoreFactory
        this.saveConfig = saveConfig
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(
        PublisherLoginMethod.COOKIE,
        PublisherLoginMethod.QR_CODE,
    )

    override fun init() {
        config = loadConfig(pluginId)
        pollService = serviceFactory(config.requestIntervalMs)
        importStoredCookies()
        mapper = BilibiliDynamicMapper()
        cursorStore = cursorStoreFactory(config.cursorPath)
        detectTask = IntervalTask(
            id = detectTaskId,
            intervalMillis = config.pollingIntervalMs,
            runImmediately = true,
        ) {
            detectAndPublish()
        }

        refreshSubscriptions(force = true)
        logger.info {
            "pluginId=$pluginId action=init configPath=${DefaultConfigService.resolvePath(pluginId).toAbsolutePath()}"
        }
    }

    override fun start() {
        if (TaskRegistry.get(detectTaskId) == null) {
            TaskRegistry.register(detectTask)
        }
        val started = TaskEngine.startById(detectTaskId)
        logger.info { "pluginId=bilibili-publisher action=start started=$started" }
    }

    override fun stop() {
        runBlocking {
            TaskEngine.unregisterAndCancel(detectTaskId)
        }
        logger.info { "pluginId=bilibili-publisher action=stop" }
    }

    override fun cleanup() {
        logger.info { "pluginId=bilibili-publisher action=cleanup" }
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
        return result
    }

    override suspend fun startQrLogin(): PublisherQrLoginChallenge? {
        return pollService.startQrLogin()
    }

    override suspend fun pollQrLogin(sessionId: String): PublisherLoginResult {
        val result = pollService.pollQrLogin(sessionId)
        persistCookiesIfLoggedIn(result)
        return result
    }

    private suspend fun detectAndPublish() {
        refreshSubscriptions(force = false)
        val start = System.currentTimeMillis()
        val followedDynamics = runCatching { pollService.fetchSubscribedLatest(config.fetchLimit) }
            .onFailure {
                logger.error(it) {
                    "pluginId=bilibili-publisher action=poll result=failed"
                }
            }
            .getOrElse { emptyList() }
        val latency = System.currentTimeMillis() - start

        subscriptions.forEach { (publisher, subscribers) ->
            val publisherId = publisher.id.toString()
            if (subscribers.isEmpty()) return@forEach
            val uid = publisher.userId?.toLongOrNull() ?: return@forEach

            val publisherDynamics = followedDynamics
                .filter { it.mid == uid }
                .sortedByDescending { it.time }
            val latest = publisherDynamics.firstOrNull()
            val cursor = cursorStore.get(publisherId)
            if (cursor == null) {
                if (latest != null) {
                    cursorStore.markSeen(publisherId, latest.id.toString(), latest.time)
                }
                logger.info {
                    "pluginId=bilibili-publisher publisherId=$publisherId action=bootstrap result=initialized latencyMs=$latency"
                }
                return@forEach
            }

            publisherDynamics
                .asReversed()
                .filterNot { cursorStore.hasSeen(publisherId, it.id.toString()) }
                .forEach { raw ->
                    val dynamic = mapper.map(raw, publisher) ?: return@forEach
                    subscribers.forEach { subscriber ->
                        DynamicEvent(source = "bilibili-plugin", target = subscriber, dynamic = dynamic).broadcast()
                    }
                    cursorStore.markSeen(publisherId, raw.id.toString(), raw.time)
                }
        }
    }

    private fun refreshSubscriptions(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSubscriptionRefreshAt < config.subscriptionRefreshIntervalMs) return

        val activePublishers = SubscribeRepository.findActivePublishersByPlatform(platformId)
        subscriptions = activePublishers.associateWith { publisher ->
            SubscribeRepository.findSubscribersByPublisherId(publisher.id.toString())
                .filter { it.state == 1 }
        }
        lastSubscriptionRefreshAt = now
        logger.info {
            "pluginId=bilibili-publisher action=refresh_subscription publisherSize=${subscriptions.size}"
        }
    }

    private fun importStoredCookies() {
        if (config.cookiesJson.isBlank()) return
        runCatching {
            runBlocking {
                pollService.importCookiesJson(config.cookiesJson)
            }
        }.onFailure {
            logger.warn(it) { "pluginId=bilibili-publisher action=import_stored_cookies result=failed" }
        }
    }

    private fun persistCookiesIfLoggedIn(result: PublisherLoginResult) {
        if (result.status != PublisherLoginStatus.SUCCESS) return
        config = config.copy(cookiesJson = pollService.exportCookiesJson())
        saveConfig(pluginId, config)
    }
}
