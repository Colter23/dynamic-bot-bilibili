package top.colter.dynamic.bilibili

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.colter.bilibili.api.follow
import top.colter.bilibili.api.getNewDynamic
import top.colter.bilibili.api.getUserInfo
import top.colter.bilibili.api.getUserNewDynamic
import top.colter.bilibili.api.isFollow
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.EditCookie
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

internal data class BilibiliPublisherSnapshot(
    val userId: String,
    val name: String,
    val official: String? = null,
    val faceUrl: String,
    val headerUrl: String? = null,
    val pendantUrl: String? = null,
)

internal interface BilibiliPlatformGateway {
    suspend fun fetchSubscribedLatest(limit: Int): List<BiliDynamic>

    suspend fun fetchLatest(uid: String, limit: Int): List<BiliDynamic>

    suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot?

    suspend fun queryFollowState(userId: String): FollowState

    suspend fun followPublisher(userId: String): FollowActionResult

    suspend fun importCookiesJson(cookiesJson: String) {
    }

    fun exportCookiesJson(): String = ""

    suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "cookie login is unsupported",
        )
    }

    suspend fun startQrLogin(): PublisherQrLoginChallenge? {
        return null
    }

    suspend fun pollQrLogin(sessionId: String): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "QR code login is unsupported",
        )
    }
}

