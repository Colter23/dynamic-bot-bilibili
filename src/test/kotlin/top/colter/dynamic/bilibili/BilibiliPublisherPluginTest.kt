package top.colter.dynamic.bilibili

import kotlinx.coroutines.runBlocking
import top.colter.bilibili.data.LazyImage as BiliLazyImage
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicList
import top.colter.bilibili.data.dynamic.BiliDynamicModules
import top.colter.bilibili.data.dynamic.module.ModuleAuthor
import top.colter.bilibili.data.dynamic.module.ModuleDynamic
import top.colter.bilibili.data.dynamic.type.OriginDynamicType
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.data.user.BiliGroup
import top.colter.bilibili.data.user.BiliUserNav
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.data.LazyImage as CoreLazyImage
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscribeRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskStatus
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        assertEquals("bilibili", profile?.platform)
        assertEquals("123", profile?.userId)
        assertEquals("demo-up", profile?.name)
        assertEquals("https://example.com/face.png", profile?.face?.url)
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
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowHours = 2, followGroupName = "Bot关注"),
            taskScheduler = scheduler,
        )

        plugin.init()
        plugin.start()

        assertFalse(scheduler.isRunning("bilibili-detect"))
        assertTrue(gateway.requestedPages.isEmpty())
        assertEquals(0, gateway.groupFetchCount)
        assertTrue(gateway.createdGroupNames.isEmpty())
        assertTrue(gateway.addedGroupUsers.isEmpty())
    }

    @Test
    fun `replay window zero should warm up current page only`() {
        val now = System.currentTimeMillis() / 1000
        val cursorStore = InMemoryCursorStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialDynamicPages = emptyMap(),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowHours = 0),
            cursorStore = cursorStore,
        )

        val seeded = seedPublisherAndSubscriber()
        val existingCursor = PublisherCursor(
            publisherId = seeded.publisher.id,
            lastSeenDynamicId = dynamicIdFor(now - 5_000L, 99),
            lastSeenAt = now - 5_000L,
            recentDynamicIds = emptyList(),
        )
        cursorStore.put(seeded.publisher.id.toString(), existingCursor)
        val page1Newest = buildDynamic(now - 3_600L, seeded.publisher.userId!!.toLong(), "demo-up", 1)
        val page1Older = buildDynamic(now - 3_700L, seeded.publisher.userId!!.toLong(), "demo-up", 2)
        val page2Newest = buildDynamic(now - 3_800L, seeded.publisher.userId!!.toLong(), "demo-up", 3)
        val page2Older = buildDynamic(now - 3_900L, seeded.publisher.userId!!.toLong(), "demo-up", 4)
        gateway.setDynamicPages(
            mapOf(
                1 to dynamicPage(true, page1Newest, page1Older),
                2 to dynamicPage(false, page2Newest, page2Older),
            )
        )

        plugin.init()
        plugin.start()

        val publisherId = seeded.publisher.id.toString()
        assertEquals(
            listOf(
                dynamicIdFor(now - 3_700L, 2),
                dynamicIdFor(now - 3_600L, 1),
            ),
            cursorStore.markedDynamicIds(publisherId),
        )

        plugin.stop()
    }

    @Test
    fun `replay window should backfill missed dynamics in order when cursor exists`() {
        val now = System.currentTimeMillis() / 1000
        val cursorStore = InMemoryCursorStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialDynamicPages = emptyMap(),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowHours = 2),
            cursorStore = cursorStore,
        )

        val seeded = seedPublisherAndSubscriber()
        val existingCursor = PublisherCursor(
            publisherId = seeded.publisher.id,
            lastSeenDynamicId = dynamicIdFor(now - 5_000L, 99),
            lastSeenAt = now - 5_000L,
            recentDynamicIds = emptyList(),
        )
        cursorStore.put(seeded.publisher.id.toString(), existingCursor)
        val page1Newest = buildDynamic(now - 3_600L, seeded.publisher.userId!!.toLong(), "demo-up", 1)
        val page1Older = buildDynamic(now - 3_700L, seeded.publisher.userId!!.toLong(), "demo-up", 2)
        val page2Newest = buildDynamic(now - 3_800L, seeded.publisher.userId!!.toLong(), "demo-up", 3)
        val page2Older = buildDynamic(now - 3_900L, seeded.publisher.userId!!.toLong(), "demo-up", 4)
        gateway.setDynamicPages(
            mapOf(
                1 to dynamicPage(true, page1Newest, page1Older),
                2 to dynamicPage(false, page2Newest, page2Older),
            )
        )

        plugin.init()
        plugin.start()

        val publisherId = seeded.publisher.id.toString()
        assertEquals(
            listOf(
                dynamicIdFor(now - 3_900L, 4),
                dynamicIdFor(now - 3_800L, 3),
                dynamicIdFor(now - 3_700L, 2),
                dynamicIdFor(now - 3_600L, 1),
            ),
            cursorStore.markedDynamicIds(publisherId),
        )

        plugin.stop()
    }

    @Test
    fun `followGroupName should create group on startup and add followed publisher to it`() = runBlocking {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(followGroupName = "Bot关注"),
            taskScheduler = scheduler,
        )

        plugin.init()
        plugin.start()

        val result = plugin.followPublisher("123")

        assertEquals(FollowActionStatus.FOLLOWED, result.status)
        assertEquals(listOf("Bot关注"), gateway.createdGroupNames)
        assertEquals(2, gateway.groupFetchCount)
        assertEquals(
            listOf(GroupUsersCall(listOf(123L), listOf(1L))),
            gateway.addedGroupUsers,
        )

        plugin.stop()
    }

    @Test
    fun `blank followGroupName should skip group api calls`() = runBlocking {
        val scheduler = TaskScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(followGroupName = ""),
            taskScheduler = scheduler,
        )

        plugin.init()
        plugin.start()
        val result = plugin.followPublisher("123")

        assertEquals(FollowActionStatus.FOLLOWED, result.status)
        assertEquals(0, gateway.groupFetchCount)
        assertTrue(gateway.createdGroupNames.isEmpty())
        assertTrue(gateway.addedGroupUsers.isEmpty())

        plugin.stop()
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
            config = testConfig(),
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

        plugin.stop()
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
            config = testConfig(),
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

        plugin.stop()
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
        val plugin = testPlugin(
            gateway,
            config = testConfig(),
            taskScheduler = scheduler,
        )

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
                    face = BiliLazyImage("https://example.com/face.png"),
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
        config: BilibiliPublisherConfig = testConfig(),
        saveConfig: (String, BilibiliPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler = TaskScheduler(),
        cursorStore: BilibiliCursorStore = InMemoryCursorStore(),
    ): BilibiliPublisherPlugin {
        val tempDir = createTempDirectory("dynamic-bot-bilibili-test").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
        return BilibiliPublisherPlugin(
            loadConfig = { config },
            serviceFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
        )
    }

    private fun testConfig(
        replayWindowHours: Int = 0,
        followGroupName: String = "",
        fetchLimit: Int = 5,
        pollingIntervalMs: Long = 30_000,
        subscriptionRefreshIntervalMs: Long = 300_000,
        requestIntervalMs: Long = 0,
    ): BilibiliPublisherConfig {
        return BilibiliPublisherConfig(
            pollingIntervalMs = pollingIntervalMs,
            subscriptionRefreshIntervalMs = subscriptionRefreshIntervalMs,
            fetchLimit = fetchLimit,
            requestIntervalMs = requestIntervalMs,
            replayWindowHours = replayWindowHours,
            followGroupName = followGroupName,
        )
    }

    private fun seedPublisherAndSubscriber(): SeededSubscription {
        val publisher = PublisherRepository.upsert(
            PublisherProfile(
                platform = "bilibili",
                userId = "123",
                name = "demo-up",
                face = CoreLazyImage("https://example.com/face.png"),
            ),
        ).value
        val subscriber = SubscriberRepository.upsert(
            platform = "qq",
            userId = "9001",
            name = "demo-subscriber",
        ).value
        SubscribeRepository.subscribe(subscriber.id.toString(), publisher.id.toString())
        return SeededSubscription(publisher = publisher, subscriber = subscriber)
    }

    private fun buildDynamic(
        epochSeconds: Long,
        mid: Long,
        name: String,
        suffix: Long,
    ): BiliDynamic {
        val author = ModuleAuthor(
            mid = mid,
            name = name,
            face = BiliLazyImage("https://example.com/$mid.png"),
        )
        return BiliDynamic(
            originType = OriginDynamicType.WORD,
            idStr = dynamicIdFor(epochSeconds, suffix),
            modules = BiliDynamicModules(
                author = author,
                dynamic = ModuleDynamic(),
            ),
        )
    }

    private fun dynamicPage(hasMore: Boolean, vararg items: BiliDynamic): BiliDynamicList {
        return BiliDynamicList(
            hasMore = hasMore,
            offset = "",
            updateBaseline = "",
            updateNum = items.size.toString(),
            items = items.toList(),
        )
    }

    private fun dynamicIdFor(epochSeconds: Long, suffix: Long): String {
        val timePart = epochSeconds - BILI_DYNAMIC_EPOCH_BASE
        return (((timePart) shl 32) + suffix).toString()
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
        initialDynamicPages: Map<Int, BiliDynamicList> = emptyMap(),
        initialGroups: List<BiliGroup> = emptyList(),
    ) : BilibiliPlatformGateway {
        private val groups: MutableList<BiliGroup> = initialGroups.toMutableList()
        private val dynamicPages: MutableMap<Int, BiliDynamicList> = initialDynamicPages.toMutableMap()
        private var nextGroupId: Long = (groups.maxOfOrNull { it.tid } ?: 0L) + 1L

        val requestedPages: MutableList<Int> = mutableListOf()
        var groupFetchCount: Int = 0
            private set
        val createdGroupNames: MutableList<String> = mutableListOf()
        val addedGroupUsers: MutableList<GroupUsersCall> = mutableListOf()

        override suspend fun fetchNewDynamicPage(page: Int, type: String): BiliDynamicList {
            requestedPages.add(page)
            return dynamicPages[page] ?: BiliDynamicList(
                hasMore = false,
                offset = "",
                updateBaseline = "",
                updateNum = "0",
                items = emptyList(),
            )
        }

        fun setDynamicPages(pages: Map<Int, BiliDynamicList>) {
            dynamicPages.clear()
            dynamicPages.putAll(pages)
        }

        override suspend fun fetchPublisherProfile(userId: String): BilibiliPublisherSnapshot? = snapshot

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult = followActionResult

        override suspend fun fetchFollowGroups(): List<BiliGroup> {
            groupFetchCount += 1
            return groups.toList()
        }

        override suspend fun createFollowGroup(tag: String) {
            createdGroupNames.add(tag)
            if (groups.none { it.name == tag }) {
                groups.add(BiliGroup(tid = nextGroupId++, name = tag, count = 0, tip = null))
            }
        }

        override suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
            addedGroupUsers.add(GroupUsersCall(fids.toList(), tagIds.toList()))
        }

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

    private class InMemoryCursorStore(
        initialStates: Map<String, PublisherCursor> = emptyMap(),
    ) : BilibiliCursorStore {
        private val states: MutableMap<String, PublisherCursor> = linkedMapOf<String, PublisherCursor>().apply {
            putAll(initialStates)
        }
        private val marks: MutableList<CursorMark> = mutableListOf()

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
            marks.add(CursorMark(publisherId, dynamicId, timestamp))
            return updated
        }

        fun markedDynamicIds(publisherId: String): List<String> {
            return marks.filter { it.publisherId == publisherId }.map { it.dynamicId }
        }

        fun put(publisherId: String, cursor: PublisherCursor) {
            states[publisherId] = cursor
        }
    }

    private data class SeededSubscription(
        val publisher: top.colter.dynamic.core.data.Publisher,
        val subscriber: top.colter.dynamic.core.data.Subscriber,
    )

    private data class CursorMark(
        val publisherId: String,
        val dynamicId: String,
        val timestamp: Long,
    )

    private data class GroupUsersCall(
        val fids: List<Long>,
        val tagIds: List<Long>,
    )

    private companion object {
        private const val BILI_DYNAMIC_EPOCH_BASE: Long = 1_498_838_400L
    }
}
