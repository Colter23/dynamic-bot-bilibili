package top.colter.dynamic.bilibili

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginBoundaryTest {
    @Test
    fun `main sources should not import core repositories`() {
        val root = Path.of("src/main/kotlin")
        val offenders = Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains("top.colter.dynamic.core.repository") }
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }

        assertEquals(emptyList(), offenders)
    }
}
