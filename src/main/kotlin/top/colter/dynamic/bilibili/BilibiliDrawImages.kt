package top.colter.dynamic.bilibili

import java.net.URI
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PublisherInfo

public enum class BilibiliDrawImageMode {
    ORIGINAL,
    COMPRESSED,
}

internal fun MediaRef.withDrawImage(mode: BilibiliDrawImageMode): MediaRef {
    val preview = if (mode == BilibiliDrawImageMode.COMPRESSED && kind in COMPRESSIBLE_KINDS) {
        compressedBilibiliImageUri(uri)
    } else {
        null
    }
    return if (preview == previewUri) this else copy(previewUri = preview)
}

internal fun PublisherInfo.withDrawImages(mode: BilibiliDrawImageMode): PublisherInfo {
    return copy(banner = banner?.withDrawImage(mode))
}

private fun compressedBilibiliImageUri(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.endsWith(".hdslb.com") || uri.rawUserInfo != null || uri.port != -1) return null
    val path = uri.rawPath ?: return null
    // 已格式化的地址可能带裁剪语义，不能覆盖；GIF、表情和挂件保留原样。
    if (!path.startsWith("/bfs/") || '@' in path) return null
    if (path.substringAfterLast('.').lowercase() !in COMPRESSIBLE_EXTENSIONS) return null
    return buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority).append(path)
        append("@1920w_80q.webp")
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
}

private val COMPRESSIBLE_KINDS = setOf(MediaKind.IMAGE, MediaKind.COVER)
private val COMPRESSIBLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
