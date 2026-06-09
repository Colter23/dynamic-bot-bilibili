package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.bilibili.exception.BiliBanException
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilibiliRequestFailureHandlerTest {
    @Test
    fun `login failure pause should notify once and recovery should notify once`() = runBlocking {
        val requests = mutableListOf<SystemNotificationPublishRequest>()
        val handler = BilibiliRequestFailureHandler(
            configProvider = {
                BilibiliPublisherConfig(maxConsecutiveLoginFailures = 2)
            },
            notificationPublisher = SystemNotificationPublisher { request ->
                requests += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.recordFailure("dynamic polling", BiliLoginException("cookie expired"))

        assertFalse(handler.isPollingPaused())
        assertTrue(requests.isEmpty())

        handler.recordFailure("dynamic polling", BiliLoginException("cookie expired"))

        assertTrue(handler.isPollingPaused())
        assertEquals(1, requests.size)
        assertEquals("bilibili.login_paused", requests.single().type)
        assertEquals(SystemNotificationSeverity.ERROR, requests.single().severity)

        handler.recordFailure("dynamic polling", BiliLoginException("cookie expired"))

        assertEquals(1, requests.size)

        handler.recordSuccess("login check")

        assertFalse(handler.isPollingPaused())
        assertEquals(2, requests.size)
        assertEquals("bilibili.login_recovered", requests.last().type)
        assertEquals(SystemNotificationSeverity.INFO, requests.last().severity)
    }

    @Test
    fun `request block should pause polling for configured cooldown and recover on success`() = runBlocking {
        val requests = mutableListOf<SystemNotificationPublishRequest>()
        var now = 1_000L
        val handler = BilibiliRequestFailureHandler(
            configProvider = {
                BilibiliPublisherConfig(requestBlockCooldownMinutes = 30)
            },
            notificationPublisher = SystemNotificationPublisher { request ->
                requests += request
                SystemNotificationPublishResult.accepted()
            },
            clockMillis = { now },
        )

        handler.recordFailure("live status", BiliBanException("blocked"))

        assertTrue(handler.isPollingPaused())
        assertEquals(1, requests.size)
        assertEquals("bilibili.request_block_paused", requests.single().type)
        assertEquals(SystemNotificationSeverity.ERROR, requests.single().severity)

        handler.recordFailure("live status", BiliBanException("blocked again"))

        assertTrue(handler.isPollingPaused())
        assertEquals(1, requests.size)

        now += 30 * 60_000L

        assertFalse(handler.isPollingPaused())

        handler.recordSuccess("login check")

        assertFalse(handler.isPollingPaused())
        assertEquals(2, requests.size)
        assertEquals("bilibili.request_block_recovered", requests.last().type)
        assertEquals(SystemNotificationSeverity.INFO, requests.last().severity)
    }
}
