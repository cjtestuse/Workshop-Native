package com.slay.workshopnative.data.postprocess

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WallpaperEngineTexConverterTest {
    @Test
    fun convertsR8TexToGrayscalePng() {
        val texBytes = buildTex(
            formatId = 9,
            width = 2,
            height = 1,
            payload = byteArrayOf(0x11, 0x7F),
        )

        val result = WallpaperEngineTexConverter.convert(texBytes)

        assertNotNull(result)
        assertEquals("png", result?.extension)
        val rgba = decodeSimpleRgbaPng(requireNotNull(result).bytes, width = 2, height = 1)
        assertEquals(listOf(0x11, 0x11, 0x11, 0xFF, 0x7F, 0x7F, 0x7F, 0xFF), rgba)
    }

    @Test
    fun convertsRg88TexToPngWithAlpha() {
        val texBytes = buildTex(
            formatId = 8,
            width = 1,
            height = 1,
            payload = byteArrayOf(0x33, 0x99.toByte()),
        )

        val result = WallpaperEngineTexConverter.convert(texBytes)

        assertNotNull(result)
        assertEquals("png", result?.extension)
        val rgba = decodeSimpleRgbaPng(requireNotNull(result).bytes, width = 1, height = 1)
        assertEquals(listOf(0x33, 0x33, 0x33, 0x99), rgba)
    }

    @Test
    fun passesThroughRawPngPayload() {
        val pngBytes = encodeReferencePng(
            width = 1,
            height = 1,
            rgba = byteArrayOf(0x12, 0x34, 0x56, 0x78),
        )
        val texBytes = buildTex(
            formatId = 0,
            width = 1,
            height = 1,
            payload = pngBytes,
        )

        val result = WallpaperEngineTexConverter.convert(texBytes)

        assertNotNull(result)
        assertEquals("png", result?.extension)
        assertEquals(pngBytes.toList(), result?.bytes?.toList())
    }

    @Test
    fun rawMp4FastPathInspectorRecognizesAndCopiesPayload() {
        val mp4Bytes = byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x18,
            0x66,
            0x74,
            0x79,
            0x70,
            0x6D,
            0x70,
            0x34,
            0x32,
        )
        val texBytes = buildTex(
            formatId = 0,
            width = 1,
            height = 1,
            payload = mp4Bytes,
        )
        val tempDir = Files.createTempDirectory("we_tex_fast_").toFile()
        val texFile = tempDir.resolve("clip.tex").apply { writeBytes(texBytes) }
        val outputFile = tempDir.resolve("clip.mp4")

        val fastPath = WallpaperEngineTexFastPathInspector.inspect(texFile)

        assertNotNull(fastPath)
        assertEquals("mp4", fastPath?.extension)
        WallpaperEngineTexFastPathInspector.copyPayload(
            sourceFile = texFile,
            targetFile = outputFile,
            fastPath = requireNotNull(fastPath),
        )
        assertEquals(mp4Bytes.toList(), outputFile.readBytes().toList())
    }

    private fun buildTex(
        formatId: Int,
        width: Int,
        height: Int,
        payload: ByteArray,
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
            stream.writeInt(0)
            stream.writeInt(0)
            stream.writeInt(Integer.reverseBytes(payload.size))
            stream.write(payload)
        }
        return output.toByteArray()
    }

    private fun decodeSimpleRgbaPng(
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): List<Int> {
        val pngSignature = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        assertEquals(pngSignature.toList(), bytes.copyOfRange(0, 8).toList())

        var offset = 8
        val idat = ByteArrayOutputStream()
        while (offset < bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            offset += 4
            val type = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            offset += 4
            val data = bytes.copyOfRange(offset, offset + length)
            offset += length
            offset += 4 // crc
            if (type == "IDAT") {
                idat.write(data)
            }
            if (type == "IEND") {
                break
            }
        }

        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val decompressed = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count <= 0) break
            decompressed.write(buffer, 0, count)
        }
        inflater.end()

        val scanlines = decompressed.toByteArray()
        val expectedSize = height * (1 + width * 4)
        assertEquals(expectedSize, scanlines.size)
        val rgba = ArrayList<Int>(width * height * 4)
        var cursor = 0
        repeat(height) {
            assertEquals(0, scanlines[cursor++].toInt() and 0xFF)
            repeat(width * 4) {
                rgba += scanlines[cursor++].toInt() and 0xFF
            }
        }
        return rgba
    }

    private fun encodeReferencePng(
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ByteArray {
        val scanlines = ByteArrayOutputStream()
        repeat(height) { row ->
            scanlines.write(0)
            val offset = row * width * 4
            scanlines.write(rgba, offset, width * 4)
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(scanlines.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            compressed.write(buffer, 0, count)
        }
        deflater.end()

        return ByteArrayOutputStream().use { output ->
            output.write(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                ),
            )
            writeChunk(output, "IHDR", ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(width)
                putInt(height)
                put(8)
                put(6)
                put(0)
                put(0)
                put(0)
            }.array())
            writeChunk(output, "IDAT", compressed.toByteArray())
            writeChunk(output, "IEND", ByteArray(0))
            output.toByteArray()
        }
    }

    private fun writeChunk(
        output: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        output.write(typeBytes)
        output.write(data)
        val crc = java.util.zip.CRC32().apply {
            update(typeBytes)
            update(data)
        }
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }
}
