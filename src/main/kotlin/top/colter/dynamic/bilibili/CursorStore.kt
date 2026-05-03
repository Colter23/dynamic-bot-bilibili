package top.colter.dynamic.bilibili

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class CursorStore(private val path: String) {
    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val file: File = File(path)
    private val lock: Any = Any()
    private val states: MutableMap<String, CursorState> = ConcurrentHashMap(load())

    fun get(publisherId: String): CursorState? = states[publisherId]

    fun hasSeen(publisherId: String, dynamicId: String): Boolean {
        val state = states[publisherId] ?: return false
        return state.lastSeenDynamicId == dynamicId || state.recentDynamicIds.contains(dynamicId)
    }

    fun markSeen(publisherId: String, dynamicId: String, timestamp: Long) {
        val previous = states[publisherId]
        val dedupe = LinkedHashSet(previous?.recentDynamicIds ?: emptyList())
        dedupe.add(dynamicId)
        while (dedupe.size > 50) {
            dedupe.remove(dedupe.first())
        }
        states[publisherId] = CursorState(
            lastSeenDynamicId = dynamicId,
            lastSeenAt = timestamp,
            recentDynamicIds = dedupe.toList(),
        )
        persist()
    }

    private fun load(): Map<String, CursorState> {
        if (!file.exists()) return emptyMap()
        return runCatching { objectMapper.readValue<Map<String, CursorState>>(file) }.getOrElse { emptyMap() }
    }

    private fun persist() {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            objectMapper.writeValue(file, states.toMap())
        }
    }
}

internal data class CursorState(
    val lastSeenDynamicId: String,
    val lastSeenAt: Long,
    val recentDynamicIds: List<String> = emptyList(),
)
