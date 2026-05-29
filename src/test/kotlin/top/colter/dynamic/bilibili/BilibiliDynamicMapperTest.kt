package top.colter.dynamic.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.bilibili.data.LazyImage as BiliLazyImage
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicModules
import top.colter.bilibili.data.dynamic.additional.AdditionalUgc
import top.colter.bilibili.data.dynamic.content.DynamicAdditional as BiliDynamicAdditional
import top.colter.bilibili.data.dynamic.content.DynamicDesc
import top.colter.bilibili.data.dynamic.content.DynamicMajor
import top.colter.bilibili.data.dynamic.content.DynamicTopic
import top.colter.bilibili.data.dynamic.content.RichTextNode
import top.colter.bilibili.data.dynamic.general.Badge
import top.colter.bilibili.data.dynamic.general.Stats as BiliMediaStats
import top.colter.bilibili.data.dynamic.major.MajorArticle
import top.colter.bilibili.data.dynamic.major.MajorDraw
import top.colter.bilibili.data.dynamic.major.MajorDrawItem
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
import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.VideoAttachment
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef

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
                                iconUrl = BiliLazyImage("https://example.com/emoji.png"),
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
                                src = BiliLazyImage("https://example.com/pic.png"),
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
        assertEquals("hello [ok]#topic#", mapped.content?.plainText)
        assertIs<DynamicContentNodeEmoji>(mapped.content?.nodes?.get(1))
        val topic = assertIs<DynamicContentNodeTag>(mapped.content?.nodes?.get(2))
        assertEquals("https://search.bilibili.com/all?keyword=topic", topic.url)
        val imageAttachment = mapped.attachments.filterIsInstance<ImageAttachment>().single()
        assertEquals("https://example.com/pic.png", imageAttachment.images.single().image.uri)
        assertEquals("42", mapped.metric("like"))
        assertEquals("12", mapped.metric("comment"))
        assertEquals("2k", mapped.metric("forward"))
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
                        covers = listOf(BiliLazyImage("https://example.com/article.png")),
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
                        cover = BiliLazyImage("https://example.com/video.png"),
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
                        cover = BiliLazyImage("https://example.com/related.png"),
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

        val video = mapped.attachments.filterIsInstance<VideoAttachment>().single()
        assertEquals("BV123", video.id)
        assertEquals("https://www.bilibili.com/video/BV123", video.link)
        val smallCard = mapped.attachments
            .filterIsInstance<CardAttachment>()
            .single { it.cardKind == "additional_ugc" }
        assertEquals("related", smallCard.id)
        val topicLabel = mapped.labels.single { it.sourceKey == "dynamic.topic" }
        assertEquals(DynamicLabelKind.TAG, topicLabel.kind)
        assertEquals("topic", topicLabel.text)

        val mappedOrigin = mapped.references.single { it.kind == DynamicReferenceKind.ORIGIN }.embedded
        val originPayload = assertIs<DynamicPayload>(mappedOrigin?.payload)
        val originCard = originPayload.attachments.filterIsInstance<CardAttachment>().single()
        assertEquals("article", originCard.cardKind)
        assertEquals("88", mappedOrigin?.publisher?.externalId)
    }

    @Test
    fun `map should use official badge resource for verified author`() {
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

        assertEquals(BILIBILI_OFFICIAL_BADGE_RESOURCE, update.publisher.official)
    }

    @Test
    fun `map should clear official badge when author is not verified`() {
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
                fallbackPublisher(official = BILIBILI_OFFICIAL_BADGE_RESOURCE),
            )
        )

        assertNull(update.publisher.official)
    }

    @Test
    fun `official badge helper should return null for absent verify`() {
        val official: OfficialVerify? = null

        assertNull(official.toOfficialBadgeResource())
        assertNull(OfficialVerify(type = OfficialVerifyType.NONE).toOfficialBadgeResource())
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

    private fun author(
        mid: Long,
        name: String,
        official: OfficialVerify? = null,
    ): ModuleAuthor {
        return ModuleAuthor(
            mid = mid,
            name = name,
            face = BiliLazyImage("https://example.com/$mid-face.png"),
            official = official,
            pubTs = 123,
        )
    }

    private fun fallbackPublisher(official: String? = null): Publisher {
        return Publisher(
            id = 1,
            key = PublisherKey.of(platformId = "bilibili", externalId = "42"),
            name = "fallback",
            official = official,
            avatar = MediaRef("https://example.com/fallback.png", MediaKind.AVATAR),
            createTime = 0,
            createUser = 0,
        )
    }

    private fun DynamicPayload.metric(key: String): String? {
        return metrics.firstOrNull { it.key == key }?.display
    }
}
