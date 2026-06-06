package top.colter.dynamic.bilibili

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BilibiliPollServiceTest {
    @Test
    fun `parse live start time should support epoch seconds`() {
        assertEquals(1_735_689_600L, "1735689600".parseLiveStartEpochSeconds())
    }

    @Test
    fun `parse live start time should support bilibili date time in shanghai timezone`() {
        val expected = LocalDateTime
            .of(2025, 1, 1, 0, 0, 0)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toEpochSecond()

        assertEquals(expected, "2025-01-01 00:00:00".parseLiveStartEpochSeconds())
    }

    @Test
    fun `parse live start time should ignore blank placeholder and unknown values`() {
        assertNull(null.parseLiveStartEpochSeconds())
        assertNull(" ".parseLiveStartEpochSeconds())
        assertNull("0000-00-00 00:00:00".parseLiveStartEpochSeconds())
        assertNull("not-a-time".parseLiveStartEpochSeconds())
    }
}
