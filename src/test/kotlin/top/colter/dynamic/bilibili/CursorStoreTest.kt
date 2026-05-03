package top.colter.dynamic.bilibili

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CursorStoreTest {

    @Test
    fun shouldPersistAndDeduplicate() {
        val tempDir = createTempDirectory("bili-cursor-test").toFile()
        val path = tempDir.resolve("cursor.yml").path
        val store = CursorStore(path)

        assertFalse(store.hasSeen("1", "d1"))
        store.markSeen("1", "d1", 1L)
        store.markSeen("1", "d2", 2L)

        assertTrue(store.hasSeen("1", "d1"))
        assertTrue(store.hasSeen("1", "d2"))

        val reloaded = CursorStore(path)
        val state = reloaded.get("1")
        assertNotNull(state)
        assertEquals("d2", state.lastSeenDynamicId)
        assertTrue(reloaded.hasSeen("1", "d1"))
    }
}
