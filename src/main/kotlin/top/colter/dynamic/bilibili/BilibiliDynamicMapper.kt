package top.colter.dynamic.bilibili

import top.colter.bilibili.data.ImageUrl as BiliImageUrl
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.additional.AdditionalCommon
import top.colter.bilibili.data.dynamic.additional.AdditionalGoods
import top.colter.bilibili.data.dynamic.additional.AdditionalLottery
import top.colter.bilibili.data.dynamic.additional.AdditionalReserve
import top.colter.bilibili.data.dynamic.additional.AdditionalUgc
import top.colter.bilibili.data.dynamic.content.DynamicAdditional as BiliDynamicAdditional
import top.colter.bilibili.data.dynamic.content.DynamicDesc
import top.colter.bilibili.data.dynamic.content.DynamicMajor
import top.colter.bilibili.data.dynamic.content.RichTextNode
import top.colter.bilibili.data.dynamic.general.Button
import top.colter.bilibili.data.dynamic.general.Desc
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
import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentIcon
import top.colter.dynamic.core.data.DynamicContentNode
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicContentTagType
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformCapability
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.PollStatus
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys

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
                blocks = buildBlocks(source, fallbackPublisher, depth),
                metrics = buildMetrics(source.modules.stat),
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
            avatarBadgeKey = author.official?.toAvatarBadgeKey()
                ?: if (author.official == null) fallbackPublisher.avatarBadgeKey else null,
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
        )
    }

    private fun buildTitle(source: BiliDynamic): String? {
        return source.modules.dynamic.major?.opus?.title.takeIfNotBlank()
    }

    private fun buildBlocks(source: BiliDynamic, fallbackPublisher: Publisher, depth: Int): List<DynamicBlock> {
        val major = source.modules.dynamic.major
        return buildList {
            buildContent(source)?.let {
                add(
                    TextBlock(
                        content = it,
                        link = major?.opusLink(),
                        sourceKind = major?.opus?.let { "bilibili.major.opus.summary" },
                    )
                )
            }
            major?.buildPics()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(ImageGridBlock(images = it, link = major.opusLink(), sourceKind = major.imageSourceKind())) }
            major?.buildMediaCards(source, depth)?.let(::addAll)
            buildRepost(source, fallbackPublisher, depth)?.let(::add)
            source.modules.dynamic.additional?.buildAdditionalBlocks()?.let(::addAll)
        }
    }

    private fun buildContent(source: BiliDynamic): DynamicContent? {
        val desc = source.modules.dynamic.desc ?: source.modules.dynamic.major?.opus?.summary
        val fallbackText = source.modules.dynamic.major?.blocked?.hintMessage.takeIfNotBlank()
        val descNodes = desc?.toDynamicContentNodes()
            ?: fallbackText?.let { listOf(DynamicContentNodeText(it)) }
            ?: emptyList()
        val topicNode = source.modules.dynamic.topic?.let { topic ->
            val topicText = topic.name.takeIfNotBlank() ?: return@let null
            DynamicContentNodeTag(
                text = topicText.ensureTopicText(),
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.TOPIC, "话题"),
                tagType = DynamicContentTagType.TOPIC,
                externalId = topic.id.takeIf { it > 0 }?.toString(),
                url = topic.jumpUrl.toNormalizedUrlOrNull(),
            )
        }
        val resolvedDescNodes = if (topicNode == null) {
            descNodes
        } else {
            descNodes.filterNot { node ->
                node is DynamicContentNodeTag && node.text.trim() == topicNode.text
            }
        }
        val nodes = buildList {
            topicNode?.let { add(it) }
            if (topicNode != null && resolvedDescNodes.isNotEmpty()) add(DynamicContentNodeText("\n"))
            addAll(resolvedDescNodes)
        }
        return nodes.takeIf { it.isNotEmpty() }?.let { DynamicContent(it) }
    }

    private fun DynamicDesc.toDynamicContentNodes(): List<DynamicContentNode> {
        val nodes = richTextNodes.mapNotNull { it.toDynamicContentNode() }
        val fallbackText = text.takeIfNotBlank()
        return nodes.ifEmpty {
            fallbackText?.let { listOf(DynamicContentNodeText(it)) }.orEmpty()
        }
    }

    private fun RichTextNode.toDynamicContentNode(): DynamicContentNode? {
        val displayText = when (type) {
            RichTextType.TEXT -> text.takeIfNotEmpty() ?: origText.takeIfNotEmpty() ?: return null
            else -> text.takeIfNotBlank() ?: origText.takeIfNotBlank() ?: return null
        }
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
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.VIDEO, "视频"),
                url = jumpUrl.toNormalizedUrlOrNull() ?: displayText
                    .takeIf { it.startsWith("BV", ignoreCase = true) }
                    ?.let { "$BILIBILI_HOME/video/$it" },
            )
            RichTextType.TOPIC -> DynamicContentNodeTag(
                text = displayText,
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.TOPIC, "话题"),
                tagType = DynamicContentTagType.TOPIC,
                externalId = rid,
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
            RichTextType.WEB -> DynamicContentNodeLink(
                text = displayText,
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.LINK, "链接"),
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
            RichTextType.VOTE -> DynamicContentNodeLink(
                text = displayText,
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.VOTE, "投票"),
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
            RichTextType.GOODS -> DynamicContentNodeLink(
                text = displayText,
                icon = contentIcon(PlatformDrawAssetKeys.ContentIcon.GOODS, "商品"),
                url = jumpUrl.toNormalizedUrlOrNull(),
            )
            RichTextType.UNKNOWN -> jumpUrl.toNormalizedUrlOrNull()
                ?.let {
                    val icon = unknownContentIcon()
                    DynamicContentNodeLink(
                        text = displayText,
                        icon = icon,
                        url = it,
                    )
                }
                ?: DynamicContentNodeText(displayText)
        }
    }

    private fun DynamicMajor.buildPics(): List<ImageItem>? {
        val drawPics = draw?.images
            ?.mapNotNull { it.toImageItem() }
            ?.takeIf { it.isNotEmpty() }
        if (drawPics != null) return drawPics

        val opusPics = opus?.pics
            ?.mapNotNull { it.toImageItem() }
            ?.takeIf { it.isNotEmpty() }
        if (opusPics != null) return opusPics

        return blocked?.toImageItem()?.let(::listOf)
    }

    private fun DynamicMajor.imageSourceKind(): String? {
        return when {
            draw != null -> "bilibili.major.draw"
            opus?.pics?.isNotEmpty() == true -> "bilibili.major.opus"
            blocked != null -> "bilibili.major.blocked"
            else -> null
        }
    }

    private fun DynamicMajor.opusLink(): String? {
        return opus?.jumpUrl.toNormalizedUrlOrNull()
    }

    private fun MajorDrawItem.toImageItem(): ImageItem? {
        val image = src.toCoreImageOrNull(MediaKind.IMAGE) ?: return null
        return ImageItem(
            image = image,
            width = width,
            height = height,
            sizeBytes = size.toLong(),
            badge = tags?.firstNotNullOfOrNull { it.text.takeIfNotBlank() }
                ?: image.inferImageBadge(width, height),
        )
    }

    private fun MajorOpusPic.toImageItem(): ImageItem? {
        val image = url.toCoreImageOrNull(MediaKind.IMAGE) ?: return null
        return ImageItem(
            image = image,
            width = width,
            height = height,
            sizeBytes = size?.toLong(),
            badge = image.inferImageBadge(width, height),
        )
    }

    private fun MajorBlocked.toImageItem(): ImageItem? {
        val image = bgImg.imgDay.toCoreImageOrNull(MediaKind.IMAGE)
            ?: bgImg.imgDark.toCoreImageOrNull(MediaKind.IMAGE)
            ?: return null
        return ImageItem(
            image = image,
            badge = hintMessage.takeIfNotBlank(),
            alt = hintMessage.takeIfNotBlank(),
        )
    }

    private fun DynamicMajor.buildMediaCards(source: BiliDynamic, depth: Int): List<DynamicBlock> {
        val fallbackBadge = source.originType.text
        return buildList {
            video?.toMediaCardBlock(
                source = source,
                sourceKind = "bilibili.major.video",
                style = if (depth > 0) MediaCardStyle.SMALL else MediaCardStyle.LARGE,
                fallbackBadge = fallbackBadge,
            )?.let(::add)
            article?.toMediaCardBlock(fallbackBadge)?.let(::add)
            music?.toMediaCardBlock(fallbackBadge)?.let(::add)
            common?.toMediaCardBlock(fallbackBadge)?.let(::add)
            live?.toMediaCardBlock(fallbackBadge)?.let(::add)
            liveRcmd?.toMediaCardBlock(fallbackBadge)?.let(::add)
            pgc?.toMediaCardBlock(fallbackBadge)?.let(::add)
            ugcSeason?.toMediaCardBlock(
                source = source,
                sourceKind = "bilibili.major.ugcSeason",
                style = MediaCardStyle.SMALL,
                fallbackBadge = fallbackBadge,
            )?.let(::add)
            mediaList?.toMediaCardBlock(fallbackBadge)?.let(::add)
        }
    }

    private fun MajorVideo.toMediaCardBlock(
        source: BiliDynamic,
        sourceKind: String,
        style: MediaCardStyle,
        fallbackBadge: String,
    ): MediaCardBlock? {
        val videoId = bvid.takeIfNotBlank() ?: aid.toString()
        return MediaCardBlock(
            style = style,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.VIDEO,
                sourceKind = sourceKind,
                id = videoId,
                title = title,
                description = description,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                durationSeconds = duration.toDurationSeconds(),
                badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
                metrics = listOfNotNull(
                    stats.play.toDisplayMetric("play"),
                    stats.danmaku.toDisplayMetric("danmaku"),
                    source.modules.stat?.like.toDisplayMetric("like"),
                ),
                link = jumpUrl.toNormalizedUrlOrNull() ?: defaultLink(),
            ),
        )
    }

    private fun MajorVideo.defaultLink(): String {
        return bvid.takeIfNotBlank()?.let { "$BILIBILI_HOME/video/$it" }
            ?: "$BILIBILI_HOME/video/av$aid"
    }

    private fun MajorArticle.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.LARGE,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.ARTICLE,
                sourceKind = "bilibili.major.article",
                id = id.toString(),
                title = title,
                description = description.takeIfNotBlank() ?: label,
                badge = fallbackBadge,
                cover = covers.firstNotNullOfOrNull { it.toCoreImageOrNull(MediaKind.COVER) },
                info = label.takeIfNotBlank(),
                link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/read/cv$id",
            ),
        )
    }

    private fun MajorLive.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LIVE,
                sourceKind = "bilibili.major.live",
                id = roomId.toString(),
                title = title,
                description = listOf(descFirst, descSecond).joinNonBlank(separator = "\n"),
                badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                info = status.text,
                link = jumpUrl.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/$roomId",
            ),
        )
    }

    private fun MajorLiveRcmd.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        val live = runCatching { liveInfo.livePlayInfo }.getOrNull() ?: return null
        return MediaCardBlock(
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LIVE,
                sourceKind = "bilibili.major.liveRcmd",
                id = live.roomId.toString(),
                title = live.title,
                description = listOf(
                    live.parentAreaName,
                    live.areaName,
                    live.watchedShow.textLarge,
                ).joinNonBlank(separator = " / "),
                badge = live.status.text.takeIfNotBlank() ?: fallbackBadge,
                cover = live.cover.toCoreImageOrNull(MediaKind.COVER),
                info = live.online.takeIf { it > 0 }?.toString(),
                link = live.link.toNormalizedUrlOrNull() ?: "https://live.bilibili.com/${live.roomId}",
            ),
        )
    }

    private fun MajorPgc.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        val statsText = listOf(stats.play, stats.danmaku).joinNonBlank(separator = " / ")
        return MediaCardBlock(
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.COLLECTION,
                sourceKind = "bilibili.major.pgc:$subType",
                id = epid.takeIf { it > 0 }?.toString() ?: seasonId.toString(),
                title = title,
                description = statsText,
                badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                info = statsText.takeIfNotBlank(),
                link = jumpUrl.toNormalizedUrlOrNull() ?: "$BILIBILI_HOME/bangumi/play/ep$epid",
            ),
        )
    }

    private fun MajorMediaList.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.COLLECTION,
                sourceKind = "bilibili.major.mediaList",
                id = id.toString(),
                title = title,
                description = subTitle,
                badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun MajorCommon.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.major.common:$bizType",
                id = id.takeIfNotBlank() ?: sketchId,
                title = title,
                description = listOf(desc, label).joinNonBlank(separator = "\n"),
                badge = badge.text.takeIfNotBlank() ?: fallbackBadge,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun MajorMusic.toMediaCardBlock(fallbackBadge: String): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.MUSIC,
                sourceKind = "bilibili.major.music",
                id = id.toString(),
                title = title,
                description = label,
                badge = fallbackBadge,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                coverRatio = 1f,
                link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun BiliDynamicAdditional.buildAdditionalBlocks(): List<DynamicBlock> {
        return buildList {
            buildSmallCard()?.let(::add)
            buildPoll()?.let(::add)
        }
    }

    private fun BiliDynamicAdditional.buildSmallCard(): MediaCardBlock? {
        return common?.toMediaCardBlock()
            ?: reserve?.toMediaCardBlock()
            ?: ugc?.toMediaCardBlock()
            ?: goods?.toMediaCardBlock()
            ?: lottery?.toMediaCardBlock()
    }

    private fun AdditionalCommon.toMediaCardBlock(): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.common:$subType",
                id = idStr,
                title = title,
                description = listOf(desc1, desc2).joinNonBlank(separator = "\n"),
                badge = headText.takeIfNotBlank() ?: subType,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                coverRatio = 1f,
                link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun AdditionalReserve.toMediaCardBlock(): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.reserve:$stype",
                id = rid.toString(),
                title = title,
                description = listOf(
                    desc1.displayText(),
                    desc2.displayText(),
                    desc3?.displayText(),
                    reserveTotal.takeIf { it > 0 }?.let { "${it}人预约" },
                ).joinNonBlank(separator = "\n"),
                badge = button.displayText() ?: "预约",
                info = desc3?.displayText(),
                link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun AdditionalUgc.toMediaCardBlock(): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.VIDEO,
                sourceKind = "bilibili.additional.ugc",
                id = idStr,
                title = title,
                description = listOf(descSecond, duration).joinNonBlank(separator = " / "),
                badge = headText,
                cover = cover.toCoreImageOrNull(MediaKind.COVER),
                durationSeconds = duration.toDurationSeconds(),
                link = jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun AdditionalGoods.toMediaCardBlock(): MediaCardBlock? {
        val item = items.firstOrNull() ?: return null
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.PRODUCT,
                sourceKind = "bilibili.additional.goods",
                id = item.id,
                title = item.name,
                description = listOf(item.brief, item.price).joinNonBlank(separator = "\n"),
                badge = headText,
                cover = item.cover.toCoreImageOrNull(MediaKind.COVER),
                coverRatio = 1f,
                link = item.jumpUrl.toNormalizedUrlOrNull() ?: jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun AdditionalLottery.toMediaCardBlock(): MediaCardBlock? {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.lottery",
                id = rid.toString(),
                title = title,
                description = desc.displayText().orEmpty(),
                badge = button.displayText() ?: "充电抽奖",
                link = jumpUrl.toNormalizedUrlOrNull() ?: button.jumpUrl.toNormalizedUrlOrNull() ?: BILIBILI_HOME,
            ),
        )
    }

    private fun BiliDynamicAdditional.buildPoll(): PollBlock? {
        val vote = vote ?: return null
        val voteId = vote.voteId
            .takeIf { it > 0 }
            ?.toString()
            ?: return null
        return PollBlock(
            id = voteId,
            title = vote.desc.takeIfNotBlank() ?: "投票",
            status = when (vote.status) {
                1 -> PollStatus.OPEN
                4 -> PollStatus.CLOSED
                else -> PollStatus.UNKNOWN
            },
            sourceKind = "bilibili.additional.vote",
            role = DynamicBlockRole.ADDITIONAL,
        )
    }

    private fun buildRepost(
        source: BiliDynamic,
        fallbackPublisher: Publisher,
        depth: Int,
    ): RepostBlock? {
        if (depth >= MAX_ORIGIN_DEPTH) return null
        return source.origin
            ?.let { map(it, fallbackPublisher, depth + 1) }
            ?.let { update ->
                RepostBlock(
                    referenceKind = DynamicReferenceKind.ORIGIN,
                    key = update.key,
                    link = update.link,
                    embedded = update,
                )
            }
    }

    private fun buildMetrics(stats: ModuleStats?): List<DynamicMetric> {
        return listOfNotNull(
            stats?.like.toDisplayMetric("like"),
            stats?.comment.toDisplayMetric("comment"),
            stats?.forward.toDisplayMetric("forward"),
        )
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

    private fun BiliImageUrl.toCoreImageOrNull(kind: MediaKind = MediaKind.IMAGE): MediaRef? {
        return url.toNormalizedUrlOrNull()?.let { MediaRef(uri = it, kind = kind) }
    }

    private fun MediaRef.inferImageBadge(width: Int?, height: Int?): String? {
        return when {
            uri.isGifImageUrl() -> "动图"
            width != null && height != null && width > 0 && height > width * 2 -> "长图"
            else -> null
        }
    }

    private fun String.isGifImageUrl(): Boolean {
        val path = substringBefore('?').substringBefore('#')
        return path.endsWith(".gif", ignoreCase = true)
    }

    private fun String?.toDurationSeconds(): Long? {
        val parts = this?.split(':')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return parts.fold(0L) { total, next -> total * 60 + next }
    }

    private fun Desc.displayText(): String? {
        return text.takeIfNotBlank()
    }

    private fun Button.displayText(): String? {
        return jumpStyle?.text.takeIfNotBlank()
            ?: check?.text.takeIfNotBlank()
            ?: uncheck?.text.takeIfNotBlank()
    }

    private fun contentIcon(assetKey: String, alt: String): DynamicContentIcon {
        return DynamicContentIcon(
            platformId = BILIBILI_PLATFORM.id,
            assetKey = assetKey,
            alt = alt,
        )
    }

    private fun RichTextNode.unknownContentIcon(): DynamicContentIcon {
        return when {
            type.info.endsWith("LOTTERY") -> contentIcon(PlatformDrawAssetKeys.ContentIcon.LOTTERY, "抽奖")
            type.info.endsWith("DISPUTE") -> contentIcon(PlatformDrawAssetKeys.ContentIcon.DISPUTE, "争议")
            else -> contentIcon(PlatformDrawAssetKeys.ContentIcon.LINK, "链接")
        }
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

    private fun String?.takeIfNotEmpty(): String? {
        return this?.takeIf { it.isNotEmpty() }
    }

    private fun String.ensureTopicText(): String {
        val value = trim()
        return if (value.startsWith("#") && value.endsWith("#")) value else "#$value#"
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
            capabilities = setOf(
                PlatformCapability.PUBLISHER_SOURCE,
                PlatformCapability.LIVE_SOURCE,
                PlatformCapability.LINK_RESOLVER,
            ),
        )
    }
}
