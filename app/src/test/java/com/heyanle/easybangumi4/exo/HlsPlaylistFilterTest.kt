package com.heyanle.easybangumi4.exo

import com.heyanle.easybangumi4.plugin.api.entity.HlsOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistFilterTest {

    @Test
    fun removesSegmentsMatchingPluginRegex() {
        val options = HlsOptions().apply {
            blockedSegmentRegex.add("/advert/")
            filterMinorityHosts = false
        }
        val result = HlsPlaylistFilter.filter(
            playlist("video/0.ts", "advert/1.ts", "video/2.ts"),
            "https://media.example/show/index.m3u8",
            options,
        )

        assertEquals(setOf(1), result.removedSegmentIndices)
        assertFalse(result.playlist.contains("advert/1.ts"))
        assertTrue(result.playlist.contains("video/2.ts"))
    }

    @Test
    fun removesShortMinorityHostButKeepsLongCdnSwitch() {
        val options = HlsOptions()
        val urls = List(20) { "https://main.example/$it.ts" } +
            listOf("https://ads.example/a.ts", "https://ads.example/b.ts")
        val durations = List(20) { 10.0 } + listOf(5.0, 5.0)

        assertEquals(
            setOf(20, 21),
            HlsPlaylistFilter.findRemovedIndices(urls, durations, urls.first(), options),
        )

        options.maxAdDurationSeconds = 5.0
        assertTrue(
            HlsPlaylistFilter.findRemovedIndices(urls, durations, urls.first(), options).isEmpty()
        )
    }

    @Test
    fun appliesDurationLimitToEachMinorityHostRun() {
        val main = List(120) { "https://main.example/$it.ts" }
        val firstAd = List(10) { "https://ads.example/a$it.ts" }
        val secondAd = List(10) { "https://ads.example/b$it.ts" }
        val urls = main.take(60) + firstAd + main.drop(60) + secondAd
        val durations = List(urls.size) { 10.0 }

        val removed = HlsPlaylistFilter.findRemovedIndices(
            urls,
            durations,
            urls.first(),
            HlsOptions(),
        )

        assertEquals(20, removed.size)
    }

    @Test
    fun doesNotTreatFrequentDiscontinuitiesOnOneHostAsAds() {
        val source = """#EXTM3U
#EXT-X-DISCONTINUITY
#EXTINF:4.0,
0.ts
#EXT-X-DISCONTINUITY
#EXTINF:4.0,
1.ts
#EXT-X-ENDLIST"""

        val result = HlsPlaylistFilter.filter(
            source,
            "https://vip.dytt-tvs.com/show/mixed.m3u8",
            HlsOptions(),
        )

        assertTrue(result.removedSegmentIndices.isEmpty())
        assertEquals(source, result.playlist)
    }

    private fun playlist(vararg urls: String): String = buildString {
        appendLine("#EXTM3U")
        urls.forEach {
            appendLine("#EXTINF:10.0,")
            appendLine(it)
        }
        append("#EXT-X-ENDLIST")
    }
}
