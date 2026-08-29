package com.heyanle.easybangumi4.exo

import com.heyanle.easybangumi4.plugin.api.entity.HlsOptions
import java.net.URI

internal object HlsPlaylistFilter {

    data class Result(
        val playlist: String,
        val removedSegmentIndices: Set<Int>,
    )

    private data class Segment(
        val index: Int,
        val startLine: Int,
        val endLine: Int,
        val uri: String,
        val duration: Double,
    )

    fun filter(
        playlist: String,
        baseUri: String,
        options: HlsOptions,
        segmentUrisOverride: List<String>? = null,
    ): Result {
        val lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.firstOrNull()?.trim() != "#EXTM3U") return Result(playlist, emptySet())
        val segments = parseSegments(lines)
        if (segments.isEmpty()) return Result(playlist, emptySet())

        val filterUris = segmentUrisOverride?.takeIf { it.size == segments.size }
            ?: segments.map { it.uri }
        val removed = findRemovedIndices(
            filterUris,
            segments.map { it.duration },
            baseUri,
            options,
        )
        if (removed.isEmpty() || removed.size == segments.size) return Result(playlist, emptySet())

        val skippedLines = HashSet<Int>()
        segments.filter { it.index in removed }.forEach { segment ->
            for (lineIndex in segment.startLine..segment.endLine) skippedLines.add(lineIndex)
        }
        val filtered = lines.filterIndexed { index, _ -> index !in skippedLines }
            .fold(ArrayList<String>()) { output, line ->
                if (line != "#EXT-X-DISCONTINUITY" || output.lastOrNull() != line) output.add(line)
                output
            }
            .joinToString("\n")
        return Result(filtered, removed)
    }

    fun findRemovedIndices(
        segmentUris: List<String>,
        durations: List<Double>,
        baseUri: String,
        options: HlsOptions,
    ): Set<Int> {
        if (segmentUris.isEmpty()) return emptySet()
        val blockedPatterns = options.blockedSegmentRegex.mapNotNull {
            runCatching { Regex(it) }.getOrNull()
        }
        val resolved = segmentUris.map { resolve(baseUri, it) }
        val removed = resolved.indices.filterTo(mutableSetOf()) { index ->
            blockedPatterns.any { it.containsMatchIn(resolved[index]) || it.containsMatchIn(segmentUris[index]) }
        }
        if (!options.filterMinorityHosts) return removed

        val safeDurations = resolved.indices.map { durations.getOrNull(it)?.coerceAtLeast(0.0) ?: 0.0 }
        val hosts = resolved.map { uri ->
            runCatching { URI(uri).host.orEmpty().lowercase() }.getOrDefault("")
        }
        val hostDurations = linkedMapOf<String, Double>()
        hosts.forEachIndexed { index, host ->
            if (host.isNotEmpty()) hostDurations[host] = hostDurations.getOrDefault(host, 0.0) + safeDurations[index]
        }
        if (hostDurations.size < 2) return removed
        val totalDuration = hostDurations.values.sum()
        if (totalDuration <= 0.0) return removed
        val threshold = options.minorityHostThreshold.coerceIn(0.0, 0.49)
        val maxAdDuration = options.maxAdDurationSeconds.coerceAtLeast(0.0)
        val minorityHosts = hostDurations.filterValues { duration ->
            duration / totalDuration <= threshold
        }.keys
        var index = 0
        while (index < hosts.size) {
            if (hosts[index] !in minorityHosts) {
                index++
                continue
            }
            val start = index
            var runDuration = 0.0
            while (index < hosts.size && hosts[index] in minorityHosts) {
                runDuration += safeDurations[index]
                index++
            }
            if (runDuration <= maxAdDuration) {
                for (segmentIndex in start until index) removed.add(segmentIndex)
            }
        }
        return removed
    }

    private fun parseSegments(lines: List<String>): List<Segment> {
        val segments = arrayListOf<Segment>()
        var extInfLine = -1
        var duration = 0.0
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                extInfLine = index
                duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
            } else if (extInfLine >= 0 && line.isNotEmpty() && !line.startsWith('#')) {
                segments.add(Segment(segments.size, extInfLine, index, line, duration))
                extInfLine = -1
            }
        }
        return segments
    }

    private fun resolve(baseUri: String, value: String): String = runCatching {
        URI(baseUri).resolve(value.trim()).toString()
    }.getOrDefault(value.trim())
}
