package top.colter.dynamic.bilibili

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
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.LazyImage as CoreLazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
                            )
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

        val mapped = assertNotNull(mapper.map(source, fallbackPublisher()))

        assertEquals(123L, mapped.time)
        assertEquals("hello [ok]#topic#", mapped.content?.text)
        assertIs<DynamicContentNodeEmoji>(mapped.content?.contentNodes?.get(1))
        val topic = assertIs<DynamicContentNodeLink>(mapped.content?.contentNodes?.get(2))
        assertEquals("https://search.bilibili.com/all?keyword=topic", topic.url)
        assertEquals("https://example.com/pic.png", mapped.media?.pics?.single()?.pic?.uri)
        assertEquals("42", mapped.stats?.like)
        assertEquals("12", mapped.stats?.comment)
        assertEquals("2k", mapped.stats?.forward)
    }

    @Test
    fun `map should convert cards additional data and forwarded origin`() {
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

        val mapped = assertNotNull(mapper.map(source, fallbackPublisher()))

        assertEquals("BV123", mapped.media?.video?.id)
        assertEquals("https://www.bilibili.com/video/BV123", mapped.media?.video?.link)
        assertEquals("additional_ugc", mapped.media?.smallCard?.type)
        assertEquals("related", mapped.additional?.card?.id)
        assertEquals("66", mapped.additional?.tags?.id)
        assertEquals("article", mapped.origin?.media?.card?.type)
        assertEquals("88", mapped.origin?.publisher?.externalId)
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

    private fun author(mid: Long, name: String): ModuleAuthor {
        return ModuleAuthor(
            mid = mid,
            name = name,
            face = BiliLazyImage("https://example.com/$mid-face.png"),
            pubTs = 123,
        )
    }

    private fun fallbackPublisher(): Publisher {
        return Publisher(
            id = 1,
            platformId = "bilibili",
            type = PublisherType.USER,
            externalId = "42",
            name = "fallback",
            face = CoreLazyImage("https://example.com/fallback.png"),
            createTime = 0,
            createUser = 0,
        )
    }
}
