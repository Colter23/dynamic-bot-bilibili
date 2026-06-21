package top.colter.dynamic.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.bilibili.data.ImageUrl as BiliImageUrl
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicModules
import top.colter.bilibili.data.dynamic.additional.AdditionalCommon
import top.colter.bilibili.data.dynamic.additional.AdditionalGoodItem
import top.colter.bilibili.data.dynamic.additional.AdditionalGoods
import top.colter.bilibili.data.dynamic.additional.AdditionalLottery
import top.colter.bilibili.data.dynamic.additional.AdditionalReserve
import top.colter.bilibili.data.dynamic.additional.AdditionalUgc
import top.colter.bilibili.data.dynamic.additional.AdditionalVote
import top.colter.bilibili.data.dynamic.content.DynamicAdditional as BiliDynamicAdditional
import top.colter.bilibili.data.dynamic.content.DynamicDesc
import top.colter.bilibili.data.dynamic.content.DynamicMajor
import top.colter.bilibili.data.dynamic.content.DynamicTopic
import top.colter.bilibili.data.dynamic.content.RichTextNode
import top.colter.bilibili.data.dynamic.general.Badge
import top.colter.bilibili.data.dynamic.general.Button
import top.colter.bilibili.data.dynamic.general.Desc
import top.colter.bilibili.data.dynamic.general.Stats as BiliMediaStats
import top.colter.bilibili.data.dynamic.major.MajorArticle
import top.colter.bilibili.data.dynamic.major.MajorDraw
import top.colter.bilibili.data.dynamic.major.MajorDrawItem
import top.colter.bilibili.data.dynamic.major.MajorOpus
import top.colter.bilibili.data.dynamic.major.MajorOpusPic
import top.colter.bilibili.data.dynamic.major.MajorVideo
import top.colter.bilibili.data.dynamic.module.ModuleAuthor
import top.colter.bilibili.data.dynamic.module.ModuleDynamic
import top.colter.bilibili.data.dynamic.module.ModuleStats
import top.colter.bilibili.data.dynamic.type.AdditionalType
import top.colter.bilibili.data.dynamic.type.MajorType
import top.colter.bilibili.data.dynamic.type.OriginDynamicType
import top.colter.bilibili.data.dynamic.type.RichTextType
import top.colter.bilibili.data.user.OfficialVerify
import top.colter.bilibili.data.user.OfficialVerifyType
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.PollStatus
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import kotlin.test.assertTrue

class BilibiliDynamicMapperTest {
    private val mapper = BilibiliDynamicMapper()

