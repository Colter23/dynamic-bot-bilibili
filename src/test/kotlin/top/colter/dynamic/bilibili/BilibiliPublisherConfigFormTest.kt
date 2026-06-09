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
