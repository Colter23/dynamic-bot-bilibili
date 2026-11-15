package top.colter.dynamic.bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.EditCookie
import top.colter.bilibili.exception.BiliLoginException
import top.colter.bilibili.tools.json
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal open class BilibiliSessionClient(
    private val boundedRequests: Boolean = true,
) : BiliClient() {
    override val retry: Int = 0

    override fun initClient(): HttpClient = HttpClient(OkHttp) {
        defaultRequest {
            header("Accept", "application/json, text/plain, */*")
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            header("Origin", "https://t.bilibili.com")
            header("Referer", "https://t.bilibili.com/")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            header("sec-ch-ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"99\"")
            header("sec-ch-ua-mobile", "?0")
            header("sec-ch-ua-platform", "\"Windows\"")
            header("sec-fetch-dest", "empty")
            header("sec-fetch-mode", "cors")
            header("sec-fetch-site", "same-site")
            header("priority", "u=1, i")
        }
        followRedirects = !boundedRequests
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
            requestTimeoutMillis = if (boundedRequests) 15_000 else null
        }
        install(HttpCookies) { storage = BilibiliResponseCookies(this@BilibiliSessionClient) }
        expectSuccess = true
        engine { config { retryOnConnectionFailure(false) } }
        ContentEncoding()
    }
}

internal class BilibiliResponseCookies(private val client: BiliClient) : CookiesStorage {
    override suspend fun get(requestUrl: Url): List<Cookie> = client.storage.getCookies(requestUrl.toString())

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        val domain = cookie.domain?.removePrefix(".")?.lowercase()?.takeIf { it.isNotBlank() } ?: requestUrl.host
        if (domain != requestUrl.host && !requestUrl.host.endsWith(".$domain")) return
        val path = cookie.path?.takeIf { it.startsWith("/") }
            ?: requestUrl.encodedPath.substringBeforeLast('/', "").ifEmpty { "/" }
        val now = System.currentTimeMillis()
        val expiration = when {
            cookie.maxAge != null -> GMTDate(if (cookie.maxAge!! <= 0) 0 else now + cookie.maxAge!!.toLong() * 1000)
            cookie.expires != null && cookie.expires!!.timestamp <= now -> GMTDate(0)
            else -> cookie.expires
        }
        client.storage.addCookie(requestUrl.toString(), cookie.copy(domain = domain, path = path, expires = expiration))
    }

    override fun close() {}
}

internal suspend fun importBilibiliCookies(client: BiliClient, cookiesJson: String) {
    client.importEditCookies(canonicalCookies(json.decodeFromString<List<EditCookie>>(cookiesJson)), true)
}

internal fun exportBilibiliCookies(client: BiliClient): String = json.encodeToString(canonicalCookies(client.exportEditCookies()))

private fun canonicalCookies(cookies: List<EditCookie>): List<EditCookie> = cookies
    .map { it.copy(domain = it.domain.removePrefix(".").lowercase(), path = it.path.ifBlank { "/" }) }
    .associateBy { Triple(it.name, it.domain, it.path) }.values
    // Keep naturally expired credentials for token binding and the refresh form, never for HTTP headers.
    // Explicit server deletions are normalized to epoch zero above and must not be resurrected.
    .filter { (it.expirationDate ?: Double.POSITIVE_INFINITY) > 0 }

internal data class BilibiliCookieRefreshInfo(val refresh: Boolean, val timestamp: Long)

internal class BilibiliSessionException(val reason: String, val requiresLogin: Boolean = false) : IllegalStateException(reason)

