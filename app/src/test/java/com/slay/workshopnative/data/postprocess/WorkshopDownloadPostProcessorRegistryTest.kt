package com.slay.workshopnative.data.postprocess

import com.slay.workshopnative.data.preferences.UserPreferences
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDownloadPostProcessorRegistryTest {
    @Test
    fun wallpaperEngineProcessorExtractsPkgContentsWhenEnabled() = runBlocking {
        val tempRoot = Files.createTempDirectory("we_pkg_test_").toFile()
        val inputRoot = File(tempRoot, "Wallpaper Test").apply { mkdirs() }
        val pkgFile = File(inputRoot, "scene.pkg")
        writePkg(
            target = pkgFile,
            entries = listOf(
                "scene.json" to """{"title":"demo"}""".toByteArray(),
                "scripts/main.js" to "console.log('demo');".toByteArray(),
            ),
        )

        val registry = WorkshopDownloadPostProcessorRegistry()
        val selection = registry.resolveSelection(
            appId = WorkshopDownloadPostProcessorRegistry.WALLPAPER_ENGINE_APP_ID,
            prefs = UserPreferences(
                wallpaperEnginePkgExtractEnabled = true,
                wallpaperEnginePkgKeepOriginal = false,
            ),
        )
        assertNotNull(selection)

        val result = requireNotNull(selection).processor.process(
            inputRoot = inputRoot,
            outputBaseName = "Wallpaper Demo",
            config = requireNotNull(selection).config,
            onPhaseChanged = {},
        )

        assertTrue(result.artifact.isDirectory)
        assertEquals("""{"title":"demo"}""", File(result.artifact, "scene/scene.json").readText())
        assertEquals("console.log('demo');", File(result.artifact, "scene/scripts/main.js").readText())
        assertEquals("已提取 1 个 PKG", result.summary)
    }

    @Test
    fun wallpaperEngineProcessorReturnsOriginalDirectoryWhenNoPkgExists() = runBlocking {
        val tempRoot = Files.createTempDirectory("we_pkg_noop_").toFile()
        val inputRoot = File(tempRoot, "Wallpaper Test").apply { mkdirs() }
        File(inputRoot, "preview.jpg").writeText("not-a-pkg")

        val registry = WorkshopDownloadPostProcessorRegistry()
        val selection = registry.resolveSelection(
            appId = WorkshopDownloadPostProcessorRegistry.WALLPAPER_ENGINE_APP_ID,
            prefs = UserPreferences(
                wallpaperEnginePkgExtractEnabled = true,
                wallpaperEnginePkgKeepOriginal = true,
            ),
        )
        assertNotNull(selection)

        val result = requireNotNull(selection).processor.process(
            inputRoot = inputRoot,
            outputBaseName = "Wallpaper Demo",
            config = requireNotNull(selection).config,
            onPhaseChanged = {},
        )

        assertEquals(inputRoot.absolutePath, result.artifact.absolutePath)
        assertEquals("未发现 PKG 文件，已保留原始结果", result.summary)
    }

    @Test
    fun wallpaperEngineProcessorConvertsSupportedTexFilesWhenEnabled() = runBlocking {
        val tempRoot = Files.createTempDirectory("we_pkg_tex_").toFile()
        val inputRoot = File(tempRoot, "Wallpaper Test").apply { mkdirs() }
        val pkgFile = File(inputRoot, "scene.pkg")
        writePkg(
            target = pkgFile,
            entries = listOf(
                "scene.tex" to buildTex(
                    formatId = 9,
                    width = 1,
                    height = 1,
                    payload = byteArrayOf(0x55),
                ),
            ),
        )

        val registry = WorkshopDownloadPostProcessorRegistry()
        val selection = registry.resolveSelection(
            appId = WorkshopDownloadPostProcessorRegistry.WALLPAPER_ENGINE_APP_ID,
            prefs = UserPreferences(
                wallpaperEnginePkgExtractEnabled = true,
                wallpaperEnginePkgKeepOriginal = false,
                wallpaperEngineTexConversionEnabled = true,
                wallpaperEngineKeepConvertedTexOriginal = false,
            ),
        )
        assertNotNull(selection)

        val result = requireNotNull(selection).processor.process(
            inputRoot = inputRoot,
            outputBaseName = "Wallpaper Demo",
            config = selection.config,
            onPhaseChanged = {},
        )

        assertTrue(File(result.artifact, "scene/scene.png").isFile)
        assertTrue(!File(result.artifact, "scene/scene.tex").exists())
        assertEquals("已提取 1 个 PKG；TEX 已转换 1 项", result.summary)
    }

    @Test
    fun wallpaperEngineProcessorSkipsCompressedRawTexAndReportsSummary() = runBlocking {
        val tempRoot = Files.createTempDirectory("we_pkg_tex_skip_").toFile()
        val inputRoot = File(tempRoot, "Wallpaper Test").apply { mkdirs() }
        val pkgFile = File(inputRoot, "scene.pkg")
        writePkg(
            target = pkgFile,
            entries = listOf(
                "scene.tex" to buildTex(
                    formatId = 0,
                    width = 1,
                    height = 1,
                    payload = byteArrayOf(1, 2, 3, 4),
                    lz4Flag = 1,
                    decompressedSize = 4,
                ),
            ),
        )

        val selection = requireNotNull(
            WorkshopDownloadPostProcessorRegistry().resolveSelection(
                appId = WorkshopDownloadPostProcessorRegistry.WALLPAPER_ENGINE_APP_ID,
                prefs = UserPreferences(
                    wallpaperEnginePkgExtractEnabled = true,
                    wallpaperEnginePkgKeepOriginal = false,
                    wallpaperEngineTexConversionEnabled = true,
                    wallpaperEngineKeepConvertedTexOriginal = true,
                ),
            ),
        )

        val result = selection.processor.process(
            inputRoot = inputRoot,
            outputBaseName = "Wallpaper Demo",
            config = selection.config,
            onPhaseChanged = {},
        )

        assertTrue(File(result.artifact, "scene/scene.tex").isFile)
        assertNull(File(result.artifact, "scene/scene.mp4").takeIf { it.exists() })
        assertEquals("已提取 1 个 PKG；保留原 TEX 1 项", result.summary)
    }

    private fun writePkg(
        target: File,
        entries: List<Pair<String, ByteArray>>,
    ) {
        val version = "PKGV0022".toByteArray()
        val entryMetadata = entries.map { (path, payload) ->
            val pathBytes = path.toByteArray()
            Triple(pathBytes, payload, payload.size)
        }
        var runningOffset = 0
        val offsets = entryMetadata.map { (_, _, size) ->
            val current = runningOffset
            runningOffset += size
            current
        }

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(Integer.reverseBytes(version.size))
            stream.write(version)
            stream.writeInt(Integer.reverseBytes(entries.size))
            entryMetadata.forEachIndexed { index, (pathBytes, _, size) ->
                stream.writeInt(Integer.reverseBytes(pathBytes.size))
                stream.write(pathBytes)
                stream.writeInt(Integer.reverseBytes(offsets[index]))
                stream.writeInt(Integer.reverseBytes(size))
            }
            entryMetadata.forEach { (_, payload, _) ->
                stream.write(payload)
            }
        }
        target.writeBytes(output.toByteArray())
    }

    private fun buildTex(
        formatId: Int,
        width: Int,
        height: Int,
        payload: ByteArray,
        lz4Flag: Int = 0,
        decompressedSize: Int = 0,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.write("TEXV0005".toByteArray())
            stream.writeByte(0)
            stream.write("TEXI0001".toByteArray())
            stream.writeByte(0)
            stream.writeInt(Integer.reverseBytes(formatId))
            stream.writeInt(0)
            stream.writeInt(Integer.reverseBytes(width))
            stream.writeInt(Integer.reverseBytes(height))
            repeat(3) { stream.writeInt(0) }
            stream.write("TEXB0004".toByteArray())
            stream.writeByte(0)
            stream.writeInt(Integer.reverseBytes(1))
            stream.writeInt(0)
            stream.writeInt(0)
            stream.writeInt(Integer.reverseBytes(1))
            stream.write(ByteArray(8))
            stream.writeInt(Integer.reverseBytes(lz4Flag))
            stream.writeInt(Integer.reverseBytes(decompressedSize))
            stream.writeInt(Integer.reverseBytes(payload.size))
            stream.write(payload)
        }
        return output.toByteArray()
    }
}
