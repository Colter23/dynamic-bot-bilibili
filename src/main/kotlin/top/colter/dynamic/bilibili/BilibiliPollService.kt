package top.colter.dynamic.bilibili

import kotlinx.coroutines.delay
import top.colter.bilibili.api.getNewDynamic
import top.colter.bilibili.api.getUserNewDynamic
import top.colter.bilibili.client.BiliClient
import top.colter.bilibili.data.dynamic.BiliDynamic

internal class BilibiliPollService(
    private val requestIntervalMs: Long,
    private val client: BiliClient = BiliClient(),
) {
    suspend fun fetchSubscribedLatest(limit: Int): List<BiliDynamic> {
        val list = client.getNewDynamic(0, "")
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
        return list.items.take(limit)
    }

    suspend fun fetchLatest(uid: String, limit: Int): List<BiliDynamic> {
        val userId = uid.toLongOrNull() ?: return emptyList()
        val list = client.getUserNewDynamic(userId, false, "")
        if (requestIntervalMs > 0) {
            delay(requestIntervalMs)
        }
        return list.items.take(limit)
    }
}
