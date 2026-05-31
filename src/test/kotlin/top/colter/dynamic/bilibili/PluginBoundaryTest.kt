package top.colter.dynamic.bilibili

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PluginBoundaryTest {
    @Test
    fun `plugin sources should not import runtime or storage implementations`() {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))
        val forbiddenImports = listOf(
            "top.colter.dynamic.core." + "repository",
            "top.colter.dynamic.core." + "table",
            "top.colter.dynamic." + "repository",
            "top.colter.dynamic." + "table",
            "top.colter.dynamic." + "plugin",
            "top.colter.dynamic." + "event",
        )

        val offenders = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .flatMap { path ->
                        val content = Files.readString(path)
                        forbiddenImports
                            .filter { content.contains(it) }
                            .map { "${root.relativize(path)} -> $it" }
                            .stream()
                    }
                    .toList()
            }
        }
            .sorted()

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `bilibili draw assets should be packaged with plugin`() {
        val resources = listOf(
            "draw/bilibili/header/default.png",
            "draw/bilibili/logo/primary.png",
            "draw/bilibili/logo/wordmark.png",
            "draw/bilibili/avatar-badge/official-individual.svg",
            "draw/bilibili/avatar-badge/official-institution.svg",
        )

        resources.forEach { path ->
            assertNotNull(javaClass.classLoader.getResource(path), "缺少插件绘图资源：$path")
        }
    }
}
