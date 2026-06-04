package top.colter.dynamic.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.colter.dynamic.core.plugin.FollowActionResult
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.tools.loggerFor

private val followLogger = loggerFor<BilibiliFollowService>()

internal class BilibiliFollowService(
    private val configProvider: () -> BilibiliPublisherConfig,
    private val gatewayProvider: () -> BilibiliPlatformGateway,
    private val requestFailureHandler: BilibiliRequestFailureHandler,
) {
    private val followGroupMutex: Mutex = Mutex()
    private var followGroupId: Long? = null

    private val config: BilibiliPublisherConfig
        get() = configProvider()

    private val gateway: BilibiliPlatformGateway
        get() = gatewayProvider()

    fun resetFollowGroupCache() {
        followGroupId = null
    }

    suspend fun queryFollowState(userId: String): FollowState {
        return requestFailureHandler.run("关注状态查询 uid=$userId") {
            gateway.queryFollowState(userId)
        }.getOrElse { FollowState.UNSUPPORTED }
    }

    suspend fun followPublisher(userId: String): FollowActionResult {
        val result = requestFailureHandler.run("关注发布者 uid=$userId") {
            gateway.followPublisher(userId)
        }.getOrElse { error ->
            return FollowActionResult(
                FollowActionStatus.FAILED,
                error.message ?: "Bilibili 关注失败",
            )
        }
        if (result.status == FollowActionStatus.FOLLOWED || result.status == FollowActionStatus.ALREADY_FOLLOWING) {
            addPublisherToFollowGroup(userId)
        }
        return result
    }

    suspend fun unfollowPublisher(userId: String): FollowActionResult {
        val groupId = findExistingFollowGroupId()
            ?: return skipAutoUnfollow(userId, "未配置或未找到 Bot 关注分组")

        val relation = requestFailureHandler.run("关注关系查询 uid=$userId") {
            gateway.fetchFollowRelation(userId)
        }.getOrNull() ?: return skipAutoUnfollow(userId, "关注关系查询失败")

        if (!relation.following) {
            return skipAutoUnfollow(userId, "当前账号未关注该 UP 主")
        }
        if (relation.tagIds != setOf(groupId)) {
            return skipAutoUnfollow(userId, "UP 主不只属于 Bot 关注分组：tagIds=${relation.tagIds}，botGroupId=$groupId")
        }

        val result = requestFailureHandler.run("取消关注发布者 uid=$userId") {
            gateway.unfollowPublisher(userId)
        }.getOrElse { error ->
            return FollowActionResult(
                FollowActionStatus.FAILED,
                error.message ?: "Bilibili 自动取消关注失败",
            )
        }

        if (result.status == FollowActionStatus.FOLLOWED) {
            followLogger.info {
                "Bilibili 自动取消关注完成：uid=$userId，groupId=$groupId"
            }
        } else {
            followLogger.warn {
                "Bilibili 自动取消关注未完成：uid=$userId，status=${result.status}，message=${result.message}"
            }
        }
        return result
    }

    suspend fun ensureFollowGroupInitialized() {
        ensureFollowGroupId()
    }

    private suspend fun addPublisherToFollowGroup(userId: String) {
        val groupId = ensureFollowGroupId() ?: return
        val uid = userId.toLongOrNull() ?: return
        requestFailureHandler.run("加入关注分组 uid=$userId groupId=$groupId") {
            gateway.addUsersToFollowGroup(listOf(uid), listOf(groupId))
        }.onFailure {
            followLogger.warn(it) {
                "加入 Bilibili 关注分组失败：uid=$userId，groupId=$groupId"
            }
        }
    }

    private suspend fun ensureFollowGroupId(): Long? {
        val groupName = normalizedFollowGroupName() ?: return null
        followGroupId?.let { return it }

        return followGroupMutex.withLock {
            followGroupId?.let { return@withLock it }
            val resolved = resolveFollowGroupId(groupName, createIfMissing = true)
            followGroupId = resolved
            resolved
        }
    }

    private suspend fun findExistingFollowGroupId(): Long? {
        val groupName = normalizedFollowGroupName() ?: return null
        followGroupId?.let { return it }

        return followGroupMutex.withLock {
            followGroupId?.let { return@withLock it }
            val resolved = resolveFollowGroupId(groupName, createIfMissing = false)
            followGroupId = resolved
            resolved
        }
    }

    private suspend fun resolveFollowGroupId(groupName: String, createIfMissing: Boolean): Long? {
        val existingGroups = requestFailureHandler.run("读取关注分组 name=$groupName") {
            gateway.fetchFollowGroups()
        }.getOrNull() ?: return null

        existingGroups.firstOrNull { it.name == groupName }?.let { matched ->
            followLogger.debug {
                "复用 Bilibili 关注分组：name=$groupName，groupId=${matched.tid}"
            }
            return matched.tid
        }

        if (!createIfMissing) {
            followLogger.info {
                "Bilibili 关注分组未找到，已跳过创建：name=$groupName"
            }
            return null
        }

        requestFailureHandler.run("创建关注分组 name=$groupName") {
            gateway.createFollowGroup(groupName)
        }.onFailure {
            followLogger.warn(it) {
                "创建 Bilibili 关注分组失败：name=$groupName"
            }
        }

        val refreshedGroups = requestFailureHandler.run("重新读取关注分组 name=$groupName") {
            gateway.fetchFollowGroups()
        }.getOrNull() ?: return null

        val createdGroup = refreshedGroups.firstOrNull { it.name == groupName }
        if (createdGroup == null) {
            followLogger.warn {
                "Bilibili 关注分组未找到：name=$groupName"
            }
            return null
        }

        followLogger.info {
            "Bilibili 关注分组已创建：name=$groupName，groupId=${createdGroup.tid}"
        }
        return createdGroup.tid
    }

    private fun normalizedFollowGroupName(): String? {
        return config.followGroupName.trim().takeIf { it.isNotBlank() }
    }

    private fun skipAutoUnfollow(userId: String, reason: String): FollowActionResult {
        followLogger.info {
            "Bilibili 自动取消关注已跳过：uid=$userId，原因=$reason"
        }
        return FollowActionResult(
            FollowActionStatus.FAILED,
            "已跳过自动取消关注：$reason",
        )
    }
}
