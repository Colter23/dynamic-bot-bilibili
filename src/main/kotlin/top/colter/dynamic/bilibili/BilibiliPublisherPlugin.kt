package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherPlatform
import top.colter.dynamic.core.data.PublisherType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.event.DynamicEvent
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.PublisherPlugin
import top.colter.dynamic.core.task.IntervalTask
import top.colter.dynamic.core.task.TaskEngine
import top.colter.dynamic.core.task.TaskRegistry
import top.colter.dynamic.core.tools.logger

public class BilibiliPublisherPlugin : PublisherPlugin {
    private val detectTaskId: String = "bilibili-detect"
    private val detectTask: IntervalTask = IntervalTask(
        id = detectTaskId,
        intervalMillis = 30_000,
        runImmediately = true
    ) {
        publishDemoDynamic()
    }

    override fun init() {
        logger.info { "BilibiliPublisherPlugin init" }
    }

    override fun start() {
        if (TaskRegistry.get(detectTaskId) == null) {
            TaskRegistry.register(detectTask)
        }

        val started = TaskEngine.startById(detectTaskId)
        val snapshot = TaskEngine.snapshot(detectTaskId)
        logger.info { "BilibiliPublisherPlugin started=$started, taskSnapshot=$snapshot" }
    }

    override fun stop() {
        runBlocking {
            TaskEngine.unregisterAndCancel(detectTaskId)
        }
        logger.info { "BilibiliPublisherPlugin stopped" }
    }

    override fun cleanup() {
        logger.info { "BilibiliPublisherPlugin cleanup" }
    }

    private fun publishDemoDynamic() {
        val subscriber = Subscriber(
            id = 1,
            platform = "mock",
            type = SubscriberType.GROUP,
            userId = "demo-group",
            name = "demo-group",
            state = 1,
            createTime = System.currentTimeMillis(),
            createUser = 0,
        )

        val dynamic = Dynamic(
            platform = PublisherPlatform(
                id = "bilibili",
                name = "BiliBili",
                link = "https://www.bilibili.com",
                icon = "",
            ),
            dynamicId = "demo-${System.currentTimeMillis()}",
            publisher = Publisher(
                id = 1,
                platform = "bilibili",
                type = PublisherType.USER,
                userId = "123456",
                name = "Demo UP",
            ),
            time = System.currentTimeMillis(),
            link = "https://t.bilibili.com",
            content = DynamicContent(
                text = "[demo] bilibili plugin dynamic",
                contentNodes = listOf(DynamicContentNodeText("[demo] bilibili plugin dynamic")),
            ),
        )

        DynamicEvent(
            source = "bilibili-plugin",
            target = subscriber,
            label = "demo",
            dynamic = dynamic,
        ).broadcast()
    }
}
