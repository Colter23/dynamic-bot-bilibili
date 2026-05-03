package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.PublisherPlugin
import top.colter.dynamic.core.repository.SubscribeRepository
import top.colter.dynamic.core.task.IntervalTask
import top.colter.dynamic.core.task.TaskEngine
import top.colter.dynamic.core.task.TaskRegistry
import top.colter.dynamic.core.tools.logger

public class BilibiliPublisherPlugin : PublisherPlugin {
    private val pluginId: String = "bilibili-publisher"
    private val detectTaskId: String = "bilibili-detect"

    private lateinit var config: BilibiliPublisherConfig
    private lateinit var pollService: BilibiliPollService
    private lateinit var mapper: BilibiliDynamicMapper
    private lateinit var cursorStore: CursorStore
    private lateinit var detectTask: IntervalTask

    @Volatile
    private var subscriptions: Map<Publisher, List<top.colter.dynamic.core.data.Subscriber>> = emptyMap()

    @Volatile
    private var lastSubscriptionRefreshAt: Long = 0

    override fun init() {
        config = DefaultConfigService.loadOrCreate(pluginId) { BilibiliPublisherConfig() }
        pollService = BilibiliPollService(config.requestIntervalMs)
        mapper = BilibiliDynamicMapper()
        cursorStore = CursorStore(config.cursorPath)
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

        val activePublishers = SubscribeRepository.findActivePublishersByPlatform("bilibili")
        subscriptions = activePublishers.associateWith { publisher ->
            SubscribeRepository.findSubscribersByPublisherId(publisher.id.toString())
                .filter { it.state == 1 }
        }
        lastSubscriptionRefreshAt = now
        logger.info {
            "pluginId=bilibili-publisher action=refresh_subscription publisherSize=${subscriptions.size}"
        }
    }
}