    @Test
    fun `map should preserve rich content images and stat counts`() {
        val source = dynamic(
            id = "123456789",
            type = OriginDynamicType.DRAW,
            dynamic = ModuleDynamic(
                desc = DynamicDesc(
                    richTextNodes = listOf(
                        RichTextNode(
                            type = RichTextType.TEXT,
                            origText = "hello ",
                            text = "hello ",
                        ),
                        RichTextNode(
                            type = RichTextType.EMOJI,
                            origText = "[ok]",
                            text = "[ok]",
                            emoji = RichTextNode.Emoji(
                                type = 1,
                                iconUrl = BiliImageUrl("https://example.com/emoji.png"),
                                size = 1,
                                text = "[ok]",
                            ),
                        ),
                        RichTextNode(
                            type = RichTextType.TOPIC,
                            origText = "#topic#",
                            text = "#topic#",
                            jumpUrl = "//search.bilibili.com/all?keyword=topic",
                        ),
                    ),
                    text = "hello [ok]#topic#",
                ),
                major = DynamicMajor(
                    type = MajorType.DRAW,
                    draw = MajorDraw(
                        id = 8,
                        images = listOf(
                            MajorDrawItem(
                                width = 640,
                                height = 480,
                                size = 12.5f,
                                src = BiliImageUrl("https://example.com/pic.png"),
                            ),
                        ),
                    ),
                ),
            ),
            stats = ModuleStats(
                comment = ModuleStats.Stats(text = null, count = 12, forbidden = false),
                forward = ModuleStats.Stats(text = "2k", count = 2_000, forbidden = false),
                like = ModuleStats.Stats(text = null, count = 42, forbidden = false),
            ),
        )

        val update = assertNotNull(mapper.map(source, fallbackPublisher()))
        val mapped = assertIs<DynamicPayload>(update.payload)

        assertEquals(SourceEventType.DYNAMIC_CREATED, update.eventType)
        assertEquals("123456789", update.key.externalId)
        assertEquals(123L, update.occurredAtEpochSeconds)
        val textBlock = mapped.blocks.filterIsInstance<TextBlock>().single()
        assertEquals("hello [ok]#topic#", textBlock.content.plainText)
        assertIs<DynamicContentNodeEmoji>(textBlock.content.nodes[1])
        val topic = assertIs<DynamicContentNodeTag>(textBlock.content.nodes[2])
        assertEquals("https://search.bilibili.com/all?keyword=topic", topic.url)
        val imageBlock = mapped.blocks.filterIsInstance<ImageGridBlock>().single()
        assertEquals("https://example.com/pic.png", imageBlock.images.single().image.uri)
        assertEquals("42", mapped.metric("like"))
        assertEquals("12", mapped.metric("comment"))
        assertEquals("2k", mapped.metric("forward"))
    }

    @Test
    fun `map should preserve newline-only text nodes between image emoji`() {
        fun emojiNode(value: String): RichTextNode {
            return RichTextNode(
                type = RichTextType.EMOJI,
                origText = value,
                text = value,
                emoji = RichTextNode.Emoji(
                    type = 1,
                    iconUrl = BiliImageUrl("https://example.com/$value.png"),
                    size = 1,
                    text = value,
                ),
            )
        }

        val source = dynamic(
            id = "123456791",
            type = OriginDynamicType.WORD,
            dynamic = ModuleDynamic(
                desc = DynamicDesc(
                    richTextNodes = listOf(
                        RichTextNode(
                            type = RichTextType.TEXT,
                            origText = "测试",
                            text = "测试",
                        ),
                        emojiNode("[emoji1]"),
                        emojiNode("[emoji2]"),
                        emojiNode("[emoji3]"),
                        RichTextNode(
                            type = RichTextType.TEXT,
                            origText = "\n\n\n",
                            text = "\n\n\n",
                        ),
                        emojiNode("[emoji4]"),
                        emojiNode("[emoji5]"),
                        emojiNode("[emoji6]"),
                    ),
                    text = "测试[emoji1][emoji2][emoji3]\n\n\n[emoji4][emoji5][emoji6]",
                ),
            ),
        )

        val mapped = assertIs<DynamicPayload>(assertNotNull(mapper.map(source, fallbackPublisher())).payload)
        val textBlock = mapped.blocks.filterIsInstance<TextBlock>().single()

        assertEquals("测试[emoji1][emoji2][emoji3]\n\n\n[emoji4][emoji5][emoji6]", textBlock.content.plainText)
        assertIs<DynamicContentNodeEmoji>(textBlock.content.nodes[1])
        assertEquals("\n\n\n", textBlock.content.nodes[4].text)
        assertIs<DynamicContentNodeEmoji>(textBlock.content.nodes[5])
    }

