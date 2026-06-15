package top.colter.dynamic.bilibili

import top.colter.bilibili.exception.BiliAuthException

internal const val BILIBILI_MISSING_CSRF_MESSAGE: String =
    "Bilibili 写操作需要完整 Cookie（缺少 CSRF Token/bili_jct），请重新登录或导入包含 bili_jct 的完整 Cookie。"

internal fun Throwable.isMissingBilibiliCsrfToken(): Boolean {
    if (this !is BiliAuthException) return false
    val reason = message ?: return false
    return reason.contains("CSRF", ignoreCase = true) ||
        reason.contains("csrf", ignoreCase = true) ||
        reason.contains("bili_jct", ignoreCase = true)
}

internal fun Throwable.toBilibiliWriteFailureMessage(defaultMessage: String): String {
    return if (isMissingBilibiliCsrfToken()) {
        BILIBILI_MISSING_CSRF_MESSAGE
    } else {
        message ?: defaultMessage
    }
}
