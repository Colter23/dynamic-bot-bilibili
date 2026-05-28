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
import top.colter.dynamic.core.data.DynamicAttachment
import top.colter.dynamic.core.data.DynamicAttachmentDisplay
import top.colter.dynamic.core.data.DynamicCardAttachment
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicImageAttachment
import top.colter.dynamic.core.data.DynamicImageItem
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPollAttachment
import top.colter.dynamic.core.data.DynamicReference
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.DynamicTagAttachment
import top.colter.dynamic.core.data.DynamicVideoAttachment
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherSnapshot
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
            labels = buildLabels(source),
            title = buildTitle(source),
            content = buildContent(source),
            attachments = buildAttachments(source),
            metrics = buildMetrics(source.modules.stat),
            references = buildReferences(source, fallbackPublisher, depth),
        )
    }

    private fun buildPublisher(author: ModuleAuthor, fallbackPublisher: Publisher): PublisherSnapshot {
        val fallback = fallbackPublisher.toSnapshot()
        return fallback.copy(
            identity = fallback.identity.copy(
                platformId = BILIBILI_PLATFORM.id,
                type = if (author.type == AUTHOR_TYPE_NORMAL) PublisherType.USER else PublisherType.OTHER,
                externalId = author.mid.takeIf { it > 0 }?.toString() ?: fallback.externalId,
            ),
            name = author.name.takeIfNotBlank() ?: fallbackPublisher.name,
            official = author.official?.title.takeIfNotBlank()
                ?: author.official?.desc.takeIfNotBlank()
                ?: fallbackPublisher.official,
            face = author.face.toCoreImageOrNull() ?: fallbackPublisher.face,
            pendant = author.pendant?.image?.toCoreImageOrNull() ?: fallbackPublisher.pendant,
        )
    }

    private fun buildLabels(source: BiliDynamic): List<DynamicLabel> {
        return listOfNotNull(
            source.modules.tag?.text.toLabel(DynamicLabelKind.TAG, "module.tag"),
            source.modules.author.pubAction.toLabel(DynamicLabelKind.NOTICE, "author.pubAction"),
            source.modules.dispute?.title.toLabel(DynamicLabelKind.WARNING, "module.dispute"),
            source.modules.fold?.statement.toLabel(DynamicLabelKind.WARNING, "module.fold"),
            source.modules.dynamic.major?.none?.tips.toLabel(DynamicLabelKind.WARNING, "major.none"),
        )
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
            RichTextType.AT -> DynamicContentNodeMention(
                text = displayText,
                platformId = BILIBILI_PLATFORM.id,
                externalId = rid,
                url = jumpUrl.toNormalizedUrlOrNull() ?: rid?.let { "https://space.bilibili.com/$it" },
            )
            RichTextType.BV -> DynamicContentNodeLink(
                text = displayText,
                url = jumpUrl.toNormalizedUrlOrNull() ?: displayText
                    .takeIf { it.startsWith("BV", ignoreCase = true) }
                    ?.let { "$BILIBILI_HOME/video/$it" },
            )
            RichTextType.TOPIC -> DynamicContentNodeTag(
                text = displayText,
                tagType = DynamicContentTagType.TOPIC,
                externalId = rid,
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
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

    private fun buildAttachments(source: BiliDynamic): List<DynamicAttachment> {
        val major = source.modules.dynamic.major
        return buildList {
            major?.buildPics()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(DynamicImageAttachment(images = it)) }
            major?.buildVideo(source)?.let(::add)
            major?.buildLargeCard(source)?.let(::add)
            (major?.buildSmallCard(source) ?: source.modules.dynamic.additional?.buildSmallCard())?.let(::add)
            major?.buildMiniCard(source)?.let(::add)
            source.modules.dynamic.additional?.buildPoll()?.let(::add)
            source.modules.dynamic.topic?.let { topic ->
                topic.id
                    .takeIf { it > 0 }
                    ?.toString()
                    ?.let { id ->
                        add(
                            DynamicTagAttachment(
                                id = id,
                                text = topic.name.takeIfNotBlank() ?: id,
                                tagType = DynamicContentTagType.TOPIC,
                                link = topic.jumpUrl.toNormalizedUrlOrNull(),
                            )
                        )
                    }
            }
        }
    }

    private fun DynamicMajor.buildPics(): List<DynamicImageItem>? {
        val drawPics = draw?.images
            ?.mapNotNull { it.toDynamicImageItem() }
            ?.takeIf { it.isNotEmpty() }
        if (drawPics != null) return drawPics

        return opus?.pics
            ?.mapNotNull { it.toDynamicImageItem() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun MajorDrawItem.toDynamicImageItem(): DynamicImageItem? {
        return DynamicImageItem(
            image = src.toCoreImageOrNull() ?: return null,
            width = width,
            height = height,
            size = size,
            badge = tags?.firstNotNullOfOrNull { it.text.takeIfNotBlank() },
        )
    }

    private fun MajorOpusPic.toDynamicImageItem(): DynamicImageItem? {
        return DynamicImageItem(
            image = url.toCoreImageOrNull() ?: return null,
            width = width,
            height = height,
            size = size?.toFloat(),
        )
    }

    private fun DynamicMajor.buildVideo(source: BiliDynamic): DynamicVideoAttachment? {
        val video = video ?: ugcSeason ?: return null
        val videoId = video.bvid.takeIfNotBlank() ?: video.aid.toString()
        return DynamicVideoAttachment(
            id = videoId,
            title = video.title,
            description = video.description,
            cover = video.cover.toCoreImageOrNull() ?: return null,
            duration = video.duration,
            badge = video.badge.text.takeIfNotBlank() ?: source.originType.text,
            metrics = listOfNotNull(
                video.stats.play.toDisplayMetric("play"),
                video.stats.danmaku.toDisplayMetric("danmaku"),
                source.modules.stat?.like.toDisplayMetric("like"),
            ),
            link = video.jumpUrl.toNormalizedUrlOrNull() ?: video.defaultLink(),
        )
    }

    private fun MajorVideo.defaultLink(): String {
        return bvid.takeIfNotBlank()?.let { "$BILIBILI_HOME/video/$it" }
            ?: "$BILIBILI_HOME/video/av$aid"
    }

    private fun DynamicMajor.buildLargeCard(source: BiliDynamic): DynamicCardAttachment? {
        val fallbackBadge = source.originType.text
        val fallbackLink = dynamicLink(source.id.toString())
        return article?.toDynamicCardAttachment(fallbackBadge)
            ?: live?.toDynamicCardAttachment(fallbackBadge)
            ?: liveRcmd?.toDynamicCardAttachment(fallbackBadge)
            ?: pgc?.toDynamicCardAttachment(fallbackBadge)
            ?: mediaList?.toDynamicCardAttachment(fallbackBadge)
            ?: blocked?.toDynamicCardAttachment(fallbackBadge, fallbackLink)
    }

    private fun DynamicMajor.buildSmallCard(source: BiliDynamic): DynamicCardAttachment? {
        return common?.toDynamicCardAttachment(source.originType.text)
            ?.copy(display = DynamicAttachmentDisplay.SMALL_CARD)
    }

    private fun DynamicMajor.buildMiniCard(source: BiliDynamic): DynamicCardAttachment? {
        return music?.toDynamicCardAttachment(source.originType.text)
            ?.copy(display = DynamicAttachmentDisplay.MINI_CARD)
    }

    private fun MajorArticle.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = id.toString(),
            cardType = "article",
            title = title,
            description = description.takeIfNotBlank() ?: label,
            badge = fallbackBadge,
            cover = covers.firstNotNullOfOrNull { it.toCoreImageOrNull() } ?: return null,
            info = label.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/read/cv$id",
        )
    }

    private fun MajorLive.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = roomId.toString(),
            cardType = "live",
            title = title,
            description = listOf(descFirst, descSecond).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            info = status.text,
            link = jumpUrl.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/$roomId",
        )
    }

    private fun MajorLiveRcmd.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        val live = runCatching { liveInfo.livePlayInfo }.getOrNull() ?: return null
        return DynamicCardAttachment(
            id = live.roomId.toString(),
            cardType = "live_rcmd",
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

    private fun MajorPgc.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        val statsText = listOf(stats.play, stats.danmaku).joinNonBlank(separator = " / ")
        return DynamicCardAttachment(
            id = epid.takeIf { it > 0 }?.toString() ?: seasonId.toString(),
            cardType = "pgc:$subType",
            title = title,
            description = statsText,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            info = statsText.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/bangumi/play/ep$epid",
        )
    }

    private fun MajorMediaList.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = id.toString(),
            cardType = "media_list",
            title = title,
            description = subTitle,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorBlocked.toDynamicCardAttachment(fallbackBadge: String, fallbackLink: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = blockedType.toString(),
            cardType = "blocked",
            title = hintMessage.takeIfNotBlank() ?: fallbackBadge,
            description = hintMessage,
            badge = fallbackBadge,
            cover = bgImg.imgDay.toCoreImageOrNull() ?: bgImg.imgDark.toCoreImageOrNull() ?: return null,
            link = fallbackLink,
        )
    }

    private fun MajorCommon.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = id.takeIfNotBlank() ?: sketchId,
            cardType = "common:$bizType",
            title = title,
            description = listOf(desc, label).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorMusic.toDynamicCardAttachment(fallbackBadge: String): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = id.toString(),
            cardType = "music",
            title = title,
            description = label,
            badge = fallbackBadge,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun BiliDynamicAdditional.buildSmallCard(): DynamicCardAttachment? {
        return common?.toDynamicCardAttachment()
            ?: ugc?.toDynamicCardAttachment()
            ?: goods?.toDynamicCardAttachment()
    }

    private fun AdditionalCommon.toDynamicCardAttachment(): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = idStr,
            cardType = "additional_common:$subType",
            title = title,
            description = listOf(desc1, desc2).joinNonBlank(separator = "\n"),
            badge = headText.takeIfNotBlank() ?: subType,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            display = DynamicAttachmentDisplay.SMALL_CARD,
        )
    }

    private fun AdditionalUgc.toDynamicCardAttachment(): DynamicCardAttachment? {
        return DynamicCardAttachment(
            id = idStr,
            cardType = "additional_ugc",
            title = title,
            description = listOf(descSecond, duration).joinNonBlank(separator = " / "),
            badge = headText,
            cover = cover.toCoreImageOrNull() ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            display = DynamicAttachmentDisplay.SMALL_CARD,
        )
    }

    private fun AdditionalGoods.toDynamicCardAttachment(): DynamicCardAttachment? {
        val item = items.firstOrNull() ?: return null
        return DynamicCardAttachment(
            id = item.id,
            cardType = "additional_goods",
            title = item.name,
            description = listOf(item.brief, item.price).joinNonBlank(separator = "\n"),
            badge = headText,
            cover = item.cover.toCoreImageOrNull() ?: return null,
            link = item.jumpUrl.toNormalizedUrlOrNull() ?: jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            display = DynamicAttachmentDisplay.SMALL_CARD,
        )
    }

    private fun BiliDynamicAdditional.buildPoll(): DynamicPollAttachment? {
        val voteId = vote?.voteId
            ?.takeIf { it > 0 }
            ?.toString()
            ?: return null
        return DynamicPollAttachment(
            id = voteId,
            title = "Vote",
        )
    }

    private fun buildMetrics(stats: ModuleStats?): List<DynamicMetric> {
        return listOfNotNull(
            stats?.like.toDisplayMetric("like"),
            stats?.comment.toDisplayMetric("comment"),
            stats?.forward.toDisplayMetric("forward"),
        )
    }

    private fun buildReferences(
        source: BiliDynamic,
        fallbackPublisher: Publisher,
        depth: Int,
    ): List<DynamicReference> {
        if (depth >= MAX_ORIGIN_DEPTH) return emptyList()
        return source.origin
            ?.let { map(it, fallbackPublisher, depth + 1) }
            ?.let { listOf(DynamicReference(DynamicReferenceKind.ORIGIN, update = it)) }
            .orEmpty()
    }

    private fun ModuleStats.Stats?.toDisplayText(): String {
        if (this == null) return ""
        return text.takeIfNotBlank() ?: count.toString()
    }

    private fun ModuleStats.Stats?.toDisplayMetric(key: String): DynamicMetric? {
        if (this == null) return null
        val display = toDisplayText().takeIfNotBlank() ?: return null
        return DynamicMetric(
            key = key,
            raw = count.toLong(),
            display = display,
        )
    }

    private fun String?.toDisplayMetric(key: String): DynamicMetric? {
        val display = takeIfNotBlank() ?: return null
        return DynamicMetric(
            key = key,
            raw = display.toLongOrNull(),
            display = display,
        )
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

    private fun String?.toLabel(kind: DynamicLabelKind, sourceKey: String): DynamicLabel? {
        return takeIfNotBlank()?.let { text ->
            DynamicLabel(text = text, kind = kind, sourceKey = sourceKey)
        }
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

        private val BILIBILI_PLATFORM: PlatformDescriptor = PlatformDescriptor(
            id = "bilibili",
            name = "Bilibili",
            homepage = BILIBILI_HOME,
            iconUri = "$BILIBILI_HOME/favicon.ico",
            kind = PlatformKind.PUBLISHER,
        )
    }
}
