package top.colter.dynamic.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef

class BilibiliDrawImagesTest {
    @Test
    fun compressionShouldPreserveOriginalDimensionsAndUrlParts() {
        val uri = "https://i0.hdslb.com/bfs/new_dyn/long.jpg?token=a%2Fb#image"
        val image = MediaRef(uri, MediaKind.IMAGE, width = 2000, height = 40000)

        val compressed = image.withDrawImage(BilibiliDrawImageMode.COMPRESSED)

        assertEquals(uri, compressed.uri)
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/long.jpg@1920w_80q.webp?token=a%2Fb#image",
            compressed.previewUri,
        )
        assertEquals(2000, compressed.width)
        assertEquals(40000, compressed.height)
        assertNull(compressed.withDrawImage(BilibiliDrawImageMode.ORIGINAL).previewUri)
    }

    @Test
    fun compressionShouldOnlyProcessSupportedCdnImages() {
        val skipped = listOf(
            "https://example.com/bfs/image.jpg",
            "https://hdslb.com.example.com/bfs/image.jpg",
            "https://i0.hdslb.com/other/image.jpg",
            "https://i0.hdslb.com/bfs/image.GIF?x=1",
            "https://i0.hdslb.com/bfs/image.jpg@640w_480h_1c.webp",
            "https://i0.hdslb.com/bfs/image.svg",
            "https://user@i0.hdslb.com/bfs/image.jpg",
            "https://i0.hdslb.com:1234/bfs/image.jpg",
            "file:///bfs/image.jpg",
            "not a URL",
        )
        skipped.forEach { uri ->
            assertNull(MediaRef(uri, MediaKind.IMAGE).withDrawImage(BilibiliDrawImageMode.COMPRESSED).previewUri, uri)
        }
        listOf(MediaKind.AVATAR, MediaKind.EMOJI, MediaKind.VIDEO, MediaKind.FILE).forEach { kind ->
            val image = MediaRef("https://i0.hdslb.com/bfs/image.png", kind)
            assertNull(image.withDrawImage(BilibiliDrawImageMode.COMPRESSED).previewUri, kind.name)
        }
        val cover = MediaRef("http://i1.hdslb.com/bfs/archive/cover.PNG", MediaKind.COVER)
        assertEquals("${cover.uri}@1920w_80q.webp", cover.withDrawImage(BilibiliDrawImageMode.COMPRESSED).previewUri)
    }
}
