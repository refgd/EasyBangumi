package com.heyanle.easybangumi4.exo

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.plugin.api.entity.PlayerInfo
import com.heyanle.easybangumi4.plugin.api.entity.HlsOptions

/**
 * Created by HeYanLe on 2023/8/13 21:21.
 * https://github.com/heyanLE
 */
@UnstableApi
class CartoonMediaSourceFactory(
    private val normalCache: Cache,
    private val downloadCache: Cache,
) {

    fun getMediaItem(playerInfo: PlayerInfo): MediaItem {
        return MediaItem.fromUri(playerInfo.uri)
    }

    /**
     * Http <- 缓存区
     */
    fun getMediaSourceFactory(playerInfo: PlayerInfo): MediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(playerInfo.normalizedHeaders())
        val upstreamDataSourceFactory = DefaultDataSource.Factory(APP, httpDataSourceFactory)
        val streamDataSinkFactory = CacheDataSink.Factory().setCache(normalCache)
        val normalCacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(normalCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheWriteDataSinkFactory(streamDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val dataSourceFactory = mediaDataSourceFactory(playerInfo, normalCacheDataSourceFactory)

        return when (playerInfo.decodeType) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
        }
    }

    /**
     * Http <- 缓存区 <- 下载区
     */
    fun getMediaSourceFactoryWithDownload(playerInfo: PlayerInfo): MediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(playerInfo.normalizedHeaders())
        val upstreamDataSourceFactory = DefaultDataSource.Factory(APP, httpDataSourceFactory)

        val streamDataSinkFactory = CacheDataSink.Factory().setCache(normalCache)
        val normalCacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(normalCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheWriteDataSinkFactory(streamDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 下载区不许写
        val normalDownloadCacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(normalCacheDataSourceFactory)
            .setCacheWriteDataSinkFactory(null)
        val dataSourceFactory = mediaDataSourceFactory(
            playerInfo,
            normalDownloadCacheDataSourceFactory,
        )


        return when (playerInfo.decodeType) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
        }
    }


    // HTTP
    fun getDataSourceFactory(playerInfo: PlayerInfo): DefaultDataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(playerInfo.normalizedHeaders())
        return DefaultDataSource.Factory(APP, httpDataSourceFactory)
    }

    fun getMediaSourceWithoutCache(playerInfo: PlayerInfo): MediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(playerInfo.normalizedHeaders())
        val dataSourceFactory = mediaDataSourceFactory(
            playerInfo,
            DefaultDataSource.Factory(APP, httpDataSourceFactory)
        )

        return when (playerInfo.decodeType) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
        }
    }

    fun getClipMediaSourceFactory(playerInfo: PlayerInfo, clippingConfiguration: ClippingConfiguration): ClippingConfigMediaSourceFactory {
        return ClippingConfigMediaSourceFactory(getMediaSourceFactory(playerInfo), clippingConfiguration)
    }

    private fun mediaDataSourceFactory(
        playerInfo: PlayerInfo,
        upstream: DataSource.Factory
    ): DataSource.Factory = if (playerInfo.decodeType == C.CONTENT_TYPE_HLS) {
        val playlistFactory = HlsPlaylistDataSource.Factory(upstream, playerInfo.hlsOptions)
        if (playerInfo.hlsOptions.segmentPayload.equals(
                HlsOptions.SEGMENT_PAYLOAD_RAW,
                ignoreCase = true
            )
        ) {
            playlistFactory
        } else {
            PngTailDataSource.Factory(playlistFactory)
        }
    } else {
        upstream
    }


    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getWithCache(playerInfo: PlayerInfo): MediaSource {
        return getMediaSourceFactory(playerInfo).createMediaSource(getMediaItem(playerInfo))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getWithoutCache(playerInfo: PlayerInfo): MediaSource {
        return getMediaSourceWithoutCache(playerInfo).createMediaSource(getMediaItem(playerInfo))
    }

}
