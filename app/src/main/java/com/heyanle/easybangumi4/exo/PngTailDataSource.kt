package com.heyanle.easybangumi4.exo

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

internal object PngTailPayload {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun isPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }

    fun isTransportStream(bytes: ByteArray): Boolean =
        bytes.size >= TS_PROBE_SIZE &&
            bytes[0] == TS_SYNC_BYTE &&
            bytes[TS_PACKET_SIZE] == TS_SYNC_BYTE &&
            bytes[TS_PACKET_SIZE * 2] == TS_SYNC_BYTE

    fun findPayloadOffset(bytes: ByteArray): Int? {
        if (!isPngSignature(bytes)) return null
        var offset = signature.size
        while (offset + 12 <= bytes.size) {
            val length = readUnsignedInt(bytes, offset)
            if (length > Int.MAX_VALUE || length > bytes.size - offset - 12) return null
            val chunkEnd = offset + 12 + length.toInt()
            if (bytes[offset + 4] == 'I'.code.toByte() &&
                bytes[offset + 5] == 'E'.code.toByte() &&
                bytes[offset + 6] == 'N'.code.toByte() &&
                bytes[offset + 7] == 'D'.code.toByte()
            ) {
                return chunkEnd
            }
            offset = chunkEnd
        }
        return null
    }

    fun stripPngPrefix(bytes: ByteArray): ByteArray {
        val payloadOffset = findPayloadOffset(bytes) ?: return bytes
        val payload = bytes.copyOfRange(payloadOffset, bytes.size)
        return if (isTransportStream(payload)) payload else bytes
    }

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    const val TS_PACKET_SIZE = 188
    const val TS_PROBE_SIZE = TS_PACKET_SIZE * 2 + 1
    private const val TS_SYNC_BYTE: Byte = 0x47
}

@UnstableApi
class PngTailDataSource private constructor(
    private val upstream: DataSource,
    private val knownOffsets: ConcurrentHashMap<String, Int>
) : DataSource by upstream {

    private var pending = ByteArray(0)
    private var pendingOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        pending = ByteArray(0)
        pendingOffset = 0
        val key = dataSpec.uri.toString()
        val knownOffset = knownOffsets[key]
        if (dataSpec.position > 0 && knownOffset != null) {
            return upstream.open(
                dataSpec.buildUpon()
                    .setPosition(dataSpec.position + knownOffset)
                    .build()
            )
        }

        val upstreamLength = upstream.open(dataSpec)
        if (dataSpec.position > 0) return upstreamLength

        val captured = ByteArrayOutputStream()
        val signature = readExactly(8) ?: return upstreamLength
        captured.write(signature)
        if (!PngTailPayload.isPngSignature(signature)) {
            pending = signature
            return upstreamLength
        }

        while (captured.size() < MAX_PNG_PREFIX_SIZE) {
            val chunkHeader = readExactly(8) ?: break
            captured.write(chunkHeader)
            val chunkLength = readUnsignedInt(chunkHeader)
            if (chunkLength > MAX_PNG_PREFIX_SIZE - captured.size() - 4) break
            val dataAndCrc = readExactly(chunkLength + 4) ?: break
            captured.write(dataAndCrc)
            val bytes = captured.toByteArray()
            val payloadOffset = PngTailPayload.findPayloadOffset(bytes)
            if (payloadOffset != null) {
                val payloadProbe = readExactly(PngTailPayload.TS_PROBE_SIZE)
                if (payloadProbe != null && PngTailPayload.isTransportStream(payloadProbe)) {
                    pending = payloadProbe
                    knownOffsets[key] = payloadOffset
                    return if (upstreamLength == C.LENGTH_UNSET.toLong()) {
                        upstreamLength
                    } else {
                        (upstreamLength - payloadOffset).coerceAtLeast(0)
                    }
                }
                if (payloadProbe != null) {
                    captured.write(payloadProbe)
                }
                break
            }
        }

        pending = captured.toByteArray()
        return upstreamLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pendingOffset < pending.size) {
            val count = minOf(length, pending.size - pendingOffset)
            pending.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
            pendingOffset += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun close() {
        pending = ByteArray(0)
        pendingOffset = 0
        upstream.close()
    }

    private fun readExactly(length: Int): ByteArray? {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = upstream.read(result, offset, length - offset)
            if (read == C.RESULT_END_OF_INPUT) return null
            offset += read
        }
        return result
    }

    private fun readUnsignedInt(bytes: ByteArray): Int {
        val value = ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        private val knownOffsets = ConcurrentHashMap<String, Int>()

        override fun createDataSource(): DataSource =
            PngTailDataSource(upstreamFactory.createDataSource(), knownOffsets)
    }

    private companion object {
        const val MAX_PNG_PREFIX_SIZE = 64 * 1024
    }
}
