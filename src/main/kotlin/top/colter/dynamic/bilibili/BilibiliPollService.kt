package top.colter.dynamic.bilibili

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.bilibili.api.follow
import top.colter.bilibili.api.getCurrentUserNav
import top.colter.bilibili.api.getDynamicDetail
import top.colter.bilibili.api.getNewDynamic
import top.colter.bilibili.api.getGroupList
import top.colter.bilibili.api.getUserInfo
import top.colter.bilibili.api.modifyGroupUsers
import top.colter.bilibili.api.isFollow
import top.colter.bilibili.api.createGroup
import top.colter.bilibili.auth.qrCode
import top.colter.bilibili.client.BiliAuthClient
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.EditCookie
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicList
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.data.user.BiliUserNav
import top.colter.bilibili.data.user.BiliGroup
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URI

internal data class BilibiliPublisherSnapshot(
    val userId: String,
    val name: String,
    val official: String? = null,
    val faceUrl: String,
    val headerUrl: String? = null,
    val pendantUrl: String? = null,
)

internal interface BilibiliPlatformGateway {
    suspend fun fetchNewDynamicPage(page: Int = 1, type: String = "all"): BiliDynamicList {
        throw UnsupportedOperationException("dynamic page fetch is unsupported")
    }

    suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        throw UnsupportedOperationException("dynamic detail fetch is unsupported")
    }

    suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
        throw UnsupportedOperationException("short URL expansion is unsupported")
    }

    suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot?

    suspend fun queryFollowState(userId: String): FollowState

    suspend fun followPublisher(userId: String): FollowActionResult

    suspend fun fetchFollowGroups(): List<BiliGroup> {
        throw UnsupportedOperationException("follow group api is unsupported")
    }

    suspend fun createFollowGroup(tag: String) {
        throw UnsupportedOperationException("follow group api is unsupported")
    }

    suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
        throw UnsupportedOperationException("follow group api is unsupported")
    }

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "login status check is unsupported",
        )
    }

    suspend fun importCookiesJson(cookiesJson: String) {
    }

    fun exportCookiesJson(): String = ""

    suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "cookie login is unsupported",
        )
    }

    suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit = { _ -> },
    ): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "QR code login is unsupported",
        )
    }
}

internal data class BilibiliQrLoginOutcome(
    val result: QrCodeLoginResult,
    val cookiesJson: String,
)

internal interface BilibiliQrLoginGateway {
    suspend fun loginByQrCode(
        onQrCode: suspend (QrCodeLoginData) -> Unit,
        onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
    ): BilibiliQrLoginOutcome
}

internal class BilibiliClientQrLoginGateway(
    private val client: BiliAuthClient = BiliAuthClient(),
    private val pollIntervalMs: Long = 1000,
    private val timeoutMs: Long = 180_000,
) : BilibiliQrLoginGateway {
    override suspend fun loginByQrCode(
        onQrCode: suspend (QrCodeLoginData) -> Unit,
        onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
    ): BilibiliQrLoginOutcome {
        val result = client.qrCode(pollIntervalMs, timeoutMs, onQrCode, onStatusChanged)
        return BilibiliQrLoginOutcome(
            result = result,
            cookiesJson = client.exportEditCookiesJson(),
        )
    }
}

