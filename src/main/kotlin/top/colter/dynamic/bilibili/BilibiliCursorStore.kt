package top.colter.dynamic.bilibili

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.PublisherCursor
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.repository.PublisherCursorRepository
import top.colter.dynamic.core.repository.PublisherLiveStatusRepository

internal interface BilibiliCursorStore {
    fun get(publisherId: String): PublisherCursor?

    fun ensureBaseline(publisherId: String, timestamp: Long): PublisherCursor

    fun markSeen(publisherId: String, dynamicId: String, timestamp: Long): PublisherCursor
}

internal class DatabaseBilibiliCursorStore : BilibiliCursorStore {
    private val cache: MutableMap<Int, PublisherCursor> = ConcurrentHashMap()

    override fun get(publisherId: String): PublisherCursor? {
        val id = publisherId.toIntOrNull() ?: return null
        cache[id]?.let { return it }
        return PublisherCursorRepository.findByPublisherId(id)?.also { cache[id] = it }
    }

    override fun ensureBaseline(publisherId: String, timestamp: Long): PublisherCursor {
        val id = publisherId.toIntOrNull() ?: error("invalid publisher id: $publisherId")
        val updated = PublisherCursorRepository.ensureBaseline(id, timestamp)
        cache[id] = updated
        return updated
    }

    override fun markSeen(publisherId: String, dynamicId: String, timestamp: Long): PublisherCursor {
        val id = publisherId.toIntOrNull() ?: error("invalid publisher id: $publisherId")
        val updated = PublisherCursorRepository.markSeen(id, dynamicId, timestamp)
        cache[id] = updated
        return updated
    }
}

internal interface BilibiliLiveStatusStore {
    fun get(publisherId: String): PublisherLiveStatus?

    fun save(state: PublisherLiveStatus): PublisherLiveStatus
}

internal class DatabaseBilibiliLiveStatusStore : BilibiliLiveStatusStore {
    private val cache: MutableMap<Int, PublisherLiveStatus> = ConcurrentHashMap()

    override fun get(publisherId: String): PublisherLiveStatus? {
        val id = publisherId.toIntOrNull() ?: return null
        cache[id]?.let { return it }
        return PublisherLiveStatusRepository.findByPublisherId(id)?.also { cache[id] = it }
    }

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        val updated = PublisherLiveStatusRepository.upsert(state)
        cache[state.publisherId] = updated
        return updated
    }
}
