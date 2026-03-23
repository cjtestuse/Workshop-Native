package com.slay.workshopnative.core.storage

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopExportPlanningTest {
    @Test
    fun deduplicatesSanitizedFileNamesWithinSameDirectory() {
        val root = Files.createTempDirectory("export_plan_collision_").toFile()
        root.resolve("a?b.txt").writeText("first")
        root.resolve("a*b.txt").writeText("second")

        val plan = buildWorkshopExportPlan(root)
        val relativePaths = plan.files.map { it.relativePath }.sorted()

        assertEquals(2, relativePaths.distinct().size)
        assertEquals(listOf("a b.txt", "a b_2.txt"), relativePaths)
        assertEquals(1, plan.renamedCount)
    }

    @Test
    fun resolvesFileAndDirectoryPrefixConflictsAfterSanitization() {
        val root = Files.createTempDirectory("export_plan_prefix_").toFile()
        root.resolve("a*b").writeText("file")
        root.resolve("a?b").mkdirs()
        root.resolve("a?b/file.txt").writeText("nested")

        val plan = buildWorkshopExportPlan(root)
        val relativePaths = plan.files.map { it.relativePath }.sorted()

        assertTrue(relativePaths.contains("a b"))
        assertTrue(relativePaths.contains("a b_2/file.txt"))
        assertEquals(1, plan.renamedCount)
    }
}
