package top.colter.dynamic.bilibili

import top.colter.bilibili.data.LazyImage as BiliLazyImage
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.additional.AdditionalCommon
import top.colter.bilibili.data.dynamic.additional.AdditionalGoods
import top.colter.bilibili.data.dynamic.additional.AdditionalUgc
import top.colter.bilibili.data.dynamic.content.DynamicAdditional as BiliDynamicAdditional
import top.colter.bilibili.data.dynamic.content.DynamicDesc
import top.colter.bilibili.data.dynamic.content.DynamicMajor
import top.colter.bilibili.data.dynamic.content.RichTextNode
import top.colter.bilibili.data.dynamic.major.MajorArticle
import top.colter.bilibili.data.dynamic.major.MajorBlocked
import top.colter.bilibili.data.dynamic.major.MajorCommon
import top.colter.bilibili.data.dynamic.major.MajorDrawItem
import top.colter.bilibili.data.dynamic.major.MajorLive
import top.colter.bilibili.data.dynamic.major.MajorLiveRcmd
import top.colter.bilibili.data.dynamic.major.MajorMediaList
import top.colter.bilibili.data.dynamic.major.MajorMusic
import top.colter.bilibili.data.dynamic.major.MajorOpusPic
import top.colter.bilibili.data.dynamic.major.MajorPgc
import top.colter.bilibili.data.dynamic.major.MajorVideo
import top.colter.bilibili.data.dynamic.module.ModuleAuthor
import top.colter.bilibili.data.dynamic.module.ModuleStats
import top.colter.bilibili.data.dynamic.type.RichTextType
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.DynamicAdditional
import top.colter.dynamic.core.data.DynamicAdditionalCard
import top.colter.dynamic.core.data.DynamicAdditionalTags
import top.colter.dynamic.core.data.DynamicAdditionalVote
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicMedia
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaPic
import top.colter.dynamic.core.data.DynamicMediaVideo
import top.colter.dynamic.core.data.DynamicMediaVideoStats
import top.colter.dynamic.core.data.DynamicStats
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherType

internal class BilibiliDynamicMapper {
    fun map(source: BiliDynamic, fallbackPublisher: Publisher): Dynamic? {
        return map(source, fallbackPublisher, depth = 0)
    }

    private fun map(source: BiliDynamic, fallbackPublisher: Publisher, depth: Int): Dynamic? {
        if (source.id <= 0) return null

        val dynamicId = source.id.toString()
        return Dynamic(
            platform = BILIBILI_PLATFORM,
            dynamicId = dynamicId,
            publisher = buildPublisher(source.modules.author, fallbackPublisher),
            time = source.timestampSeconds(),
            link = dynamicLink(dynamicId),
            notice = buildNotice(source),
            title = buildTitle(source),
            content = buildContent(source),
            media = buildMedia(source),
            additional = buildAdditional(source),
            stats = buildStats(source.modules.stat),
            origin = if (depth < MAX_ORIGIN_DEPTH) {
                source.origin?.let { map(it, fallbackPublisher, depth + 1) }
            } else {
                null
            },
        )
    }

    private fun buildPublisher(author: ModuleAuthor, fallbackPublisher: Publisher): Publisher {
        return fallbackPublisher.copy(
            platformId = BILIBILI_PLATFORM.id,
            type = if (author.type == AUTHOR_TYPE_NORMAL) PublisherType.USER else PublisherType.OTHER,
            externalId = author.mid.takeIf { it > 0 }?.toString() ?: fallbackPublisher.externalId,
            name = author.name.takeIfNotBlank() ?: fallbackPublisher.name,
            official = author.official?.title.takeIfNotBlank()
                ?: author.official?.desc.takeIfNotBlank()
                ?: fallbackPublisher.official,
            face = author.face.toCoreImageOrNull() ?: fallbackPublisher.face,
            pendant = author.pendant?.image?.toCoreImageOrNull() ?: fallbackPublisher.pendant,
        )
    }

    private fun buildNotice(source: BiliDynamic): String? {
        return listOf(
            source.modules.tag?.text,
            source.modules.author.pubAction,
            source.modules.dispute?.title,
            source.modules.fold?.statement,
            source.modules.dynamic.major?.none?.tips,
        ).joinNonBlank(separator = " | ")
    }

