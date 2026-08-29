package com.heyanle.easybangumi4.exo

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.heyanle.easybangumi4.plugin.api.entity.HlsOptions
import java.io.ByteArrayOutputStream

@UnstableApi
class HlsPlaylistDataSource private constructor(
    private val upstream: DataSource,
    private val options: HlsOptions,
) : DataSource by upstream {

    private var buffered = ByteArray(0)
    private var pending = ByteArray(0)
    private var position = 0
    private var isBufferedPlaylist = false

    override fun open(dataSpec: DataSpec): Long {
        buffered = ByteArray(0)
        pending = ByteArray(0)
        position = 0
        isBufferedPlaylist = false
        val upstreamLength = upstream.open(dataSpec)
        if (dataSpec.position != 0L) return upstreamLength

        val probe = readProbe()
        if (!probe.toString(Charsets.US_ASCII).startsWith("#EXTM3U")) {
            pending = probe
            return upstreamLength
        }

        val output = ByteArrayOutputStream()
        output.write(probe)
        val chunk = ByteArray(8192)
        while (true) {
            val read = upstream.read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(chunk, 0, read)
        }
        val original = output.toByteArray()
        val text = original.toString(Charsets.UTF_8)
        buffered = if (text.trimStart().startsWith("#EXTM3U")) {
            HlsPlaylistFilter.filter(text, upstream.uri?.toString() ?: dataSpec.uri.toString(), options)
                .playlist.toByteArray(Charsets.UTF_8)
        } else {
            original
        }
        isBufferedPlaylist = true
        return buffered.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isBufferedPlaylist) {
            if (position >= buffered.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, buffered.size - position)
            buffered.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        if (position < pending.size) {
            val count = minOf(length, pending.size - position)
            pending.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        return upstream.read(buffer, offset, length)
    }

    override fun close() {
        buffered = ByteArray(0)
        pending = ByteArray(0)
        position = 0
        isBufferedPlaylist = false
        upstream.close()
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val options: HlsOptions,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = HlsPlaylistDataSource(
            upstreamFactory.createDataSource(),
            options,
        )
    }

    private fun readProbe(): ByteArray {
        val probe = ByteArray(7)
        var count = 0
        while (count < probe.size) {
            val read = upstream.read(probe, count, probe.size - count)
            if (read == C.RESULT_END_OF_INPUT) break
            count += read
        }
        return if (count == probe.size) probe else probe.copyOf(count)
    }

}