internal class BilibiliPollService(
    private val requestIntervalMs: Long,
    private val client: BiliClient = BiliClient(),
    private val currentUserNavProvider: suspend () -> BiliUserNav = { client.getCurrentUserNav() },
    private val qrLoginGatewayFactory: () -> BilibiliQrLoginGateway = { BilibiliClientQrLoginGateway() },
) : BilibiliPlatformGateway {
    override suspend fun fetchNewDynamicPage(page: Int, type: String): BiliDynamicList {
        val list = client.getNewDynamic(page, type)
        applyRequestDelay()
        return list
    }

    override suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        val id = dynamicId.toLongOrNull() ?: return null
        val detail = client.getDynamicDetail(id)
        applyRequestDelay()
        return detail
    }

    override suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(1)
        return withTimeoutOrNull(boundedTimeoutMs) {
            withContext(Dispatchers.IO) {
                expandRedirects(url, boundedTimeoutMs.toInt().coerceAtLeast(1))
            }
        }
    }

    override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? {
        val uid = userId.toLongOrNull() ?: return null
        val info = client.getUserInfo(uid)
        applyRequestDelay()
        return BilibiliPublisherSnapshot(
            userId = info.mid.toString(),
            name = info.name,
            official = info.official?.title?.takeIf { it.isNotBlank() }
                ?: info.official?.desc?.takeIf { it.isNotBlank() },
            faceUrl = info.face.url,
            headerUrl = null,
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

    override suspend fun fetchFollowGroups(): List<BiliGroup> {
        val groups = client.getGroupList()
        applyRequestDelay()
        return groups
    }

    override suspend fun createFollowGroup(tag: String) {
        client.createGroup(tag)
        applyRequestDelay()
    }

    override suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
        client.modifyGroupUsers(fids, tagIds)
        applyRequestDelay()
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return try {
            val nav = currentUserNavProvider()
            PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "login success",
                account = PublisherLoginAccount(
                    userId = nav.mid.takeIf { it > 0 }?.toString(),
                    name = nav.name.takeIf { it.isNotBlank() },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BiliLoginException) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Bilibili is not logged in",
            )
        } catch (e: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = e.message ?: "login check failed",
            )
        } finally {
            applyRequestDelay()
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

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return try {
            val outcome = qrLoginGatewayFactory().loginByQrCode(
                onQrCode = { qrCodeData -> onQrCode(qrCodeData.toChallenge()) },
                onStatusChanged = { qrCodeResult -> onStatusChanged(qrCodeResult.toPublisherLoginResult()) },
            )
            if (outcome.result.status == QrCodeLoginStatus.SUCCESS) {
                importAndVerifyCookies {
                    client.importEditCookiesJson(outcome.cookiesJson, true)
                }
            } else {
                outcome.result.toPublisherLoginResult()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.toQrFailureResult()
        }
    }

    private suspend fun applyRequestDelay() {
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
    }

    private suspend fun importAndVerifyCookies(importer: suspend () -> Unit): PublisherLoginResult {
        val previousCookiesJson = client.exportEditCookiesJson()
        return try {
            importer()
            val result = checkLoginState()
            if (result.status != PublisherLoginStatus.SUCCESS) {
                restoreCookies(previousCookiesJson)
            }
            result
        } catch (e: CancellationException) {
            restoreCookies(previousCookiesJson)
            throw e
        } catch (e: Throwable) {
            restoreCookies(previousCookiesJson)
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = e.message ?: "login failed",
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

    private fun QrCodeLoginData.toChallenge(): PublisherQrLoginChallenge {
        return PublisherQrLoginChallenge(
            qrContent = url,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + QR_EXPIRES_SECONDS,
            message = "waiting for Bilibili app scan confirmation",
        )
    }

    private fun QrCodeLoginResult.toPublisherLoginResult(): PublisherLoginResult {
        val resolvedMessage = message.ifBlank { status.text }
        return when (status) {
            QrCodeLoginStatus.SUCCESS -> PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = resolvedMessage.ifBlank { "login success" },
            )
            QrCodeLoginStatus.EXPIRED -> PublisherLoginResult(
                status = PublisherLoginStatus.EXPIRED,
                message = resolvedMessage.ifBlank { "QR code expired" },
            )
            QrCodeLoginStatus.SCANNED -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = resolvedMessage.ifBlank { "QR code scanned, waiting for confirmation" },
            )
            QrCodeLoginStatus.WAITING -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = resolvedMessage.ifBlank { "waiting for QR code scan" },
            )
            QrCodeLoginStatus.UNKNOWN -> PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = resolvedMessage.ifBlank { "Bilibili QR login failed: code=$code" },
            )
        }
    }

    private fun Throwable.toQrFailureResult(): PublisherLoginResult {
        val reason = message ?: "QR code login failed"
        val status = if (reason.contains("失效") || reason.contains("超时") || reason.contains("expired", ignoreCase = true)) {
            PublisherLoginStatus.EXPIRED
        } else {
            PublisherLoginStatus.FAILED
        }
        return PublisherLoginResult(status, reason)
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

    private fun expandRedirects(initialUrl: String, timeoutMs: Int): String? {
        var current = initialUrl
        repeat(MAX_SHORT_URL_REDIRECTS) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return null
            val connection = runCatching { uri.toURL().openConnection() as? HttpURLConnection }
                .getOrNull()
                ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val statusCode = connection.responseCode
                if (statusCode in HTTP_REDIRECT_RANGE) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = uri.resolve(location).toString()
                    return@repeat
                }
                return current
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    private companion object {
        private const val DEFAULT_COOKIE_DOMAIN: String = ".bilibili.com"
        private const val DEFAULT_COOKIE_PATH: String = "/"
        private const val QR_EXPIRES_SECONDS: Long = 180
        private const val MAX_SHORT_URL_REDIRECTS: Int = 5
        private const val USER_AGENT: String = "dynamic-bot/0.0.3"
        private val HTTP_REDIRECT_RANGE: IntRange = 300..399
    }
}
