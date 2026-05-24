package top.colter.dynamic.bilibili

import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicMedia
import top.colter.dynamic.core.data.DynamicMediaPic
import top.colter.dynamic.core.data.DynamicMediaVideo
import top.colter.dynamic.core.data.DynamicMediaVideoStats
import top.colter.dynamic.core.data.DynamicStats
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherPlatform
import top.colter.dynamic.core.data.PublisherType

internal class BilibiliDynamicMapper {
    fun map(source: BiliDynamic, fallbackPublisher: Publisher): Dynamic? {
        if (source.id <= 0) return null

        val dynamicId = source.id.toString()
        val desc = source.modules.dynamic.desc
        val contentText = desc?.text?.trim().orEmpty()
        val contentNodes = if (contentText.isBlank()) {
            emptyList()
        } else {
            listOf(DynamicContentNodeText(contentText))
        }

        val media = buildMedia(source)
        val stats = source.modules.stat?.let {
            DynamicStats(
                like = it.like?.text.orEmpty(),
                comment = it.comment?.text.orEmpty(),
                forward = it.forward?.text.orEmpty(),
            )
        }

        return Dynamic(
            platform = BILIBILI_PLATFORM,
            dynamicId = dynamicId,
            publisher = fallbackPublisher.copy(
                type = PublisherType.USER,
                userId = source.mid.takeIf { it > 0 }?.toString() ?: fallbackPublisher.userId,
                name = source.name.takeIf { it.isNotBlank() } ?: fallbackPublisher.name,
                face = source.modules.author.face?.url
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::LazyImage)
                    ?: fallbackPublisher.face,
            ),
            time = source.time * 1000,
            link = "https://t.bilibili.com/$dynamicId",
            content = DynamicContent(
                text = contentText,
                contentNodes = contentNodes,
            ),
            media = media,
            stats = stats,
        )
    }

    private fun buildMedia(source: BiliDynamic): DynamicMedia? {
        val major = source.modules.dynamic.major ?: return null

        val pics = major.draw?.images?.map {
            DynamicMediaPic(
                pic = LazyImage(it.src.url),
                width = it.width,
                height = it.height,
                size = it.size,
                badge = it.tags?.firstOrNull()?.text,
            )
        }

        val video = major.video?.let {
            DynamicMediaVideo(
                id = it.bvid.ifBlank { it.aid.toString() },
                title = it.title.orEmpty(),
                description = it.description.orEmpty(),
                cover = LazyImage(it.cover.url),
                duration = it.duration.orEmpty(),
                badge = it.badge?.text.orEmpty(),
                stats = DynamicMediaVideoStats(
                    play = it.stats?.play.orEmpty(),
                    danmaku = it.stats?.danmaku.orEmpty(),
                    like = source.modules.stat?.like?.text.orEmpty(),
                ),
                link = it.jumpUrl.orEmpty().ifBlank { "https://www.bilibili.com/video/${it.bvid}" },
            )
        }

        if ((pics == null || pics.isEmpty()) && video == null) return null
        return DynamicMedia(
            pics = pics,
            video = video,
        )
    }

    private companion object {
        private val BILIBILI_PLATFORM: PublisherPlatform = PublisherPlatform(
            id = "bilibili",
            name = "Bilibili",
            link = "https://www.bilibili.com",
            icon = "https://www.bilibili.com/favicon.ico",
        )
    }
}
