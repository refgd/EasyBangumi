package com.heyanle.easybangumi4.plugin.js.source

import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayerInfo
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import java.util.ArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.Wrapper

class JsSourceHelpersTest {

    @Test
    fun entityAndResultHelpers_normalizeRhinoValuesForJavaConstructors() {
        val context = Context.enter()
        try {
            val scope = ImporterTopLevel(context)
            context.evaluateString(scope, JsSource.JS_IMPORT, "import", 1, null)

            val episode = context.evaluateString(
                scope,
                "makeEpisode({ id: 'episode-' + 1, label: 'Episode ' + 1, order: '14' })",
                "episode",
                1,
                null,
            ).unwrap<Episode>()
            assertEquals("episode-1", episode.id)
            assertEquals("Episode 1", episode.label)
            assertEquals(14, episode.order)

            val playLine = context.evaluateString(
                scope,
                "var episodes = []; episodes.push(makeEpisode({ id: '1', label: '1', order: 0 }));" +
                    "makePlayLine({ id: 'line-' + 1, label: 'Line ' + 1, episodes: episodes });",
                "playLine",
                1,
                null,
            ).unwrap<PlayLine>()
            assertEquals("line-1", playLine.id)
            assertEquals(1, playLine.episode.size)

            val page = context.evaluateString(
                scope,
                "makePageResult(14, ['item'])",
                "page",
                1,
                null,
            ).unwrap<Pair<*, *>>()
            assertEquals(14, page.first)
            assertTrue(page.second is ArrayList<*>)

            val player = context.evaluateString(
                scope,
                "makePlayerInfo({" +
                    "decodeType: PlayerInfo.DECODE_TYPE_HLS," +
                    "uri: 'https://' + 'example.com/video.m3u8'," +
                    "headers: { Referer: 'https://' + 'example.com/' }," +
                    "hlsOptions: {" +
                    "segmentPayload: 'auto', filterMinorityHosts: 'false'," +
                    "minorityHostThreshold: '0.2', maxAdDurationSeconds: '90'," +
                    "blockedSegmentRegex: ['/ad/' + 'segment']" +
                    "}})",
                "player",
                1,
                null,
            ).unwrap<PlayerInfo>()
            assertEquals(PlayerInfo.DECODE_TYPE_HLS, player.decodeType)
            assertEquals("https://example.com/video.m3u8", player.uri)
            assertEquals("https://example.com/", player.normalizedHeaders()["Referer"])
            assertFalse(player.hlsOptions.filterMinorityHosts)
            assertEquals(0.2, player.hlsOptions.minorityHostThreshold, 0.0)
            assertEquals(90.0, player.hlsOptions.maxAdDurationSeconds, 0.0)
            assertEquals(listOf("/ad/segment"), player.hlsOptions.blockedSegmentRegex)
        } finally {
            Context.exit()
        }
    }

    private inline fun <reified T> Any.unwrap(): T =
        ((this as? Wrapper)?.unwrap() ?: this) as T
}