/** Fixed endpoints only; API errors never include server text, tickets or credential-bearing URLs. */
internal class BilibiliSessionProtocol(
    private val requestTimeoutMillis: Long = 15_000,
) {
    suspend fun validate(client: BiliClient): PublisherLoginResult {
        val data = request(client, NAV)["data"]?.jsonObject
            ?: throw BilibiliSessionException("Bilibili 登录校验响应不完整")
        val uid = data["mid"]?.jsonPrimitive?.longOrNull
        if (data["isLogin"]?.jsonPrimitive?.booleanOrNull != true || uid == null || uid <= 0) {
            throw BiliLoginException("Bilibili 未登录")
        }
        return PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "登录成功",
            PublisherLoginAccount(
                userId = uid.toString(),
                name = data["uname"]?.jsonPrimitive?.content,
                avatar = data["face"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?.let { MediaRef(it, MediaKind.AVATAR) },
            ),
        )
    }

    suspend fun refreshInfo(client: BiliClient): BilibiliCookieRefreshInfo {
        val response = try {
            request(client, "$PASSPORT/cookie/info", mapOf("csrf" to csrf(client)))
        } catch (_: BiliLoginException) {
            // Expired session cookies do not necessarily invalidate the independent long token.
            return BilibiliCookieRefreshInfo(refresh = true, timestamp = System.currentTimeMillis())
        }
        val data = response["data"]?.jsonObject
            ?: throw BilibiliSessionException("Bilibili 续期检查响应不完整")
        return BilibiliCookieRefreshInfo(
            data["refresh"]?.jsonPrimitive?.booleanOrNull
                ?: throw BilibiliSessionException("Bilibili 续期检查缺少刷新标志"),
            data["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    suspend fun refresh(client: BiliClient, token: String, timestamp: Long): String {
        if (timestamp <= 0) throw BilibiliSessionException("Bilibili 续期检查缺少服务器时间")
        val previousSession = client.exportEditCookies().firstOrNull {
            it.name == "SESSDATA" && it.domain.removePrefix(".") == "bilibili.com" && it.path == "/"
        }?.value
        val html = text(client, "https://www.bilibili.com/correspond/1/${correspondPath(timestamp)}")
        val refreshCsrf = Regex("""<[^>]+\bid\s*=\s*["']1-name["'][^>]*>\s*([A-Za-z0-9_-]+)\s*</""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: throw BilibiliSessionException("Bilibili 续期页面未提供令牌，请重新扫码", requiresLogin = true)
        val data = request(client, "$PASSPORT/cookie/refresh", mapOf(
            "csrf" to csrf(client), "refresh_csrf" to refreshCsrf,
            "source" to "main_web", "refresh_token" to token,
        ), post = true)["data"]?.jsonObject
        val newToken = data?.get("refresh_token")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw BilibiliSessionException("Bilibili 续期响应缺少长期令牌")
        val currentCookies = client.storage.getCookies("https://www.bilibili.com/")
        if (currentCookies.none { it.name == "SESSDATA" && it.value.isNotBlank() && it.value != previousSession } ||
            currentCookies.none { it.name == "bili_jct" && it.value.isNotBlank() }) {
            throw BilibiliSessionException("Bilibili 续期未获得新的登录 Cookie，已保留原会话")
        }
        return newToken
    }

    suspend fun confirm(client: BiliClient, oldToken: String) {
        request(client, "$PASSPORT/confirm/refresh", mapOf(
            "csrf" to csrf(client), "refresh_token" to oldToken,
        ), post = true)
    }

    private fun csrf(client: BiliClient): String = client.exportEditCookies()
        .firstOrNull { it.name == "bili_jct" && it.domain.removePrefix(".") == "bilibili.com" && it.path == "/" }
        ?.value?.takeIf { it.isNotBlank() }
        ?: throw BilibiliSessionException(BILIBILI_MISSING_CSRF_MESSAGE)

    private suspend fun request(client: BiliClient, url: String, params: Map<String, String> = emptyMap(), post: Boolean = false): JsonObject {
        val body = text(client, url, params, post)
        val result = try { json.parseToJsonElement(body).jsonObject }
        catch (e: CancellationException) { throw e }
        catch (e: BilibiliSessionException) { throw e }
        catch (_: Exception) { throw BilibiliSessionException("Bilibili 登录接口返回了无法识别的数据") }
        when (val code = result["code"]?.jsonPrimitive?.intOrNull) {
            0 -> return result
            -101 -> throw BiliLoginException("Bilibili 未登录")
            -111, 86095 -> throw BilibiliSessionException("Bilibili 续期凭据不匹配，请重新扫码登录（code=$code）", requiresLogin = true)
            else -> throw BilibiliSessionException("Bilibili 登录接口请求失败（code=${code ?: "unknown"}）")
        }
    }

    private suspend fun text(client: BiliClient, url: String, params: Map<String, String> = emptyMap(), post: Boolean = false): String =
        withTimeout(requestTimeoutMillis) {
            // Bypass the library's generic I/O retry: a refresh POST must never be blindly replayed.
            client.clients.first().config { followRedirects = false; expectSuccess = false }.use { http ->
                val response = http.request(url) {
                    method = if (post) HttpMethod.Post else HttpMethod.Get
                    header("Referer", "https://www.bilibili.com/")
                    if (post) setBody(FormDataContent(Parameters.build { params.forEach { (key, value) -> append(key, value) } }))
                    else params.forEach { (key, value) -> parameter(key, value) }
                }
                if (response.status.value !in 200..299) throw BilibiliSessionException("Bilibili 登录接口 HTTP ${response.status.value}")
                response.bodyAsText()
            }
        }

    companion object {
        private const val PASSPORT = "https://passport.bilibili.com/x/passport-login/web"
        private const val NAV = "https://api.bilibili.com/x/web-interface/nav"
        // Public modulus from Bilibili's wasm_rsa_encrypt_bg.wasm; OAEP and MGF1 both use SHA-256.
        private val publicKey by lazy {
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(
                "cb81dd8e02470656da04dd38544446e2a3412051cfe9adc6a330a5ef90228509684960970b91c3360ca29c49e1690ff8fa068cb9dfc6179d1e9585cb9424e847db1ef59f33e37dd4dca8ccfb7631ee9b4a92640d00c8204300152a0ab7cd802889d3445aec69918fe6022b534912e7b095be3424dad1ba81145e969b533181f1", 16,
            ), BigInteger.valueOf(65537)))
        }

        internal fun correspondPath(timestamp: Long): String = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal("refresh_$timestamp".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
