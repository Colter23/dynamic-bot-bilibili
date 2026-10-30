package top.colter.dynamic.bilibili

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoQuality
import top.colter.dynamic.core.link.ParsedLink

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

    @Test
    fun `video download should read cache per quality bucket`() = runBlocking {
        // 缓存按 {id}-{quality} 分桶：P720 的文件只应被 P720 请求命中。
        // 这里用已存在的缓存文件验证命中路径（不触发网络）；若命名回退成只按 id，查找会落空。
        val directory = createTempDirectory("bili-video-cache").toFile()
        val cachedFile = directory.resolve("BV1xx411c7mX-p720.mp4")
        cachedFile.writeBytes(byteArrayOf(0, 0, 0, 1))

        val service = BilibiliPollService(requestIntervalMs = 0)
        val request = LinkVideoDownloadRequest(
            parsedLink = ParsedLink(
                platformId = PlatformId.of("bilibili"),
                kind = LinkKinds.VIDEO,
                targetId = "BV1xx411c7mX",
                normalizedUrl = "https://www.bilibili.com/video/BV1xx411c7mX",
            ),
            directory = directory,
            maxBytes = 0,
            quality = LinkVideoQuality.P720,
        )

        val result = service.downloadVideoLink(request)

        assertEquals(cachedFile.absolutePath, result.video.uri)
        assertEquals(4L, result.fileSizeBytes)
    }
}
