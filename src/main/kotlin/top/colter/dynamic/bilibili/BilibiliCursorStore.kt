package top.colter.dynamic.bilibili

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.repository.PublisherLiveStatusRepository
import top.colter.dynamic.core.repository.SourceCursorRepository

internal const val BILIBILI_DYNAMIC_SOURCE_KEY: String = "dynamic-feed"
internal const val BILIBILI_LIVE_SOURCE_KEY: String = "live-status"

internal interface BilibiliCursorStore {
    fun get(publisherId: Int): SourceCursor?

    fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor

    fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): SourceCursor
}

internal class DatabaseBilibiliCursorStore : BilibiliCursorStore {
    private val cache: MutableMap<Int, SourceCursor> = ConcurrentHashMap()

    override fun get(publisherId: Int): SourceCursor? {
        cache[publisherId]?.let { return it }
        return SourceCursorRepository
            .find(publisherId, BILIBILI_DYNAMIC_SOURCE_KEY, SourceEventType.DYNAMIC_CREATED)
            ?.also { cache[publisherId] = it }
    }

    override fun ensureBaseline(publisherId: Int, timestamp: Long): SourceCursor {
        val updated = SourceCursorRepository.ensureBaseline(
            publisherId = publisherId,
            sourceKey = BILIBILI_DYNAMIC_SOURCE_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }

    override fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): SourceCursor {
        val updated = SourceCursorRepository.markSeen(
            publisherId = publisherId,
            sourceKey = BILIBILI_DYNAMIC_SOURCE_KEY,
            eventType = SourceEventType.DYNAMIC_CREATED,
            updateKey = dynamicId,
            timestamp = timestamp,
        )
        cache[publisherId] = updated
        return updated
    }
}

internal interface BilibiliLiveStatusStore {
    fun get(publisherId: Int): PublisherLiveStatus?

    fun save(state: PublisherLiveStatus): PublisherLiveStatus
}

internal class DatabaseBilibiliLiveStatusStore : BilibiliLiveStatusStore {
    private val cache: MutableMap<Int, PublisherLiveStatus> = ConcurrentHashMap()

    override fun get(publisherId: Int): PublisherLiveStatus? {
        cache[publisherId]?.let { return it }
        return PublisherLiveStatusRepository
            .findByPublisherId(publisherId)
            .maxByOrNull { it.lastObservedAtEpochSeconds }
            ?.also { cache[publisherId] = it }
    }

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        val updated = PublisherLiveStatusRepository.upsert(state)
        cache[state.publisherId] = updated
        return updated
    }
}
