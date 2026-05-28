package top.colter.dynamic.bilibili

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import top.colter.dynamic.core.data.LiveChange
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.LiveStatusUpdate
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherProfile
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.SourceUpdateEvent
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.event.register
import top.colter.dynamic.core.link.DynamicLinkResolution
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.PersistenceManager
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskStatus
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BilibiliPublisherPluginTest {

    @AfterTest
    fun cleanup() {
        EventManger.shutdown()
    }

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

        assertEquals("bilibili", profile?.platformId)
        assertEquals("123", profile?.externalId)
        assertEquals("demo-up", profile?.name)
        assertEquals("https://example.com/face.png", profile?.face?.uri)
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
    fun `parseDynamicLink should support Bilibili direct dynamic links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        val parsed = plugin.parseDynamicLink("https://t.bilibili.com/1205230877720707077?spm_id_from=demo")

        requireNotNull(parsed)
        assertEquals("bilibili", parsed.platformId)
        assertEquals("1205230877720707077", parsed.dynamicId)
        assertEquals("https://t.bilibili.com/1205230877720707077", parsed.normalizedUrl)
    }

    @Test
    fun `parseDynamicLink should support Bilibili opus and mobile dynamic links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        val opus = plugin.parseDynamicLink("https://www.bilibili.com/opus/774783779415785528")
        val mobileOpus = plugin.parseDynamicLink("https://m.bilibili.com/opus/774783779415785529")
        val mobileDynamic = plugin.parseDynamicLink("https://m.bilibili.com/dynamic/774783779415785530")

        assertEquals("774783779415785528", opus?.dynamicId)
        assertEquals("774783779415785529", mobileOpus?.dynamicId)
        assertEquals("774783779415785530", mobileDynamic?.dynamicId)
    }

    @Test
    fun `parseDynamicLink should expand Bilibili short links`() = runBlocking {
        val shortUrl = "https://b23.tv/demo"
        val gateway = defaultGateway(
            shortUrlExpansions = mapOf(shortUrl to "https://www.bilibili.com/opus/774783779415785528?share_source=copy_link"),
        )
        val plugin = testPlugin(gateway, config = testConfig(shortUrlResolveTimeoutMs = 10))
        plugin.init()

        val parsed = plugin.parseDynamicLink(shortUrl)

        requireNotNull(parsed)
        assertEquals("774783779415785528", parsed.dynamicId)
        assertEquals("https://t.bilibili.com/774783779415785528", parsed.normalizedUrl)
        assertEquals(shortUrl, parsed.sourceUrl)
        assertEquals(listOf(shortUrl), gateway.expandedShortUrls)
    }

    @Test
    fun `parseDynamicLink should ignore unsupported links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        assertNull(plugin.parseDynamicLink("https://www.bilibili.com/video/BV123"))
        assertNull(plugin.parseDynamicLink("https://example.com/opus/774783779415785528"))
    }

    @Test
    fun `resolveDynamicLink should fetch detail and map dynamic`() = runBlocking {
        val detail = buildDynamic(
            epochSeconds = BILI_DYNAMIC_EPOCH_BASE + 123,
            mid = 42,
            name = "demo-up",
            suffix = 1,
        )
        val gateway = defaultGateway(dynamicDetails = mapOf(detail.id.toString() to detail))
        val plugin = testPlugin(gateway)
        plugin.init()

        val parsed = plugin.parseDynamicLink("https://t.bilibili.com/${detail.id}")!!
        val resolution = plugin.resolveDynamicLink(parsed)

        assertIs<DynamicLinkResolution.Success>(resolution)
        assertEquals(detail.id.toString(), resolution.dynamic.dynamicId)
        assertEquals("42", resolution.dynamic.publisher.externalId)
        assertEquals("demo-up", resolution.dynamic.publisher.name)
        assertEquals(listOf(detail.id.toString()), gateway.requestedDetails)
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
        cursorStore.put(seeded.publisher.id, existingCursor)
        val page1Newest = buildDynamic(now - 3_600L, seeded.publisher.externalId.toLong(), "demo-up", 1)
        val page1Older = buildDynamic(now - 3_700L, seeded.publisher.externalId.toLong(), "demo-up", 2)
        val page2Newest = buildDynamic(now - 3_800L, seeded.publisher.externalId.toLong(), "demo-up", 3)
        val page2Older = buildDynamic(now - 3_900L, seeded.publisher.externalId.toLong(), "demo-up", 4)
        gateway.setDynamicPages(
            mapOf(
                1 to dynamicPage(true, page1Newest, page1Older),
                2 to dynamicPage(false, page2Newest, page2Older),
            )
        )

        plugin.init()
        plugin.start()

        val publisherId = seeded.publisher.id
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
        cursorStore.put(seeded.publisher.id, existingCursor)
        val page1Newest = buildDynamic(now - 3_600L, seeded.publisher.externalId.toLong(), "demo-up", 1)
        val page1Older = buildDynamic(now - 3_700L, seeded.publisher.externalId.toLong(), "demo-up", 2)
        val page2Newest = buildDynamic(now - 3_800L, seeded.publisher.externalId.toLong(), "demo-up", 3)
        val page2Older = buildDynamic(now - 3_900L, seeded.publisher.externalId.toLong(), "demo-up", 4)
        gateway.setDynamicPages(
            mapOf(
                1 to dynamicPage(true, page1Newest, page1Older),
                2 to dynamicPage(false, page2Newest, page2Older),
            )
        )

        plugin.init()
        plugin.start()

        val publisherId = seeded.publisher.id
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
    fun `checkLoginState should delegate to gateway`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(
                PublisherLoginStatus.SUCCESS,
                "logged in",
                PublisherLoginAccount(userId = "123", name = "demo-up"),
            ),
        )
        val plugin = testPlugin(gateway)

        plugin.init()
        val result = plugin.checkLoginState()

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("logged in", result.message)
        assertEquals("123", result.account?.userId)
        assertEquals("demo-up", result.account?.name)
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
    fun `subscription event should add publisher baseline and trigger immediate detect`() = runBlocking {
        val scheduler = TaskScheduler()
        val cursorStore = InMemoryCursorStore()
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
            cursorStore = cursorStore,
        )

        plugin.init()
        plugin.start()
        withTimeout(3_000) {
            while ((scheduler.snapshot("bilibili-detect")?.runCount ?: 0L) == 0L) {
                delay(10)
            }
        }

        val seeded = seedPublisherAndSubscriber()
        val subscription = SubscriptionRepository.findBySubscriberAndPublisher(
            seeded.subscriber.id,
            seeded.publisher.id,
        )!!
        val rawDynamic = buildDynamic(
            epochSeconds = subscription.createdAtEpochSeconds + 1,
            mid = seeded.publisher.externalId.toLong(),
            name = seeded.publisher.name,
            suffix = 42,
        )
        gateway.setDynamicPages(mapOf(1 to dynamicPage(false, rawDynamic)))

        val received = CompletableDeferred<SourceUpdateEvent>()
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                if (!received.isCompleted) received.complete(event)
            }
        }.register<SourceUpdateEvent>()

        plugin.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.SUBSCRIBED,
                subscription = subscription,
                publisher = seeded.publisher,
                subscriber = seeded.subscriber,
                changedAtEpochSeconds = subscription.createdAtEpochSeconds,
            )
        )

        val dynamicEvent = withTimeout(3_000) { received.await() }
        assertNull(dynamicEvent.target)
        assertEquals(rawDynamic.id.toString(), dynamicEvent.update.updateId)
        assertEquals(listOf(1), gateway.requestedPages)
        assertEquals(
            rawDynamic.id.toString(),
            cursorStore.markedDynamicIds(seeded.publisher.id).last(),
        )

        plugin.stop()
    }

    @Test
    fun `live polling should baseline startup then emit started and ended updates`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val liveStore = InMemoryLiveStatusStore()
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalMs = 25),
            liveStatusStore = liveStore,
        )
        seedPublisherAndSubscriber()
        val received = kotlinx.coroutines.channels.Channel<SourceUpdateEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                received.send(event)
            }
        }.register<SourceUpdateEvent>()

        plugin.init()
        plugin.start()
        assertEquals(LiveStatus.CLOSE, liveStore.get(1)?.status)
        assertNull(withTimeoutOrNull(100) { received.receive() })

        val startedAt = System.currentTimeMillis() / 1000
        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.OPEN, startedAt)))
        val started = withTimeout(3_000) { received.receive() }.update as LiveStatusUpdate
        assertEquals(LiveChange.STARTED, started.change)
        assertEquals("456", started.roomId)
        assertEquals(startedAt, started.startedAt)

        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.CLOSE)))
        val ended = withTimeout(3_000) { received.receive() }.update as LiveStatusUpdate
        assertEquals(LiveChange.ENDED, ended.change)
        assertEquals(startedAt, ended.startedAt)
        assertTrue(ended.endedAt != null)

        plugin.stop()
    }

    @Test
    fun `live polling should not emit close and round transitions`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val plugin = testPlugin(gateway, config = testConfig(pollingIntervalMs = 25))
        seedPublisherAndSubscriber()
        val received = CompletableDeferred<SourceUpdateEvent>()
        object : Listener<SourceUpdateEvent> {
            override suspend fun onMessage(event: SourceUpdateEvent) {
                if (!received.isCompleted) received.complete(event)
            }
        }.register<SourceUpdateEvent>()

        plugin.init()
        plugin.start()
        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.ROUND)))

        assertNull(withTimeoutOrNull(200) { received.await() })
        plugin.stop()
    }

    @Test
    fun `live polling should request status in configured batches`() {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalMs = 30_000, liveStatusBatchSize = 2),
        )
        seedPublisherAndSubscriber(externalId = "101", targetId = "9001")
        seedPublisherAndSubscriber(externalId = "102", targetId = "9002")
        seedPublisherAndSubscriber(externalId = "103", targetId = "9003")

        plugin.init()
        plugin.start()
        plugin.stop()

        assertEquals(listOf(listOf(101L, 102L), listOf(103L)), gateway.requestedLiveBatches.take(2))
    }

    @Test
    fun `poll service checkLoginState should map BiliLoginException to failed result`() = runBlocking {
        val service = BilibiliPollService(
            requestIntervalMs = 0,
            currentUserNavProvider = { throw BiliLoginException("not logged in") },
        )

        val result = service.checkLoginState()

        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertEquals("Bilibili 未登录", result.message)
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
        liveStatusStore: BilibiliLiveStatusStore = InMemoryLiveStatusStore(),
    ): BilibiliPublisherPlugin {
        val tempDir = createTempDirectory("dynamic-bot-bilibili-test").toFile()
        PersistenceManager.init(tempDir.resolve("test.db").path)
        return BilibiliPublisherPlugin(
            loadConfig = { config },
            serviceFactory = { gateway },
            cursorStoreFactory = { cursorStore },
            liveStatusStoreFactory = { liveStatusStore },
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
        )
    }

    private fun testConfig(
        replayWindowHours: Int = 0,
        followGroupName: String = "",
        fetchLimit: Int = 5,
        pollingIntervalMs: Long = 30_000,
        requestIntervalMs: Long = 0,
        shortUrlResolveTimeoutMs: Long = 3_000,
        liveDetectionEnabled: Boolean = true,
        liveStatusBatchSize: Int = 50,
    ): BilibiliPublisherConfig {
        return BilibiliPublisherConfig(
            pollingIntervalMs = pollingIntervalMs,
            fetchLimit = fetchLimit,
            requestIntervalMs = requestIntervalMs,
            replayWindowHours = replayWindowHours,
            followGroupName = followGroupName,
            shortUrlResolveTimeoutMs = shortUrlResolveTimeoutMs,
            liveDetectionEnabled = liveDetectionEnabled,
            liveStatusBatchSize = liveStatusBatchSize,
        )
    }

    private fun defaultGateway(
        dynamicDetails: Map<String, BiliDynamic> = emptyMap(),
        shortUrlExpansions: Map<String, String?> = emptyMap(),
    ): FakeGateway {
        return FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.FOLLOWED),
            dynamicDetails = dynamicDetails,
            shortUrlExpansions = shortUrlExpansions,
        )
    }

    private fun seedPublisherAndSubscriber(
        externalId: String = "123",
        targetId: String = "9001",
    ): SeededSubscription {
        val publisher = PublisherRepository.upsertProfile(
            PublisherProfile(
                platformId = "bilibili",
                externalId = externalId,
                name = "demo-up",
                face = CoreLazyImage("https://example.com/face.png"),
            ),
        ).value
        val subscriber = SubscriberRepository.upsert(
            platformId = "qq",
            targetId = targetId,
            name = "demo-subscriber",
            type = SubscriberType.GROUP,
        ).value
        SubscriptionRepository.subscribe(subscriber.id, publisher.id)
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
        private val dynamicDetails: Map<String, BiliDynamic> = emptyMap(),
        private val shortUrlExpansions: Map<String, String?> = emptyMap(),
        initialDynamicPages: Map<Int, BiliDynamicList> = emptyMap(),
        initialGroups: List<BiliGroup> = emptyList(),
        initialLiveSnapshots: Map<Long, BilibiliLiveSnapshot> = emptyMap(),
    ) : BilibiliPlatformGateway {
        private val groups: MutableList<BiliGroup> = initialGroups.toMutableList()
        private val dynamicPages: MutableMap<Int, BiliDynamicList> = initialDynamicPages.toMutableMap()
        private val liveSnapshots: MutableMap<Long, BilibiliLiveSnapshot> = initialLiveSnapshots.toMutableMap()
        private var nextGroupId: Long = (groups.maxOfOrNull { it.tid } ?: 0L) + 1L

        val requestedPages: MutableList<Int> = mutableListOf()
        val requestedLiveBatches: MutableList<List<Long>> = mutableListOf()
        var groupFetchCount: Int = 0
            private set
        val createdGroupNames: MutableList<String> = mutableListOf()
        val addedGroupUsers: MutableList<GroupUsersCall> = mutableListOf()
        val requestedDetails: MutableList<String> = mutableListOf()
        val expandedShortUrls: MutableList<String> = mutableListOf()

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

        override suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
            requestedDetails.add(dynamicId)
            return dynamicDetails[dynamicId]
        }

        override suspend fun fetchLiveStatusBatch(uids: Iterable<Long>): List<BilibiliLiveSnapshot> {
            val requested = uids.toList()
            requestedLiveBatches.add(requested)
            return requested.mapNotNull { liveSnapshots[it] }
        }

        fun setLiveSnapshots(next: Map<Long, BilibiliLiveSnapshot>) {
            liveSnapshots.clear()
            liveSnapshots.putAll(next)
        }

        override suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
            expandedShortUrls.add(url)
            return shortUrlExpansions[url]
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
        initialStates: Map<Int, PublisherCursor> = emptyMap(),
    ) : BilibiliCursorStore {
        private val states: MutableMap<Int, PublisherCursor> = linkedMapOf<Int, PublisherCursor>().apply {
            putAll(initialStates)
        }
        private val marks: MutableList<CursorMark> = mutableListOf()

        override fun get(publisherId: Int): PublisherCursor? = states[publisherId]

        override fun ensureBaseline(publisherId: Int, timestamp: Long): PublisherCursor {
            states[publisherId]?.let { return it }
            return markSeen(publisherId, "__baseline__$timestamp", timestamp)
        }

        override fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): PublisherCursor {
            val previous = states[publisherId]
            val recent = LinkedHashSet(previous?.recentDynamicIds ?: emptyList())
            recent.add(dynamicId)
            while (recent.size > 50) {
                recent.remove(recent.first())
            }
            val updated = PublisherCursor(
                publisherId = publisherId,
                lastSeenDynamicId = dynamicId,
                lastSeenAt = timestamp,
                recentDynamicIds = recent.toList(),
            )
            states[publisherId] = updated
            marks.add(CursorMark(publisherId, dynamicId, timestamp))
            return updated
        }

        fun markedDynamicIds(publisherId: Int): List<String> {
            return marks.filter { it.publisherId == publisherId }.map { it.dynamicId }
        }

        fun put(publisherId: Int, cursor: PublisherCursor) {
            states[publisherId] = cursor
        }
    }

    private class InMemoryLiveStatusStore(
        initialStates: Map<Int, PublisherLiveStatus> = emptyMap(),
    ) : BilibiliLiveStatusStore {
        private val states: MutableMap<Int, PublisherLiveStatus> = linkedMapOf<Int, PublisherLiveStatus>().apply {
            putAll(initialStates)
        }

        override fun get(publisherId: Int): PublisherLiveStatus? = states[publisherId]

        override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
            states[state.publisherId] = state
            return state
        }
    }

    private fun liveSnapshot(status: LiveStatus, startedAt: Long? = null): BilibiliLiveSnapshot {
        return BilibiliLiveSnapshot(
            userId = "123",
            roomId = "456",
            status = status,
            title = "Live title",
            area = "Games",
            coverUrl = "https://example.com/cover.png",
            startedAtEpochSeconds = startedAt,
        )
    }

    private data class SeededSubscription(
        val publisher: top.colter.dynamic.core.data.Publisher,
        val subscriber: top.colter.dynamic.core.data.Subscriber,
    )

    private data class CursorMark(
        val publisherId: Int,
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
