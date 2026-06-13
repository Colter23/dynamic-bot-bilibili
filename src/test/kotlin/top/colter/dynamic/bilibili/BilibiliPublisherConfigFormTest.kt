package top.colter.dynamic.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BilibiliPublisherConfigFormTest {
    @Test
    fun `form should hide cookie field and expose polling restart fields`() {
        val pollingField = BilibiliPublisherConfigForm.spec.fields.single { it.path == "pollingIntervalSeconds" }

        assertFalse(BilibiliPublisherConfigForm.spec.fields.any { it.path == "cookiesJson" })
        assertTrue(pollingField.restartRequired)
        assertEquals(1L, pollingField.min)
        assertTrue(pollingField.description.contains("秒"))
    }

    @Test
    fun `form should group fields by user scenario`() {
        val sections = BilibiliPublisherConfigForm.spec.fields.groupBy { it.section }

        assertEquals(
            setOf("轮询与风控", "动态与直播", "关注与链接"),
            sections.keys,
        )
        assertEquals(
            listOf(
                "pollingIntervalSeconds",
                "requestIntervalSeconds",
                "maxConsecutiveLoginFailures",
                "requestBlockCooldownMinutes",
            ),
            sections.getValue("轮询与风控").map { it.path },
        )
        assertEquals(
            listOf("liveDetectionEnabled", "liveStatusBatchSize", "replayWindowMinutes"),
            sections.getValue("动态与直播").map { it.path },
        )
        assertEquals(
            listOf("followGroupName", "shortUrlResolveTimeoutSeconds"),
            sections.getValue("关注与链接").map { it.path },
        )
    }

    @Test
    fun `validator should reject invalid polling values`() {
        BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig())

        assertFailsWith<IllegalArgumentException> {
            BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig(pollingIntervalSeconds = 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig(shortUrlResolveTimeoutSeconds = 0.0))
        }
    }
}