internal class BilibiliPollService(
    private val requestIntervalMs: Long,
    private val client: BiliClient = BiliClient(),
) : BilibiliPlatformGateway {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override suspend fun fetchSubscribedLatest(limit: Int): List<BiliDynamic> {
        val list = client.getNewDynamic(0, "")
        applyRequestDelay()
        return list.items.take(limit)
    }

    override suspend fun fetchLatest(uid: String, limit: Int): List<BiliDynamic> {
        val userId = uid.toLongOrNull() ?: return emptyList()
        val list = client.getUserNewDynamic(userId, false, "")
        applyRequestDelay()
        return list.items.take(limit)
    }

    override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? {
        val uid = userId.toLongOrNull() ?: return null
        val info = client.getUserInfo(uid)
        applyRequestDelay()
        val official = info.official?.title?.takeIf { it.isNotBlank() }
            ?: info.official?.desc?.takeIf { it.isNotBlank() }
        return BilibiliPublisherSnapshot(
            userId = info.mid.toString(),
            name = info.name,
            official = official,
            faceUrl = info.face.url,
            headerUrl = info.topPhoto.ifBlank { null },
            pendantUrl = info.pendant?.image?.url?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        val uid = userId.toLongOrNull() ?: return FollowState.UNSUPPORTED
        val relation = client.isFollow(uid)
        applyRequestDelay()
        return if (relation.attribute > 0) {
            FollowState.FOLLOWING
        } else {
            FollowState.NOT_FOLLOWING
        }
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        return when (queryFollowState(userId)) {
            FollowState.FOLLOWING -> FollowActionResult(FollowActionStatus.ALREADY_FOLLOWING)
            FollowState.UNSUPPORTED -> FollowActionResult(FollowActionStatus.UNSUPPORTED)
            FollowState.NOT_FOLLOWING -> {
                val uid = userId.toLongOrNull()
                    ?: return FollowActionResult(
                        FollowActionStatus.FAILED,
                        "invalid bilibili user id: $userId",
                    )
                client.follow(uid)
                applyRequestDelay()
                FollowActionResult(FollowActionStatus.FOLLOWED)
            }
        }
    }

    override suspend fun importCookiesJson(cookiesJson: String) {
        client.importEditCookiesJson(cookiesJson, true)
    }

    override fun exportCookiesJson(): String {
        return client.exportEditCookiesJson()
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "cookie is blank")
        }

        return importAndVerifyCookies {
            if (trimmed.startsWith("[")) {
                client.importEditCookiesJson(trimmed, true)
            } else {
                val cookies = parseCookieHeader(trimmed)
                if (cookies.isEmpty()) {
                    throw IllegalArgumentException("no valid cookie pairs found")
                }
                client.importEditCookies(cookies, true)
            }
        }
    }

    override suspend fun startQrLogin(): PublisherQrLoginChallenge? {
        val response = sendRequest(
            HttpRequest.newBuilder(URI.create(QR_GENERATE_URL))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
        )
        val root = objectMapper.readTree(response.body())
        val apiCode = root.path("code").asInt(Int.MIN_VALUE)
        if (apiCode != 0) {
            val message = root.path("message").asText("unknown error")
            throw IllegalStateException("Bilibili QR generate failed: code=$apiCode, message=$message")
        }

        val data = root.path("data")
        val qrContent = data.path("url").asText("")
        val sessionId = data.path("qrcode_key").asText("")
        require(qrContent.isNotBlank() && sessionId.isNotBlank()) {
            "Bilibili QR generate response is missing url or qrcode_key"
        }

        return PublisherQrLoginChallenge(
            sessionId = sessionId,
            qrContent = qrContent,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + QR_EXPIRES_SECONDS,
            message = "waiting for Bilibili app scan confirmation",
        )
    }

    override suspend fun pollQrLogin(sessionId: String): PublisherLoginResult {
        if (sessionId.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "sessionId is blank")
        }

        val encodedKey = URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
        val response = sendRequest(
            HttpRequest.newBuilder(URI.create("$QR_POLL_URL?qrcode_key=$encodedKey"))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
        )
        val root = objectMapper.readTree(response.body())
        val apiCode = root.path("code").asInt(Int.MIN_VALUE)
        if (apiCode != 0) {
            val message = root.path("message").asText("unknown error")
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Bilibili QR poll failed: code=$apiCode, message=$message",
            )
        }

        val data = root.path("data")
        val loginCode = data.path("code").asInt(Int.MIN_VALUE)
        val message = data.path("message").asText("")
        return when (loginCode) {
            0 -> {
                val cookies = parseSetCookieHeaders(response.headers().allValues("set-cookie"))
                if (cookies.isEmpty()) {
                    PublisherLoginResult(PublisherLoginStatus.FAILED, "Bilibili QR login returned no cookies")
                } else {
                    importAndVerifyCookies {
                        client.importEditCookies(cookies, true)
                    }
                }
            }
            86038 -> PublisherLoginResult(
                status = PublisherLoginStatus.EXPIRED,
                message = message.ifBlank { "QR code expired" },
            )
            86090 -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = message.ifBlank { "QR code scanned, waiting for confirmation" },
            )
            86101 -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = message.ifBlank { "waiting for QR code scan" },
            )
            else -> PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = message.ifBlank { "Bilibili QR login failed: code=$loginCode" },
            )
        }
    }

    private suspend fun applyRequestDelay() {
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
    }

    private suspend fun importAndVerifyCookies(importer: suspend () -> Unit): PublisherLoginResult {
        val previousCookiesJson = client.exportEditCookiesJson()
        return runCatching {
            importer()
            val result = verifyLogin(client.exportEditCookies())
            if (result.status != PublisherLoginStatus.SUCCESS) {
                restoreCookies(previousCookiesJson)
            }
            result
        }.getOrElse { throwable ->
            restoreCookies(previousCookiesJson)
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = throwable.message ?: "login failed",
            )
        }
    }

    private suspend fun restoreCookies(cookiesJson: String) {
        runCatching {
            if (cookiesJson.isBlank()) {
                client.importEditCookies(emptyList<EditCookie>(), true)
            } else {
                client.importEditCookiesJson(cookiesJson, true)
            }
        }
    }

    private suspend fun verifyLogin(cookies: List<EditCookie>): PublisherLoginResult {
        val cookieHeader = cookies.toCookieHeader()
        if (cookieHeader.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "no cookies were imported")
        }

        val response = sendRequest(
            HttpRequest.newBuilder(URI.create(NAV_URL))
                .header("User-Agent", USER_AGENT)
                .header("Cookie", cookieHeader)
                .GET()
                .build()
        )
        val root = objectMapper.readTree(response.body())
        val apiCode = root.path("code").asInt(Int.MIN_VALUE)
        if (apiCode != 0) {
            val message = root.path("message").asText("unknown error")
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Bilibili login check failed: code=$apiCode, message=$message",
            )
        }

        val data = root.path("data")
        if (!data.path("isLogin").asBoolean(false)) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "Bilibili cookie is not logged in")
        }

        return PublisherLoginResult(
            status = PublisherLoginStatus.SUCCESS,
            message = "login success",
            account = PublisherLoginAccount(
                userId = data.optionalText("mid"),
                name = data.optionalText("uname"),
            ),
        )
    }

    private suspend fun sendRequest(request: HttpRequest): HttpResponse<String> {
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private fun parseCookieHeader(cookieHeader: String): List<EditCookie> {
        return cookieHeader.split(";")
            .mapNotNull { rawPair ->
                val index = rawPair.indexOf("=")
                if (index <= 0) return@mapNotNull null

                val name = rawPair.substring(0, index).trim()
                val value = rawPair.substring(index + 1).trim()
                if (name.isBlank()) return@mapNotNull null
                EditCookie(
                    domain = DEFAULT_COOKIE_DOMAIN,
                    expirationDate = null,
                    httpOnly = false,
                    name = name,
                    path = DEFAULT_COOKIE_PATH,
                    secure = false,
                    value = value,
                )
            }
            .associateBy { it.name }
            .values
            .toList()
    }

    private fun parseSetCookieHeaders(headers: List<String>): List<EditCookie> {
        return headers.flatMap { header ->
            runCatching { HttpCookie.parse(header) }
                .getOrDefault(emptyList())
                .mapNotNull { cookie ->
                    val name = cookie.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    EditCookie(
                        domain = cookie.domain?.takeIf { it.isNotBlank() } ?: DEFAULT_COOKIE_DOMAIN,
                        expirationDate = cookie.expirationDate(),
                        httpOnly = cookie.isHttpOnly,
                        name = name,
                        path = cookie.path?.takeIf { it.isNotBlank() } ?: DEFAULT_COOKIE_PATH,
                        secure = cookie.secure,
                        value = cookie.value,
                    )
                }
        }
    }

    private fun HttpCookie.expirationDate(): Double? {
        return if (maxAge >= 0) {
            System.currentTimeMillis() / 1000.0 + maxAge
        } else {
            null
        }
    }

    private fun List<EditCookie>.toCookieHeader(): String {
        return joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    private fun JsonNode.optionalText(field: String): String? {
        val value = path(field)
        if (value.isMissingNode || value.isNull) return null
        return value.asText("").takeIf { it.isNotBlank() && it != "0" }
    }

    private companion object {
        private const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
        private const val DEFAULT_COOKIE_DOMAIN: String = ".bilibili.com"
        private const val DEFAULT_COOKIE_PATH: String = "/"
        private const val QR_EXPIRES_SECONDS: Long = 180
        private const val QR_GENERATE_URL: String =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val QR_POLL_URL: String =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        private const val NAV_URL: String = "https://api.bilibili.com/x/web-interface/nav"
    }
}
