package com.heyanle.easybangumi4.cartoon.story.download.utils

import java.net.URI

object DownloadProgressUtils {

    fun normalizeDownloadTaskCount(value: Long): Int = value.coerceIn(1L, 6L).toInt()

    fun normalizeTransformTaskCount(value: Long): Int = value.coerceIn(1L, 2L).toInt()

    fun m3u8PeerCount(maxDownloadTasks: Int): Int =
        (12 / maxDownloadTasks.coerceIn(1, 6)).coerceIn(2, 6)

    fun nativePercentToFraction(percent: Float): Float =
        (percent.coerceIn(0f, 100f) / 100f).coerceIn(0f, 1f)

    fun hlsFraction(peerIndex: Int, peerCount: Int): Float? =
        if (peerCount > 0) {
            (peerIndex.coerceAtLeast(0) / peerCount.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    fun byteFraction(current: Long, total: Long, percent: Int): Float? = when {
        total > 0L -> (current / total.toFloat()).coerceIn(0f, 1f)
        percent in 1..100 -> percent / 100f
        else -> null
    }

    fun shouldShowStatus(
        status: String,
        detail: String,
        downloadingStatus: String,
        isError: Boolean,
    ): Boolean = status.isNotBlank() &&
            (isError || detail.isBlank() || status != downloadingStatus)

    fun resolveUrl(baseUrl: String, path: String): String {
        val trimmedPath = path.trim()
        if (trimmedPath.startsWith("http://", true) || trimmedPath.startsWith("https://", true)) {
            return trimmedPath
        }
        return URI(baseUrl).resolve(trimmedPath).toString()
    }
}
