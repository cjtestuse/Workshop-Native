package com.slay.workshopnative.data.postprocess

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.io.RandomAccessFile
import net.jpountz.lz4.LZ4Factory

internal data class WallpaperEngineTexConversionResult(
    val extension: String,
    val bytes: ByteArray,
)

internal data class WallpaperEngineTexFastPathCopy(
    val extension: String,
    val payloadOffset: Long,
    val payloadSize: Long,
)

internal data class WallpaperEngineTexHeaderInfo(
    val formatId: Long,
    val lz4Flag: Long,
    val payloadSize: Long,
) {
    val isCompressedRaw: Boolean
        get() = formatId == 0L && lz4Flag != 0L
}

internal object WallpaperEngineTexConverter {
    fun convert(texBytes: ByteArray): WallpaperEngineTexConversionResult? {
        val tex = WallpaperEngineTexParser.parse(texBytes) ?: return null
        return when (tex.contentType) {
            WallpaperEngineTexContentType.Png ->
                WallpaperEngineTexConversionResult("png", tex.payload)
            WallpaperEngineTexContentType.Jpg ->
                WallpaperEngineTexConversionResult("jpg", tex.payload)
            WallpaperEngineTexContentType.Mp4 ->
                WallpaperEngineTexConversionResult("mp4", tex.payload)
            WallpaperEngineTexContentType.R8 -> expandR8(tex)?.let { rgba ->
                WallpaperEngineTexConversionResult(
                    extension = "png",
                    bytes = SimplePngEncoder.encodeRgba(
                        width = tex.width,
                        height = tex.height,
                        rgba = rgba,
                    ),
                )
            }
            WallpaperEngineTexContentType.Rg88 -> expandRg88(tex)?.let { rgba ->
                WallpaperEngineTexConversionResult(
                    extension = "png",
                    bytes = SimplePngEncoder.encodeRgba(
                        width = tex.width,
                        height = tex.height,
                        rgba = rgba,
                    ),
                )
            }
            WallpaperEngineTexContentType.Unsupported -> null
        }
    }

    private fun expandR8(tex: ParsedWallpaperEngineTex): ByteArray? {
        val expected = tex.width * tex.height
        if (tex.payload.size < expected) return null
        val result = ByteArray(expected * 4)
        var target = 0
        repeat(expected) { index ->
            val channel = tex.payload[index]
            result[target++] = channel
            result[target++] = channel
            result[target++] = channel
            result[target++] = 0xFF.toByte()
        }
        return result
    }

    private fun expandRg88(tex: ParsedWallpaperEngineTex): ByteArray? {
        val expectedPixels = tex.width * tex.height
        val expectedBytes = expectedPixels * 2
        if (tex.payload.size < expectedBytes) return null
        val result = ByteArray(expectedPixels * 4)
        var source = 0
        var target = 0
        repeat(expectedPixels) {
            val luminance = tex.payload[source++]
            val alpha = tex.payload[source++]
            result[target++] = luminance
            result[target++] = luminance
            result[target++] = luminance
            result[target++] = alpha
        }
        return result
    }
}

