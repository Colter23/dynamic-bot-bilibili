package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.bilibili.data.LazyImage
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.data.user.BiliUserNav
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskStatus
import kotlin.io.path.createTempDirectory
import java.util.LinkedHashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BilibiliPublisherPluginTest {

    @Test
    fun `fetchPublisherProfile should map gateway snapshot to publisher profile`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = BilibiliPublisherSnapshot(
                userId = "123",
                name = "demo-up",
                official = "official",
                faceUrl = "https://example.com/face.png",
                headerUrl = "https://example.com/header.png",
                pendantUrl = "https://example.com/pendant.png",
            ),
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val profile = plugin.fetchPublisherProfile("123")

        assertNotNull(profile)
        assertEquals("bilibili", profile.platform)
        assertEquals("123", profile.userId)
        assertEquals("demo-up", profile.name)
        assertEquals("https://example.com/face.png", profile.face.url)
    }

    @Test
    fun `queryFollowState should delegate to gateway`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val state = plugin.queryFollowState("123")

        assertEquals(FollowState.NOT_FOLLOWING, state)
    }

    @Test
    fun `followPublisher should delegate to gateway`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FAILED, "follow failed"),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val result = plugin.followPublisher("123")

        assertEquals(FollowActionStatus.FAILED, result.status)
        assertEquals("follow failed", result.message)
    }

    @Test
    fun `start should skip detection task when login check fails`() {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
        )
        val plugin = testPlugin(gateway, taskScheduler = scheduler)
        plugin.init()
        plugin.start()

        assertFalse(scheduler.isRunning("bilibili-detect"))
        assertEquals(null, scheduler.snapshot("bilibili-detect"))
    }

    @Test
    fun `loginByCookie should persist cookies and start detection task after startup skip`() = runBlocking {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
            cookieLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "cookie login success"),
            exportedCookiesJson = """[{"name":"SESSDATA","value":"demo"}]""",
        )
        var savedConfig: BilibiliPublisherConfig? = null
        val plugin = testPlugin(
            gateway,
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = scheduler,
        )
        plugin.init()
        plugin.start()

        assertFalse(scheduler.isRunning("bilibili-detect"))

        val result = plugin.loginByCookie("SESSDATA=demo")

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertTrue(scheduler.isRunning("bilibili-detect"))
        assertEquals("""[{"name":"SESSDATA","value":"demo"}]""", savedConfig?.cookiesJson)
    }

    @Test
    fun `loginByQrCode should persist cookies and start detection task after startup skip`() = runBlocking {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
            qrLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "qr login success"),
            exportedCookiesJson = """[{"name":"SESSDATA","value":"qr-demo"}]""",
        )
        var savedConfig: BilibiliPublisherConfig? = null
        val plugin = testPlugin(
            gateway,
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = scheduler,
        )
        plugin.init()
        plugin.start()

        assertFalse(scheduler.isRunning("bilibili-detect"))

        val result = plugin.loginByQrCode(onQrCode = {}, onStatusChanged = {})

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertTrue(scheduler.isRunning("bilibili-detect"))
        assertEquals("""[{"name":"SESSDATA","value":"qr-demo"}]""", savedConfig?.cookiesJson)
    }

    @Test
    fun `start and stop should manage detection task scheduler`() {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(gateway, taskScheduler = scheduler)
        plugin.init()

        plugin.start()
        assertTrue(scheduler.isRunning("bilibili-detect"))

        plugin.stop()
        assertFalse(scheduler.isRunning("bilibili-detect"))
        assertEquals(TaskStatus.CANCELLED, scheduler.snapshot("bilibili-detect")?.status)
    }

    @Test
    fun `poll service checkLoginState should map BiliLoginException to failed result`() = runBlocking {
        val service = BilibiliPollService(
            requestIntervalMs = 0,
            currentUserNavProvider = { throw BiliLoginException("not logged in") },
        )

        val result = service.checkLoginState()

        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertEquals("Bilibili is not logged in", result.message)
    }

    @Test
    fun `poll service qr login should delegate to auth gateway and emit challenge`() = runBlocking {
        val authGateway = FakeQrLoginGateway(
            result = QrCodeLoginResult(
                url = "",
                refreshToken = "",
                timestamp = 0,
                code = QrCodeLoginStatus.SUCCESS.code,
                message = "success",
            ),
            cookiesJson = "[]",
        )
        val service = BilibiliPollService(
            requestIntervalMs = 0,
            currentUserNavProvider = {
                BiliUserNav(
                    mid = 123L,
                    name = "demo",
                    face = LazyImage("https://example.com/face.png"),
                    official = null,
                    pendant = null,
                    vip = null,
                )
            },
            qrLoginGatewayFactory = { authGateway },
        )
        var qrContent: String? = null
        var status: PublisherLoginResult? = null

        val result = service.loginByQrCode(
            onQrCode = { challenge -> qrContent = challenge.qrContent },
            onStatusChanged = { update -> status = update },
        )

        assertEquals("https://example.com/qr", qrContent)
        assertEquals(PublisherLoginStatus.PENDING, status?.status)
        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
    }

    @Test
    fun `poll service qr login should map expired result`() = runBlocking {
        val authGateway = FakeQrLoginGateway(
            result = QrCodeLoginResult(
                url = "",
                refreshToken = "",
                timestamp = 0,
                code = QrCodeLoginStatus.EXPIRED.code,
                message = "expired",
            ),
            cookiesJson = "",
        )
        val service = BilibiliPollService(
            requestIntervalMs = 0,
            qrLoginGatewayFactory = { authGateway },
        )

        val result = service.loginByQrCode(onQrCode = {}, onStatusChanged = {})

        assertEquals(PublisherLoginStatus.EXPIRED, result.status)
    }

    private fun testPlugin(
        gateway: BilibiliPlatformGateway,
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler = TaskScheduler(),
        cursorStore: BilibiliCursorStore = InMemoryCursorStore(),
    ): BilibiliPublisherPlugin {
        val tempDir = createTempDirectory("dynamic-bot-bilibili-test").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
        return BilibiliPublisherPlugin(
            loadConfig = {
                BilibiliPublisherConfig(
                    pollingIntervalMs = 30_000,
                    subscriptionRefreshIntervalMs = 300_000,
                    fetchLimit = 5,
                    requestIntervalMs = 0,
                )
            },
            serviceFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
        )
    }

    private class FakeGateway(
        private val snapshot: BilibiliPublisherSnapshot?,
        private val followState: FollowState,
        private val followActionResult: FollowActionResult,
        private val loginStateResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "login success",
        ),
        private val cookieLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "cookie login success",
        ),
        private val qrLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.SUCCESS,
            "qr login success",
        ),
        private val exportedCookiesJson: String = "",
    ) : BilibiliPlatformGateway {
        override suspend fun fetchSubscribedLatest(limit: Int) = emptyList<top.colter.bilibili.data.dynamic.BiliDynamic>()

        override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? = snapshot

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult = followActionResult

        override suspend fun checkLoginState(): PublisherLoginResult = loginStateResult

        override suspend fun loginByCookie(cookie: String): PublisherLoginResult = cookieLoginResult

        override suspend fun loginByQrCode(
            onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
            onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        ): PublisherLoginResult {
            onQrCode(PublisherQrLoginChallenge(qrContent = "https://example.com/qr"))
            onStatusChanged(PublisherLoginResult(PublisherLoginStatus.PENDING, "waiting"))
            return qrLoginResult
        }

        override fun exportCookiesJson(): String = exportedCookiesJson
    }

    private class FakeQrLoginGateway(
        private val result: QrCodeLoginResult,
        private val cookiesJson: String,
    ) : BilibiliQrLoginGateway {
        override suspend fun loginByQrCode(
            onQrCode: suspend (QrCodeLoginData) -> Unit,
            onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
        ): BilibiliQrLoginOutcome {
            onQrCode(QrCodeLoginData("https://example.com/qr", "key"))
            onStatusChanged(
                QrCodeLoginResult(
                    url = "",
                    refreshToken = "",
                    timestamp = 0,
                    code = QrCodeLoginStatus.WAITING.code,
                    message = "waiting",
                )
            )
            return BilibiliQrLoginOutcome(result, cookiesJson)
        }
    }

    private class InMemoryCursorStore : BilibiliCursorStore {
        private val states: MutableMap<String, PublisherCursor> = linkedMapOf()

        override fun get(publisherId: String): PublisherCursor? = states[publisherId]

        override fun markSeen(publisherId: String, dynamicId: String, timestamp: Long): PublisherCursor {
            val previous = states[publisherId]
            val recent = LinkedHashSet(previous?.recentDynamicIds ?: emptyList())
            recent.add(dynamicId)
            while (recent.size > 50) {
                recent.remove(recent.first())
            }
            val updated = PublisherCursor(
                publisherId = publisherId.toInt(),
                lastSeenDynamicId = dynamicId,
                lastSeenAt = timestamp,
                recentDynamicIds = recent.toList(),
            )
            states[publisherId] = updated
            return updated
        }
    }
}