    private fun buildTitle(source: BiliDynamic): String? {
        return source.modules.dynamic.major?.opus?.title.takeIfNotBlank()
    }

    private fun buildContent(source: BiliDynamic): DynamicContent? {
        val desc = source.modules.dynamic.desc ?: source.modules.dynamic.major?.opus?.summary ?: return null
        return desc.toDynamicContent()
    }

    private fun DynamicDesc.toDynamicContent(): DynamicContent? {
        val nodes = richTextNodes.mapNotNull { it.toDynamicContentNode() }
        val text = this.text.takeIfNotBlank() ?: nodes.joinToString(separator = "") { it.text }.takeIfNotBlank()

        if (text == null && nodes.isEmpty()) return null

        return DynamicContent(
            text = text.orEmpty(),
            contentNodes = nodes.ifEmpty { text?.let { listOf(DynamicContentNodeText(it)) }.orEmpty() },
        )
    }

    private fun RichTextNode.toDynamicContentNode(): DynamicContentNode? {
        val displayText = text.takeIfNotBlank() ?: origText.takeIfNotBlank() ?: return null
        return when (type) {
            RichTextType.TEXT -> DynamicContentNodeText(displayText)
            RichTextType.EMOJI -> emoji?.iconUrl?.toCoreImageOrNull()
                ?.let { DynamicContentNodeEmoji(text = displayText, image = it) }
                ?: DynamicContentNodeText(displayText)
            RichTextType.AT -> DynamicContentNodeLink(
                text = displayText,
                url = jumpUrl.toNormalizedUrlOrNull() ?: rid?.let { "https://space.bilibili.com/$it" },
            )
            RichTextType.BV -> DynamicContentNodeLink(
                text = displayText,
                url = jumpUrl.toNormalizedUrlOrNull() ?: displayText
                    .takeIf { it.startsWith("BV", ignoreCase = true) }
                    ?.let { "$BILIBILI_HOME/video/$it" },
            )
            RichTextType.TOPIC,
            RichTextType.WEB,
            RichTextType.VOTE,
            RichTextType.GOODS -> DynamicContentNodeLink(
                text = displayText,
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
            RichTextType.UNKNOWN -> jumpUrl.toNormalizedUrlOrNull()
                ?.let { DynamicContentNodeLink(text = displayText, url = it) }
                ?: DynamicContentNodeText(displayText)
        }
    }

    private fun buildMedia(source: BiliDynamic): DynamicMedia? {
        val major = source.modules.dynamic.major
        val media = DynamicMedia(
            pics = major?.buildPics(),
            video = major?.buildVideo(source),
            card = major?.buildLargeCard(source),
            smallCard = major?.buildSmallCard(source) ?: source.modules.dynamic.additional?.buildSmallCard(),
            miniCard = major?.buildMiniCard(source),
        )

        return media.takeIf {
            !it.pics.isNullOrEmpty() ||
                it.video != null ||
                it.card != null ||
                it.smallCard != null ||
                it.miniCard != null
        }
    }

    private fun DynamicMajor.buildPics(): List<DynamicMediaPic>? {
        val drawPics = draw?.images
            ?.mapNotNull { it.toDynamicMediaPic() }
            ?.takeIf { it.isNotEmpty() }
        if (drawPics != null) return drawPics

        return opus?.pics
            ?.mapNotNull { it.toDynamicMediaPic() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun MajorDrawItem.toDynamicMediaPic(): DynamicMediaPic? {
        return DynamicMediaPic(
            pic = src.toCoreImageOrNull() ?: return null,
            width = width,
            height = height,
            size = size,
            badge = tags?.firstNotNullOfOrNull { it.text.takeIfNotBlank() },
        )
    }

    private fun MajorOpusPic.toDynamicMediaPic(): DynamicMediaPic? {
        return DynamicMediaPic(
            pic = url.toCoreImageOrNull() ?: return null,
            width = width,
            height = height,
            size = size?.toFloat(),
        )
    }

    private fun DynamicMajor.buildVideo(source: BiliDynamic): DynamicMediaVideo? {
        val video = video ?: ugcSeason ?: return null
        val videoId = video.bvid.takeIfNotBlank() ?: video.aid.toString()
        return DynamicMediaVideo(
            id = videoId,
            title = video.title,
            description = video.description,
            cover = video.cover.toCoreImageOrNull() ?: return null,
            duration = video.duration,
            badge = video.badge.text.takeIfNotBlank() ?: source.originType.text,
            stats = DynamicMediaVideoStats(
                play = video.stats.play,
                danmaku = video.stats.danmaku,
                like = source.modules.stat?.like.toDisplayText(),
            ),
            link = video.jumpUrl.toNormalizedUrlOrNull() ?: video.defaultLink(),
        )
    }

    private fun MajorVideo.defaultLink(): String {
        return bvid.takeIfNotBlank()?.let { "$BILIBILI_HOME/video/$it" }
            ?: "$BILIBILI_HOME/video/av$aid"
    }

    private fun DynamicMajor.buildLargeCard(source: BiliDynamic): DynamicMediaCard? {
        val fallbackBadge = source.originType.text
        val fallbackLink = dynamicLink(source.id.toString())
        return article?.toDynamicMediaCard(fallbackBadge)
            ?: live?.toDynamicMediaCard(fallbackBadge)
            ?: liveRcmd?.toDynamicMediaCard(fallbackBadge)
            ?: pgc?.toDynamicMediaCard(fallbackBadge)
            ?: mediaList?.toDynamicMediaCard(fallbackBadge)
            ?: blocked?.toDynamicMediaCard(fallbackBadge, fallbackLink)
    }

    private fun DynamicMajor.buildSmallCard(source: BiliDynamic): DynamicMediaCard? {
        return common?.toDynamicMediaCard(source.originType.text)
    }

    private fun DynamicMajor.buildMiniCard(source: BiliDynamic): DynamicMediaCard? {
        return music?.toDynamicMediaCard(source.originType.text)
    }

    private fun MajorArticle.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = id.toString(),
            type = "article",
            title = title,
            description = description.takeIfNotBlank() ?: label,
            badge = fallbackBadge,
            cover = covers.firstNotNullOfOrNull { it.toCoreImageOrNull() } ?: return null,
            info = label.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/read/cv$id",
        )
    }

    private fun MajorLive.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = roomId.toString(),
            type = "live",
            title = title,
            description = listOf(descFirst, descSecond).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            info = status.text,
            link = jumpUrl.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/$roomId",
        )
    }

    private fun MajorLiveRcmd.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        val live = runCatching { liveInfo.livePlayInfo }.getOrNull() ?: return null
        return DynamicMediaCard(
            id = live.roomId.toString(),
            type = "live_rcmd",
            title = live.title,
            description = listOf(
                live.parentAreaName,
                live.areaName,
                live.watchedShow.textLarge,
            ).joinNonBlank(separator = " / "),
            badge = live.status.text.takeIfNotBlank() ?: fallbackBadge,
            cover = live.cover.toCoreImageOrNull() ?: return null,
            info = live.online.takeIf { it > 0 }?.toString(),
            link = live.link.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/${live.roomId}",
        )
    }

    private fun MajorPgc.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        val statsText = listOf(stats.play, stats.danmaku).joinNonBlank(separator = " / ")
        return DynamicMediaCard(
            id = epid.takeIf { it > 0 }?.toString() ?: seasonId.toString(),
            type = "pgc:$subType",
            title = title,
            description = statsText,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            info = statsText.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/bangumi/play/ep$epid",
        )
    }

    private fun MajorMediaList.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = id.toString(),
            type = "media_list",
            title = title,
            description = subTitle,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorBlocked.toDynamicMediaCard(fallbackBadge: String, fallbackLink: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = blockedType.toString(),
            type = "blocked",
            title = hintMessage.takeIfNotBlank() ?: fallbackBadge,
            description = hintMessage,
            badge = fallbackBadge,
            cover = bgImg.imgDay.toCoreImageOrNull() ?: bgImg.imgDark.toCoreImageOrNull() ?: return null,
            link = fallbackLink,
        )
    }

    private fun MajorCommon.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = id.takeIfNotBlank() ?: sketchId,
            type = "common:$bizType",
            title = title,
            description = listOf(desc, label).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorMusic.toDynamicMediaCard(fallbackBadge: String): DynamicMediaCard? {
        return DynamicMediaCard(
            id = id.toString(),
            type = "music",
            title = title,
            description = label,
            badge = fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun BiliDynamicAdditional.buildSmallCard(): DynamicMediaCard? {
        return common?.toDynamicMediaCard()
            ?: ugc?.toDynamicMediaCard()
            ?: goods?.toDynamicMediaCard()
    }

    private fun AdditionalCommon.toDynamicMediaCard(): DynamicMediaCard? {
        return DynamicMediaCard(
            id = idStr,
            type = "additional_common:$subType",
            title = title,
            description = listOf(desc1, desc2).joinNonBlank(separator = "\n"),
            badge = headText.takeIfNotBlank() ?: subType,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun AdditionalUgc.toDynamicMediaCard(): DynamicMediaCard? {
        return DynamicMediaCard(
            id = idStr,
            type = "additional_ugc",
            title = title,
            description = listOf(descSecond, duration).joinNonBlank(separator = " / "),
            badge = headText,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun AdditionalGoods.toDynamicMediaCard(): DynamicMediaCard? {
        val item = items.firstOrNull() ?: return null
        return DynamicMediaCard(
            id = item.id,
            type = "additional_goods",
            title = item.name,
            description = listOf(item.brief, item.price).joinNonBlank(separator = "\n"),
            badge = headText,
            cover = item.cover.toCoreImageOrNull() ?: return null,
            link = item.jumpUrl.toNormalizedUrlOrNull() ?: jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun buildAdditional(source: BiliDynamic): DynamicAdditional? {
        val additional = source.modules.dynamic.additional
        val card = additional?.toAdditionalCard()
        val vote = additional?.vote?.voteId
            ?.takeIf { it > 0 }
            ?.toString()
            ?.let(::DynamicAdditionalVote)
        val tags = source.modules.dynamic.topic?.id
            ?.takeIf { it > 0 }
            ?.toString()
            ?.let(::DynamicAdditionalTags)

        return DynamicAdditional(
            card = card,
            vote = vote,
            tags = tags,
        ).takeIf { it.card != null || it.vote != null || it.tags != null }
    }

    private fun BiliDynamicAdditional.toAdditionalCard(): DynamicAdditionalCard? {
        return listOfNotNull(
            common?.idStr,
            reserve?.rid?.toString(),
            ugc?.idStr,
            goods?.items?.firstOrNull()?.id,
            lottery?.rid?.toString(),
        ).firstNotNullOfOrNull { it.takeIfNotBlank() }?.let(::DynamicAdditionalCard)
    }

    private fun buildStats(stats: ModuleStats?): DynamicStats? {
        if (stats == null) return null
        return DynamicStats(
            like = stats.like.toDisplayText(),
            comment = stats.comment.toDisplayText(),
            forward = stats.forward.toDisplayText(),
        )
    }

    private fun ModuleStats.Stats?.toDisplayText(): String {
        if (this == null) return ""
        return text.takeIfNotBlank() ?: count.toString()
    }

    private fun BiliDynamic.timestampSeconds(): Long {
        return modules.author.pubTs.takeIf { it > 0 } ?: time
    }

    private fun BiliLazyImage.toCoreImageOrNull(): LazyImage? {
        return url.toNormalizedUrlOrNull()?.let(::LazyImage)
    }

    private fun String?.toNormalizedUrlOrNull(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$BILIBILI_HOME$value"
            else -> value
        }
    }

    private fun String?.takeIfNotBlank(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    private fun List<String?>.joinNonBlank(separator: String): String {
        return mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(separator = separator)
    }

    private fun dynamicLink(dynamicId: String): String {
        return "$BILIBILI_DYNAMIC_HOME/$dynamicId"
    }

    private companion object {
        private const val MAX_ORIGIN_DEPTH = 4
        private const val AUTHOR_TYPE_NORMAL = "AUTHOR_TYPE_NORMAL"
        private const val BILIBILI_HOME = "https://www.bilibili.com"
        private const val BILIBILI_DYNAMIC_HOME = "https://t.bilibili.com"
        private const val COVER_RATIO = 16f / 9f
        private const val BANNER_RATIO = 10f / 3f
        private const val SQUARE_RATIO = 1f

        private val BILIBILI_PLATFORM: PlatformDescriptor = PlatformDescriptor(
            id = "bilibili",
            name = "Bilibili",
            homepage = BILIBILI_HOME,
            iconUri = "$BILIBILI_HOME/favicon.ico",
            kind = PlatformKind.PUBLISHER,
        )
    }
}