internal object WallpaperEngineTexFastPathInspector {
    private const val MAGIC_SIZE = 8
    private const val SEPARATOR_SIZE = 1
    private const val INT_SIZE = 4
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    fun inspectHeader(file: File): WallpaperEngineTexHeaderInfo? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 80L) return null
            val texv = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            val texi = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            val formatId = raf.readUInt32Le()
            if (!raf.skipSafely(INT_SIZE)) return null
            val width = raf.readUInt32Le()
            val height = raf.readUInt32Le()
            if (width <= 0L || height <= 0L) return null
            if (!raf.skipSafely(INT_SIZE * 3)) return null
            val texb = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            if (!raf.skipSafely(INT_SIZE)) return null
            if (!raf.skipSafely(INT_SIZE * 2)) return null
            if (texb == "TEXB0004" && !raf.skipSafely(INT_SIZE)) return null
            if (!raf.skipSafely(MAGIC_SIZE)) return null
            val lz4Flag = raf.readUInt32Le()
            raf.readUInt32Le()
            val payloadSize = raf.readUInt32Le()
            if (!texv.startsWith("TEXV") || !texi.startsWith("TEXI") || !texb.startsWith("TEXB")) {
                return null
            }
            return WallpaperEngineTexHeaderInfo(
                formatId = formatId,
                lz4Flag = lz4Flag,
                payloadSize = payloadSize,
            )
        }
    }

    fun inspect(file: File): WallpaperEngineTexFastPathCopy? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 80L) return null

            val texv = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            val texi = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            val formatId = raf.readUInt32Le()
            if (!raf.skipSafely(INT_SIZE)) return null
            val width = raf.readUInt32Le()
            val height = raf.readUInt32Le()
            if (width <= 0L || height <= 0L) return null
            if (!raf.skipSafely(INT_SIZE * 3)) return null
            val texb = raf.readAscii(MAGIC_SIZE) ?: return null
            if (!raf.skipSafely(SEPARATOR_SIZE)) return null
            if (!raf.skipSafely(INT_SIZE)) return null
            if (!raf.skipSafely(INT_SIZE * 2)) return null
            if (texb == "TEXB0004" && !raf.skipSafely(INT_SIZE)) return null
            if (!raf.skipSafely(MAGIC_SIZE)) return null
            val lz4Flag = raf.readUInt32Le()
            raf.readUInt32Le() // decompressedSize
            val payloadSize = raf.readUInt32Le()
            val payloadOffset = raf.filePointer
            if (
                !texv.startsWith("TEXV") ||
                !texi.startsWith("TEXI") ||
                !texb.startsWith("TEXB") ||
                formatId != 0L ||
                lz4Flag != 0L ||
                payloadSize <= 0L ||
                payloadOffset + payloadSize > raf.length()
            ) {
                return null
            }

            val signatureBuffer = ByteArray(16)
            val signatureRead = raf.read(signatureBuffer)
            if (signatureRead <= 0) return null
            val extension = matchRawSignature(signatureBuffer.copyOf(signatureRead)) ?: return null
            return WallpaperEngineTexFastPathCopy(
                extension = extension,
                payloadOffset = payloadOffset,
                payloadSize = payloadSize,
            )
        }
    }

    fun copyPayload(
        sourceFile: File,
        targetFile: File,
        fastPath: WallpaperEngineTexFastPathCopy,
        ensureActive: () -> Unit = {},
    ) {
        RandomAccessFile(sourceFile, "r").use { raf ->
            raf.seek(fastPath.payloadOffset)
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var remaining = fastPath.payloadSize
                while (remaining > 0L) {
                    ensureActive()
                    val readSize = minOf(remaining, buffer.size.toLong()).toInt()
                    val actualRead = raf.read(buffer, 0, readSize)
                    require(actualRead > 0) { "TEX fast-path 读取中断: ${sourceFile.name}" }
                    output.write(buffer, 0, actualRead)
                    remaining -= actualRead.toLong()
                }
            }
        }
    }

    private fun matchRawSignature(payload: ByteArray): String? {
        return when {
            payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "png"
            payload.size >= 3 &&
                payload[0] == 0xFF.toByte() &&
                payload[1] == 0xD8.toByte() &&
                payload[2] == 0xFF.toByte() -> "jpg"
            payload.size >= 8 && payload.copyOfRange(4, 8).contentEquals(MP4_SIGNATURE) -> "mp4"
            else -> null
        }
    }

    private fun RandomAccessFile.readAscii(count: Int): String? {
        if (length() - filePointer < count.toLong()) return null
        val bytes = ByteArray(count)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun RandomAccessFile.skipSafely(count: Int): Boolean {
        if (count <= 0) return true
        if (length() - filePointer < count.toLong()) return false
        seek(filePointer + count)
        return true
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        return Integer.toUnsignedLong(Integer.reverseBytes(readInt()))
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private val MP4_SIGNATURE = byteArrayOf(
        0x66,
        0x74,
        0x79,
        0x70,
    )
}

private data class ParsedWallpaperEngineTex(
    val width: Int,
    val height: Int,
    val contentType: WallpaperEngineTexContentType,
    val payload: ByteArray,
)

private enum class WallpaperEngineTexContentType {
    Png,
    Jpg,
    Mp4,
    R8,
    Rg88,
    Unsupported,
}

private object WallpaperEngineTexParser {
    private const val MAGIC_SIZE = 8
    private const val SEPARATOR_SIZE = 1
    private const val INT_SIZE = 4

    fun parse(bytes: ByteArray): ParsedWallpaperEngineTex? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() < 80) return null

        val texv = readAscii(buffer, MAGIC_SIZE) ?: return null
        if (!skip(buffer, SEPARATOR_SIZE)) return null
        val texi = readAscii(buffer, MAGIC_SIZE) ?: return null
        if (!skip(buffer, SEPARATOR_SIZE)) return null
        val formatId = buffer.int
        if (!skip(buffer, INT_SIZE)) return null
        val width = buffer.int
        val height = buffer.int
        if (width <= 0 || height <= 0) return null
        if (!skip(buffer, INT_SIZE * 3)) return null
        val texb = readAscii(buffer, MAGIC_SIZE) ?: return null
        if (!skip(buffer, SEPARATOR_SIZE)) return null
        if (!skip(buffer, INT_SIZE)) return null // image_count
        if (!skip(buffer, INT_SIZE * 2)) return null
        if (texb == "TEXB0004") {
            if (!skip(buffer, INT_SIZE)) return null // mipmap_count
        }
        if (!skip(buffer, MAGIC_SIZE)) return null
        val lz4Flag = buffer.int
        val decompressedSize = buffer.int
        val payloadSize = buffer.int
        if (payloadSize < 0 || payloadSize > buffer.remaining()) return null
        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        val resolvedPayload = if (lz4Flag == 1) {
            if (decompressedSize <= 0) return null
            runCatching {
                LZ4Factory.fastestInstance()
                    .safeDecompressor()
                    .decompress(payload, decompressedSize)
            }.getOrNull() ?: return null
        } else {
            payload
        }

        if (!texv.startsWith("TEXV") || !texi.startsWith("TEXI") || !texb.startsWith("TEXB")) {
            return null
        }

        val contentType = when (formatId) {
            0 -> matchRawSignature(resolvedPayload)
            8 -> WallpaperEngineTexContentType.Rg88
            9 -> WallpaperEngineTexContentType.R8
            else -> WallpaperEngineTexContentType.Unsupported
        }

        return ParsedWallpaperEngineTex(
            width = width,
            height = height,
            contentType = contentType,
            payload = resolvedPayload,
        )
    }

    private fun matchRawSignature(payload: ByteArray): WallpaperEngineTexContentType {
        return when {
            payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ->
                WallpaperEngineTexContentType.Png
            payload.size >= 3 && payload[0] == 0xFF.toByte() &&
                payload[1] == 0xD8.toByte() &&
                payload[2] == 0xFF.toByte() ->
                WallpaperEngineTexContentType.Jpg
            payload.size >= 8 && payload.copyOfRange(4, 8).contentEquals(MP4_SIGNATURE) ->
                WallpaperEngineTexContentType.Mp4
            else -> WallpaperEngineTexContentType.Unsupported
        }
    }

    private fun readAscii(buffer: ByteBuffer, count: Int): String? {
        if (buffer.remaining() < count) return null
        val bytes = ByteArray(count)
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun skip(buffer: ByteBuffer, count: Int): Boolean {
        if (count <= 0) return true
        if (buffer.remaining() < count) return false
        buffer.position(buffer.position() + count)
        return true
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private val MP4_SIGNATURE = byteArrayOf(
        0x66,
        0x74,
        0x79,
        0x70,
    )
}

private object SimplePngEncoder {
    fun encodeRgba(
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ByteArray {
        require(width > 0 && height > 0) { "PNG 宽高必须大于 0" }
        require(rgba.size == width * height * 4) { "PNG RGBA 数据长度不正确" }

        val scanlines = ByteArrayOutputStream(height * (width * 4 + 1))
        repeat(height) { row ->
            scanlines.write(0) // no filter
            val offset = row * width * 4
            scanlines.write(rgba, offset, width * 4)
        }
        val compressed = deflate(scanlines.toByteArray())

        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        writeChunk(output, "IHDR", buildIhdr(width, height))
        writeChunk(output, "IDAT", compressed)
        writeChunk(output, "IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun buildIhdr(width: Int, height: Int): ByteArray {
        val buffer = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.put(8) // bit depth
        buffer.put(6) // color type RGBA
        buffer.put(0) // compression
        buffer.put(0) // filter
        buffer.put(0) // interlace
        return buffer.array()
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
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
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
}
