package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
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

    private fun testPlugin(gateway: BilibiliPlatformGateway): BilibiliPublisherPlugin {
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
        )
    }

    private class FakeGateway(
        private val snapshot: BilibiliPublisherSnapshot?,
        private val followState: FollowState,
        private val followActionResult: FollowActionResult,
    ) : BilibiliPlatformGateway {
        override suspend fun fetchSubscribedLatest(limit: Int) = emptyList<top.colter.bilibili.data.dynamic.BiliDynamic>()

        override suspend fun fetchLatest(uid: String, limit: Int) = emptyList<top.colter.bilibili.data.dynamic.BiliDynamic>()

        override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? = snapshot

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult = followActionResult
    }
}
