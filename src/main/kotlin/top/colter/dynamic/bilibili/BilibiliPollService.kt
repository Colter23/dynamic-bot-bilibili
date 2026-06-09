package top.colter.dynamic.bilibili

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.bilibili.api.downloadVideo
import top.colter.bilibili.api.follow
import top.colter.bilibili.api.getCurrentUserNav
import top.colter.bilibili.api.getDynamicDetail
import top.colter.bilibili.api.getLiveInfo
import top.colter.bilibili.api.getLiveStatusBatch
import top.colter.bilibili.api.getNewDynamic
import top.colter.bilibili.api.getGroupList
import top.colter.bilibili.api.getUserInfo
import top.colter.bilibili.api.getVideoDetail
import top.colter.bilibili.api.modifyGroupUsers
import top.colter.bilibili.api.createGroup
import top.colter.bilibili.auth.qrCode
import top.colter.bilibili.client.BiliAuthClient
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.EditCookie
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicList
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.data.user.BiliGroup
import top.colter.bilibili.data.video.BiliVideo
import top.colter.bilibili.data.video.BiliVideoDownloadResult
import top.colter.bilibili.data.video.BiliVideoQuality as BiliClientVideoQuality
import top.colter.bilibili.exception.BiliDownloadException
import top.colter.bilibili.exception.BiliLoginException
import top.colter.dynamic.core.link.LinkVideoDownloadRequest
import top.colter.dynamic.core.link.LinkVideoDownloadResult
import top.colter.dynamic.core.link.LinkVideoQuality
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import kotlinx.coroutines.CancellationException
import top.colter.bilibili.api.relation
import top.colter.bilibili.api.unfollow
import top.colter.bilibili.data.user.BiliUserInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class BilibiliPublisherSnapshot(
    val userId: String,
    val name: String,
    val avatarBadgeKey: String? = null,
    val faceUrl: String,
    val headerUrl: String? = null,
    val pendantUrl: String? = null,
)

internal data class BilibiliVideoSnapshot(
    val aid: Long,
    val bvid: String,
    val title: String,
    val description: String,
    val coverUrl: String? = null,
    val ownerId: String,
    val ownerName: String,
    val ownerFaceUrl: String? = null,
    val durationSeconds: Long? = null,
    val publishedAtEpochSeconds: Long? = null,
    val play: Long? = null,
    val danmaku: Long? = null,
    val like: Long? = null,
)

internal data class BilibiliLiveSnapshot(
    val userId: String,
    val roomId: String,
    val status: LiveStatus,
    val title: String,
    val area: String? = null,
    val coverUrl: String? = null,
    val startedAtEpochSeconds: Long? = null,
)

internal data class BilibiliLiveRoomSnapshot(
    val userId: String,
    val roomId: String,
    val status: LiveStatus,
    val title: String,
    val area: String? = null,
    val coverUrl: String? = null,
    val startedAtEpochSeconds: Long? = null,
    val online: Long? = null,
    val attention: Long? = null,
)

internal data class BilibiliFollowRelationSnapshot(
    val userId: String,
    val following: Boolean,
    val tagIds: Set<Long>,
)

private class BilibiliVideoDownloadSizeExceededException(
    maxBytes: Long,
) : BiliDownloadException("视频下载超过大小上限：maxBytes=$maxBytes")

internal interface BilibiliPlatformGateway {
    fun close() {
    }

    suspend fun fetchNewDynamicPage(page: Int = 1, type: String = "all"): BiliDynamicList {
        throw UnsupportedOperationException("不支持拉取动态列表")
    }

    suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        throw UnsupportedOperationException("不支持拉取动态详情")
    }

    suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
        throw UnsupportedOperationException("不支持短链接解析")
    }

    suspend fun fetchPublisherSnapshot(userId: String): BilibiliPublisherSnapshot?

    suspend fun fetchVideoSnapshot(videoId: String): BilibiliVideoSnapshot? {
        throw UnsupportedOperationException("不支持获取视频详情")
    }

    suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        throw UnsupportedOperationException("不支持视频下载")
    }

    suspend fun fetchLiveRoomSnapshot(roomId: String): BilibiliLiveRoomSnapshot? {
        throw UnsupportedOperationException("不支持获取直播间详情")
    }

    suspend fun fetchLiveStatusBatch(uids: Iterable<Long>): List<BilibiliLiveSnapshot> {
        throw UnsupportedOperationException("不支持直播状态查询")
    }

    suspend fun queryFollowState(userId: String): FollowState

    suspend fun followPublisher(userId: String): FollowActionResult

    suspend fun fetchFollowRelation(userId: String): BilibiliFollowRelationSnapshot? {
        throw UnsupportedOperationException("不支持关注关系接口")
    }

    suspend fun unfollowPublisher(userId: String): FollowActionResult {
        return FollowActionResult(
            status = FollowActionStatus.UNSUPPORTED,
            message = "不支持取消关注",
        )
    }

    suspend fun fetchFollowGroups(): List<BiliGroup> {
        throw UnsupportedOperationException("不支持关注分组接口")
    }

    suspend fun createFollowGroup(tag: String) {
        throw UnsupportedOperationException("不支持关注分组接口")
    }

    suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
        throw UnsupportedOperationException("不支持关注分组接口")
    }

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持登录状态检查",
        )
    }

    suspend fun importCookiesJson(cookiesJson: String) {
    }

    fun exportCookiesJson(): String = ""

    suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持 Cookie 登录",
        )
    }

    suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit = { _ -> },
    ): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持二维码登录",
        )
    }
}

internal data class BilibiliQrLoginOutcome(
    val result: QrCodeLoginResult,
    val cookiesJson: String,
)

internal interface BilibiliQrLoginGateway {
    suspend fun loginByQrCode(
        onQrCode: suspend (QrCodeLoginData) -> Unit,
        onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
    ): BilibiliQrLoginOutcome

    fun close() {
    }
}

internal class BilibiliClientQrLoginGateway(
    private val client: BiliAuthClient = BiliAuthClient(),
    private val pollIntervalMs: Long = 1000,
    private val timeoutMs: Long = 180_000,
) : BilibiliQrLoginGateway {
    override suspend fun loginByQrCode(
        onQrCode: suspend (QrCodeLoginData) -> Unit,
        onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
    ): BilibiliQrLoginOutcome {
        val result = client.qrCode(pollIntervalMs, timeoutMs, onQrCode, onStatusChanged)
        return BilibiliQrLoginOutcome(
            result = result,
            cookiesJson = client.exportEditCookiesJson(),
        )
    }

    override fun close() {
        client.close()
    }
}

