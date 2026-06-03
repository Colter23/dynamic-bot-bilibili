package top.colter.dynamic.bilibili

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.plugin.SourceStateStore

internal const val BILIBILI_DYNAMIC_FEED_KEY: String = "dynamic-feed"
internal const val BILIBILI_LIVE_SOURCE_KEY: String = "live-status"

internal interface BilibiliCursorStore {
    fun get(publisherId: Int): SourceCursor?

    fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor

    fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): SourceCursor

    fun evict(publisherId: Int)
}

internal class SourceStateBilibiliCursorStore(
    private val sourceStateStore: SourceStateStore,
) : BilibiliCursorStore {
    private val cache: MutableMap<Int, SourceCursor> = ConcurrentHashMap()

    override fun get(publisherId: Int): SourceCursor? {
        cache[publisherId]?.let { return it }
        return sourceStateStore
            .findCursor(publisherId, BILIBILI_DYNAMIC_FEED_KEY, SourceEventType.DYNAMIC_CREATED)
            ?.also { cache[publisherId] = it }
    }

    override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
        val updated = sourceStateStore.ensureCursorBaseline(
            publisherId = publisherId,
            sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): SourceCursor {
        val updated = sourceStateStore.markCursorSeen(
            publisherId = publisherId,
            sourceKey = BILIBILI_DYNAMIC_FEED_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            updateKey = dynamicId,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun evict(publisherId: Int) {
        cache.remove(publisherId)
    }
}

internal interface BilibiliLiveStatusStore {
    fun get(publisherId: Int): PublisherLiveStatus?

    fun save(state: PublisherLiveStatus): PublisherLiveStatus

    fun evict(publisherId: Int)
}

internal class SourceStateBilibiliLiveStatusStore(
    private val sourceStateStore: SourceStateStore,
) : BilibiliLiveStatusStore {
    private val cache: MutableMap<Int, PublisherLiveStatus> = ConcurrentHashMap()

    override fun get(publisherId: Int): PublisherLiveStatus? {
        cache[publisherId]?.let { return it }
        return sourceStateStore
            .findLatestLiveStatus(publisherId)
            ?.also { cache[publisherId] = it }
    }

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        val updated = sourceStateStore.saveLiveStatus(state)
        cache[state.publisherId] = updated
        return updated
    }

    override fun evict(publisherId: Int) {
        cache.remove(publisherId)
    }
}
