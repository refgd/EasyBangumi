package com.heyanle.easybangumi4.exo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class PngTailPayloadTest {

    @Test
    fun findsTransportStreamAfterPngEndChunk() {
        val png = makePngPrefix()
        val transportStream = ByteArray(188 * 3).also {
            it[0] = 0x47
            it[188] = 0x47
            it[376] = 0x47
        }

        assertEquals(png.size, PngTailPayload.findPayloadOffset(png + transportStream))
        assertEquals(true, PngTailPayload.isTransportStream(transportStream))
        assertEquals(
            transportStream.toList(),
            PngTailPayload.stripPngPrefix(png + transportStream).toList()
        )
    }

    @Test
    fun leavesNormalTransportStreamUntouched() {
        val transportStream = ByteArray(188).also { it[0] = 0x47 }

        assertNull(PngTailPayload.findPayloadOffset(transportStream))
    }

    @Test
    fun doesNotTreatOrdinaryPngTailAsTransportStream() {
        assertEquals(false, PngTailPayload.isTransportStream(ByteArray(188 * 3)))
    }

    @Test
    fun rejectsIncompletePng() {
        val png = makePngPrefix()

        assertNull(PngTailPayload.findPayloadOffset(png.copyOf(png.size - 3)))
    }

    private fun makePngPrefix(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(output, "IHDR", ByteArray(13))
        writeChunk(output, "IDAT", byteArrayOf(1, 2, 3, 4))
        writeChunk(output, "IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        output.write(byteArrayOf(
            (data.size ushr 24).toByte(),
            (data.size ushr 16).toByte(),
            (data.size ushr 8).toByte(),
            data.size.toByte()
        ))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32().also {
            it.update(typeBytes)
            it.update(data)
        }.value
        output.write(byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte()
        ))
    }
}
