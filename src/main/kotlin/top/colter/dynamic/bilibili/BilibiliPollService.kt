package top.colter.dynamic.bilibili

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.request.parameter
import java.util.concurrent.atomic.AtomicLong
import top.colter.bilibili.api.downloadVideo
import top.colter.bilibili.api.follow
import top.colter.bilibili.api.getDynamicDetail
import top.colter.bilibili.api.getLiveInfo
import top.colter.bilibili.api.getLiveStatusBatch
import top.colter.bilibili.api.getNewDynamic
import top.colter.bilibili.api.getUserNewDynamic
import top.colter.bilibili.api.getGroupList
import top.colter.bilibili.api.getUserInfo
import top.colter.bilibili.api.getUserInfoBatch
import top.colter.bilibili.api.getVideoDetail
import top.colter.bilibili.api.modifyGroupUsers
import top.colter.bilibili.api.probeVideoDownloadSize
import top.colter.bilibili.api.createGroup
import top.colter.bilibili.api.LOGIN_QRCODE
import top.colter.bilibili.api.LOGIN_VERIFY
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.client.getData
import top.colter.bilibili.data.EditCookie
import top.colter.bilibili.data.dynamic.BiliDynamic
import top.colter.bilibili.data.dynamic.BiliDynamicList
import top.colter.bilibili.data.login.QrCodeLoginData
import top.colter.bilibili.data.login.QrCodeLoginResult
import top.colter.bilibili.data.login.QrCodeLoginStatus
import top.colter.bilibili.data.search.UserSearchOrder
import top.colter.bilibili.data.search.UserType
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
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import kotlinx.coroutines.CancellationException
import top.colter.bilibili.api.FollowAction
import top.colter.bilibili.api.modifyFollowBatch
import top.colter.bilibili.api.relation
import top.colter.bilibili.api.relations
import top.colter.bilibili.api.searchUser
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
    val comment: Long? = null,
    val favorite: Long? = null,
    val coin: Long? = null,
    val share: Long? = null,
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
    sizeBytes: Long? = null,
) : BiliDownloadException(
    if (sizeBytes == null) {
        "视频下载超过大小上限：maxBytes=$maxBytes"
    } else {
        "视频下载超过大小上限：sizeBytes=$sizeBytes，maxBytes=$maxBytes"
    },
)

private fun BiliUserInfo.toPublisherSnapshot(): BilibiliPublisherSnapshot {
    return BilibiliPublisherSnapshot(
        userId = mid.toString(),
        name = name,
        avatarBadgeKey = official.toAvatarBadgeKey(),
        faceUrl = face.url,
        headerUrl = header?.image?.url,
        pendantUrl = pendant?.image?.url?.takeIf { it.isNotBlank() },
    )
}

internal interface BilibiliPlatformGateway {
    fun close() {
    }

    suspend fun fetchNewDynamicPage(page: Int = 1, type: String = "all"): BiliDynamicList {
        throw UnsupportedOperationException("不支持拉取动态列表")
    }

    suspend fun fetchUserNewDynamic(userId: Long): BiliDynamicList {
        throw UnsupportedOperationException("不支持拉取用户动态列表")
    }

    suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        throw UnsupportedOperationException("不支持拉取动态详情")
    }

    suspend fun expandShortUrl(url: String, timeoutMs: Long): String? {
        throw UnsupportedOperationException("不支持短链接解析")
    }

    suspend fun fetchPublisherSnapshot(userId: String): BilibiliPublisherSnapshot?

    suspend fun fetchPublisherSnapshots(userIds: Collection<String>): Map<String, BilibiliPublisherSnapshot> {
        return userIds.distinct().mapNotNull { userId ->
            fetchPublisherSnapshot(userId)?.let { userId to it }
        }.toMap()
    }

    suspend fun searchPublisherSnapshots(query: String, limit: Int = 10): List<BilibiliPublisherSnapshot> {
        return fetchPublisherSnapshot(query)?.let(::listOf).orEmpty()
    }

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

    /**
     * 执行关注写操作，不在网关层判断当前关注状态。
     *
     * 是否需要关注、是否需要加入 Bot 关注分组由 [BilibiliFollowService] 统一处理。
     */
    suspend fun followPublisher(userId: String): FollowActionResult

    suspend fun fetchFollowRelation(userId: String): BilibiliFollowRelationSnapshot? {
        throw UnsupportedOperationException("不支持关注关系接口")
    }

    suspend fun fetchFollowRelations(userIds: Collection<String>): Map<String, BilibiliFollowRelationSnapshot> {
        return userIds.distinct().mapNotNull { userId ->
            val relation = fetchFollowRelation(userId) ?: return@mapNotNull null
            userId to relation
        }.toMap()
    }

    suspend fun unfollowPublisher(userId: String): FollowActionResult {
        return FollowActionResult(
            status = FollowActionStatus.UNSUPPORTED,
            message = "不支持取消关注",
        )
    }

    suspend fun followPublishers(userIds: Collection<String>): FollowActionResult {
        val ids = userIds.distinct()
        return when {
            ids.isEmpty() -> FollowActionResult(FollowActionStatus.NOOP)
            ids.size == 1 -> followPublisher(ids.single())
            else -> FollowActionResult(FollowActionStatus.UNSUPPORTED, "不支持批量关注")
        }
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

    fun importSession(state: BilibiliStoredSession) {}

    fun exportSession(): BilibiliStoredSession = BilibiliStoredSession()

    fun exportSessionSnapshot(): BilibiliSessionSnapshot = BilibiliSessionSnapshot(exportCookiesJson(), exportSession())

    suspend fun confirmSessionSaved(state: BilibiliStoredSession) {}

    suspend fun persistSession(save: (BilibiliSessionSnapshot) -> Boolean): Boolean = save(exportSessionSnapshot())

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

    suspend fun refreshCookieSession() {
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
    private val client: BiliClient = BilibiliSessionClient(),
    private val pollIntervalMs: Long = 2000,
    private val timeoutMs: Long = 180_000,
    private val requestTimeoutMs: Long = 15_000,
) : BilibiliQrLoginGateway {
    override suspend fun loginByQrCode(
        onQrCode: suspend (QrCodeLoginData) -> Unit,
        onStatusChanged: suspend (QrCodeLoginResult) -> Unit,
    ): BilibiliQrLoginOutcome {
        return withTimeout(timeoutMs) {
            val qrCode = read { client.getData<QrCodeLoginData>(LOGIN_QRCODE) }
            onQrCode(qrCode)
            while (true) {
                val result = read {
                    client.getData<QrCodeLoginResult>(LOGIN_VERIFY) { parameter("qrcode_key", qrCode.qrcodeKey) }
                }
                onStatusChanged(result)
                when (result.status) {
                    QrCodeLoginStatus.SUCCESS, QrCodeLoginStatus.EXPIRED ->
                        return@withTimeout BilibiliQrLoginOutcome(result, exportBilibiliCookies(client))
                    QrCodeLoginStatus.UNKNOWN -> throw BilibiliSessionException("无法识别的扫码状态（code=${result.code}）")
                    else -> delay(pollIntervalMs)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private suspend fun <T> read(block: suspend () -> T): T {
        repeat(3) { attempt ->
            try { return withTimeout(requestTimeoutMs) { block() } }
            catch (e: CancellationException) { currentCoroutineContext().ensureActive(); if (attempt == 2) throw e }
            catch (e: java.io.IOException) { if (attempt == 2) throw BilibiliSessionException("扫码登录网络请求失败，请稍后重试") }
            delay(1_000L * (attempt + 1))
        }
        error("unreachable")
    }

    override fun close() {
        client.close()
    }
}

internal class BilibiliPollService(
    private val requestIntervalMs: Long,
    client: BiliClient = BilibiliSessionClient(boundedRequests = false),
    private val currentUserNavProvider: (suspend () -> BiliUserInfo)? = null,
    private val qrLoginGatewayFactory: () -> BilibiliQrLoginGateway = { BilibiliClientQrLoginGateway() },
    private val sessionClientFactory: () -> BiliClient = { BilibiliSessionClient(boundedRequests = false) },
    private val sessionProtocol: BilibiliSessionProtocol = BilibiliSessionProtocol(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val loginTimeoutMillis: Long = 210_000,
    private val validationTimeoutMillis: Long = 15_000,
) : BilibiliPlatformGateway {
    private val sessionLogger = loggerFor<BilibiliPollService>()
    private val clientLock = Any()
    private var client: BiliClient = client
    private val users = mutableMapOf<BiliClient, Int>()
    private val retired = mutableSetOf<BiliClient>()
    private var closed = false
    private val generation = AtomicLong()
    private var credentialUpdateVersion: Long? = null
    private val refreshMutex = Mutex()
    private val persistenceMutex = Mutex()
    private var lastRefreshAttemptAt = Long.MIN_VALUE
    @Volatile private var session = BilibiliStoredSession()

    override fun close() {
        synchronized(clientLock) {
            if (closed) return
            generation.incrementAndGet()
            closed = true
            retire(client)
        }
    }

    override suspend fun fetchNewDynamicPage(page: Int, type: String): BiliDynamicList {
        return callWithRequestDelay { client -> client.getNewDynamic(page, type) }
    }

    override suspend fun fetchUserNewDynamic(userId: Long): BiliDynamicList {
        return callWithRequestDelay { client -> client.getUserNewDynamic(userId) }
    }

    override suspend fun fetchDynamicDetail(dynamicId: String): BiliDynamic? {
        val id = dynamicId.toLongOrNull() ?: return null
        return callWithRequestDelay { client -> client.getDynamicDetail(id) }
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
        val info = callWithRequestDelay { client -> client.getUserInfo(uid) }
        return info.toPublisherSnapshot()
    }

    override suspend fun fetchPublisherSnapshots(userIds: Collection<String>): Map<String, BilibiliPublisherSnapshot> {
        val uidByText = userIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { userId -> userId.toLongOrNull()?.let { userId to it } }
        if (uidByText.isEmpty()) return emptyMap()
        val snapshots = callWithRequestDelay { client -> client.getUserInfoBatch(uidByText.map { it.second }) }
            .associateBy { it.mid }
        return uidByText.mapNotNull { (userId, uid) ->
            snapshots[uid]?.toPublisherSnapshot()?.let { userId to it }
        }.toMap()
    }

    override suspend fun searchPublisherSnapshots(query: String, limit: Int): List<BilibiliPublisherSnapshot> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        if (keyword.toLongOrNull() != null) {
            return fetchPublisherSnapshot(keyword)?.let(::listOf).orEmpty()
        }

        val boundedLimit = limit.coerceAtLeast(1)
        val response = callWithRequestDelay { client ->
            client.searchUser(
                keyword = keyword,
                page = 1,
                order = UserSearchOrder.FANS,
                userType = UserType.UP,
            )
        }
        return response.result
            .asSequence()
            .filter { it.mid > 0 }
            .distinctBy { it.mid }
            .take(boundedLimit)
            .map { user ->
                BilibiliPublisherSnapshot(
                    userId = user.mid.toString(),
                    name = user.name,
                    avatarBadgeKey = user.official.toAvatarBadgeKey(),
                    faceUrl = user.face.fullUrl,
                )
            }
            .toList()
    }

    override suspend fun fetchVideoSnapshot(videoId: String): BilibiliVideoSnapshot? {
        val normalized = videoId.trim()
        if (normalized.isBlank()) return null
        val aid = normalized.removePrefix("av").removePrefix("AV").toLongOrNull()
        val bvid = normalized.takeIf { it.startsWith("BV", ignoreCase = true) }
        val video = callWithRequestDelay { client -> client.getVideoDetail(aid = aid, bvid = bvid) }
        return video.toSnapshot()
    }

    override suspend fun downloadVideoLink(request: LinkVideoDownloadRequest): LinkVideoDownloadResult {
        val normalized = request.parsedLink.targetId.trim()
        require(normalized.isNotBlank()) { "视频 ID 不能为空" }
        require(request.maxBytes >= 0) { "视频大小上限不能为负数" }

        val aid = normalized.removePrefix("av").removePrefix("AV").toLongOrNull()
        val bvid = normalized.takeIf { it.startsWith("BV", ignoreCase = true) }
        require(aid != null || !bvid.isNullOrBlank()) { "不支持的 Bilibili 视频 ID：$normalized" }

        val directory = request.directory
        // 缓存按清晰度分桶：否则用户把 videoDownload.quality 调高后仍会命中旧的低清晰度文件，
        // 直到缓存按 idle 过期（默认为 7 天）才生效。
        val fileName = "${normalized.toVideoCacheFileName()}-${request.quality.name.lowercase()}"
        val cachedFile = directory.resolve("$fileName.mp4")
        if (cachedFile.isFile) {
            val cachedSize = cachedFile.length()
            if (request.maxBytes <= 0 || cachedSize <= request.maxBytes) {
                // 注意：缓存命中不回填 title/duration（避免为此多打一次详情接口），
                // 只会影响 {video} 的 altText；正文与模板里的 {title}/{duration} 取的是 preview 的字段。
                return LinkVideoDownloadResult(
                    video = MediaRef(cachedFile.absolutePath, MediaKind.VIDEO, mimeType = "video/mp4"),
                    fileSizeBytes = cachedSize,
                )
            }
            cachedFile.delete()
        }

        cleanupVideoCacheFiles(directory, fileName)
        return withClient { client -> try {
            val detail = client.getVideoDetail(aid = aid, bvid = bvid)
            val quality = request.quality.toBiliClientQuality()
            val probedSize = client.probeVideoDownloadSize(
                aid = detail.aid,
                bvid = detail.bvid,
                cid = detail.cid,
                quality = quality,
            )
            if (request.maxBytes > 0) {
                val totalBytes = probedSize.totalBytes
                    ?: throw BiliDownloadException("无法探测视频下载大小")
                if (totalBytes > request.maxBytes) {
                    throw BilibiliVideoDownloadSizeExceededException(
                        maxBytes = request.maxBytes,
                        sizeBytes = totalBytes,
                    )
                }
            }
            val result = client.downloadVideo(
                playUrl = probedSize.playUrl,
                directory = directory,
                fileName = fileName,
                aid = detail.aid,
                bvid = detail.bvid,
                ffmpegPath = request.ffmpegPath.trim().takeIf { it.isNotBlank() },
                overwrite = true,
                keepStreams = false,
                quality = quality,
            )
            val finalFile = result.finalMp4File()
            val finalSize = finalFile.length()
            LinkVideoDownloadResult(
                video = MediaRef(finalFile.absolutePath, MediaKind.VIDEO, mimeType = "video/mp4"),
                fileSizeBytes = finalSize,
                title = detail.title,
                durationSeconds = detail.duration.takeIf { it > 0 }?.toLong(),
            )
        } catch (e: CancellationException) {
            cleanupVideoCacheFiles(directory, fileName)
            throw e
        } catch (e: Throwable) {
            cleanupVideoCacheFiles(directory, fileName)
            throw e.toUserFacingDownloadError()
        } finally {
            applyRequestDelay()
        } }
    }

    /**
     * ffmpeg 缺失或合并失败时，bilibili-client 抛出的英文异常会原样进入用户提示，
     * 这里归一化成可操作的中文说明；其它异常保持原样。
     */
    private fun Throwable.toUserFacingDownloadError(): Throwable {
        val message = message ?: return this
        if (!message.contains("ffmpeg", ignoreCase = true)) return this
        return IllegalStateException(
            "需要 ffmpeg 合并音视频，请在「链接解析 → 视频下载」中配置 ffmpegPath，或把 ffmpeg 放到项目目录",
            this,
        )
    }

    override suspend fun fetchLiveRoomSnapshot(roomId: String): BilibiliLiveRoomSnapshot? {
        val id = roomId.toLongOrNull() ?: return null
        val info = callWithRequestDelay { client -> client.getLiveInfo(id) }
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
        val data = callWithRequestDelay { client -> client.getLiveStatusBatch(uidList) }
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
        val relation = callWithRequestDelay { client -> client.relation(uid) }
        return BilibiliFollowRelationSnapshot(
            userId = relation.mid.takeIf { it > 0 }?.toString() ?: uid.toString(),
            following = relation.attribute.isFollowingRelation(),
            tagIds = relation.tag.orEmpty().map { it.toLong() }.toSet(),
        )
    }

    override suspend fun fetchFollowRelations(userIds: Collection<String>): Map<String, BilibiliFollowRelationSnapshot> {
        val uidByText = userIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { text -> text.toLongOrNull()?.let { uid -> text to uid } }
            .toList()
        if (uidByText.isEmpty()) return emptyMap()

        val relations = callWithRequestDelay { client -> client.relations(uidByText.map { it.second }) }
        return uidByText.mapNotNull { (text, uid) ->
            val relation = relations[uid] ?: return@mapNotNull null
            text to BilibiliFollowRelationSnapshot(
                userId = relation.mid.takeIf { it > 0 }?.toString() ?: uid.toString(),
                following = relation.attribute.isFollowingRelation(),
                tagIds = relation.tag.orEmpty().map { it.toLong() }.toSet(),
            )
        }.toMap()
    }

    override suspend fun followPublisher(userId: String): FollowActionResult {
        val uid = userId.toLongOrNull()
            ?: return FollowActionResult(
                FollowActionStatus.FAILED,
                "无效的 Bilibili 用户 ID：$userId",
            )
        callWithRequestDelay(retryAfterLogin = false) { client -> client.follow(uid) }
        return FollowActionResult(FollowActionStatus.DONE)
    }

    override suspend fun followPublishers(userIds: Collection<String>): FollowActionResult {
        val mids = userIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { it.toLongOrNull() }
            .toList()
        if (mids.isEmpty()) return FollowActionResult(FollowActionStatus.NOOP)

        callWithRequestDelay(retryAfterLogin = false) { client -> client.modifyFollowBatch(mids, FollowAction.FOLLOW) }
        return FollowActionResult(FollowActionStatus.DONE)
    }

    override suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val uid = userId.toLongOrNull()
            ?: return FollowActionResult(
                FollowActionStatus.FAILED,
                "无效的 Bilibili 用户 ID：$userId",
            )
        callWithRequestDelay(retryAfterLogin = false) { client -> client.unfollow(uid) }
        return FollowActionResult(
            FollowActionStatus.DONE,
            "已取消关注 Bilibili 用户：$userId",
        )
    }

    override suspend fun fetchFollowGroups(): List<BiliGroup> {
        return callWithRequestDelay { client -> client.getGroupList() }
    }

    override suspend fun createFollowGroup(tag: String) {
        callWithRequestDelay(retryAfterLogin = false) { client -> client.createGroup(tag) }
    }

    override suspend fun addUsersToFollowGroup(fids: Iterable<Long>, tagIds: Iterable<Long>) {
        callWithRequestDelay(retryAfterLogin = false) { client -> client.modifyGroupUsers(fids, tagIds) }
    }

    override suspend fun refreshCookieSession() {
        refreshSession(force = false)
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        if (currentUserNavProvider == null && exportCookiesJson() == "[]" && session.refreshToken.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "Bilibili 未登录")
        }
        val result = try {
            var previousClient: BiliClient? = null
            try {
                withTimeout(validationTimeoutMillis) { withClient { previousClient = it; validate(it) } }
            } catch (e: BiliLoginException) {
                recoverSession()
                if (synchronized(clientLock) { client === previousClient }) throw e
                withTimeout(validationTimeoutMillis) { withClient { validate(it) } }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            PublisherLoginResult(PublisherLoginStatus.FAILED, "Bilibili 登录状态检查超时，请稍后重试")
        } catch (e: BiliLoginException) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "Bilibili 未登录",
            )
        } catch (e: Exception) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = safeLoginFailure(e),
            )
        } finally {
            applyRequestDelay()
        }
        return result.withRenewalWarning(exportSession())
    }

    override suspend fun importCookiesJson(cookiesJson: String) {
        val version = beginCredentialUpdate()
        val candidate = sessionClientFactory()
        try {
            importBilibiliCookies(candidate, cookiesJson)
            if (!install(candidate, version, BilibiliStoredSession())) candidate.close()
        } catch (e: Exception) {
            candidate.close()
            throw e
        } finally {
            endCredentialUpdate(version)
        }
    }

    override fun exportCookiesJson(): String {
        return synchronized(clientLock) { exportBilibiliCookies(client) }
    }

    override fun importSession(state: BilibiliStoredSession) {
        synchronized(clientLock) {
            session = state.takeIf { it.credentialDigest == bilibiliCredentialDigest(exportBilibiliCookies(client)) }
                ?: BilibiliStoredSession()
            lastRefreshAttemptAt = Long.MIN_VALUE
        }
    }

    override fun exportSession(): BilibiliStoredSession = synchronized(clientLock) {
        session.copy(credentialDigest = bilibiliCredentialDigest(exportBilibiliCookies(client)))
    }

    override fun exportSessionSnapshot(): BilibiliSessionSnapshot = synchronized(clientLock) {
        val cookies = exportBilibiliCookies(client)
        BilibiliSessionSnapshot(cookies, session.copy(credentialDigest = bilibiliCredentialDigest(cookies)))
    }

    override suspend fun confirmSessionSaved(state: BilibiliStoredSession) {
        refreshMutex.withLock {
            val (version, current, cookies) = synchronized(clientLock) {
                Triple(generation.get(), session, exportBilibiliCookies(client))
            }
            if (current.pendingConfirmationToken.isBlank() || current.nextAttemptAtMillis > clockMillis() ||
                current != state.copy(credentialDigest = current.credentialDigest) ||
                state.credentialDigest != bilibiliCredentialDigest(cookies)) return@withLock
            val next = try {
                withClient(version) { sessionProtocol.confirm(it, current.pendingConfirmationToken) }
                current.copy(pendingConfirmationToken = "", failureCount = 0, nextAttemptAtMillis = 0)
            } catch (_: BilibiliLoginReplacedException) {
                return@withLock
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val failures = (current.failureCount + 1).coerceAtMost(4)
                val requiresLogin = e is BiliLoginException ||
                    (e is BilibiliSessionException && e.requiresLogin) || failures >= 4
                val reason = if (requiresLogin) "旧凭据确认失败，自动续期已停止，请重新扫码登录" else ""
                current.copy(
                    pendingConfirmationToken = if (requiresLogin) "" else current.pendingConfirmationToken,
                    failureCount = failures,
                    nextAttemptAtMillis = if (requiresLogin) 0 else clockMillis() +
                        listOf(5 * 60_000L, 30 * 60_000L, 6 * 60 * 60_000L)[failures - 1],
                    requiresLogin = requiresLogin,
                    renewalFailureReason = reason,
                )
            }
            synchronized(clientLock) {
                if (generation.get() == version && session == current) {
                    session = next
                    if (next.requiresLogin) {
                        sessionLogger.warn { "Bilibili ${next.renewalFailureReason}；当前会话可用时仍会继续使用" }
                    } else if (next.failureCount == 1) {
                        sessionLogger.warn { "Bilibili 新凭据已保存，旧凭据确认暂未完成，将退避重试" }
                    }
                }
            }
        }
    }

    override suspend fun persistSession(save: (BilibiliSessionSnapshot) -> Boolean): Boolean = persistenceMutex.withLock {
        val snapshot = exportSessionSnapshot()
        if (!save(snapshot)) return@withLock false
        if (snapshot.state.pendingConfirmationToken.isBlank()) return@withLock true
        confirmSessionSaved(snapshot.state)
        save(exportSessionSnapshot())
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "Cookie 不能为空")
        }

        return importAndVerifyCookies { candidate ->
            if (trimmed.startsWith("[")) {
                importBilibiliCookies(candidate, trimmed)
            } else {
                val cookies = parseCookieHeader(trimmed)
                if (cookies.isEmpty()) {
                    throw IllegalArgumentException("未找到有效的 Cookie 键值对")
                }
                candidate.importEditCookies(cookies.map { it.copy(domain = it.domain.removePrefix(".").lowercase()) }, true)
            }
        }
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        val version = beginCredentialUpdate()
        val gateway = qrLoginGatewayFactory()
        return try {
            withTimeout(loginTimeoutMillis) {
                val outcome = gateway.loginByQrCode(
                    onQrCode = { qrCodeData -> onQrCode(qrCodeData.toChallenge()) },
                    onStatusChanged = { qrCodeResult ->
                        if (generation.get() != version) throw BilibiliLoginReplacedException()
                        onStatusChanged(if (qrCodeResult.status == QrCodeLoginStatus.SUCCESS) {
                            PublisherLoginResult(PublisherLoginStatus.PENDING, "扫码已确认，正在验证登录")
                        } else qrCodeResult.toPublisherLoginResult())
                    },
                )
                if (outcome.result.status == QrCodeLoginStatus.SUCCESS) {
                    importAndVerifyCookies(version, outcome.result.refreshToken) { candidate ->
                        importBilibiliCookies(candidate, outcome.cookiesJson)
                    }
                } else {
                    outcome.result.toPublisherLoginResult()
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return PublisherLoginResult(PublisherLoginStatus.EXPIRED, "二维码登录或账号校验超时，请重试")
        } catch (_: BilibiliLoginReplacedException) {
            PublisherLoginResult(PublisherLoginStatus.CANCELED, "登录操作已被更新的凭据替换")
        } catch (e: Exception) {
            PublisherLoginResult(PublisherLoginStatus.FAILED, safeLoginFailure(e))
        } finally {
            runCatching { gateway.close() }
            endCredentialUpdate(version)
        }
    }

    private suspend fun applyRequestDelay() {
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
    }

    private suspend fun <T> callWithRequestDelay(retryAfterLogin: Boolean = true, block: suspend (BiliClient) -> T): T {
        var previousClient: BiliClient? = null
        return try {
            try { withClient { previousClient = it; block(it) } }
            catch (e: BiliLoginException) {
                if (!retryAfterLogin) throw e
                recoverSession()
                if (synchronized(clientLock) { client === previousClient }) throw e
                withClient(block = block)
            }
        } finally {
            applyRequestDelay()
        }
    }

    private suspend fun <T> withClient(expectedVersion: Long? = null, block: suspend (BiliClient) -> T): T {
        val acquired = synchronized(clientLock) {
            check(!closed) { "Bilibili 客户端已关闭" }
            if (expectedVersion != null && generation.get() != expectedVersion) throw BilibiliLoginReplacedException()
            client.also { users[it] = (users[it] ?: 0) + 1 }
        }
        return try { block(acquired) } finally {
            synchronized(clientLock) {
                val remaining = users.getValue(acquired) - 1
                if (remaining == 0) {
                    users.remove(acquired)
                    if (retired.remove(acquired)) acquired.close()
                } else users[acquired] = remaining
            }
        }
    }

    private fun retire(previous: BiliClient) {
        if ((users[previous] ?: 0) == 0) previous.close() else retired.add(previous)
    }

    private fun beginCredentialUpdate(): Long = synchronized(clientLock) {
        check(!closed) { "Bilibili 客户端已关闭" }
        generation.incrementAndGet().also { credentialUpdateVersion = it }
    }

    private fun endCredentialUpdate(version: Long) = synchronized(clientLock) {
        if (credentialUpdateVersion == version) credentialUpdateVersion = null
    }

    private suspend fun recoverSession() {
        try {
            refreshSession(force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionLogger.warn { "Bilibili 登录恢复未完成：${safeLoginFailure(e)}" }
        }
    }

    private fun install(candidate: BiliClient, version: Long, state: BilibiliStoredSession): Boolean = synchronized(clientLock) {
        if (closed || !generation.compareAndSet(version, version + 1)) return@synchronized false
        val previous = client
        client = candidate
        session = state
        lastRefreshAttemptAt = Long.MIN_VALUE
        retire(previous)
        true
    }

    private suspend fun validate(candidate: BiliClient): PublisherLoginResult {
        val testProvider = currentUserNavProvider ?: return sessionProtocol.validate(candidate)
        val nav = testProvider()
        if (nav.mid <= 0) throw BiliLoginException("Bilibili 未登录")
        return PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功", PublisherLoginAccount(
            userId = nav.mid.toString(), name = nav.name,
            avatar = nav.face.url.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.AVATAR) },
        ))
    }

    private suspend fun refreshSession(force: Boolean) = refreshMutex.withLock {
        val now = clockMillis()
        val (version, previous, cookies) = synchronized(clientLock) {
            val current = session
            if (closed || credentialUpdateVersion != null || current.refreshToken.isBlank() || current.requiresLogin || current.pendingConfirmationToken.isNotBlank() ||
                (lastRefreshAttemptAt != Long.MIN_VALUE && now - lastRefreshAttemptAt < 5 * 60_000L) ||
                current.nextAttemptAtMillis > now || (!force && now - current.lastCheckAtMillis < 24 * 60 * 60_000L)) return@withLock
            lastRefreshAttemptAt = now
            Triple(generation.get(), current, exportBilibiliCookies(client))
        }
        val candidate = sessionClientFactory()
        var installed = false
        try {
            withTimeout(60_000) {
                importBilibiliCookies(candidate, cookies)
                val info = sessionProtocol.refreshInfo(candidate)
                if (!info.refresh) {
                    synchronized(clientLock) {
                        if (generation.get() == version) session = previous.copy(lastCheckAtMillis = now, failureCount = 0, nextAttemptAtMillis = 0)
                    }
                    return@withTimeout
                }
                val token = sessionProtocol.refresh(candidate, previous.refreshToken, info.timestamp)
                importBilibiliCookies(candidate, exportBilibiliCookies(candidate))
                val verified = validate(candidate)
                if (verified.account?.userId != previous.accountId || previous.accountId.isBlank()) {
                    throw BilibiliSessionException("Bilibili 续期账号不一致，请重新扫码登录", requiresLogin = true)
                }
                installed = install(candidate, version, BilibiliStoredSession(
                    accountId = previous.accountId, refreshToken = token,
                    pendingConfirmationToken = previous.refreshToken, lastCheckAtMillis = now,
                ))
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(clientLock) {
                if (generation.get() == version) {
                    val failures = (previous.failureCount + 1).coerceAtMost(3)
                    session = previous.copy(
                        failureCount = failures,
                        nextAttemptAtMillis = now + listOf(5 * 60_000L, 30 * 60_000L, 6 * 60 * 60_000L)[failures - 1],
                        requiresLogin = e is BilibiliSessionException && e.requiresLogin,
                        renewalFailureReason = if (e is BilibiliSessionException && e.requiresLogin) e.reason else "",
                    )
                }
            }
            throw BilibiliSessionException(safeLoginFailure(e))
        } finally {
            if (!installed) candidate.close()
        }
    }

    private fun safeLoginFailure(error: Exception): String = when (error) {
        is BilibiliSessionException -> error.reason
        is BiliLoginException -> "Bilibili 未登录"
        is CancellationException -> "Bilibili 登录请求超时，请稍后重试"
        else -> "Bilibili 登录请求失败（${error.javaClass.simpleName}），请稍后重试"
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
            play = stat?.view?.takeIf { it > 0 }?.toLong(),
            danmaku = stat?.danmaku?.takeIf { it > 0 }?.toLong(),
            like = stat?.like?.takeIf { it > 0 }?.toLong(),
            comment = stat?.reply?.takeIf { it > 0 }?.toLong(),
            favorite = stat?.favorite?.takeIf { it > 0 }?.toLong(),
            coin = stat?.coin?.takeIf { it > 0 }?.toLong(),
            share = stat?.share?.takeIf { it > 0 }?.toLong(),
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

    private suspend fun importAndVerifyCookies(
        version: Long = beginCredentialUpdate(),
        refreshToken: String = "",
        importer: suspend (BiliClient) -> Unit,
    ): PublisherLoginResult {
        val candidate = sessionClientFactory()
        var installed = false
        return try {
            withTimeout(validationTimeoutMillis) {
                importer(candidate)
                val result = validate(candidate)
                currentCoroutineContext().ensureActive()
                installed = install(candidate, version, BilibiliStoredSession(
                    accountId = result.account?.userId.orEmpty(), refreshToken = refreshToken,
                    lastCheckAtMillis = clockMillis(),
                ))
                if (!installed) PublisherLoginResult(PublisherLoginStatus.CANCELED, "登录操作已被更新的凭据替换")
                else result.copy(message = if (refreshToken.isBlank()) "登录成功；未提供长期令牌，自动续期不可用" else "登录成功")
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            PublisherLoginResult(PublisherLoginStatus.FAILED, "Bilibili 账号校验超时，请稍后重试")
        } catch (e: Exception) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = safeLoginFailure(e),
            )
        } finally {
            if (!installed) candidate.close()
            endCredentialUpdate(version)
        }
    }

    private class BilibiliLoginReplacedException : IllegalStateException()

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
