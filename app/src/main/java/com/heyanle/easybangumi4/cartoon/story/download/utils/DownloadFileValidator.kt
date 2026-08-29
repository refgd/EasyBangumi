package com.heyanle.easybangumi4.cartoon.story.download.utils

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.net.URI

object DownloadFileValidator {

    private val keyUriRegex = Regex("URI=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)

    fun validateMedia(file: File): String? {
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
            return "媒体文件不存在、为空或不可读"
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasMediaTrack = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    hasMediaTrack = true
                }
            }
            when {
                !hasMediaTrack -> "媒体文件没有可识别的音视频轨道"
                extractor.sampleTrackIndex < 0 -> "媒体文件没有可读取的音视频样本"
                else -> null
            }
        } catch (e: Throwable) {
            "媒体文件无法解析：${e.message ?: e.javaClass.simpleName}"
        } finally {
            extractor.release()
        }
    }

    fun validateLocalM3u8(file: File): String? {
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
            return "m3u8 清单不存在、为空或不可读"
        }
        return runCatching {
            var segmentCount = 0
            file.useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.isBlank() -> Unit
                        line.startsWith("#EXT-X-KEY", ignoreCase = true) &&
                                !line.contains("METHOD=NONE", ignoreCase = true) -> {
                            val keyPath = keyUriRegex.find(line)?.groupValues?.getOrNull(1)
                                ?: return "m3u8 加密信息缺少 key 地址"
                            val keyFile = file.resolveLocalReference(keyPath)
                            if (keyFile == null || !keyFile.isFile || !keyFile.canRead() || keyFile.length() <= 0L) {
                                return "m3u8 key 文件不存在、为空或不可读"
                            }
                        }
                        line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                            val mapPath = keyUriRegex.find(line)?.groupValues?.getOrNull(1)
                                ?: return "m3u8 初始化分片缺少地址"
                            val mapFile = file.resolveLocalReference(mapPath)
                            if (mapFile == null || !mapFile.isFile || !mapFile.canRead() || mapFile.length() <= 0L) {
                                return "m3u8 初始化分片不存在、为空或不可读"
                            }
                        }
                        !line.startsWith("#") -> {
                            segmentCount++
                            val segment = file.resolveLocalReference(line)
                            if (segment == null || !segment.isFile || !segment.canRead() || segment.length() <= 0L) {
                                return "m3u8 第 $segmentCount 个分片不存在、为空或不可读"
                            }
                        }
                    }
                }
            }
            if (segmentCount == 0) "m3u8 清单没有可用分片" else null
        }.getOrElse {
            "m3u8 清单无法解析：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun File.resolveLocalReference(reference: String): File? {
        val value = reference.trim().removeSurrounding("\"")
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return null
        }
        return runCatching {
            when {
                value.startsWith("file:", true) -> File(URI(value))
                File(value).isAbsolute -> File(value)
                else -> File(parentFile, value)
            }
        }.getOrNull()
    }
}