    @Test
    fun `map should infer dynamic image badges`() {
        val source = dynamic(
            id = "123456790",
            type = OriginDynamicType.DRAW,
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.DRAW,
                    draw = MajorDraw(
                        id = 9,
                        images = listOf(
                            MajorDrawItem(
                                width = 400,
                                height = 901,
                                size = 12.5f,
                                src = BiliImageUrl("https://example.com/long.jpg"),
                            ),
                            MajorDrawItem(
                                width = 640,
                                height = 360,
                                size = 10f,
                                src = BiliImageUrl("https://example.com/animated.GIF?x=1"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapped = assertIs<DynamicPayload>(assertNotNull(mapper.map(source, fallbackPublisher())).payload)
        val imageBlock = mapped.blocks.filterIsInstance<ImageGridBlock>().single()

        assertEquals(listOf("长图", "动图"), imageBlock.images.map { it.badge })
    }

    @Test
    fun `map should convert cards labels and forwarded origin`() {
        val origin = dynamic(
            id = "100",
            type = OriginDynamicType.ARTICLE,
            author = author(mid = 88, name = "writer"),
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.ARTICLE,
                    article = MajorArticle(
                        id = 7,
                        title = "article",
                        description = "article body",
                        label = "100 reads",
                        jumpUrl = "//www.bilibili.com/read/cv7",
                        covers = listOf(BiliImageUrl("https://example.com/article.png")),
                    ),
                ),
            ),
        )
        val source = dynamic(
            id = "200",
            type = OriginDynamicType.FORWARD,
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.ARCHIVE,
                    video = MajorVideo(
                        type = 1,
                        aid = 9,
                        bvid = "BV123",
                        title = "video",
                        cover = BiliImageUrl("https://example.com/video.png"),
                        description = "video body",
                        duration = "01:00",
                        jumpUrl = "//www.bilibili.com/video/BV123",
                        stats = BiliMediaStats(danmaku = "4", play = "3"),
                        badge = Badge(bgColor = "", color = "", text = "video"),
                    ),
                ),
                additional = BiliDynamicAdditional(
                    type = AdditionalType.UGC,
                    ugc = AdditionalUgc(
                        idStr = "related",
                        title = "related video",
                        cover = BiliImageUrl("https://example.com/related.png"),
                        descSecond = "related body",
                        duration = "02:00",
                        headText = "related",
                        jumpUrl = "//www.bilibili.com/video/BV456",
                        multiLine = false,
                    ),
                ),
                topic = DynamicTopic(
                    id = 66,
                    name = "topic",
                    jumpUrl = "//www.bilibili.com/topic/66",
                ),
            ),
            origin = origin,
        )

        val update = assertNotNull(mapper.map(source, fallbackPublisher()))
        val mapped = assertIs<DynamicPayload>(update.payload)

        val video = mapped.blocks.filterIsInstance<MediaCardBlock>().single { it.card.sourceKind == "bilibili.major.video" }
        assertEquals(MediaCardStyle.LARGE, video.style)
        assertEquals("BV123", video.card.id)
        assertEquals("https://www.bilibili.com/video/BV123", video.card.link)
        val smallCard = mapped.blocks
            .filterIsInstance<MediaCardBlock>()
            .single { it.card.sourceKind == "bilibili.additional.ugc" }
        assertEquals(MediaCardStyle.MINI, smallCard.style)
        assertEquals("related", smallCard.card.id)
        val topic = assertIs<TextBlock>(mapped.blocks.first()).content.nodes.first()
        assertEquals("#topic#", topic.text)

        val mappedOrigin = assertIs<RepostBlock>(
            mapped.blocks.single { it is RepostBlock && it.referenceKind == DynamicReferenceKind.ORIGIN },
        ).embedded
        val originPayload = assertIs<DynamicPayload>(mappedOrigin?.payload)
        val originCard = originPayload.blocks.filterIsInstance<MediaCardBlock>().single()
        assertEquals("bilibili.major.article", originCard.card.sourceKind)
        assertEquals("88", mappedOrigin?.publisher?.externalId)
    }

    @Test
    fun `map should convert opus summary pictures and link`() {
        val source = dynamic(
            id = "250",
            type = OriginDynamicType.DRAW,
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.OPUS,
                    opus = MajorOpus(
                        title = "opus title",
                        jumpUrl = "//www.bilibili.com/opus/250",
                        summary = DynamicDesc(
                            richTextNodes = listOf(
                                RichTextNode(
                                    type = RichTextType.TEXT,
                                    origText = "opus summary",
                                    text = "opus summary",
                                ),
                            ),
                            text = "opus summary",
                        ),
                        pics = listOf(
                            MajorOpusPic(
                                width = 640,
                                height = 360,
                                size = 23.0,
                                url = BiliImageUrl("https://example.com/opus.png"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val mapped = assertIs<DynamicPayload>(assertNotNull(mapper.map(source, fallbackPublisher())).payload)
        val textBlock = mapped.blocks.filterIsInstance<TextBlock>().single()
        val imageBlock = mapped.blocks.filterIsInstance<ImageGridBlock>().single()

        assertEquals("opus title", mapped.title)
        assertEquals("opus summary", textBlock.content.plainText)
        assertEquals("https://www.bilibili.com/opus/250", textBlock.link)
        assertEquals("bilibili.major.opus.summary", textBlock.sourceKind)
        assertEquals("bilibili.major.opus", imageBlock.sourceKind)
        assertEquals("https://www.bilibili.com/opus/250", imageBlock.link)
        assertEquals("https://example.com/opus.png", imageBlock.images.single().image.uri)
    }

    @Test
    fun `map should convert all supported additional blocks`() {
        val cards = listOf(
            additionalPayload(
                BiliDynamicAdditional(
                    type = AdditionalType.COMMON,
                    common = AdditionalCommon(
                        idStr = "activity",
                        title = "activity title",
                        cover = BiliImageUrl(""),
                        subType = "official_activity",
                        desc1 = "activity desc",
                        desc2 = "activity time",
                        headText = "活动",
                        jumpUrl = "//www.bilibili.com/activity",
                        style = 1,
                        button = button(),
                    ),
                )
            ).blocks.filterIsInstance<MediaCardBlock>().single(),
            additionalPayload(
                BiliDynamicAdditional(
                    type = AdditionalType.RESERVE,
                    reserve = AdditionalReserve(
                        rid = 9001,
                        upMid = 42,
                        title = "reserve title",
                        reserveTotal = 1234,
                        desc1 = Desc(text = "直播预约", style = 0),
                        desc2 = Desc(text = "今晚八点", style = 0),
                        desc3 = Desc(text = "已预约", style = 0),
                        state = 1,
                        stype = 2,
                        jumpUrl = "//live.bilibili.com/9001",
                        button = button(),
                    ),
                )
            ).blocks.filterIsInstance<MediaCardBlock>().single(),
            additionalPayload(
                BiliDynamicAdditional(
                    type = AdditionalType.UGC,
                    ugc = AdditionalUgc(
                        idStr = "ugc",
                        title = "ugc title",
                        cover = BiliImageUrl(""),
                        descSecond = "ugc desc",
                        duration = "03:21",
                        headText = "相关视频",
                        jumpUrl = "//www.bilibili.com/video/BV1",
                        multiLine = false,
                    ),
                )
            ).blocks.filterIsInstance<MediaCardBlock>().single(),
            additionalPayload(
                BiliDynamicAdditional(
                    type = AdditionalType.GOODS,
                    goods = AdditionalGoods(
                        headIcon = "",
                        headText = "商品",
                        jumpUrl = "//mall.bilibili.com",
                        items = listOf(
                            AdditionalGoodItem(
                                id = "goods",
                                name = "goods title",
                                brief = "goods brief",
                                cover = BiliImageUrl(""),
                                price = "12",
                                jumpDesc = "去看看",
                                jumpUrl = "//mall.bilibili.com/goods",
                            ),
                        ),
                    ),
                )
            ).blocks.filterIsInstance<MediaCardBlock>().single(),
            additionalPayload(
                BiliDynamicAdditional(
                    type = AdditionalType.LOTTERY,
                    lottery = AdditionalLottery(
                        rid = 7001,
                        title = "lottery title",
                        mid = 42,
                        state = 1,
                        desc = Desc(text = "lottery desc", style = 0),
                        button = button(),
                        jumpUrl = "//www.bilibili.com/lottery",
                    ),
                )
            ).blocks.filterIsInstance<MediaCardBlock>().single(),
        )
        val poll = additionalPayload(
            BiliDynamicAdditional(
                type = AdditionalType.VOTE,
                vote = AdditionalVote(
                    uid = 42,
                    voteId = 6001,
                    desc = "vote title",
                    type = 1,
                    status = 1,
                    joinNum = 12,
                    endTime = 0,
                    choiceCnt = 1,
                    defaultShare = 0,
                ),
            )
        ).blocks.filterIsInstance<PollBlock>().single()

        assertEquals(
            listOf(
                "bilibili.additional.common:official_activity",
                "bilibili.additional.reserve:2",
                "bilibili.additional.ugc",
                "bilibili.additional.goods",
                "bilibili.additional.lottery",
            ),
            cards.map { it.card.sourceKind },
        )
        assertTrue(cards.all { it.style == MediaCardStyle.MINI })
        assertTrue(cards.all { it.role == DynamicBlockRole.ADDITIONAL })
        assertEquals("vote title", poll.title)
        assertEquals(PollStatus.OPEN, poll.status)
        assertEquals(DynamicBlockRole.ADDITIONAL, poll.role)
    }

    @Test
    fun `map should keep major card when cover is absent`() {
        val source = dynamic(
            id = "251",
            type = OriginDynamicType.ARTICLE,
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.ARTICLE,
                    article = MajorArticle(
                        id = 251,
                        title = "article without cover",
                        description = "article body",
                        label = "100 reads",
                        jumpUrl = "//www.bilibili.com/read/cv251",
                        covers = listOf(BiliImageUrl("")),
                    ),
                ),
            ),
        )

        val mapped = assertIs<DynamicPayload>(assertNotNull(mapper.map(source, fallbackPublisher())).payload)
        val card = mapped.blocks.filterIsInstance<MediaCardBlock>().single()

        assertEquals("bilibili.major.article", card.card.sourceKind)
        assertNull(card.card.cover)
    }

    @Test
    fun `map should make video small inside forwarded origin`() {
        val origin = dynamic(
            id = "101",
            type = OriginDynamicType.AV,
            dynamic = ModuleDynamic(
                major = DynamicMajor(
                    type = MajorType.ARCHIVE,
                    video = MajorVideo(
                        type = 1,
                        aid = 10,
                        bvid = "BV999",
                        title = "origin video",
                        cover = BiliImageUrl("https://example.com/origin-video.png"),
                        description = "origin body",
                        duration = "03:21",
                        jumpUrl = "//www.bilibili.com/video/BV999",
                        stats = BiliMediaStats(danmaku = "6", play = "5"),
                        badge = Badge(bgColor = "", color = "", text = "video"),
                    ),
                ),
            ),
        )
        val source = dynamic(
            id = "201",
            type = OriginDynamicType.FORWARD,
            dynamic = ModuleDynamic(desc = DynamicDesc(richTextNodes = emptyList(), text = "forward")),
            origin = origin,
        )

        val mapped = assertIs<DynamicPayload>(assertNotNull(mapper.map(source, fallbackPublisher())).payload)
        val originPayload = assertIs<DynamicPayload>(
            assertIs<RepostBlock>(mapped.blocks.filterIsInstance<RepostBlock>().single()).embedded?.payload,
        )
        val originVideo = originPayload.blocks.filterIsInstance<MediaCardBlock>().single()

        assertEquals("bilibili.major.video", originVideo.card.sourceKind)
        assertEquals(MediaCardStyle.SMALL, originVideo.style)
    }

    @Test
    fun `map should use avatar badge key for verified author`() {
        val source = dynamic(
            id = "300",
            type = OriginDynamicType.WORD,
            dynamic = ModuleDynamic(),
            author = author(
                mid = 42,
                name = "publisher",
                official = OfficialVerify(type = OfficialVerifyType.INDIVIDUAL),
            ),
        )

        val update = assertNotNull(mapper.map(source, fallbackPublisher()))

        assertEquals(BILIBILI_OFFICIAL_INDIVIDUAL_BADGE_KEY, update.publisher.avatarBadgeKey)
    }

    @Test
    fun `map should clear avatar badge key when author is not verified`() {
        val source = dynamic(
            id = "301",
            type = OriginDynamicType.WORD,
            dynamic = ModuleDynamic(),
            author = author(
                mid = 42,
                name = "publisher",
                official = OfficialVerify(type = OfficialVerifyType.NONE),
            ),
        )

        val update = assertNotNull(
            mapper.map(
                source,
                fallbackPublisher(avatarBadgeKey = BILIBILI_OFFICIAL_INDIVIDUAL_BADGE_KEY),
            )
        )

        assertNull(update.publisher.avatarBadgeKey)
    }

    @Test
    fun `avatar badge helper should return null for absent verify`() {
        val official: OfficialVerify? = null

        assertNull(official.toAvatarBadgeKey())
        assertNull(OfficialVerify(type = OfficialVerifyType.NONE).toAvatarBadgeKey())
    }

    @Test
    fun `avatar badge helper should map non individual verify to institution key`() {
        val institutionType = enumValues<OfficialVerifyType>()
            .firstOrNull { it != OfficialVerifyType.NONE && it != OfficialVerifyType.INDIVIDUAL }
            ?: return

        assertEquals(
            BILIBILI_OFFICIAL_INSTITUTION_BADGE_KEY,
            OfficialVerify(type = institutionType).toAvatarBadgeKey(),
        )
    }

    private fun dynamic(
        id: String,
        type: OriginDynamicType,
        dynamic: ModuleDynamic,
        author: ModuleAuthor = author(mid = 42, name = "publisher"),
        stats: ModuleStats? = null,
        origin: BiliDynamic? = null,
    ): BiliDynamic {
        return BiliDynamic(
            originType = type,
            idStr = id,
            modules = BiliDynamicModules(
                author = author,
                dynamic = dynamic,
                stat = stats,
            ),
            origin = origin,
        )
    }

    private fun additionalPayload(additional: BiliDynamicAdditional): DynamicPayload {
        return assertIs<DynamicPayload>(
            assertNotNull(
                mapper.map(
                    dynamic(
                        id = additional.type.name.hashCode().toUInt().toString(),
                        type = OriginDynamicType.WORD,
                        dynamic = ModuleDynamic(additional = additional),
                    ),
                    fallbackPublisher(),
                )
            ).payload
        )
    }

    private fun button(): Button {
        return Button(
            type = 1,
            jumpUrl = "//www.bilibili.com/button",
        )
    }

    private fun author(
        mid: Long,
        name: String,
        official: OfficialVerify? = null,
    ): ModuleAuthor {
        return ModuleAuthor(
            mid = mid,
            name = name,
            face = BiliImageUrl("https://example.com/$mid-face.png"),
            official = official,
            pubTs = 123,
        )
    }

    private fun fallbackPublisher(avatarBadgeKey: String? = null): Publisher {
        return Publisher(
            id = 1,
            key = PublisherKey.of(platformId = "bilibili", externalId = "42"),
            name = "fallback",
            avatarBadgeKey = avatarBadgeKey,
            avatar = MediaRef("https://example.com/fallback.png", MediaKind.AVATAR),
            createTime = 0,
            createUser = 0,
        )
    }

    private fun DynamicPayload.metric(key: String): String? {
        return metrics.firstOrNull { it.key == key }?.display
    }
}
