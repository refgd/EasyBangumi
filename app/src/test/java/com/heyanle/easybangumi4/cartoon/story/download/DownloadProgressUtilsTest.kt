package com.heyanle.easybangumi4.cartoon.story.download

import com.heyanle.easybangumi4.cartoon.story.download.utils.DownloadFileValidator
import com.heyanle.easybangumi4.cartoon.story.download.utils.DownloadProgressUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadProgressUtilsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesRelativeM3u8Urls() {
        val master = "https://example.com/show/master.m3u8"
        val variant = DownloadProgressUtils.resolveUrl(master, "3000k/hls/mixed.m3u8")
        assertEquals("https://example.com/show/3000k/hls/mixed.m3u8", variant)
        assertEquals(
            "https://example.com/show/3000k/hls/segment-1.ts",
            DownloadProgressUtils.resolveUrl(variant, "segment-1.ts")
        )
        assertEquals(
            "https://cdn.example.com/video.m3u8",
            DownloadProgressUtils.resolveUrl(master, "https://cdn.example.com/video.m3u8")
        )
    }

    @Test
    fun normalizesConcurrencyAndProgress() {
        assertEquals(1, DownloadProgressUtils.normalizeDownloadTaskCount(0L))
        assertEquals(6, DownloadProgressUtils.normalizeDownloadTaskCount(99L))
        assertEquals(1, DownloadProgressUtils.normalizeTransformTaskCount(0L))
        assertEquals(2, DownloadProgressUtils.normalizeTransformTaskCount(8L))
        assertEquals(6, DownloadProgressUtils.m3u8PeerCount(1))
        assertEquals(4, DownloadProgressUtils.m3u8PeerCount(3))
        assertEquals(2, DownloadProgressUtils.m3u8PeerCount(6))
        assertEquals(0.5f, DownloadProgressUtils.nativePercentToFraction(50f), 0.0001f)
        assertEquals(0.5f, DownloadProgressUtils.hlsFraction(5, 10)!!, 0.0001f)
        assertEquals(0.25f, DownloadProgressUtils.byteFraction(25, 100, 0)!!, 0.0001f)
        assertNull(DownloadProgressUtils.byteFraction(25, 0, 0))
    }

    @Test
    fun hidesOnlyGenericDownloadingStatusWithDetails() {
        assertFalse(DownloadProgressUtils.shouldShowStatus("下载中", "HLS | 1 MB/s", "下载中", false))
        assertTrue(DownloadProgressUtils.shouldShowStatus("下载中", "", "下载中", false))
        assertTrue(DownloadProgressUtils.shouldShowStatus("解密中", "1/10", "下载中", false))
        assertTrue(DownloadProgressUtils.shouldShowStatus("下载失败", "旧详情", "下载中", true))
    }

    @Test
    fun validatesLocalM3u8SegmentsAndKey() {
        val folder = temporaryFolder.newFolder("hls")
        File(folder, "key.bin").writeBytes(ByteArray(16) { 1 })
        File(folder, "segment-1.ts").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "segment-2.ts").writeBytes(byteArrayOf(4, 5, 6))
        val playlist = File(folder, "local.m3u8").apply {
            writeText(
                """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:10,
                segment-1.ts
                #EXTINF:10,
                segment-2.ts
                #EXT-X-ENDLIST
                """.trimIndent()
            )
        }

        assertNull(DownloadFileValidator.validateLocalM3u8(playlist))
        File(folder, "segment-2.ts").delete()
        assertNotNull(DownloadFileValidator.validateLocalM3u8(playlist))
    }

    @Test
    fun rejectsEmptyMediaFileBeforeExtractor() {
        val emptyMedia = temporaryFolder.newFile("empty.mp4")
        assertNotNull(DownloadFileValidator.validateMedia(emptyMedia))
    }
}
