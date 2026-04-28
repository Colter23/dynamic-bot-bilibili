package top.colter.dynamic.bilibili

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

public class BilibiliPublisherPlugin : PublisherPlugin {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private var running: Boolean = false

    override fun init() {
        println("BilibiliPublisherPlugin init")
    }

    override fun start() {
        if (running) return
        running = true

        scope.launch {
            while (isActive && running) {
                publishDemoDynamic()
                delay(30_000)
            }
        }

        println("BilibiliPublisherPlugin started")
    }

    override fun stop() {
        running = false
        scope.cancel("BilibiliPublisherPlugin stop")
        println("BilibiliPublisherPlugin stopped")
    }

    override fun cleanup() {
        println("BilibiliPublisherPlugin cleanup")
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
