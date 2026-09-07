package io.legado.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XuanjuanUpdateLogContractTest {

    @Test
    fun `packaged update log belongs to Xuanjuan and matches the app version`() {
        val updateLog = projectFile("app/src/main/assets/updateLog.md")
        val buildGradle = projectFile("app/build.gradle")

        val currentVersion = buildGradle
            .substringAfter("System.getenv(\"NOVEL_VERSION_NAME\")")
            .let { Regex("""\?:\s*\"([^\"]+)\"""").find(it)?.groupValues?.get(1) }
            ?: error("Default NOVEL_VERSION_NAME not found")
        val firstLoggedVersion = Regex("""(?m)^## v(\d+\.\d+\.\d+)\s*$""")
            .find(updateLog)
            ?.groupValues
            ?.get(1)
            ?: error("Xuanjuan update log version heading not found")

        assertTrue(updateLog.startsWith("# 玄卷更新日志"))
        assertEquals(currentVersion, firstLoggedVersion)
        assertTrue(updateLog.contains("新增 24 个社区书源"))
        assertTrue(updateLog.contains("内置书源总数由 13 个扩充到 37 个"))
        assertFalse(updateLog.contains("LegadoTeam/legado"))
        assertFalse(updateLog.contains("cronet版本"))
        assertFalse(updateLog.contains("**2026/07/"))
    }

    @Test
    fun `upgrade dialog and about page share the Xuanjuan update log asset`() {
        val mainActivity = projectFile(
            "app/src/main/java/io/legado/app/ui/main/MainActivity.kt"
        )
        val aboutFragment = projectFile(
            "app/src/main/java/io/legado/app/ui/about/AboutFragment.kt"
        )

        assertTrue(mainActivity.contains("assets.open(\"updateLog.md\")"))
        assertTrue(
            aboutFragment.contains(
                "showMdFile(getString(R.string.update_log), \"updateLog.md\")"
            )
        )
    }

    private fun projectFile(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
