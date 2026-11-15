package top.colter.dynamic.bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BilibiliSessionTest {
    @Test
    fun `QR login stays pending until account verification and retains refresh token`() = runBlocking {
        val verifying = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/nav")) { verifying.complete(Unit); finish.await() }
        }
        val states = mutableListOf<PublisherLoginStatus>()
        val service = server.service(qr = qr())
        try {
            val login = async { service.loginByQrCode({}, { states.add(it.status) }) }
            verifying.await()
            assertTrue(states.isNotEmpty())
            assertTrue(states.all { it == PublisherLoginStatus.PENDING })
            assertEquals("old", sessionValue(service))
            finish.complete(Unit)
            assertEquals(PublisherLoginStatus.SUCCESS, login.await().status)
            assertEquals("qr", sessionValue(service))
            assertEquals("long-token", service.exportSession().refreshToken)
            assertEquals("123", service.exportSession().accountId)
            assertFalse(service.exportSession().toString().contains("long-token"))
        } finally { service.close() }
    }

    @Test
    fun `failed QR validation preserves previous session and reports no success`() = runBlocking {
        val server = Server()
        server.loggedIn = false
        val service = server.service(qr = qr())
        val states = mutableListOf<PublisherLoginStatus>()
        try {
            val result = service.loginByQrCode({}, { states.add(it.status) })
            assertEquals(PublisherLoginStatus.FAILED, result.status)
            assertFalse(PublisherLoginStatus.SUCCESS in states)
            assertEquals("old", sessionValue(service))
            assertEquals("", service.exportSession().refreshToken)
        } finally { service.close() }
    }

    @Test
    fun `late older login cannot replace newer account`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/nav") && request.headers["Cookie"].orEmpty().contains("SESSDATA=first")) {
                firstEntered.complete(Unit)
                finishFirst.await()
            }
        }
        val service = server.service()
        try {
            val first = async { service.loginByCookie("SESSDATA=first; bili_jct=csrf") }
            firstEntered.await()
            assertEquals(PublisherLoginStatus.SUCCESS, service.loginByCookie("SESSDATA=second; bili_jct=csrf").status)
            finishFirst.complete(Unit)
            assertEquals(PublisherLoginStatus.CANCELED, first.await().status)
            assertEquals("second", sessionValue(service))
        } finally { service.close() }
    }

    @Test
    fun `canceled and timed out login preserve active session`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/nav")) { entered.complete(Unit); awaitCancellation() }
        }
        val service = server.service(validationTimeoutMillis = 80)
        try {
            assertEquals(PublisherLoginStatus.FAILED, service.loginByCookie("SESSDATA=new").status)
            assertEquals("old", sessionValue(service))
            val canceled = async(start = CoroutineStart.UNDISPATCHED) { service.loginByCookie("SESSDATA=new") }
            entered.await()
            canceled.cancelAndJoin()
            assertTrue(canceled.isCancelled)
            assertEquals("old", sessionValue(service))
        } finally { service.close() }
    }

    @Test
    fun `QR has strict total timeout and closes gateway`() = runBlocking {
        var closed = false
        val gateway = object : BilibiliQrLoginGateway {
            override suspend fun loginByQrCode(onQrCode: suspend (QrCodeLoginData) -> Unit, onStatusChanged: suspend (QrCodeLoginResult) -> Unit): BilibiliQrLoginOutcome {
                onQrCode(QrCodeLoginData("https://account.bilibili.com/qr", "test"))
                awaitCancellation()
            }
            override fun close() { closed = true }
        }
        val server = Server()
        val service = server.service(qr = gateway, loginTimeoutMillis = 80)
        try {
            assertEquals(PublisherLoginStatus.EXPIRED, service.loginByQrCode({}, {}).status)
            assertTrue(closed)
            assertEquals("old", sessionValue(service))
        } finally { service.close() }
    }

    @Test
    fun `gateway accepts latest QR shape and retries transient read without browser SSO`() = runBlocking {
        val polls = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()
        val mock = MockBiliClient { request ->
            requests.add(request.url.encodedPath)
            if (request.url.encodedPath.endsWith("/generate")) {
                respondJson("""{"code":0,"message":"OK","data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=test","qrcode_key":"test"}}""")
            } else {
                when (polls.incrementAndGet()) {
                    1 -> throw IOException("network")
                    2 -> respondJson("""{"code":0,"message":"OK","data":{"code":86090,"message":"scanned"}}""")
                    else -> respondJson("""{"code":0,"message":"OK","data":{"code":0,"message":"","refresh_token":"long-token","timestamp":1794701166288}}""", cookies("qr"))
                }
            }
        }
        val gateway = BilibiliClientQrLoginGateway(mock, pollIntervalMs = 1)
        try {
            var content = ""
            val outcome = gateway.loginByQrCode({ content = it.url }, {})
            assertTrue(content.contains("scan-web"))
            assertEquals("long-token", outcome.result.refreshToken)
            assertTrue(outcome.cookiesJson.contains("SESSDATA"))
            assertTrue(requests.all { it.endsWith("/generate") || it.endsWith("/poll") })
            assertEquals(3, polls.get())
        } finally { gateway.close() }
    }

    @Test
    fun `daily checks single flight refresh verify account then confirm only after saving`() = runBlocking {
        val server = Server()
        val service = server.service()
        try {
            server.restore(service)
            (1..8).map { async { service.refreshCookieSession() } }.awaitAll()
            assertEquals(1, server.refreshes.get())
            assertEquals("renewed", sessionValue(service))
            assertEquals("new-long-token", service.exportSession().refreshToken)
            assertEquals("long-token", service.exportSession().pendingConfirmationToken)
            assertEquals(0, server.confirms.get())
            val snapshot = service.exportSessionSnapshot()
            service.confirmSessionSaved(snapshot.state.copy(credentialDigest = "wrong"))
            assertEquals(0, server.confirms.get())
            service.confirmSessionSaved(snapshot.state)
            assertEquals(1, server.confirms.get())
            assertEquals("", service.exportSession().pendingConfirmationToken)
            service.refreshCookieSession()
            assertEquals(1, server.refreshes.get())
        } finally { service.close() }
    }

    @Test
    fun `refresh keeps previous cookies when account changes or response is visitor`() = runBlocking {
        for (visitor in listOf(false, true)) {
            val server = Server()
            if (visitor) server.loggedIn = false else server.uid = "999"
            val service = server.service()
            try {
                server.restore(service)
                try { service.refreshCookieSession() } catch (_: Exception) { }
                assertEquals("old", sessionValue(service))
                assertEquals("long-token", service.exportSession().refreshToken)
                assertEquals(0, server.confirms.get())
            } finally { service.close() }
        }
    }

    @Test
    fun `refresh response without new login cookie cannot confirm old session`() = runBlocking {
        val server = Server()
        server.issueRefreshCookies = false
        val service = server.service()
        try {
            server.restore(service)
            try { service.refreshCookieSession() } catch (_: BilibiliSessionException) { }
            assertEquals(1, server.refreshes.get())
            assertEquals("old", sessionValue(service))
            assertEquals("long-token", service.exportSession().refreshToken)
            assertEquals("", service.exportSession().pendingConfirmationToken)
            assertTrue(service.persistSession { true })
            assertEquals(0, server.confirms.get())
        } finally { service.close() }
    }

    @Test
    fun `expired cookies retain token binding across restart without being sent`() = runBlocking {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/cookie/info")) assertTrue(request.headers["Cookie"].isNullOrBlank())
        }
        val service = server.service()
        try {
            val expired = cookieJson("expired").replace("\"path\":\"/\"", "\"expirationDate\":1,\"path\":\"/\"")
            service.importCookiesJson(expired)
            service.importSession(BilibiliStoredSession(
                credentialDigest = bilibiliCredentialDigest(expired), accountId = "123", refreshToken = "long-token",
            ))
            val snapshot = service.exportSessionSnapshot()
            val restarted = server.service()
            try {
                restarted.importCookiesJson(snapshot.cookiesJson)
                restarted.importSession(snapshot.state)
                assertEquals("long-token", restarted.exportSession().refreshToken)
                restarted.refreshCookieSession()
                assertEquals("renewed", sessionValue(restarted))
                assertEquals(1, server.refreshes.get())
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `no credentials causes zero maintenance requests and missing token does not refresh`() = runBlocking {
        val server = Server()
        val service = server.service()
        try {
            service.refreshCookieSession()
            assertTrue(server.paths.isEmpty())
            assertEquals("", service.exportSession().refreshToken)
            service.importCookiesJson("[]")
            assertEquals(PublisherLoginStatus.FAILED, service.checkLoginState().status)
            assertTrue(server.paths.isEmpty())
        } finally { service.close() }
    }

    @Test
    fun `server not requiring refresh only checked once per day and after restart`() = runBlocking {
        var now = 2 * DAY
        val server = Server()
        server.refreshRequired = false
        val service = server.service(clock = { now })
        try {
            server.restore(service)
            service.refreshCookieSession()
            service.refreshCookieSession()
            assertEquals(1, server.infos.get())
            val snapshot = service.exportSessionSnapshot()
            val restarted = server.service(clock = { now })
            try {
                restarted.importCookiesJson(snapshot.cookiesJson)
                restarted.importSession(snapshot.state)
                restarted.refreshCookieSession()
                assertEquals(1, server.infos.get())
                now += DAY
                restarted.refreshCookieSession()
                assertEquals(2, server.infos.get())
                assertEquals(0, server.refreshes.get())
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `failed refresh backs off across restart and never replays POST`() = runBlocking {
        var now = 2 * DAY
        val server = Server()
        server.refreshFailure = true
        val service = server.service(clock = { now })
        try {
            server.restore(service)
            try { service.refreshCookieSession() } catch (_: Exception) { }
            assertEquals(1, server.refreshes.get())
            assertEquals(1, service.exportSession().failureCount)
            val snapshot = service.exportSessionSnapshot()
            val restarted = server.service(clock = { now })
            try {
                restarted.importCookiesJson(snapshot.cookiesJson)
                restarted.importSession(snapshot.state)
                restarted.refreshCookieSession()
                assertEquals(1, server.refreshes.get())
                now += 5 * 60_000L
                try { restarted.refreshCookieSession() } catch (_: Exception) { }
                assertEquals(2, server.refreshes.get())
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `credential replacement ignores old refresh token metadata`() = runBlocking {
        val server = Server()
        val service = server.service()
        try {
            server.restore(service)
            val state = service.exportSession()
            service.importCookiesJson(cookieJson("another-account"))
            service.importSession(state)
            assertEquals("", service.exportSession().refreshToken)
            service.refreshCookieSession()
            assertEquals(0, server.infos.get())
        } finally { service.close() }
    }

    @Test
    fun `pending confirmation survives restart with new token`() = runBlocking {
        val server = Server()
        val service = server.service()
        try {
            server.restore(service)
            service.refreshCookieSession()
            val saved = service.exportSessionSnapshot()
            val restarted = server.service()
            try {
                restarted.importCookiesJson(saved.cookiesJson)
                restarted.importSession(saved.state)
                assertEquals("new-long-token", restarted.exportSession().refreshToken)
                restarted.confirmSessionSaved(saved.state)
                assertEquals(1, server.confirms.get())
                assertEquals(1, server.refreshes.get())
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `failed disk save never confirms and succeeding retry confirms once`() = runBlocking {
        val server = Server()
        val service = server.service()
        try {
            server.restore(service)
            service.refreshCookieSession()
            assertFalse(service.persistSession { false })
            assertEquals(0, server.confirms.get())
            val saves = mutableListOf<BilibiliSessionSnapshot>()
            assertTrue(service.persistSession { saves.add(it); true })
            assertEquals(1, server.confirms.get())
            assertEquals("long-token", saves.first().state.pendingConfirmationToken)
            assertEquals("", saves.last().state.pendingConfirmationToken)
            assertTrue(saves.all { it.state.refreshToken == "new-long-token" })
        } finally { service.close() }
    }

    @Test
    fun `confirmation network failure backs off across restart and later succeeds`() = runBlocking {
        var now = 2 * DAY
        val server = Server().apply { confirmationNetworkFailure = true }
        val service = server.service(clock = { now })
        try {
            server.restore(service)
            service.refreshCookieSession()
            assertTrue(service.persistSession { true })
            assertEquals(1, server.confirms.get())
            val snapshot = service.exportSessionSnapshot()
            assertEquals(1, snapshot.state.failureCount)
            assertEquals(now + 5 * 60_000L, snapshot.state.nextAttemptAtMillis)
            val restarted = server.service(clock = { now })
            try {
                restarted.importCookiesJson(snapshot.cookiesJson)
                restarted.importSession(snapshot.state)
                assertTrue(restarted.persistSession { true })
                assertEquals(1, server.confirms.get())
                now += 5 * 60_000L
                server.confirmationNetworkFailure = false
                assertTrue(restarted.persistSession { true })
                assertEquals(2, server.confirms.get())
                assertEquals("", restarted.exportSession().pendingConfirmationToken)
                assertEquals(0, restarted.exportSession().failureCount)
                assertEquals(0, restarted.exportSession().nextAttemptAtMillis)
                assertFalse(restarted.exportSession().requiresLogin)
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `permanent confirmation rejection keeps new session and requests re-login without retry loop`() = runBlocking {
        var now = 2 * DAY
        val server = Server().apply { confirmationCode = -111 }
        val service = server.service(clock = { now })
        try {
            server.restore(service)
            service.refreshCookieSession()
            assertTrue(service.persistSession { true })
            assertEquals("renewed", sessionValue(service))
            assertTrue(service.exportSession().requiresLogin)
            assertEquals("", service.exportSession().pendingConfirmationToken)
            assertTrue(service.checkLoginState().message.contains("重新扫码"))
            assertEquals(PublisherLoginStatus.SUCCESS, service.checkLoginState().status)
            now += 3 * DAY
            service.refreshCookieSession()
            assertTrue(service.persistSession { true })
            assertEquals(1, server.refreshes.get())
            assertEquals(1, server.confirms.get())
            assertEquals(PublisherLoginStatus.SUCCESS, service.loginByQrCode({}, {}).status)
            assertFalse(service.exportSession().requiresLogin)
            assertEquals("", service.exportSession().renewalFailureReason)
        } finally { service.close() }
    }

    @Test
    fun `confirmation has bounded retries and persists terminal warning across restart`() = runBlocking {
        var now = 2 * DAY
        val server = Server().apply { confirmationNetworkFailure = true }
        val service = server.service(clock = { now })
        try {
            server.restore(service)
            service.refreshCookieSession()
            for (attempt in 1..4) {
                assertTrue(service.persistSession { true })
                assertEquals(attempt, server.confirms.get())
                val state = service.exportSession()
                assertEquals(attempt, state.failureCount)
                if (attempt < 4) {
                    assertFalse(state.requiresLogin)
                    assertTrue(state.pendingConfirmationToken.isNotBlank())
                    val delay = listOf(5 * 60_000L, 30 * 60_000L, 6 * 60 * 60_000L)[attempt - 1]
                    assertEquals(now + delay, state.nextAttemptAtMillis)
                    now += delay
                }
            }
            val snapshot = service.exportSessionSnapshot()
            assertTrue(snapshot.state.requiresLogin)
            assertEquals("", snapshot.state.pendingConfirmationToken)
            val restarted = server.service(clock = { now })
            try {
                restarted.importCookiesJson(snapshot.cookiesJson)
                restarted.importSession(snapshot.state)
                assertTrue(restarted.checkLoginState().message.contains("重新扫码"))
                now += 3 * DAY
                restarted.refreshCookieSession()
                assertTrue(restarted.persistSession { true })
                assertEquals(1, server.refreshes.get())
                assertEquals(4, server.confirms.get())
            } finally { restarted.close() }
        } finally { service.close() }
    }

    @Test
    fun `confirmation timeout records backoff instead of canceling caller`() = runBlocking {
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/confirm/refresh")) awaitCancellation()
        }
        val service = server.service(protocol = BilibiliSessionProtocol(requestTimeoutMillis = 100))
        try {
            server.restore(service)
            service.refreshCookieSession()
            assertTrue(service.persistSession { true })
            assertEquals(1, service.exportSession().failureCount)
            assertTrue(service.exportSession().pendingConfirmationToken.isNotBlank())
        } finally { service.close() }
    }

    @Test
    fun `late confirmation rejection cannot disable newer login`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/confirm/refresh")) { entered.complete(Unit); finish.await() }
        }.apply { confirmationCode = -111 }
        val service = server.service()
        try {
            server.restore(service)
            service.refreshCookieSession()
            val confirm = async { service.persistSession { true } }
            entered.await()
            assertEquals(PublisherLoginStatus.SUCCESS, service.loginByCookie("SESSDATA=manual; bili_jct=csrf").status)
            finish.complete(Unit)
            assertTrue(confirm.await())
            assertEquals("manual", sessionValue(service))
            assertFalse(service.exportSession().requiresLogin)
            assertEquals(0, service.exportSession().failureCount)
        } finally { service.close() }
    }

    @Test
    fun `late refresh cannot overwrite explicit login`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val server = Server { request ->
            if (request.url.encodedPath.endsWith("/cookie/refresh")) { entered.complete(Unit); finish.await() }
        }
        val service = server.service()
        try {
            server.restore(service)
            val refresh = async { service.refreshCookieSession() }
            entered.await()
            assertEquals(PublisherLoginStatus.SUCCESS, service.loginByCookie("SESSDATA=manual; bili_jct=csrf").status)
            finish.complete(Unit)
            refresh.await()
            assertEquals("manual", sessionValue(service))
            assertEquals("", service.exportSession().refreshToken)
            assertEquals(0, server.confirms.get())
        } finally { service.close() }
    }

    @Test
    fun `maintenance cannot replace an already pending QR login`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val gateway = object : BilibiliQrLoginGateway {
            override suspend fun loginByQrCode(onQrCode: suspend (QrCodeLoginData) -> Unit, onStatusChanged: suspend (QrCodeLoginResult) -> Unit): BilibiliQrLoginOutcome {
                onQrCode(QrCodeLoginData("https://account.bilibili.com/h5/account-h5/auth/scan-web", "test"))
                entered.complete(Unit)
                finish.await()
                return BilibiliQrLoginOutcome(QrCodeLoginResult(code = 0, message = "", refreshToken = "qr-token"), cookieJson("qr"))
            }
        }
        val server = Server()
        val service = server.service(qr = gateway)
        try {
            server.restore(service)
            val login = async { service.loginByQrCode({}, {}) }
            entered.await()
            service.refreshCookieSession()
            assertEquals(0, server.infos.get())
            finish.complete(Unit)
            assertEquals(PublisherLoginStatus.SUCCESS, login.await().status)
            assertEquals("qr-token", service.exportSession().refreshToken)
            assertEquals("qr", sessionValue(service))
        } finally { service.close() }
    }

    @Test
    fun `unauthenticated reads recover once and write operations never replay`() = runBlocking {
        for (write in listOf(false, true)) {
            val server = Server()
            server.readNeedsRefresh = true
            val service = server.service()
            try {
                server.restore(service)
                if (write) {
                    try { service.unfollowPublisher("456") } catch (_: Exception) { }
                    assertEquals(0, server.refreshes.get())
                    assertEquals(1, server.reads.get())
                } else {
                    assertTrue(service.fetchNewDynamicPage().items.isEmpty())
                    assertEquals(1, server.refreshes.get())
                    assertEquals(2, server.reads.get())
                }
            } finally { service.close() }
        }
    }

    @Test
    fun `failed recovery preserves unauthenticated error for polling pause`() = runBlocking {
        val server = Server()
        server.readNeedsRefresh = true
        server.refreshFailure = true
        val service = server.service()
        try {
            server.restore(service)
            var error: Exception? = null
            try { service.fetchNewDynamicPage() } catch (e: Exception) { error = e }
            assertTrue(error is BiliLoginException)
            assertEquals(1, server.reads.get())
            assertEquals(1, server.refreshes.get())
        } finally { service.close() }
    }

    @Test
    fun `cookie normalization preserves domain path expiration and deletion across restart`() = runBlocking {
        val client = MockBiliClient { request ->
            respondJson("{}", listOf(
                "SESSDATA=new; Domain=bilibili.com; Path=/; Secure; HttpOnly",
                "bili_jct=gone; Domain=.bilibili.com; Path=/; Max-Age=0",
            ))
        }
        try {
            importBilibiliCookies(client, cookieJson("old"))
            client.clients.first().get("https://passport.bilibili.com/test")
            val exported = exportBilibiliCookies(client)
            assertFalse(exported.contains("\"value\":\"old\""))
            assertFalse(exported.contains("bili_jct"))
            val cookies = top.colter.bilibili.tools.json.decodeFromString<List<top.colter.bilibili.data.EditCookie>>(exported)
            assertEquals(1, cookies.count { it.name == "SESSDATA" })
            assertTrue(cookies.single().httpOnly)
            assertTrue(cookies.single().secure)
            importBilibiliCookies(client, exported)
            assertEquals("new", client.storage.getCookies("https://api.bilibili.com/").single().value)
            assertTrue(client.storage.getCookies("https://passport.biligame.com/").isEmpty())
        } finally { client.close() }
    }

    @Test
    fun `credential binding ignores device cookie rotation but not changed login`() {
        val before = cookieJson("old")
        val withDevice = before.dropLast(1) + """,{"domain":"bilibili.com","httpOnly":false,"name":"bili_ticket","path":"/","secure":true,"value":"device"}]"""
        assertEquals(bilibiliCredentialDigest(before), bilibiliCredentialDigest(withDevice))
        assertNotEquals(bilibiliCredentialDigest(before), bilibiliCredentialDigest(cookieJson("new")))
    }

    @Test
    fun `refresh RSA path has expected length and randomized padding`() {
        val first = BilibiliSessionProtocol.correspondPath(1794701166288)
        assertTrue(first.matches(Regex("[a-f0-9]{256}")))
        assertNotEquals(first, BilibiliSessionProtocol.correspondPath(1794701166288))
    }

    private class Server(val before: suspend (HttpRequestData) -> Unit = {}) {
        val paths = CopyOnWriteArrayList<String>()
        val infos = AtomicInteger()
        val refreshes = AtomicInteger()
        val confirms = AtomicInteger()
        val reads = AtomicInteger()
        var loggedIn = true
        var uid = "123"
        var refreshRequired = true
        var refreshFailure = false
        var issueRefreshCookies = true
        var readNeedsRefresh = false
        var confirmationNetworkFailure = false
        var confirmationCode = 0

        fun client(): BiliClient = MockBiliClient { request ->
            paths.add(request.url.encodedPath)
            before(request)
            when {
                request.url.encodedPath.endsWith("/feed/all") || request.url.encodedPath.endsWith("/relation/modify") -> {
                    reads.incrementAndGet()
                    if (readNeedsRefresh && !request.headers["Cookie"].orEmpty().contains("SESSDATA=renewed")) {
                        respondJson("""{"code":-101,"message":"not logged in"}""")
                    } else respondJson("""{"code":0,"message":"OK","data":{"has_more":false,"offset":"","update_baseline":"","update_num":"0","items":[]}}""")
                }
                request.url.encodedPath.endsWith("/nav") -> respondJson("""{"code":0,"message":"OK","data":{"isLogin":$loggedIn,"mid":$uid,"uname":"demo","face":"https://i0.hdslb.com/avatar.png"}}""")
                request.url.encodedPath.endsWith("/cookie/info") -> {
                    infos.incrementAndGet()
                    respondJson("""{"code":0,"message":"OK","data":{"refresh":$refreshRequired,"timestamp":1794701166288}}""")
                }
                request.url.encodedPath.contains("/correspond/1/") -> respondJson("""<html><div id="1-name">refresh-csrf</div></html>""")
                request.url.encodedPath.endsWith("/cookie/refresh") -> {
                    refreshes.incrementAndGet()
                    assertEquals("long-token", (request.body as FormDataContent).formData["refresh_token"])
                    if (refreshFailure) throw IOException("secret-bearing URL must not be logged")
                    respondJson("""{"code":0,"message":"OK","data":{"refresh_token":"new-long-token"}}""", if (issueRefreshCookies) cookies("renewed") else emptyList())
                }
                request.url.encodedPath.endsWith("/confirm/refresh") -> {
                    confirms.incrementAndGet()
                    assertTrue(request.headers["Cookie"].orEmpty().contains("SESSDATA=renewed"))
                    assertEquals("long-token", (request.body as FormDataContent).formData["refresh_token"])
                    if (confirmationNetworkFailure) throw IOException("test network failure")
                    respondJson("""{"code":$confirmationCode,"message":"mock confirmation response"}""")
                }
                else -> error("Unexpected mock request: ${request.url.encodedPath}")
            }
        }

        suspend fun service(
            qr: BilibiliQrLoginGateway = qr(),
            clock: () -> Long = { 2 * DAY },
            loginTimeoutMillis: Long = 210_000,
            validationTimeoutMillis: Long = 15_000,
            protocol: BilibiliSessionProtocol = BilibiliSessionProtocol(),
        ): BilibiliPollService {
            val active = client()
            active.importEditCookiesJson(cookieJson("old"), true)
            return BilibiliPollService(0, active, qrLoginGatewayFactory = { qr }, sessionClientFactory = ::client,
                clockMillis = clock, loginTimeoutMillis = loginTimeoutMillis, validationTimeoutMillis = validationTimeoutMillis,
                sessionProtocol = protocol)
        }

        fun restore(service: BilibiliPollService) {
            service.importSession(BilibiliStoredSession(
                credentialDigest = bilibiliCredentialDigest(service.exportCookiesJson()),
                accountId = "123", refreshToken = "long-token",
            ))
        }
    }

    private class MockBiliClient(private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) : BiliClient() {
        override val retry = 0
        override val clientCount = 1
        override fun initClient(): HttpClient = HttpClient(MockEngine(handler)) {
            followRedirects = false
            install(HttpCookies) {
                storage = BilibiliResponseCookies(this@MockBiliClient)
            }
        }
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        private fun qr() = object : BilibiliQrLoginGateway {
            override suspend fun loginByQrCode(onQrCode: suspend (QrCodeLoginData) -> Unit, onStatusChanged: suspend (QrCodeLoginResult) -> Unit): BilibiliQrLoginOutcome {
                onQrCode(QrCodeLoginData("https://account.bilibili.com/h5/account-h5/auth/scan-web", "test-key"))
                val result = QrCodeLoginResult(code = QrCodeLoginStatus.SUCCESS.code, message = "", refreshToken = "long-token")
                onStatusChanged(result)
                return BilibiliQrLoginOutcome(result, cookieJson("qr"))
            }
        }

        private fun cookieJson(value: String) = """[{"domain":".bilibili.com","httpOnly":true,"name":"SESSDATA","path":"/","secure":true,"value":"$value"},{"domain":".bilibili.com","httpOnly":false,"name":"bili_jct","path":"/","secure":true,"value":"csrf"}]"""
        private fun cookies(value: String) = listOf("SESSDATA=$value; Domain=bilibili.com; Path=/; Secure; HttpOnly", "bili_jct=csrf; Domain=bilibili.com; Path=/; Secure")
        private fun MockRequestHandleScope.respondJson(body: String, cookies: List<String> = emptyList()) = respond(body, HttpStatusCode.OK,
            headersOf(*((listOf("Content-Type" to listOf("application/json"))) + if (cookies.isEmpty()) emptyList() else listOf("Set-Cookie" to cookies)).toTypedArray()))
        private fun sessionValue(service: BilibiliPollService): String = top.colter.bilibili.tools.json.parseToJsonElement(service.exportCookiesJson()).let { value ->
            (value as kotlinx.serialization.json.JsonArray).first { it.toString().contains("\"SESSDATA\"") }
                .let { (it as kotlinx.serialization.json.JsonObject)["value"].toString().trim('"') }
        }
    }
}
