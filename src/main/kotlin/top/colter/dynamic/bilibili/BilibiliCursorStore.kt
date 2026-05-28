package top.colter.dynamic.bilibili

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.repository.PublisherCursorRepository
import top.colter.dynamic.core.repository.PublisherLiveStatusRepository

internal interface BilibiliCursorStore {
    fun get(publisherId: Int): PublisherCursor?

    fun ensureBaseline(publisherId: Int, timestamp: Long): PublisherCursor

    fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): PublisherCursor
}

internal class DatabaseBilibiliCursorStore : BilibiliCursorStore {
    private val cache: MutableMap<Int, PublisherCursor> = ConcurrentHashMap()

    override fun get(publisherId: Int): PublisherCursor? {
        cache[publisherId]?.let { return it }
        return PublisherCursorRepository.findByPublisherId(publisherId)?.also { cache[publisherId] = it }
    }

    override fun ensureBaseline(publisherId: Int, timestamp: Long): PublisherCursor {
        val updated = PublisherCursorRepository.ensureBaseline(publisherId, timestamp)
        cache[publisherId] = updated
        return updated
    }

    override fun markSeen(publisherId: Int, dynamicId: String, timestamp: Long): PublisherCursor {
        val updated = PublisherCursorRepository.markSeen(publisherId, dynamicId, timestamp)
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
        return PublisherLiveStatusRepository.findByPublisherId(publisherId)?.also { cache[publisherId] = it }
    }

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        val updated = PublisherLiveStatusRepository.upsert(state)
        cache[state.publisherId] = updated
        return updated
    }
}
