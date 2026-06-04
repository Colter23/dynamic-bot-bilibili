package top.colter.dynamic.bilibili

import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.core.tools.loggerFor
import java.net.URI
import kotlin.math.roundToLong

private val linkResolverLogger = loggerFor<BilibiliLinkResolver>()

internal class BilibiliLinkResolver(
    private val platformId: PlatformId,
    private val configProvider: () -> BilibiliPublisherConfig,
    private val gatewayProvider: () -> BilibiliPlatformGateway,
    private val mapper: BilibiliDynamicMapper,
    private val publisherInfoResolver: suspend (String) -> PublisherInfo?,
) {
    private val config: BilibiliPublisherConfig
        get() = configProvider()

    private val gateway: BilibiliPlatformGateway
        get() = gatewayProvider()

    suspend fun parseLink(inputUrl: String): ParsedLink? {
        val normalizedInput = inputUrl.trim().trimUrlPunctuation()
        if (normalizedInput.isBlank()) return null

        parseDirectLink(normalizedInput)?.let { return it }
        if (!isBilibiliShortUrl(normalizedInput)) return null

        val expanded = runCatching {
            gateway.expandShortUrl(
                normalizedInput,
                secondsToMillis(config.shortUrlResolveTimeoutSeconds, minimumMillis = 1),
            )
        }.onFailure {
            linkResolverLogger.warn(it) {
                "Bilibili 短链解析失败：url=$normalizedInput"
            }
        }.getOrNull() ?: return null

        return parseDirectLink(expanded)?.copy(sourceUrl = normalizedInput)
    }

    suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        if (parsedLink.platformId != platformId) {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "不支持的平台：${parsedLink.platformId.value}",
            )
        }

        if (parsedLink.kind != LinkKinds.DYNAMIC) {
            return when (parsedLink.kind) {
                LinkKinds.VIDEO -> resolveVideoPreview(parsedLink)
                LinkKinds.LIVE -> resolveLivePreview(parsedLink)
                LinkKinds.USER -> resolveUserPreview(parsedLink)
                else -> LinkResolution.Failed(parsedLink, "不支持的 Bilibili 链接类型：${parsedLink.kind}")
            }
        }

        val source = runCatching { gateway.fetchDynamicDetail(parsedLink.targetId) }
            .getOrElse { error ->
                return LinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "获取 Bilibili 动态详情失败",
                    cause = error,
                )
            }
            ?: return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "未找到 Bilibili 动态：${parsedLink.targetId}",
            )

        val dynamic = mapper.map(source, fallbackPublisher())
            ?: return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "Bilibili 动态映射失败：${parsedLink.targetId}",
            )

        return LinkResolution.Dynamic(parsedLink, dynamic)
    }

    private suspend fun resolveVideoPreview(parsedLink: ParsedLink): LinkResolution {
        val snapshot = runCatching { gateway.fetchVideoSnapshot(parsedLink.targetId) }
            .getOrElse { error ->
                return LinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "获取 Bilibili 视频详情失败",
                    cause = error,
                )
            }
            ?: return LinkResolution.Failed(parsedLink, "未找到 Bilibili 视频：${parsedLink.targetId}")

        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = LinkKinds.VIDEO,
                id = snapshot.bvid.ifBlank { "av${snapshot.aid}" },
                url = videoLink(snapshot.bvid.ifBlank { "av${snapshot.aid}" }),
                title = snapshot.title,
                description = snapshot.description,
                badge = "视频",
                cover = snapshot.coverUrl?.let { MediaRef(it, MediaKind.COVER) },
                publisher = snapshot.toPublisherInfo(),
                metrics = listOfNotNull(
                    snapshot.play.toDisplayMetric("play"),
                    snapshot.danmaku.toDisplayMetric("danmaku"),
                    snapshot.like.toDisplayMetric("like"),
                ),
            ),
        )
    }

    private suspend fun resolveLivePreview(parsedLink: ParsedLink): LinkResolution {
        val snapshot = runCatching { gateway.fetchLiveRoomSnapshot(parsedLink.targetId) }
            .getOrElse { error ->
                return LinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "获取 Bilibili 直播间详情失败",
                    cause = error,
                )
            }
            ?: return LinkResolution.Failed(parsedLink, "未找到 Bilibili 直播间：${parsedLink.targetId}")

        val publisher = snapshot.userId.takeIf { it.isNotBlank() }?.let { publisherInfoResolver(it) }
        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = LinkKinds.LIVE,
                id = snapshot.roomId,
                url = liveLink(snapshot.roomId),
                title = snapshot.title.ifBlank { "Bilibili 直播间 ${snapshot.roomId}" },
                description = snapshot.area.orEmpty(),
                badge = snapshot.status.label(),
                cover = snapshot.coverUrl?.let { MediaRef(it, MediaKind.COVER) },
                publisher = publisher,
                metrics = listOfNotNull(
                    snapshot.online.toDisplayMetric("online"),
                    snapshot.attention.toDisplayMetric("follow"),
                ),
            ),
        )
    }

    private suspend fun resolveUserPreview(parsedLink: ParsedLink): LinkResolution {
        val publisher = runCatching { publisherInfoResolver(parsedLink.targetId) }
            .getOrElse { error ->
                return LinkResolution.Failed(
                    parsedLink = parsedLink,
                    reason = error.message ?: "获取 Bilibili 用户信息失败",
                    cause = error,
                )
            }
            ?: return LinkResolution.Failed(parsedLink, "未找到 Bilibili 用户：${parsedLink.targetId}")

        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = LinkKinds.USER,
                id = publisher.externalId,
                url = userLink(publisher.externalId),
                title = publisher.name,
                description = "Bilibili 用户 ${publisher.externalId}",
                badge = "用户",
                cover = publisher.banner,
                publisher = publisher,
            ),
        )
    }

    private fun parseDirectLink(inputUrl: String): ParsedLink? {
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        val pathSegments = uri.path
            ?.split("/")
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return when (host) {
            "t.bilibili.com" -> pathSegments.firstOrNull()
                ?.takeIf { it.all(Char::isDigit) }
                ?.let { dynamicParsedLink(it, inputUrl) }
            "www.bilibili.com",
            "m.bilibili.com" -> when (pathSegments.firstOrNull()) {
                "opus",
                "dynamic" -> pathSegments.getOrNull(1)
                    ?.takeIf { it.all(Char::isDigit) }
                    ?.let { dynamicParsedLink(it, inputUrl) }
                "video" -> pathSegments.getOrNull(1)
                    ?.takeIf { it.isBilibiliVideoId() }
                    ?.let { videoParsedLink(it, inputUrl) }
                "read" -> null
                else -> null
            }
            "live.bilibili.com" -> pathSegments.firstOrNull()
                ?.takeIf { it.all(Char::isDigit) }
                ?.let { liveParsedLink(it, inputUrl) }
            "space.bilibili.com" -> pathSegments.firstOrNull()
                ?.takeIf { it.all(Char::isDigit) }
                ?.let { userParsedLink(it, inputUrl) }
            else -> null
        }
    }

    private fun isBilibiliShortUrl(inputUrl: String): Boolean {
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        return host == "b23.tv" || host.endsWith(".b23.tv")
    }

    private fun String.trimUrlPunctuation(): String {
        return trim().trimEnd(
            '.',
            ',',
            ';',
            ':',
            '!',
            '?',
            ')',
            ']',
            '}',
            '>',
            '。',
            '，',
            '；',
            '：',
            '！',
            '？',
            '）',
            '】',
            '》',
        )
    }

    private fun dynamicLink(dynamicId: String): String {
        return "$BILIBILI_DYNAMIC_HOME/$dynamicId"
    }

    private fun videoLink(videoId: String): String {
        return "$BILIBILI_HOME/video/$videoId"
    }

    private fun liveLink(roomId: String): String {
        return if (roomId.isBlank()) BILIBILI_LIVE_HOME else "$BILIBILI_LIVE_HOME/$roomId"
    }

    private fun userLink(userId: String): String {
        return "$BILIBILI_SPACE_HOME/$userId"
    }

    private fun dynamicParsedLink(dynamicId: String, sourceUrl: String): ParsedLink {
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.DYNAMIC,
            targetId = dynamicId,
            normalizedUrl = dynamicLink(dynamicId),
            sourceUrl = sourceUrl,
        )
    }

    private fun videoParsedLink(videoId: String, sourceUrl: String): ParsedLink {
        val normalizedId = videoId.normalizeBilibiliVideoId()
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.VIDEO,
            targetId = normalizedId,
            normalizedUrl = videoLink(normalizedId),
            sourceUrl = sourceUrl,
        )
    }

    private fun liveParsedLink(roomId: String, sourceUrl: String): ParsedLink {
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.LIVE,
            targetId = roomId,
            normalizedUrl = liveLink(roomId),
            sourceUrl = sourceUrl,
        )
    }

    private fun userParsedLink(userId: String, sourceUrl: String): ParsedLink {
        return ParsedLink(
            platformId = platformId,
            kind = LinkKinds.USER,
            targetId = userId,
            normalizedUrl = userLink(userId),
            sourceUrl = sourceUrl,
        )
    }

    private fun BilibiliVideoSnapshot.toPublisherInfo(): PublisherInfo? {
        if (ownerId.isBlank()) return null
        return PublisherInfo(
            key = PublisherKey.of(platformId.value, PublisherKind.USER, ownerId),
            name = ownerName.ifBlank { ownerId },
            avatar = MediaRef(ownerFaceUrl.orEmpty(), MediaKind.AVATAR),
        )
    }

    private fun Long?.toDisplayMetric(key: String): DynamicMetric? {
        val value = this?.takeIf { it > 0 } ?: return null
        return DynamicMetric(
            key = key,
            raw = value,
            display = value.toDisplayCount(),
        )
    }

    private fun Long.toDisplayCount(): String {
        return when {
            this >= 100_000_000L -> "%.1f亿".format(this / 100_000_000.0).trimTrailingZero()
            this >= 10_000L -> "%.1f万".format(this / 10_000.0).trimTrailingZero()
            else -> toString()
        }
    }

    private fun String.trimTrailingZero(): String {
        return replace(".0", "")
    }

    private fun LiveStatus.label(): String {
        return when (this) {
            LiveStatus.OPEN -> "直播中"
            LiveStatus.ROUND -> "轮播中"
            LiveStatus.CLOSE -> "未开播"
        }
    }

    private fun String.isBilibiliVideoId(): Boolean {
        return startsWith("BV", ignoreCase = true) && length > 2 && all { it.isLetterOrDigit() } ||
            startsWith("av", ignoreCase = true) && drop(2).all { it.isDigit() }
    }

    private fun String.normalizeBilibiliVideoId(): String {
        return when {
            startsWith("BV", ignoreCase = true) -> "BV" + drop(2)
            startsWith("av", ignoreCase = true) -> "av" + drop(2)
            else -> this
        }
    }

    private fun fallbackPublisher(): Publisher {
        return Publisher(
            id = 0,
            key = PublisherKey.of(platformId.value, PublisherKind.USER, "unknown"),
            name = "",
            avatar = MediaRef("", MediaKind.AVATAR),
            createTime = 0,
            createUser = 0,
        )
    }

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private companion object {
        private const val BILIBILI_DYNAMIC_HOME: String = "https://t.bilibili.com"
        private const val BILIBILI_HOME: String = "https://www.bilibili.com"
        private const val BILIBILI_LIVE_HOME: String = "https://live.bilibili.com"
        private const val BILIBILI_SPACE_HOME: String = "https://space.bilibili.com"
    }
}
