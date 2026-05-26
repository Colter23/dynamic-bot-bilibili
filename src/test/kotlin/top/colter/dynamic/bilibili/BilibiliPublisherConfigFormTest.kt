package top.colter.dynamic.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BilibiliPublisherConfigFormTest {
    @Test
    fun `form should expose cookie secret and polling restart fields`() {
        val cookieField = BilibiliPublisherConfigForm.spec.fields.single { it.path == "cookiesJson" }
        val pollingField = BilibiliPublisherConfigForm.spec.fields.single { it.path == "pollingIntervalMs" }

        assertTrue(cookieField.secret)
        assertTrue(cookieField.restartRequired)
        assertTrue(pollingField.restartRequired)
        assertEquals(1_000, pollingField.min)
    }

    @Test
    fun `validator should reject invalid polling values`() {
        BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig())

        assertFailsWith<IllegalArgumentException> {
            BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig(fetchLimit = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            BilibiliPublisherConfigForm.validate(BilibiliPublisherConfig(shortUrlResolveTimeoutMs = 0))
        }
    }
}
