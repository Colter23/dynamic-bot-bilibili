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
import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicAttachment
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformCapability
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PollAttachment
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SourceUpdateReference
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.data.VideoAttachment

internal class BilibiliDynamicMapper {
    fun map(source: BiliDynamic, fallbackPublisher: Publisher): SourceUpdate? {
        return map(source, fallbackPublisher, depth = 0)
    }

    private fun map(source: BiliDynamic, fallbackPublisher: Publisher, depth: Int): SourceUpdate? {
        if (source.id <= 0) return null

        val dynamicId = source.id.toString()
        val publisher = buildPublisher(source.modules.author, fallbackPublisher)
        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = dynamicId,
            ),
            publisher = publisher,
            occurredAtEpochSeconds = source.timestampSeconds(),
            observedAtEpochSeconds = System.currentTimeMillis() / 1000,
            link = dynamicLink(dynamicId),
            payload = DynamicPayload(
                labels = buildLabels(source),
                title = buildTitle(source),
                content = buildContent(source),
                attachments = buildAttachments(source),
                metrics = buildMetrics(source.modules.stat),
                references = buildReferences(source, fallbackPublisher, depth),
            ),
        )
    }

    private fun buildPublisher(author: ModuleAuthor, fallbackPublisher: Publisher): PublisherInfo {
        val fallback = fallbackPublisher.toInfo()
        val key = PublisherKey.of(
            platformId = BILIBILI_PLATFORM.id.value,
            kind = if (author.type == AUTHOR_TYPE_NORMAL) PublisherKind.USER else PublisherKind.OTHER,
            externalId = author.mid.takeIf { it > 0 }?.toString() ?: fallback.externalId,
        )
        return fallback.copy(
            key = key,
            name = author.name.takeIfNotBlank() ?: fallbackPublisher.name,
            official = author.official?.toOfficialBadgeResource()
                ?: if (author.official == null) fallbackPublisher.official else null,
            avatar = author.face.toCoreImageOrNull(MediaKind.AVATAR) ?: fallbackPublisher.avatar,
            pendant = author.pendant?.image?.toCoreImageOrNull(MediaKind.AVATAR) ?: fallbackPublisher.pendant,
        )
    }

    private fun buildLabels(source: BiliDynamic): List<DynamicLabel> {
        return listOfNotNull(
            source.modules.tag?.text.toLabel(DynamicLabelKind.TAG, "module.tag"),
            source.modules.author.pubAction.toLabel(DynamicLabelKind.NOTICE, "author.pubAction"),
            source.modules.dispute?.title.toLabel(DynamicLabelKind.WARNING, "module.dispute"),
            source.modules.fold?.statement.toLabel(DynamicLabelKind.WARNING, "module.fold"),
            source.modules.dynamic.major?.none?.tips.toLabel(DynamicLabelKind.WARNING, "major.none"),
            source.modules.dynamic.topic?.name.toLabel(DynamicLabelKind.TAG, "dynamic.topic"),
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
        val fallbackText = text.takeIfNotBlank()
        val resolvedNodes = nodes.ifEmpty {
            fallbackText?.let { listOf(DynamicContentNodeText(it)) }.orEmpty()
        }
        return resolvedNodes.takeIf { it.isNotEmpty() }?.let { DynamicContent(nodes = it) }
    }

    private fun RichTextNode.toDynamicContentNode(): DynamicContentNode? {
        val displayText = text.takeIfNotBlank() ?: origText.takeIfNotBlank() ?: return null
        return when (type) {
            RichTextType.TEXT -> DynamicContentNodeText(displayText)
            RichTextType.EMOJI -> emoji?.iconUrl?.toCoreImageOrNull(MediaKind.EMOJI)
                ?.let { DynamicContentNodeEmoji(text = displayText, image = it) }
                ?: DynamicContentNodeText(displayText)
            RichTextType.AT -> DynamicContentNodeMention(
                text = displayText,
                publisherKey = rid?.let { PublisherKey.of(BILIBILI_PLATFORM.id.value, PublisherKind.USER, it) },
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
                ?.let { add(ImageAttachment(images = it)) }
            major?.buildVideo(source)?.let(::add)
            major?.buildLargeCard(source)?.let(::add)
            (major?.buildSmallCard(source) ?: source.modules.dynamic.additional?.buildSmallCard())?.let(::add)
            major?.buildMiniCard(source)?.let(::add)
            source.modules.dynamic.additional?.buildPoll()?.let(::add)
        }
    }

    private fun DynamicMajor.buildPics(): List<ImageItem>? {
        val drawPics = draw?.images
            ?.mapNotNull { it.toImageItem() }
            ?.takeIf { it.isNotEmpty() }
        if (drawPics != null) return drawPics

        return opus?.pics
            ?.mapNotNull { it.toImageItem() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun MajorDrawItem.toImageItem(): ImageItem? {
        return ImageItem(
            image = src.toCoreImageOrNull(MediaKind.IMAGE) ?: return null,
            width = width,
            height = height,
            sizeBytes = size.toLong(),
            badge = tags?.firstNotNullOfOrNull { it.text.takeIfNotBlank() },
        )
    }

    private fun MajorOpusPic.toImageItem(): ImageItem? {
        return ImageItem(
            image = url.toCoreImageOrNull(MediaKind.IMAGE) ?: return null,
            width = width,
            height = height,
            sizeBytes = size?.toLong(),
        )
    }

    private fun DynamicMajor.buildVideo(source: BiliDynamic): VideoAttachment? {
        val video = video ?: ugcSeason ?: return null
        val videoId = video.bvid.takeIfNotBlank() ?: video.aid.toString()
        return VideoAttachment(
            id = videoId,
            title = video.title,
            description = video.description,
            cover = video.cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
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

    private fun DynamicMajor.buildLargeCard(source: BiliDynamic): CardAttachment? {
        val fallbackBadge = source.originType.text
        val fallbackLink = dynamicLink(source.id.toString())
        return article?.toCardAttachment(fallbackBadge)
            ?: live?.toCardAttachment(fallbackBadge)
            ?: liveRcmd?.toCardAttachment(fallbackBadge)
            ?: pgc?.toCardAttachment(fallbackBadge)
            ?: mediaList?.toCardAttachment(fallbackBadge)
            ?: blocked?.toCardAttachment(fallbackBadge, fallbackLink)
    }

    private fun DynamicMajor.buildSmallCard(source: BiliDynamic): CardAttachment? {
        return common?.toCardAttachment(source.originType.text)
    }

    private fun DynamicMajor.buildMiniCard(source: BiliDynamic): CardAttachment? {
        return music?.toCardAttachment(source.originType.text)
    }

    private fun MajorArticle.toCardAttachment(fallbackBadge: String): CardAttachment? {
        return CardAttachment(
            id = id.toString(),
            cardKind = "article",
            title = title,
            description = description.takeIfNotBlank() ?: label,
            badge = fallbackBadge,
            cover = covers.firstNotNullOfOrNull { it.toCoreImageOrNull(MediaKind.COVER) } ?: return null,
            info = label.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/read/cv$id",
        )
    }

    private fun MajorLive.toCardAttachment(fallbackBadge: String): CardAttachment? {
        return CardAttachment(
            id = roomId.toString(),
            cardKind = "live",
            title = title,
            description = listOf(descFirst, descSecond).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            info = status.text,
            link = jumpUrl.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/$roomId",
        )
    }

    private fun MajorLiveRcmd.toCardAttachment(fallbackBadge: String): CardAttachment? {
        val live = runCatching { liveInfo.livePlayInfo }.getOrNull() ?: return null
        return CardAttachment(
            id = live.roomId.toString(),
            cardKind = "live_rcmd",
            title = live.title,
            description = listOf(
                live.parentAreaName,
                live.areaName,
                live.watchedShow.textLarge,
            ).joinNonBlank(separator = " / "),
            badge = live.status.text.takeIfNotBlank() ?: fallbackBadge,
            cover = live.cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            info = live.online.takeIf { it > 0 }?.toString(),
            link = live.link.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/${live.roomId}",
        )
    }

    private fun MajorPgc.toCardAttachment(fallbackBadge: String): CardAttachment? {
        val statsText = listOf(stats.play, stats.danmaku).joinNonBlank(separator = " / ")
        return CardAttachment(
            id = epid.takeIf { it > 0 }?.toString() ?: seasonId.toString(),
            cardKind = "pgc:$subType",
            title = title,
            description = statsText,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            info = statsText.takeIfNotBlank(),
            link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/bangumi/play/ep$epid",
        )
    }

    private fun MajorMediaList.toCardAttachment(fallbackBadge: String): CardAttachment? {
        return CardAttachment(
            id = id.toString(),
            cardKind = "media_list",
            title = title,
            description = subTitle,
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorBlocked.toCardAttachment(fallbackBadge: String, fallbackLink: String): CardAttachment? {
        return CardAttachment(
            id = blockedType.toString(),
            cardKind = "blocked",
            title = hintMessage.takeIfNotBlank() ?: fallbackBadge,
            description = hintMessage,
            badge = fallbackBadge,
            cover = bgImg.imgDay.toCoreImageOrNull(MediaKind.COVER)
                ?: bgImg.imgDark.toCoreImageOrNull(MediaKind.COVER)
                ?: return null,
            link = fallbackLink,
        )
    }

    private fun MajorCommon.toCardAttachment(fallbackBadge: String): CardAttachment? {
        return CardAttachment(
            id = id.takeIfNotBlank() ?: sketchId,
            cardKind = "common:$bizType",
            title = title,
            description = listOf(desc, label).joinNonBlank(separator = "\n"),
            badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun MajorMusic.toCardAttachment(fallbackBadge: String): CardAttachment? {
        return CardAttachment(
            id = id.toString(),
            cardKind = "music",
            title = title,
            description = label,
            badge = fallbackBadge,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun BiliDynamicAdditional.buildSmallCard(): CardAttachment? {
        return common?.toCardAttachment()
            ?: ugc?.toCardAttachment()
            ?: goods?.toCardAttachment()
    }

    private fun AdditionalCommon.toCardAttachment(): CardAttachment? {
        return CardAttachment(
            id = idStr,
            cardKind = "additional_common:$subType",
            title = title,
            description = listOf(desc1, desc2).joinNonBlank(separator = "\n"),
            badge = headText.takeIfNotBlank() ?: subType,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun AdditionalUgc.toCardAttachment(): CardAttachment? {
        return CardAttachment(
            id = idStr,
            cardKind = "additional_ugc",
            title = title,
            description = listOf(descSecond, duration).joinNonBlank(separator = " / "),
            badge = headText,
            cover = cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun AdditionalGoods.toCardAttachment(): CardAttachment? {
        val item = items.firstOrNull() ?: return null
        return CardAttachment(
            id = item.id,
            cardKind = "additional_goods",
            title = item.name,
            description = listOf(item.brief, item.price).joinNonBlank(separator = "\n"),
            badge = headText,
            cover = item.cover.toCoreImageOrNull(MediaKind.COVER) ?: return null,
            link = item.jumpUrl.toNormalizedUrlOrNull() ?: jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
        )
    }

    private fun BiliDynamicAdditional.buildPoll(): PollAttachment? {
        val voteId = vote?.voteId
            ?.takeIf { it > 0 }
            ?.toString()
            ?: return null
        return PollAttachment(
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
    ): List<SourceUpdateReference> {
        if (depth >= MAX_ORIGIN_DEPTH) return emptyList()
        return source.origin
            ?.let { map(it, fallbackPublisher, depth + 1) }
            ?.let { update ->
                listOf(
                    SourceUpdateReference(
                        kind = DynamicReferenceKind.ORIGIN,
                        key = update.key,
                        link = update.link,
                        embedded = update,
                    )
                )
            }
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

    private fun BiliLazyImage.toCoreImageOrNull(kind: MediaKind = MediaKind.IMAGE): MediaRef? {
        return url.toNormalizedUrlOrNull()?.let { MediaRef(uri = it, kind = kind) }
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

        private val BILIBILI_PLATFORM: PlatformDescriptor = PlatformDescriptor.of(
            id = "bilibili",
            displayName = "Bilibili",
            homepageUri = BILIBILI_HOME,
            iconUri = "$BILIBILI_HOME/favicon.ico",
            capabilities = setOf(PlatformCapability.PUBLISHER_SOURCE, PlatformCapability.LINK_RESOLVER),
        )
    }
}
