package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.PersistenceManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
    fun `loginByQrCode should persist cookies on success`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            qrLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "login success"),
            exportedCookiesJson = """[{"name":"SESSDATA","value":"demo"}]""",
        )
        var savedConfig: BilibiliPublisherConfig? = null
        val plugin = testPlugin(gateway) { _, config -> savedConfig = config }
        plugin.init()

        val result = plugin.loginByQrCode(onQrCode = {}, onStatusChanged = {})

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("""[{"name":"SESSDATA","value":"demo"}]""", savedConfig?.cookiesJson)
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
            qrLoginGatewayFactory = { authGateway },
            loginVerifier = { PublisherLoginResult(PublisherLoginStatus.SUCCESS, "verified") },
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
            loginVerifier = { PublisherLoginResult(PublisherLoginStatus.SUCCESS, "verified") },
        )

        val result = service.loginByQrCode(onQrCode = {}, onStatusChanged = {})

        assertEquals(PublisherLoginStatus.EXPIRED, result.status)
    }

    private fun testPlugin(
        gateway: BilibiliPlatformGateway,
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
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
                    cursorPath = tempDir.resolve("cursor.yml").path,
                )
            },
            serviceFactory = { gateway },
            cursorStoreFactory = { path -> CursorStore(path) },
            saveConfig = saveConfig,
        )
    }

    private class FakeGateway(
        private val snapshot: BilibiliPublisherSnapshot?,
        private val followState: FollowState,
        private val followActionResult: FollowActionResult,
        private val qrLoginResult: PublisherLoginResult = PublisherLoginResult(
            PublisherLoginStatus.UNSUPPORTED,
            "unsupported",
        ),
        private val exportedCookiesJson: String = "",
    ) : BilibiliPlatformGateway {
        override suspend fun fetchSubscribedLatest(limit: Int) = emptyList<top.colter.bilibili.data.dynamic.BiliDynamic>()

        override suspend fun fetchLatest(uid: String, limit: Int) = emptyList<top.colter.bilibili.data.dynamic.BiliDynamic>()

        override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? = snapshot

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult = followActionResult

        override suspend fun loginByQrCode(
            onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
            onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        ): PublisherLoginResult {
            onQrCode(PublisherQrLoginChallenge(qrContent = "https://example.com/qr"))
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
}
