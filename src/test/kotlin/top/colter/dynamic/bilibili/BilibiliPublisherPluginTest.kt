package top.colter.dynamic.bilibili

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.bilibili.data.ImageUrl as BiliImageUrl
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
import top.colter.bilibili.data.user.BiliUserInfo
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.SubscriptionSubscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.command.CommandPublisher
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class BilibiliPublisherPluginTest {

    private fun BilibiliPublisherPlugin.init(
        sourceUpdates: RecordingSourceUpdatePublisher = RecordingSourceUpdatePublisher(),
    ) {
        runBlocking { onLoad(testPluginContext(sourceUpdates)) }
    }

    private fun BilibiliPublisherPlugin.start() {
        runBlocking { onStart() }
    }

    private fun BilibiliPublisherPlugin.stop() {
        runBlocking { onStop() }
    }

    private fun testPluginContext(
        sourceUpdates: RecordingSourceUpdatePublisher = RecordingSourceUpdatePublisher(),
    ): PluginContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return PluginContext(
            pluginId = "bilibili-publisher",
            descriptor = PluginDescriptor(
                id = "bilibili-publisher",
                name = "Bilibili Publisher Plugin",
                version = "test",
                mainClass = BilibiliPublisherPlugin::class.java.name,
            ),
            configService = InMemoryConfigService(),
            dataStore = InMemoryPluginDataStore("bilibili-publisher"),
            scope = scope,
            taskScheduler = testScheduler(),
            commandPublisher = CommandPublisher { },
            sourceUpdatePublisher = sourceUpdates,
            sourceStateStore = TestSourceStateStore,
            subscriptionQueryService = TestSubscriptions,
        )
    }

    private fun testScheduler(): TaskScheduler {
        return TestTaskScheduler(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @Test
    fun `fetchPublisherInfo should map gateway snapshot to publisher info`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = BilibiliPublisherSnapshot(
                userId = "123",
                name = "demo-up",
                avatarBadgeKey = "avatarBadge.official.individual",
                faceUrl = "https://example.com/face.png",
                headerUrl = "https://example.com/header.png",
                pendantUrl = "https://example.com/pendant.png",
            ),
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val profile = plugin.fetchPublisherInfo("123")

        assertEquals("bilibili", profile?.platformId?.value)
        assertEquals("123", profile?.externalId)
        assertEquals("demo-up", profile?.name)
        assertEquals("https://example.com/face.png", profile?.avatar?.uri)
    }

    @Test
    fun `queryFollowState should delegate to gateway`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
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
    fun `parseLink should support Bilibili direct dynamic links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        val parsed = plugin.parseLink("https://t.bilibili.com/1205230877720707077?spm_id_from=demo")

        requireNotNull(parsed)
        assertEquals("bilibili", parsed.platformId.value)
        assertEquals(LinkKinds.DYNAMIC, parsed.kind)
        assertEquals("1205230877720707077", parsed.targetId)
        assertEquals("https://t.bilibili.com/1205230877720707077", parsed.normalizedUrl)
    }

    @Test
    fun `parseLink should support Bilibili opus and mobile dynamic links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        val opus = plugin.parseLink("https://www.bilibili.com/opus/774783779415785528")
        val mobileOpus = plugin.parseLink("https://m.bilibili.com/opus/774783779415785529")
        val mobileDynamic = plugin.parseLink("https://m.bilibili.com/dynamic/774783779415785530")

        assertEquals("774783779415785528", opus?.targetId)
        assertEquals("774783779415785529", mobileOpus?.targetId)
        assertEquals("774783779415785530", mobileDynamic?.targetId)
    }

    @Test
    fun `parseLink should support Bilibili preview link types`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        val video = plugin.parseLink("https://www.bilibili.com/video/BV1xx411c7mD")
        val mobileVideo = plugin.parseLink("https://m.bilibili.com/video/av170001")
        val live = plugin.parseLink("https://live.bilibili.com/12345")
        val user = plugin.parseLink("https://space.bilibili.com/42")

        assertEquals(LinkKinds.VIDEO, video?.kind)
        assertEquals("BV1xx411c7mD", video?.targetId)
        assertEquals("av170001", mobileVideo?.targetId)
        assertEquals(LinkKinds.LIVE, live?.kind)
        assertEquals("12345", live?.targetId)
        assertEquals(LinkKinds.USER, user?.kind)
        assertEquals("42", user?.targetId)
    }

    @Test
    fun `parseLink should expand Bilibili short links`() = runBlocking {
        val shortUrl = "https://b23.tv/demo"
        val gateway = defaultGateway(
            shortUrlExpansions = mapOf(shortUrl to "https://www.bilibili.com/opus/774783779415785528?share_source=copy_link"),
        )
        val plugin = testPlugin(gateway, config = testConfig(shortUrlResolveTimeoutSeconds = 0.01))
        plugin.init()

        val parsed = plugin.parseLink(shortUrl)

        requireNotNull(parsed)
        assertEquals(LinkKinds.DYNAMIC, parsed.kind)
        assertEquals("774783779415785528", parsed.targetId)
        assertEquals("https://t.bilibili.com/774783779415785528", parsed.normalizedUrl)
        assertEquals(shortUrl, parsed.sourceUrl)
        assertEquals(listOf(shortUrl), gateway.expandedShortUrls)
    }

    @Test
    fun `parseLink should ignore unsupported links`() = runBlocking {
        val plugin = testPlugin(defaultGateway())
        plugin.init()

        assertNull(plugin.parseLink("https://www.bilibili.com/read/cv123"))
        assertNull(plugin.parseLink("https://example.com/opus/774783779415785528"))
    }

    @Test
    fun `resolveLink should fetch detail and map dynamic`() = runBlocking {
        val detail = buildDynamic(
            epochSeconds = BILI_DYNAMIC_EPOCH_BASE + 123,
            mid = 42,
            name = "demo-up",
            suffix = 1,
        )
        val gateway = defaultGateway(dynamicDetails = mapOf(detail.id.toString() to detail))
        val plugin = testPlugin(gateway)
        plugin.init()

        val parsed = plugin.parseLink("https://t.bilibili.com/${detail.id}")!!
        val resolution = plugin.resolveLink(parsed)

        assertIs<LinkResolution.Dynamic>(resolution)
        assertEquals(detail.id.toString(), resolution.update.key.externalId)
        assertEquals("42", resolution.update.publisher.externalId)
        assertEquals("demo-up", resolution.update.publisher.name)
        assertEquals(listOf(detail.id.toString()), gateway.requestedDetails)
    }

    @Test
    fun `resolveLink should map video preview`() = runBlocking {
        val gateway = defaultGateway(
            videoSnapshots = mapOf(
                "BV1xx411c7mD" to BilibiliVideoSnapshot(
                    aid = 170001,
                    bvid = "BV1xx411c7mD",
                    title = "demo video",
                    description = "video description",
                    coverUrl = "https://example.com/video-cover.png",
                    ownerId = "42",
                    ownerName = "demo-up",
                    ownerFaceUrl = "https://example.com/owner-face.png",
                    durationSeconds = 120,
                    play = 12_345,
                    danmaku = 234,
                    like = 56,
                ),
            ),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val parsed = plugin.parseLink("https://www.bilibili.com/video/BV1xx411c7mD")!!
        val resolution = plugin.resolveLink(parsed)

        assertIs<LinkResolution.Preview>(resolution)
        assertEquals(LinkKinds.VIDEO, resolution.preview.kind)
        assertEquals("demo video", resolution.preview.title)
        assertEquals("视频", resolution.preview.badge)
        assertEquals("42", resolution.preview.publisher?.externalId)
        assertEquals(120, resolution.preview.durationSeconds)
        assertEquals(listOf("play", "danmaku", "like"), resolution.preview.metrics.map { it.key })
        assertEquals(listOf("BV1xx411c7mD"), gateway.requestedVideos)
    }

    @Test
    fun `resolveLink should map live room preview`() = runBlocking {
        val gateway = defaultGateway(
            snapshot = BilibiliPublisherSnapshot(
                userId = "42",
                name = "live-up",
                faceUrl = "https://example.com/live-face.png",
                headerUrl = "https://example.com/live-header.png",
            ),
            liveRoomSnapshots = mapOf(
                "12345" to BilibiliLiveRoomSnapshot(
                    userId = "42",
                    roomId = "12345",
                    status = LiveStatus.OPEN,
                    title = "demo live",
                    area = "Games / Demo",
                    coverUrl = "https://example.com/live-cover.png",
                    online = 123,
                    attention = 456,
                ),
            ),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val parsed = plugin.parseLink("https://live.bilibili.com/12345")!!
        val resolution = plugin.resolveLink(parsed)

        assertIs<LinkResolution.Preview>(resolution)
        assertEquals(LinkKinds.LIVE, resolution.preview.kind)
        assertEquals("demo live", resolution.preview.title)
        assertEquals("live-up", resolution.preview.publisher?.name)
        assertEquals(listOf("online", "follow"), resolution.preview.metrics.map { it.key })
        assertEquals(listOf("12345"), gateway.requestedLiveRooms)
    }

    @Test
    fun `resolveLink should map user preview`() = runBlocking {
        val gateway = defaultGateway(
            snapshot = BilibiliPublisherSnapshot(
                userId = "42",
                name = "demo-up",
                faceUrl = "https://example.com/face.png",
                headerUrl = "https://example.com/header.png",
            ),
        )
        val plugin = testPlugin(gateway)
        plugin.init()

        val parsed = plugin.parseLink("https://space.bilibili.com/42")!!
        val resolution = plugin.resolveLink(parsed)

        assertIs<LinkResolution.Preview>(resolution)
        assertEquals(LinkKinds.USER, resolution.preview.kind)
        assertEquals("42", resolution.preview.id)
        assertEquals("demo-up", resolution.preview.title)
        assertEquals("用户", resolution.preview.badge)
        assertEquals("https://example.com/header.png", resolution.preview.cover?.uri)
    }

    @Test
    fun `start should skip detection task when login check fails`() {
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowMinutes = 120, followGroupName = "Bot关注"),
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
    fun `polling should pause requests after consecutive login exceptions`() = runBlocking {
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            failingDynamicPages = setOf(1),
            dynamicPageFailure = BiliLoginException("cookie expired"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(
                pollingIntervalSeconds = 0.025,
                maxConsecutiveLoginFailures = 2,
            ),
            taskScheduler = scheduler,
        )
        seedPublisherAndSubscriber()

        plugin.init()
        plugin.start()

        withTimeout(3_000) {
            while (gateway.requestedPages.size < 2) {
                delay(10)
            }
        }
        val requestCountAfterPause = gateway.requestedPages.size
        delay(150)

        assertEquals(requestCountAfterPause, gateway.requestedPages.size)
        assertTrue(scheduler.isRunning("bilibili-detect"))

        plugin.stop()
    }

    @Test
    fun `replay window zero should warm up current page only`() {
        val now = System.currentTimeMillis() / 1000
        val cursorStore = InMemoryCursorStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialDynamicPages = emptyMap(),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowMinutes = 0),
            cursorStore = cursorStore,
        )

        val seeded = seedPublisherAndSubscriber()
        val existingCursor = SourceCursor(
            publisherId = seeded.publisher.id,
            sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            lastSeenUpdateKey = dynamicIdFor(now - 5_000L, 99),
            lastSeenAtEpochSeconds = now - 5_000L,
            recentUpdateKeys = emptyList(),
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
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialDynamicPages = emptyMap(),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowMinutes = 120),
            cursorStore = cursorStore,
        )

        val seeded = seedPublisherAndSubscriber()
        val existingCursor = SourceCursor(
            publisherId = seeded.publisher.id,
            sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            lastSeenUpdateKey = dynamicIdFor(now - 5_000L, 99),
            lastSeenAtEpochSeconds = now - 5_000L,
            recentUpdateKeys = emptyList(),
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
    fun `replay window should keep collected pages when later page fails`() {
        val now = System.currentTimeMillis() / 1000
        val cursorStore = InMemoryCursorStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialDynamicPages = emptyMap(),
            failingDynamicPages = setOf(2),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(replayWindowMinutes = 120),
            cursorStore = cursorStore,
        )

        val seeded = seedPublisherAndSubscriber()
        cursorStore.put(
            seeded.publisher.id,
            SourceCursor(
                publisherId = seeded.publisher.id,
                sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
                eventType = SourceEventType.DYNAMIC_CREATED,
                lastSeenUpdateKey = dynamicIdFor(now - 5_000L, 99),
                lastSeenAtEpochSeconds = now - 5_000L,
                recentUpdateKeys = emptyList(),
            ),
        )
        val page1Newest = buildDynamic(now - 3_600L, seeded.publisher.externalId.toLong(), "demo-up", 1)
        val page1Older = buildDynamic(now - 3_700L, seeded.publisher.externalId.toLong(), "demo-up", 2)
        gateway.setDynamicPages(
            mapOf(
                1 to dynamicPage(true, page1Newest, page1Older),
            )
        )

        plugin.init()
        plugin.start()

        assertEquals(listOf(1, 2), gateway.requestedPages.take(2))
        assertEquals(
            listOf(
                dynamicIdFor(now - 3_700L, 2),
                dynamicIdFor(now - 3_600L, 1),
            ),
            cursorStore.markedDynamicIds(seeded.publisher.id),
        )

        plugin.stop()
    }

    @Test
    fun `followGroupName should create group on startup and add followed publisher to it`() = runBlocking {
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
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

        assertEquals(FollowActionStatus.DONE, result.status)
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
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.NOT_FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
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

        assertEquals(FollowActionStatus.DONE, result.status)
        assertEquals(0, gateway.groupFetchCount)
        assertTrue(gateway.createdGroupNames.isEmpty())
        assertTrue(gateway.addedGroupUsers.isEmpty())

        plugin.stop()
    }

    @Test
    fun `unfollowPublisher should unfollow when publisher only belongs to bot group`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            initialGroups = listOf(BiliGroup(tid = 1L, name = "Bot关注", count = 1, tip = null)),
            followRelation = BilibiliFollowRelationSnapshot(
                userId = "123",
                following = true,
                tagIds = setOf(1L),
            ),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(followGroupName = "Bot关注"),
        )

        plugin.init()
        val result = plugin.unfollowPublisher("123")

        assertEquals(FollowActionStatus.DONE, result.status)
        assertEquals(listOf("123"), gateway.unfollowedUsers)
        assertEquals(1, gateway.groupFetchCount)
        assertTrue(gateway.createdGroupNames.isEmpty())
    }

    @Test
    fun `unfollowPublisher should skip when publisher is not in bot group`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            initialGroups = listOf(BiliGroup(tid = 1L, name = "Bot关注", count = 1, tip = null)),
            followRelation = BilibiliFollowRelationSnapshot(
                userId = "123",
                following = true,
                tagIds = setOf(2L),
            ),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(followGroupName = "Bot关注"),
        )

        plugin.init()
        val result = plugin.unfollowPublisher("123")

        assertEquals(FollowActionStatus.NOOP, result.status)
        assertTrue(gateway.unfollowedUsers.isEmpty())
        assertEquals(1, gateway.groupFetchCount)
    }

    @Test
    fun `unfollowPublisher should skip when publisher also belongs to other groups`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            initialGroups = listOf(BiliGroup(tid = 1L, name = "Bot关注", count = 1, tip = null)),
            followRelation = BilibiliFollowRelationSnapshot(
                userId = "123",
                following = true,
                tagIds = setOf(1L, 2L),
            ),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(followGroupName = "Bot关注"),
        )

        plugin.init()
        val result = plugin.unfollowPublisher("123")

        assertEquals(FollowActionStatus.NOOP, result.status)
        assertTrue(gateway.unfollowedUsers.isEmpty())
        assertEquals(1, gateway.groupFetchCount)
    }

    @Test
    fun `unfollowPublisher should skip unsafe or unavailable relation states`() = runBlocking {
        val cases = listOf(
            UnfollowSkipCase(
                name = "未关注",
                followGroupName = "Bot关注",
                initialGroups = listOf(BiliGroup(tid = 1L, name = "Bot关注", count = 1, tip = null)),
                relation = BilibiliFollowRelationSnapshot("123", following = false, tagIds = setOf(1L)),
                expectedGroupFetchCount = 1,
            ),
            UnfollowSkipCase(
                name = "未配置分组",
                followGroupName = "",
                relation = BilibiliFollowRelationSnapshot("123", following = true, tagIds = setOf(1L)),
                expectedGroupFetchCount = 0,
            ),
            UnfollowSkipCase(
                name = "分组不存在",
                followGroupName = "Bot关注",
                relation = BilibiliFollowRelationSnapshot("123", following = true, tagIds = setOf(1L)),
                expectedGroupFetchCount = 1,
            ),
            UnfollowSkipCase(
                name = "关系查询失败",
                followGroupName = "Bot关注",
                initialGroups = listOf(BiliGroup(tid = 1L, name = "Bot关注", count = 1, tip = null)),
                relationFailure = IllegalStateException("relation failed"),
                expectedGroupFetchCount = 1,
            ),
        )

        cases.forEach { case ->
            val gateway = FakeGateway(
                snapshot = null,
                followState = FollowState.FOLLOWING,
                followActionResult = FollowActionResult(FollowActionStatus.DONE),
                initialGroups = case.initialGroups,
                followRelation = case.relation,
                relationFailure = case.relationFailure,
            )
            val plugin = testPlugin(
                gateway,
                config = testConfig(followGroupName = case.followGroupName),
            )

            plugin.init()
            val result = plugin.unfollowPublisher("123")

            assertEquals(FollowActionStatus.NOOP, result.status, case.name)
            assertTrue(gateway.unfollowedUsers.isEmpty(), case.name)
            assertEquals(case.expectedGroupFetchCount, gateway.groupFetchCount, case.name)
            assertTrue(gateway.createdGroupNames.isEmpty(), case.name)
        }
    }

    @Test
    fun `loginByCookie should persist cookies and start detection task after startup skip`() = runBlocking {
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
            cookieLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "cookie login success"),
            exportedCookiesJson = """
                [
                  {
                    "name": "SESSDATA",
                    "value": "demo value"
                  }
                ]
            """.trimIndent(),
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
        assertEquals("""[{"name":"SESSDATA","value":"demo value"}]""", savedConfig?.cookiesJson)

        plugin.stop()
    }

    @Test
    fun `checkLoginState should delegate to gateway`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
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
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "not logged in"),
            qrLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "qr login success"),
            exportedCookiesJson = """
                [
                  {
                    "name": "SESSDATA",
                    "value": "qr demo"
                  }
                ]
            """.trimIndent(),
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
        assertEquals("""[{"name":"SESSDATA","value":"qr demo"}]""", savedConfig?.cookiesJson)

        plugin.stop()
    }

    @Test
    fun `start and stop should manage detection task scheduler`() {
        val scheduler = testScheduler()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
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
        val scheduler = testScheduler()
        val cursorStore = InMemoryCursorStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(),
            taskScheduler = scheduler,
            cursorStore = cursorStore,
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()

        plugin.init(sourceUpdates)
        plugin.start()
        withTimeout(3_000) {
            while ((scheduler.snapshot("bilibili-detect")?.runCount ?: 0L) == 0L) {
                delay(10)
            }
        }

        val seeded = seedPublisherAndSubscriber()
        val subscription = seeded.subscription
        val rawDynamic = buildDynamic(
            epochSeconds = subscription.createdAtEpochSeconds + 1,
            mid = seeded.publisher.externalId.toLong(),
            name = seeded.publisher.name,
            suffix = 42,
        )
        gateway.setDynamicPages(mapOf(1 to dynamicPage(false, rawDynamic)))

        plugin.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.SUBSCRIBED,
                subscription = subscription,
                publisher = seeded.publisher,
                subscriber = seeded.subscriber,
                changedAtEpochSeconds = subscription.createdAtEpochSeconds,
            )
        )

        val dynamicEvent = withTimeout(3_000) { sourceUpdates.receive() }
        assertNull(dynamicEvent.deliveryTarget)
        assertEquals(rawDynamic.id.toString(), dynamicEvent.update.key.externalId)
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
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val liveStore = InMemoryLiveStatusStore()
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 0.025),
            liveStatusStore = liveStore,
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        seedPublisherAndSubscriber(policy = livePolicy())

        plugin.init(sourceUpdates)
        plugin.start()
        assertEquals(LiveStatus.CLOSE, liveStore.get(1)?.status)
        assertNull(withTimeoutOrNull(100) { sourceUpdates.receive() })

        val startedAt = System.currentTimeMillis() / 1000
        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.OPEN, startedAt)))
        val startedUpdate = withTimeout(3_000) { sourceUpdates.receive() }.update
        val started = assertIs<LivePayload>(startedUpdate.payload)
        assertEquals(SourceEventType.LIVE_STARTED, startedUpdate.eventType)
        assertEquals("456", started.roomId)
        assertEquals(startedAt, started.startedAtEpochSeconds)

        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.CLOSE)))
        val endedUpdate = withTimeout(3_000) { sourceUpdates.receive() }.update
        val ended = assertIs<LivePayload>(endedUpdate.payload)
        assertEquals(SourceEventType.LIVE_ENDED, endedUpdate.eventType)
        assertEquals(startedAt, ended.startedAtEpochSeconds)
        assertTrue(ended.endedAtEpochSeconds != null)

        plugin.stop()
    }

    @Test
    fun `subscription update should enable live status baseline`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val liveStore = InMemoryLiveStatusStore()
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 30.0),
            liveStatusStore = liveStore,
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val seeded = seedPublisherAndSubscriber(policy = SubscriptionPolicy.default())

        plugin.init(sourceUpdates)
        plugin.start()
        assertNull(liveStore.get(seeded.publisher.id))

        val updatedSubscription = TestSubscriptions.updatePolicy(seeded.subscription, livePolicy())
        plugin.onSubscriptionChanged(
            SubscriptionChangedEvent(
                changeType = SubscriptionChangeType.UPDATED,
                subscription = updatedSubscription,
                publisher = seeded.publisher,
                subscriber = seeded.subscriber,
                changedAtEpochSeconds = updatedSubscription.updatedAtEpochSeconds,
            )
        )

        assertEquals(LiveStatus.CLOSE, liveStore.get(seeded.publisher.id)?.status)
        plugin.stop()
    }

    @Test
    fun `live polling should not emit close and round transitions`() = runBlocking {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val plugin = testPlugin(gateway, config = testConfig(pollingIntervalSeconds = 0.025))
        val sourceUpdates = RecordingSourceUpdatePublisher()
        seedPublisherAndSubscriber(policy = livePolicy())

        plugin.init(sourceUpdates)
        plugin.start()
        gateway.setLiveSnapshots(mapOf(123L to liveSnapshot(LiveStatus.ROUND)))

        assertNull(withTimeoutOrNull(200) { sourceUpdates.receive() })
        plugin.stop()
    }

    @Test
    fun `live polling should keep previous open state when batch fails`() = runBlocking {
        val startedAt = System.currentTimeMillis() / 1000 - 60
        val liveStore = InMemoryLiveStatusStore(
            initialStates = mapOf(
                1 to PublisherLiveStatus(
                    publisherId = 1,
                    roomId = "456",
                    status = LiveStatus.OPEN,
                    title = "Live title",
                    area = "Games",
                    startedAtEpochSeconds = startedAt,
                    lastObservedAtEpochSeconds = startedAt,
                )
            )
        )
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            failingLiveBatches = setOf(listOf(123L)),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 0.025, liveStatusBatchSize = 1),
            liveStatusStore = liveStore,
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        seedPublisherAndSubscriber(policy = livePolicy())

        plugin.init(sourceUpdates)
        plugin.start()

        assertNull(withTimeoutOrNull(200) { sourceUpdates.receive() })
        assertEquals(LiveStatus.OPEN, liveStore.get(1)?.status)

        plugin.stop()
    }

    @Test
    fun `live polling should skip failed batches and process successful batches`() = runBlocking {
        val startedAt = System.currentTimeMillis() / 1000 - 60
        val liveStore = InMemoryLiveStatusStore()
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(
                101L to liveSnapshot(LiveStatus.OPEN, startedAt, userId = "101", roomId = "501"),
                102L to liveSnapshot(LiveStatus.OPEN, startedAt, userId = "102", roomId = "502"),
            ),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 0.025, liveStatusBatchSize = 1),
            liveStatusStore = liveStore,
        )
        val sourceUpdates = RecordingSourceUpdatePublisher()
        val failedPublisher = seedPublisherAndSubscriber(
            externalId = "101",
            targetId = "9001",
            policy = livePolicy(),
        ).publisher
        val closedPublisher = seedPublisherAndSubscriber(
            externalId = "102",
            targetId = "9002",
            policy = livePolicy(),
        ).publisher

        plugin.init(sourceUpdates)
        plugin.start()
        assertEquals(LiveStatus.OPEN, liveStore.get(failedPublisher.id)?.status)
        assertEquals(LiveStatus.OPEN, liveStore.get(closedPublisher.id)?.status)

        gateway.setFailingLiveBatches(setOf(listOf(101L)))
        gateway.setLiveSnapshots(
            mapOf(
                102L to liveSnapshot(LiveStatus.CLOSE, userId = "102", roomId = "502"),
            )
        )

        val endedUpdate = withTimeout(3_000) { sourceUpdates.receive() }.update
        assertEquals(SourceEventType.LIVE_ENDED, endedUpdate.eventType)
        assertEquals("102", endedUpdate.publisher.externalId)
        assertNull(withTimeoutOrNull(150) { sourceUpdates.receive() })
        assertEquals(LiveStatus.OPEN, liveStore.get(failedPublisher.id)?.status)
        assertEquals(LiveStatus.CLOSE, liveStore.get(closedPublisher.id)?.status)

        plugin.stop()
    }

    @Test
    fun `live polling should request status in configured batches`() {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 30.0, liveStatusBatchSize = 2),
        )
        seedPublisherAndSubscriber(externalId = "101", targetId = "9001", policy = livePolicy())
        seedPublisherAndSubscriber(externalId = "102", targetId = "9002", policy = livePolicy())
        seedPublisherAndSubscriber(externalId = "103", targetId = "9003", policy = livePolicy())

        plugin.init()
        plugin.start()
        plugin.stop()

        assertEquals(listOf(listOf(101L, 102L), listOf(103L)), gateway.requestedLiveBatches.take(2))
    }

    @Test
    fun `live polling should ignore publishers without live event subscription`() {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 30.0),
        )
        seedPublisherAndSubscriber()

        plugin.init()
        plugin.start()
        plugin.stop()

        assertTrue(gateway.requestedLiveBatches.isEmpty())
    }

    @Test
    fun `dynamic polling should ignore live only subscriptions`() {
        val gateway = FakeGateway(
            snapshot = null,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            loginStateResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "logged in"),
            initialLiveSnapshots = mapOf(123L to liveSnapshot(LiveStatus.CLOSE)),
        )
        val plugin = testPlugin(
            gateway,
            config = testConfig(pollingIntervalSeconds = 30.0),
        )
        seedPublisherAndSubscriber(policy = livePolicy())

        plugin.init()
        plugin.start()
        plugin.stop()

        assertTrue(gateway.requestedPages.isEmpty())
        assertEquals(listOf(listOf(123L)), gateway.requestedLiveBatches.take(1))
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
                BiliUserInfo(
                    mid = 123L,
                    name = "demo",
                    face = BiliImageUrl("https://example.com/face.png"),
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
        taskScheduler: TaskScheduler = testScheduler(),
        cursorStore: BilibiliCursorStore = InMemoryCursorStore(),
        liveStatusStore: BilibiliLiveStatusStore = InMemoryLiveStatusStore(),
    ): BilibiliPublisherPlugin {
        TestSubscriptions.reset()
        TestSourceStateStore.reset()
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
        replayWindowMinutes: Int = 0,
        followGroupName: String = "",
        pollingIntervalSeconds: Double = 30.0,
        requestIntervalSeconds: Double = 0.0,
        shortUrlResolveTimeoutSeconds: Double = 3.0,
        liveDetectionEnabled: Boolean = true,
        liveStatusBatchSize: Int = 50,
        maxConsecutiveLoginFailures: Int = 3,
    ): BilibiliPublisherConfig {
        return BilibiliPublisherConfig(
            pollingIntervalSeconds = pollingIntervalSeconds,
            requestIntervalSeconds = requestIntervalSeconds,
            replayWindowMinutes = replayWindowMinutes,
            followGroupName = followGroupName,
            shortUrlResolveTimeoutSeconds = shortUrlResolveTimeoutSeconds,
            liveDetectionEnabled = liveDetectionEnabled,
            liveStatusBatchSize = liveStatusBatchSize,
            maxConsecutiveLoginFailures = maxConsecutiveLoginFailures,
        )
    }

    private fun livePolicy(): SubscriptionPolicy {
        return SubscriptionPolicy(
            enabledEvents = setOf(
                SubscriptionEventKind.LIVE_STARTED,
                SubscriptionEventKind.LIVE_ENDED,
            ),
        )
    }

    private fun defaultGateway(
        snapshot: BilibiliPublisherSnapshot? = null,
        dynamicDetails: Map<String, BiliDynamic> = emptyMap(),
        shortUrlExpansions: Map<String, String?> = emptyMap(),
        videoSnapshots: Map<String, BilibiliVideoSnapshot> = emptyMap(),
        liveRoomSnapshots: Map<String, BilibiliLiveRoomSnapshot> = emptyMap(),
    ): FakeGateway {
        return FakeGateway(
            snapshot = snapshot,
            followState = FollowState.FOLLOWING,
            followActionResult = FollowActionResult(FollowActionStatus.DONE),
            dynamicDetails = dynamicDetails,
            shortUrlExpansions = shortUrlExpansions,
            videoSnapshots = videoSnapshots,
            liveRoomSnapshots = liveRoomSnapshots,
        )
    }

    private fun seedPublisherAndSubscriber(
        externalId: String = "123",
        targetId: String = "9001",
        policy: SubscriptionPolicy = SubscriptionPolicy.default(),
    ): SeededSubscription {
        val publisher = TestSubscriptions.upsertPublisher(
            PublisherInfo(
                key = PublisherKey.of(platformId = "bilibili", externalId = externalId),
                name = "demo-up",
                avatar = MediaRef("https://example.com/face.png", MediaKind.AVATAR),
            ),
        )
        val subscriber = TestSubscriptions.upsertSubscriber(
            address = TargetAddress.of(
                platformId = "qq",
                kind = TargetKind.GROUP,
                externalId = targetId,
            ),
            name = "demo-subscriber",
        )
        val subscription = TestSubscriptions.subscribe(subscriber, publisher, policy)
        return SeededSubscription(publisher = publisher, subscriber = subscriber, subscription = subscription)
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
            face = BiliImageUrl("https://example.com/$mid.png"),
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
        private val videoSnapshots: Map<String, BilibiliVideoSnapshot> = emptyMap(),
        private val liveRoomSnapshots: Map<String, BilibiliLiveRoomSnapshot> = emptyMap(),
        private val followRelation: BilibiliFollowRelationSnapshot? = null,
        private val relationFailure: Throwable? = null,
        private val unfollowActionResult: FollowActionResult = FollowActionResult(
            FollowActionStatus.DONE,
            "unfollowed",
        ),
        initialDynamicPages: Map<Int, BiliDynamicList> = emptyMap(),
        failingDynamicPages: Set<Int> = emptySet(),
        private val dynamicPageFailure: Throwable? = null,
        initialGroups: List<BiliGroup> = emptyList(),
        initialLiveSnapshots: Map<Long, BilibiliLiveSnapshot> = emptyMap(),
        failingLiveBatches: Set<List<Long>> = emptySet(),
    ) : BilibiliPlatformGateway {
        private val groups: MutableList<BiliGroup> = initialGroups.toMutableList()
        private val dynamicPages: MutableMap<Int, BiliDynamicList> = initialDynamicPages.toMutableMap()
        private val dynamicPageFailures: MutableSet<Int> = failingDynamicPages.toMutableSet()
        private val liveSnapshots: MutableMap<Long, BilibiliLiveSnapshot> = initialLiveSnapshots.toMutableMap()
        private val liveBatchFailures: MutableSet<List<Long>> = failingLiveBatches.map { it.toList() }.toMutableSet()
        private var nextGroupId: Long = (groups.maxOfOrNull { it.tid } ?: 0L) + 1L

        val requestedPages: MutableList<Int> = mutableListOf()
        val requestedLiveBatches: MutableList<List<Long>> = mutableListOf()
        var groupFetchCount: Int = 0
            private set
        val createdGroupNames: MutableList<String> = mutableListOf()
        val addedGroupUsers: MutableList<GroupUsersCall> = mutableListOf()
        val requestedDetails: MutableList<String> = mutableListOf()
        val requestedVideos: MutableList<String> = mutableListOf()
        val requestedLiveRooms: MutableList<String> = mutableListOf()
        val expandedShortUrls: MutableList<String> = mutableListOf()
        val unfollowedUsers: MutableList<String> = mutableListOf()

        override suspend fun fetchNewDynamicPage(page: Int, type: String): BiliDynamicList {
            requestedPages.add(page)
            if (page in dynamicPageFailures) {
                throw dynamicPageFailure ?: IllegalStateException("dynamic page failed: $page")
            }
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

        fun setFailingDynamicPages(pages: Set<Int>) {
            dynamicPageFailures.clear()
            dynamicPageFailures.addAll(pages)
        }

        override suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
            requestedDetails.add(dynamicId)
            return dynamicDetails[dynamicId]
        }

        override suspend fun fetchVideoSnapshot(videoId: String): BilibiliVideoSnapshot? {
            requestedVideos.add(videoId)
            return videoSnapshots[videoId]
        }

        override suspend fun fetchLiveRoomSnapshot(roomId: String): BilibiliLiveRoomSnapshot? {
            requestedLiveRooms.add(roomId)
            return liveRoomSnapshots[roomId]
        }

        override suspend fun fetchLiveStatusBatch(uids: Iterable<Long>): List<BilibiliLiveSnapshot> {
            val requested = uids.toList()
            requestedLiveBatches.add(requested)
            if (requested in liveBatchFailures) {
                error("live status failed: $requested")
            }
            return requested.mapNotNull { liveSnapshots[it] }
        }

        fun setLiveSnapshots(next: Map<Long, BilibiliLiveSnapshot>) {
            liveSnapshots.clear()
            liveSnapshots.putAll(next)
        }

        fun setFailingLiveBatches(next: Set<List<Long>>) {
            liveBatchFailures.clear()
            liveBatchFailures.addAll(next.map { it.toList() })
        }

        override suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
            expandedShortUrls.add(url)
            return shortUrlExpansions[url]
        }

        override suspend fun fetchPublisherSnapshot(userId: String): BilibiliPublisherSnapshot? = snapshot

        override suspend fun queryFollowState(userId: String): FollowState = followState

        override suspend fun followPublisher(userId: String): FollowActionResult = followActionResult

        override suspend fun fetchFollowRelation(userId: String): BilibiliFollowRelationSnapshot? {
            relationFailure?.let { throw it }
            return followRelation ?: BilibiliFollowRelationSnapshot(
                userId = userId,
                following = followState == FollowState.FOLLOWING,
                tagIds = emptySet(),
            )
        }

        override suspend fun unfollowPublisher(userId: String): FollowActionResult {
            unfollowedUsers.add(userId)
            return unfollowActionResult
        }

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
        initialStates: Map<Int, SourceCursor> = emptyMap(),
    ) : BilibiliCursorStore {
        private val states: MutableMap<Int, SourceCursor> = linkedMapOf<Int, SourceCursor>().apply {
            putAll(initialStates)
        }
        private val marks: MutableList<CursorMark> = mutableListOf()

        override fun get(publisherId: Int): SourceCursor? = states[publisherId]

        override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
            states[publisherId]?.let { return it }
            return markSeen(publisherId, "__baseline__$timestamp", timestamp)
        }

        override fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): SourceCursor {
            val previous = states[publisherId]
            val recent = LinkedHashSet(previous?.recentUpdateKeys ?: emptyList())
            recent.add(dynamicId)
            while (recent.size > 50) {
                recent.remove(recent.first())
            }
            val updated = SourceCursor(
                publisherId = publisherId,
                sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
                eventType = SourceEventType.DYNAMIC_CREATED,
                lastSeenUpdateKey = dynamicId,
                lastSeenAtEpochSeconds = timestamp,
                recentUpdateKeys = recent.toList(),
            )
            states[publisherId] = updated
            marks.add(CursorMark(publisherId, dynamicId, timestamp))
            return updated
        }

        fun markedDynamicIds(publisherId: Int): List<String> {
            return marks.filter { it.publisherId == publisherId }.map { it.dynamicId }
        }

        fun put(publisherId: Int, cursor: SourceCursor) {
            states[publisherId] = cursor
        }

        override fun evict(publisherId: Int) {
            states.remove(publisherId)
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

        override fun evict(publisherId: Int) {
            states.remove(publisherId)
        }
    }

    private class RecordingSourceUpdatePublisher(
        private val result: SourceUpdatePublishResult = SourceUpdatePublishResult.enqueued(1),
    ) : SourceUpdatePublisher {
        private val requests = Channel<SourceUpdatePublishRequest>(Channel.UNLIMITED)

        override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
            requests.send(request)
            return result
        }

        suspend fun receive(): SourceUpdatePublishRequest = requests.receive()
    }

    private class InMemoryConfigService : ConfigService {
        private val values: MutableMap<String, Any> = linkedMapOf()
        private val root: Path = createTempDirectory("dynamic-bot-bilibili-config")

        override fun <T : Any> loadOrCreate(
            pluginId: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
            defaultProvider: () -> T,
        ): T {
            val value = values.getOrPut(pluginId) { defaultProvider() }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override fun <T : Any> save(pluginId: String, config: T) {
            values[pluginId] = config
        }

        override fun <T : Any> reload(
            pluginId: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return values.getValue(pluginId) as T
        }

        override fun exists(pluginId: String): Boolean = pluginId in values

        override fun delete(pluginId: String): Boolean = values.remove(pluginId) != null

        override fun resolvePath(pluginId: String): Path = root.resolve("$pluginId.yml")
    }

    private class InMemoryPluginDataStore(pluginId: String) : PluginDataStore {
        override val dataDir: Path = createTempDirectory("dynamic-bot-bilibili-data").resolve(pluginId)
        private val values: MutableMap<String, Any> = linkedMapOf()

        override fun <T : Any> loadOrCreate(
            name: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
            defaultProvider: () -> T,
        ): T {
            val value = values.getOrPut(name) { defaultProvider() }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override fun <T : Any> save(name: String, value: T) {
            values[name] = value
        }

        override fun <T : Any> reload(
            name: String,
            clazz: KClass<T>,
            migrations: List<ConfigMigration>,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return values.getValue(name) as T
        }

        override fun exists(name: String): Boolean = name in values

        override fun delete(name: String): Boolean = values.remove(name) != null

        override fun resolvePath(name: String): Path = dataDir.resolve("$name.yml")
    }

    private object TestSourceStateStore : SourceStateStore {
        private val cursors: MutableMap<String, SourceCursor> = linkedMapOf()
        private val liveStates: MutableMap<Int, PublisherLiveStatus> = linkedMapOf()

        fun reset() {
            cursors.clear()
            liveStates.clear()
        }

        override fun findCursor(publisherId: Int, sourceKey: String, eventType: SourceEventType): SourceCursor? {
            return cursors[cursorKey(publisherId, sourceKey, eventType)]
        }

        override fun ensureCursorBaseline(
            publisherId: Int,
            sourceKey: String,
            eventType: SourceEventType,
            timestamp: Long,
        ): SourceCursor {
            return findCursor(publisherId, sourceKey, eventType)
                ?: markCursorSeen(publisherId, sourceKey, eventType, "__baseline__$timestamp", timestamp)
        }

        override fun markCursorSeen(
            publisherId: Int,
            sourceKey: String,
            eventType: SourceEventType,
            updateKey: String,
            timestamp: Long,
        ): SourceCursor {
            val key = cursorKey(publisherId, sourceKey, eventType)
            val recent = LinkedHashSet(cursors[key]?.recentUpdateKeys.orEmpty())
            recent.add(updateKey)
            val updated = SourceCursor(
                publisherId = publisherId,
                sourceKey = sourceKey,
                eventType = eventType,
                lastSeenUpdateKey = updateKey,
                lastSeenAtEpochSeconds = timestamp,
                recentUpdateKeys = recent.toList(),
            )
            cursors[key] = updated
            return updated
        }

        override fun findLatestLiveStatus(publisherId: Int): PublisherLiveStatus? = liveStates[publisherId]

        override fun saveLiveStatus(state: PublisherLiveStatus): PublisherLiveStatus {
            liveStates[state.publisherId] = state
            return state
        }

        private fun cursorKey(publisherId: Int, sourceKey: String, eventType: SourceEventType): String {
            return "$publisherId|$sourceKey|${eventType.value}"
        }
    }

    private object TestSubscriptions : SubscriptionQueryService {
        private val publishers: MutableMap<Int, Publisher> = linkedMapOf()
        private val subscribers: MutableMap<Int, Subscriber> = linkedMapOf()
        private val subscriptions: MutableMap<Pair<Int, Int>, Subscription> = linkedMapOf()
        private var nextPublisherId = 1
        private var nextSubscriberId = 1
        private var nextSubscriptionId = 1

        fun reset() {
            publishers.clear()
            subscribers.clear()
            subscriptions.clear()
            nextPublisherId = 1
            nextSubscriberId = 1
            nextSubscriptionId = 1
        }

        fun upsertPublisher(info: PublisherInfo): Publisher {
            val existing = publishers.values.firstOrNull { it.key == info.key }
            val publisher = Publisher(
                id = existing?.id ?: nextPublisherId++,
                key = info.key,
                name = info.name,
                avatarBadgeKey = info.avatarBadgeKey,
                state = info.state,
                avatar = info.avatar,
                banner = info.banner,
                pendant = info.pendant,
                createTime = existing?.createTime ?: 1L,
                createUser = existing?.createUser ?: 1,
            )
            publishers[publisher.id] = publisher
            return publisher
        }

        fun upsertSubscriber(address: TargetAddress, name: String): Subscriber {
            val existing = subscribers.values.firstOrNull { it.address == address }
            val subscriber = Subscriber(
                id = existing?.id ?: nextSubscriberId++,
                address = address,
                name = name,
                state = EntityState.ACTIVE,
                createTime = existing?.createTime ?: 1L,
                createUser = existing?.createUser ?: 1,
            )
            subscribers[subscriber.id] = subscriber
            return subscriber
        }

        fun subscribe(
            subscriber: Subscriber,
            publisher: Publisher,
            policy: SubscriptionPolicy = SubscriptionPolicy.default(),
        ): Subscription {
            val key = subscriber.id to publisher.id
            val existing = subscriptions[key]
            if (existing != null) return existing

            val now = System.currentTimeMillis() / 1000
            val subscription = Subscription(
                id = nextSubscriptionId++,
                subscriberId = subscriber.id,
                publisherId = publisher.id,
                createdAtEpochSeconds = now,
                updatedAtEpochSeconds = now,
                policy = policy,
            )
            subscriptions[key] = subscription
            return subscription
        }

        fun updatePolicy(subscription: Subscription, policy: SubscriptionPolicy): Subscription {
            val updated = subscription.copy(
                policy = policy,
                updatedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            subscriptions[subscription.subscriberId to subscription.publisherId] = updated
            return updated
        }

        override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
            val publisher = publishers[publisherId] ?: return null
            val activeSubscriptions = subscriptions.values
                .filter { it.publisherId == publisherId && it.state == EntityState.ACTIVE }
                .mapNotNull { subscription ->
                    val subscriber = subscribers[subscription.subscriberId]
                        ?.takeIf { it.state == EntityState.ACTIVE }
                        ?: return@mapNotNull null
                    SubscriptionSubscriber(subscription, subscriber)
                }
            if (activeSubscriptions.isEmpty()) return null
            return PublisherSubscribers(publisher, activeSubscriptions)
        }

        override fun findActivePublishersWithSubscribersBySourcePlatform(
            platformId: String,
        ): List<PublisherSubscribers> {
            return publishers.values
                .filter { it.platformId.value == platformId && it.state == EntityState.ACTIVE }
                .mapNotNull { publisher ->
                    findActivePublisherWithSubscribersById(publisher.id)
                }
        }
    }

    private class TestTaskScheduler(
        private val scope: CoroutineScope,
    ) : TaskScheduler {
        private val tasks: MutableMap<String, TaskRuntime> = linkedMapOf()

        override fun start(task: TaskDefinition): Boolean {
            val existing = tasks[task.id]
            if (existing?.job?.isActive == true) return false

            val runtime = TaskRuntime(task)
            runtime.status = TaskStatus.RUNNING
            runtime.job = scope.launch {
                try {
                    runTask(runtime)
                    if (runtime.status == TaskStatus.RUNNING) {
                        runtime.status = TaskStatus.COMPLETED
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    runtime.status = TaskStatus.CANCELLED
                }
            }
            tasks[task.id] = runtime
            return true
        }

        override fun start(id: String): Boolean {
            val definition = tasks[id]?.definition ?: return false
            return start(definition)
        }

        override suspend fun stop(id: String): Boolean {
            val runtime = tasks[id] ?: return false
            val job = runtime.job ?: return false
            if (!job.isActive) return false
            job.cancelAndJoin()
            runtime.status = TaskStatus.CANCELLED
            return true
        }

        override suspend fun restart(id: String): Boolean {
            val definition = tasks[id]?.definition ?: return false
            stop(id)
            return start(definition)
        }

        override suspend fun stopAll() {
            tasks.keys.toList().forEach { stop(it) }
        }

        override suspend fun shutdown() {
            stopAll()
            tasks.clear()
        }

        override fun isRunning(id: String): Boolean {
            return tasks[id]?.job?.isActive == true && tasks[id]?.status == TaskStatus.RUNNING
        }

        override fun snapshot(id: String): TaskSnapshot? = tasks[id]?.snapshot()

        override fun snapshots(): List<TaskSnapshot> = tasks.values.map { it.snapshot() }.sortedBy { it.id }

        private suspend fun runTask(runtime: TaskRuntime) {
            when (val schedule = runtime.definition.schedule) {
                TaskSchedule.Once -> runRound(runtime)
                is TaskSchedule.FixedDelay -> {
                    if (!schedule.runImmediately) delayAndRecord(runtime, schedule.delay)
                    while (true) {
                        runRound(runtime)
                        delayAndRecord(runtime, schedule.delay)
                    }
                }
                is TaskSchedule.Cron -> {
                    delayAndRecord(runtime, schedule.timeToNextRun(java.time.ZonedDateTime.now(schedule.zone)))
                    runRound(runtime)
                }
            }
        }

        private suspend fun runRound(runtime: TaskRuntime) {
            runtime.nextRunAtMillis = null
            runtime.lastRunAtMillis = System.currentTimeMillis()
            runtime.runCount += 1
            runtime.definition.action()
            runtime.lastSuccessAtMillis = System.currentTimeMillis()
            runtime.lastErrorSummary = null
        }

        private suspend fun delayAndRecord(runtime: TaskRuntime, duration: Duration) {
            runtime.nextRunAtMillis = System.currentTimeMillis() + duration.inWholeMilliseconds
            delay(duration)
        }

        private class TaskRuntime(
            val definition: TaskDefinition,
        ) {
            var job: Job? = null
            var status: TaskStatus = TaskStatus.COMPLETED
            var nextRunAtMillis: Long? = null
            var lastRunAtMillis: Long? = null
            var lastSuccessAtMillis: Long? = null
            var runCount: Long = 0
            var lastErrorSummary: String? = null

            fun snapshot(): TaskSnapshot = TaskSnapshot(
                id = definition.id,
                name = definition.name,
                description = definition.description,
                status = status,
                schedule = definition.schedule,
                retryBackoffMillis = definition.retryBackoff.inWholeMilliseconds,
                nextRunAtMillis = nextRunAtMillis,
                lastRunAtMillis = lastRunAtMillis,
                lastSuccessAtMillis = lastSuccessAtMillis,
                runCount = runCount,
                lastErrorSummary = lastErrorSummary,
            )
        }
    }

    private fun liveSnapshot(
        status: LiveStatus,
        startedAt: Long? = null,
        userId: String = "123",
        roomId: String = "456",
    ): BilibiliLiveSnapshot {
        return BilibiliLiveSnapshot(
            userId = userId,
            roomId = roomId,
            status = status,
            title = "Live title",
            area = "Games",
            coverUrl = "https://example.com/cover.png",
            startedAtEpochSeconds = startedAt,
        )
    }

    private data class SeededSubscription(
        val publisher: Publisher,
        val subscriber: Subscriber,
        val subscription: Subscription,
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

    private data class UnfollowSkipCase(
        val name: String,
        val followGroupName: String,
        val initialGroups: List<BiliGroup> = emptyList(),
        val relation: BilibiliFollowRelationSnapshot? = null,
        val relationFailure: Throwable? = null,
        val expectedGroupFetchCount: Int,
    )

    private companion object {
        private const val BILI_DYNAMIC_EPOCH_BASE: Long = 1_498_838_400L
    }
}
