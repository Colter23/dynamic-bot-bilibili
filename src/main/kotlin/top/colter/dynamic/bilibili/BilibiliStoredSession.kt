package top.colter.dynamic.bilibili

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.bilibili.tools.json
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus

/** Internal persisted credentials; deliberately absent from the configuration form. */
public data class BilibiliStoredSession(
    val credentialDigest: String = "",
    val accountId: String = "",
    val refreshToken: String = "",
    val pendingConfirmationToken: String = "",
    val lastCheckAtMillis: Long = 0,
    val nextAttemptAtMillis: Long = 0,
    val failureCount: Int = 0,
    val requiresLogin: Boolean = false,
    val renewalFailureReason: String = "",
) {
    override fun toString(): String = "BilibiliStoredSession(accountId=$accountId, credentials=[redacted])"
}

internal fun PublisherLoginResult.withRenewalWarning(state: BilibiliStoredSession): PublisherLoginResult {
    if (status != PublisherLoginStatus.SUCCESS || !state.requiresLogin) return this
    val warning = state.renewalFailureReason.ifBlank { "自动续期已停止，请重新扫码登录" }
    return if (message.contains(warning)) this else copy(message = "$message；$warning")
}

internal fun credentialDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Ignore expiring device cookies, formatting and array order when binding the account's long token. */
internal fun bilibiliCredentialDigest(value: String): String {
    val identities = (json.parseToJsonElement(value) as JsonArray).mapNotNull { element ->
        val cookie = element as? JsonObject ?: return@mapNotNull null
        val name = cookie["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val domain = cookie["domain"]?.jsonPrimitive?.content?.removePrefix(".")?.lowercase().orEmpty()
        if (name !in setOf("SESSDATA", "DedeUserID", "bili_jct") || domain != "bilibili.com") return@mapNotNull null
        listOf(domain, cookie["path"]?.jsonPrimitive?.content.orEmpty().ifBlank { "/" }, name, cookie["value"]?.jsonPrimitive?.content.orEmpty())
            .joinToString("\u0000")
    }.sorted().joinToString("\u0001")
    return credentialDigest(identities)
}

internal data class BilibiliSessionSnapshot(val cookiesJson: String, val state: BilibiliStoredSession) {
    override fun toString(): String = "BilibiliSessionSnapshot(credentials=[redacted])"
}