internal class BilibiliPollService(
    private val requestIntervalMs: Long,
    private val client: BiliClient = BiliClient(),
    private val currentUserNavProvider: suspend () -> BiliUserInfo = { client.getCurrentUserNav() },
    private val qrLoginGatewayFactory: () -> BilibiliQrLoginGateway = { BilibiliClientQrLoginGateway() },
) : BilibiliPlatformGateway {
    override fun close() {
        client.close()
    }

    override suspend fun fetchNewDynamicPage(page: Int, type: String): BiliDynamicList {
        return callWithRequestDelay { client.getNewDynamic(page, type) }
    }

    override suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        val id = dynamicId.toLongOrNull() ?: return null
        return callWithRequestDelay { client.getDynamicDetail(id) }
    }

    override suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(1)
        return withTimeoutOrNull(boundedTimeoutMs) {
            withContext(Dispatchers.IO) {
                expandRedirects(url, boundedTimeoutMs.toInt().coerceAtLeast(1))
            }
        }
    }

    override suspend fun fetchPublisherSnapshot(userId: String): BilibiliPublisherSnapshot? {
        val uid = userId.toLongOrNull() ?: return null
        val info = callWithRequestDelay { client.getUserInfo(uid) }
        return BilibiliPublisherSnapshot(
            userId = info.mid.toString(),
            name = info.name,
            avatarBadgeKey = info.official.toAvatarBadgeKey(),
            faceUrl = info.face.url,
            headerUrl = info.header?.image?.url,
            pendantUrl = info.pendant?.image?.url?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun fetchVideoSnapshot(videoId: String): BilibiliVideoSnapshot? {
        val normalized = videoId.trim()
        if (normalized.isBlank()) return null
        val aid = normalized.removePrefix("av").removePrefix("AV").toLongOrNull()
        val bvid = normalized.takeIf { it.startsWith("BV", ignoreCase = true) }
        val video = callWithRequestDelay { client.getVideoDetail(aid = aid, bvid = bvid) }
        return video.toSnapshot()
    }

    override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        val normalized = request.parsedLink.targetId.trim()
        require(normalized.isNotBlank()) { "视频 ID 不能为空" }
        require(request.maxBytes > 0) { "视频大小上限必须大于 0" }

        val aid = normalized.removePrefix("av").removePrefix("AV").toLongOrNull()
        val bvid = normalized.takeIf { it.startsWith("BV", ignoreCase = true) }
        require(aid != null || !bvid.isNullOrBlank()) { "不支持的 Bilibili 视频 ID：$normalized" }

        val directory = request.directory
        val fileName = normalized.toVideoCacheFileName()
        val cachedFile = directory.resolve("$fileName.mp4")
        if (cachedFile.isFile) {
            val cachedSize = cachedFile.length()
            if (cachedSize <= request.maxBytes) {
                return LinkVideoDownloadResult(
                    video = MediaRef(cachedFile.absolutePath, MediaKind.VIDEO, mimeType = "video/mp4"),
                    fileSizeBytes = cachedSize,
                )
            }
            cachedFile.delete()
        }

        cleanupVideoCacheFiles(directory, fileName)
        return try {
            val detail = client.getVideoDetail(aid = aid, bvid = bvid)
            val result = client.downloadVideo(
                directory = directory,
                aid = detail.aid,
                bvid = detail.bvid,
                fileName = fileName,
                ffmpegPath = request.ffmpegPath.trim().takeIf { it.isNotBlank() },
                overwrite = true,
                keepStreams = false,
                quality = request.quality.toBiliClientQuality(),
                onProgress = {
                    if (directory.cacheBytes(fileName) > request.maxBytes) {
                        throw BilibiliVideoDownloadSizeExceededException(request.maxBytes)
                    }
                },
            )
            val finalFile = result.finalMp4File()
            val size = finalFile.length()
            if (size > request.maxBytes) {
                throw BilibiliVideoDownloadSizeExceededException(request.maxBytes)
            }
            LinkVideoDownloadResult(
                video = MediaRef(finalFile.absolutePath, MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = size,
                title = detail.title,
                durationSeconds = detail.duration.takeIf { it > 0 }?.toLong(),
            )
        } catch (e: CancellationException) {
            cleanupVideoCacheFiles(directory, fileName)
            throw e
        } catch (e: Throwable) {
            cleanupVideoCacheFiles(directory, fileName)
            throw e
        } finally {
            applyRequestDelay()
        }
    }

    override suspend fun fetchLiveRoomSnapshot(roomId: String): BilibiliLiveRoomSnapshot? {
        val id = roomId.toLongOrNull() ?: return null
        val info = callWithRequestDelay { client.getLiveInfo(id) }
        return BilibiliLiveRoomSnapshot(
            userId = info.uid.takeIf { it > 0 }?.toString().orEmpty(),
            roomId = info.roomId.takeIf { it > 0 }?.toString() ?: roomId,
            status = when (info.status.value) {
                1 -> LiveStatus.OPEN
                2 -> LiveStatus.ROUND
                else -> LiveStatus.CLOSE
            },
            title = info.title,
            area = listOf(info.parentAreaName, info.areaName)
                .mapNotNull { it.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(" / ")
                .takeIf(String::isNotBlank),
            coverUrl = info.cover.url.toNormalizedBiliImageUrl()
                ?: info.keyframe.url.toNormalizedBiliImageUrl()
                ?: info.background.url.toNormalizedBiliImageUrl(),
            startedAtEpochSeconds = info.liveTime.parseLiveStartEpochSeconds(),
            online = info.online.takeIf { it > 0 }?.toLong(),
            attention = info.attention.takeIf { it > 0 }?.toLong(),
        )
    }

    override suspend fun fetchLiveStatusBatch(uids: Iterable<Long>): List<BilibiliLiveSnapshot> {
        val uidList = uids.toList()
        if (uidList.isEmpty()) return emptyList()
        val data = callWithRequestDelay { client.getLiveStatusBatch(uidList) }
        return data.map { (uid, live) ->
            BilibiliLiveSnapshot(
                userId = live.uid.takeIf { it > 0 }?.toString() ?: uid.toString(),
                roomId = live.roomId.takeIf { it > 0 }?.toString().orEmpty(),
                status = when (live.liveStatus) {
                    1 -> LiveStatus.OPEN
                    2 -> LiveStatus.ROUND
                    else -> LiveStatus.CLOSE
                },
                title = live.title,
                area = live.area.takeIf { it.isNotBlank() },
                coverUrl = live.cover.toNormalizedBiliImageUrl(),
                startedAtEpochSeconds = live.liveTime.takeIf { it > 0 },
            )
        }
    }

    override suspend fun queryFollowState(userId: String): FollowState {
        // 本网关始终支持关注查询；fetchFollowRelation 返回 null 仅代表 uid 非法（输入错误），
        // 真正的请求失败会抛异常并由上层 requestFailureHandler 处理。无效 uid 归为 NOT_FOLLOWING，
        // 让后续关注动作给出"无效的 Bilibili 用户 ID"的精确失败，而不是误判为平台不支持关注。
        val relation = fetchFollowRelation(userId) ?: return FollowState.NOT_FOLLOWING
        return if (relation.following) {
            FollowState.FOLLOWING
        } else {
            FollowState.NOT_FOLLOWING
        }
    }

    override suspend fun fetchFollowRelation(userId: String): BilibiliFollowRelationSnapshot? {
        val uid = userId.toLongOrNull() ?: return null
        val relation = callWithRequestDelay { client.relation(uid) }
        return BilibiliFollowRelationSnapshot(
            userId = relation.mid.takeIf { it > 0 }?.toString() ?: uid.toString(),
            following = relation.attribute.isFollowingRelation(),
            tagIds = relation.tag.orEmpty().map { it.toLong() }.toSet(),
        )
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        return when (queryFollowState(userId)) {
            FollowState.FOLLOWING -> FollowActionResult(FollowActionStatus.NOOP)
            FollowState.UNSUPPORTED -> FollowActionResult(FollowActionStatus.UNSUPPORTED)
            FollowState.NOT_FOLLOWING -> {
                val uid = userId.toLongOrNull()
                    ?: return FollowActionResult(
                        FollowActionStatus.FAILED,
                        "无效的 Bilibili 用户 ID：$userId",
                    )
                callWithRequestDelay { client.follow(uid) }
                FollowActionResult(FollowActionStatus.DONE)
            }
        }
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val uid = userId.toLongOrNull()
            ?: return FollowActionResult(
                FollowActionStatus.FAILED,
                "无效的 Bilibili 用户 ID：$userId",
            )
        callWithRequestDelay { client.unfollow(uid) }
        return FollowActionResult(
            FollowActionStatus.DONE,
            "已取消关注 Bilibili 用户：$userId",
        )
    }

    override suspend fun fetchFollowGroups(): List<BiliGroup> {
        return callWithRequestDelay { client.getGroupList() }
    }

    override suspend fun createFollowGroup(tag: String) {
        callWithRequestDelay { client.createGroup(tag) }
    }

    override suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
        callWithRequestDelay { client.modifyGroupUsers(fids, tagIds) }
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return try {
            val nav = currentUserNavProvider()
            PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "登录成功",
                account = PublisherLoginAccount(
                    userId = nav.mid.takeIf { it > 0 }?.toString(),
                    name = nav.name.takeIf { it.isNotBlank() },
                    avatar = nav.face.url.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.AVATAR) },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BiliLoginException) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Bilibili 未登录",
            )
        } catch (e: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = e.message ?: "登录状态检查失败",
            )
        } finally {
            applyRequestDelay()
        }
    }

    override suspend fun importCookiesJson(cookiesJson: String) {
        client.importEditCookiesJson(cookiesJson, true)
    }

    override fun exportCookiesJson(): String {
        return client.exportEditCookiesJson()
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "Cookie 不能为空")
        }

        return importAndVerifyCookies {
            if (trimmed.startsWith("[")) {
                client.importEditCookiesJson(trimmed, true)
            } else {
                val cookies = parseCookieHeader(trimmed)
                if (cookies.isEmpty()) {
                    throw IllegalArgumentException("未找到有效的 Cookie 键值对")
                }
                client.importEditCookies(cookies, true)
            }
        }
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        val gateway = qrLoginGatewayFactory()
        return try {
            val outcome = gateway.loginByQrCode(
                onQrCode = { qrCodeData -> onQrCode(qrCodeData.toChallenge()) },
                onStatusChanged = { qrCodeResult -> onStatusChanged(qrCodeResult.toPublisherLoginResult()) },
            )
            if (outcome.result.status == QrCodeLoginStatus.SUCCESS) {
                importAndVerifyCookies {
                    client.importEditCookiesJson(outcome.cookiesJson, true)
                }
            } else {
                outcome.result.toPublisherLoginResult()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.toQrFailureResult()
        } finally {
            runCatching { gateway.close() }
        }
    }

    private suspend fun applyRequestDelay() {
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
    }

    private suspend fun <T> callWithRequestDelay(block: suspend () -> T): T {
        return try {
            block()
        } finally {
            applyRequestDelay()
        }
    }

    private fun BiliVideo.toSnapshot(): BilibiliVideoSnapshot {
        return BilibiliVideoSnapshot(
            aid = aid,
            bvid = bvid,
            title = title,
            description = desc,
            coverUrl = cover.url.toNormalizedBiliImageUrl(),
            ownerId = owner.mid.takeIf { it > 0 }?.toString().orEmpty(),
            ownerName = owner.name,
            ownerFaceUrl = owner.face.url.toNormalizedBiliImageUrl(),
            durationSeconds = duration.takeIf { it > 0 }?.toLong(),
            publishedAtEpochSeconds = pubTime.takeIf { it > 0 },
        )
    }

    private fun BiliVideoDownloadResult.finalMp4File(): File {
        return listOfNotNull(finalFile, durlFiles.singleOrNull())
            .firstOrNull { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?: throw BiliDownloadException("视频下载未得到可发送的 mp4 文件，请配置 ffmpeg 后重试")
    }

    private fun LinkVideoQuality.toBiliClientQuality(): BiliClientVideoQuality {
        return when (this) {
            LinkVideoQuality.AUTO_LOWEST -> BiliClientVideoQuality.AUTO_LOWEST
            LinkVideoQuality.P240 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P240)
            LinkVideoQuality.P360 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P360)
            LinkVideoQuality.P480 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P480)
            LinkVideoQuality.P720 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P720)
            LinkVideoQuality.P720_60 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P720_60)
            LinkVideoQuality.P1080 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P1080)
            LinkVideoQuality.P1080_PLUS -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P1080_PLUS)
            LinkVideoQuality.P1080_60 -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P1080_60)
            LinkVideoQuality.P4K -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P4K)
            LinkVideoQuality.HDR -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.HDR)
            LinkVideoQuality.DOLBY -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.DOLBY)
            LinkVideoQuality.P8K -> BiliClientVideoQuality.atMost(BiliClientVideoQuality.P8K)
            LinkVideoQuality.AUTO_HIGHEST -> BiliClientVideoQuality.AUTO_HIGHEST
        }
    }

    private fun String.toVideoCacheFileName(): String {
        return replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_', '.', ' ')
            .ifBlank { "video" }
    }

    private fun File.cacheBytes(fileName: String): Long {
        return cacheFiles(fileName).sumOf { file -> if (file.isFile) file.length() else 0L }
    }

    private fun cleanupVideoCacheFiles(directory: File, fileName: String) {
        directory.cacheFiles(fileName).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun File.cacheFiles(fileName: String): List<File> {
        return listFiles()
            ?.filter { file ->
                file.isFile && (file.name == "$fileName.mp4" || file.name.startsWith("$fileName."))
            }
            .orEmpty()
    }

    private suspend fun importAndVerifyCookies(importer: suspend () -> Unit): PublisherLoginResult {
        val previousCookiesJson = client.exportEditCookiesJson()
        return try {
            importer()
            val result = checkLoginState()
            if (result.status != PublisherLoginStatus.SUCCESS) {
                restoreCookies(previousCookiesJson)
            }
            result
        } catch (e: CancellationException) {
            restoreCookies(previousCookiesJson)
            throw e
        } catch (e: Throwable) {
            restoreCookies(previousCookiesJson)
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = e.message ?: "登录失败",
            )
        }
    }

    private suspend fun restoreCookies(cookiesJson: String) {
        runCatching {
            if (cookiesJson.isBlank()) {
                client.importEditCookies(emptyList<EditCookie>(), true)
            } else {
                client.importEditCookiesJson(cookiesJson, true)
            }
        }
    }

    private fun QrCodeLoginData.toChallenge(): PublisherQrLoginChallenge {
        return PublisherQrLoginChallenge(
            qrContent = url,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + QR_EXPIRES_SECONDS,
            message = "请使用 Bilibili App 扫码并确认登录",
            instruction = "请使用 Bilibili App 扫码并确认登录",
            validityHint = "三分钟内有效",
            statusPollIntervalMillis = 2_500,
        )
    }

    private fun QrCodeLoginResult.toPublisherLoginResult(): PublisherLoginResult {
        val resolvedMessage = message.ifBlank { status.text }
        return when (status) {
            QrCodeLoginStatus.SUCCESS -> PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = resolvedMessage.ifBlank { "登录成功" },
            )
            QrCodeLoginStatus.EXPIRED -> PublisherLoginResult(
                status = PublisherLoginStatus.EXPIRED,
                message = resolvedMessage.ifBlank { "二维码已过期" },
            )
            QrCodeLoginStatus.SCANNED -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = resolvedMessage.ifBlank { "已扫码，等待确认" },
            )
            QrCodeLoginStatus.WAITING -> PublisherLoginResult(
                status = PublisherLoginStatus.PENDING,
                message = resolvedMessage.ifBlank { "等待扫码" },
            )
            QrCodeLoginStatus.UNKNOWN -> PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = resolvedMessage.ifBlank { "Bilibili 二维码登录失败：code=$code" },
            )
        }
    }

    private fun Throwable.toQrFailureResult(): PublisherLoginResult {
        val reason = message ?: "二维码登录失败"
        val status = if (reason.contains("失效") || reason.contains("超时") || reason.contains("expired", ignoreCase = true)) {
            PublisherLoginStatus.EXPIRED
        } else {
            PublisherLoginStatus.FAILED
        }
        return PublisherLoginResult(status, reason)
    }

    private fun parseCookieHeader(cookieHeader: String): List<EditCookie> {
        return cookieHeader.split(";")
            .mapNotNull { rawPair ->
                val index = rawPair.indexOf("=")
                if (index <= 0) return@mapNotNull null

                val name = rawPair.substring(0, index).trim()
                val value = rawPair.substring(index + 1).trim()
                if (name.isBlank()) return@mapNotNull null
                EditCookie(
                    domain = DEFAULT_COOKIE_DOMAIN,
                    expirationDate = null,
                    httpOnly = false,
                    name = name,
                    path = DEFAULT_COOKIE_PATH,
                    secure = false,
                    value = value,
                )
            }
            .associateBy { it.name }
            .values
            .toList()
    }

    private fun expandRedirects(initialUrl: String, timeoutMs: Int): String? {
        var current = initialUrl
        repeat(MAX_SHORT_URL_REDIRECTS) {
            val uri = runCatching { URI(current) }.getOrNull() ?: return null
            val connection = runCatching { uri.toURL().openConnection() as? HttpURLConnection }
                .getOrNull()
                ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                //connection.setRequestProperty("User-Agent", USER_AGENT)

                val statusCode = connection.responseCode
                if (statusCode in HTTP_REDIRECT_RANGE) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = uri.resolve(location).toString()
                    return@repeat
                }
                return current
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    private companion object {
        private const val DEFAULT_COOKIE_DOMAIN: String = ".bilibili.com"
        private const val DEFAULT_COOKIE_PATH: String = "/"
        private const val QR_EXPIRES_SECONDS: Long = 180
        private const val MAX_SHORT_URL_REDIRECTS: Int = 5
        private const val USER_AGENT: String = "dynamic-bot/0.0.3"
        private const val FOLLOWING_ATTRIBUTE: Int = 2
        private val HTTP_REDIRECT_RANGE: IntRange = 300..399

        private fun Int.isFollowingRelation(): Boolean {
            return (this and FOLLOWING_ATTRIBUTE) != 0
        }
    }
}

private fun String?.toNormalizedBiliImageUrl(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> "https://www.bilibili.com$value"
        else -> value
    }
}

internal fun String?.parseLiveStartEpochSeconds(): Long? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "0000-00-00 00:00:00" } ?: return null
    return value.toLongOrNull()
        ?: runCatching {
            LocalDateTime
                .parse(value, BILI_LIVE_TIME_FORMATTER)
                .atZone(BILI_TIME_ZONE)
                .toEpochSecond()
        }.getOrNull()
}

private val BILI_TIME_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val BILI_LIVE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
