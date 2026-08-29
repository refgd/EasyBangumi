package com.heyanle.easybangumi4.cartoon.story.download.action

import android.app.Application
import com.heyanle.easybangumi4.cartoon.entity.CartoonDownloadReq
import com.heyanle.easybangumi4.cartoon.story.download.runtime.CartoonDownloadRuntime
import com.heyanle.easybangumi4.cartoon.story.download.utils.M3U8Utils
import com.heyanle.easybangumi4.cartoon.story.download.utils.DownloadFileValidator
import com.heyanle.easybangumi4.cartoon.story.download.utils.DownloadProgressUtils
import com.heyanle.easybangumi4.exo.HlsPlaylistFilter
import com.heyanle.easybangumi4.exo.PngTailPayload
import com.heyanle.easybangumi4.plugin.api.entity.HlsOptions
import com.heyanle.easybangumi4.utils.CoroutineProvider
import com.heyanle.easybangumi4.utils.EasyMemoryInfo
import com.heyanle.easybangumi4.utils.getCachePath
import com.heyanle.easybangumi4.utils.stringRes
import com.jeffmony.m3u8library.VideoProcessManager
import com.jeffmony.m3u8library.listener.IVideoTransformListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by heyanle on 2024/8/4.
 * https://github.com/heyanLE
 */
class TranscodeAction(
    private val application: Application
) : BaseAction {

    companion object {
        const val NAME = "Transcode"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 该阶段同时只能处理一个任务
    private val executor = CoroutineProvider.newSingleExecutor
    private val cacheFolder = application.getCachePath("transcode")

    private val decryptCacheFolder = File(cacheFolder, "decrypt")
    private val ffmpegCacheFolder = File(cacheFolder, "ffmpeg")


    inner class TranscodeRunnable(
        private val runtime: CartoonDownloadRuntime
    ) : Runnable {
        override fun run() {
            if (runtime.isCanceled() || runtime.isError()) {
                return
            }
            try {
                innerRun(runtime)
            } finally {
                if (runtime.isCanceled()) {
                    cleanupCache(runtime.req.uuid, includeFinalOutput = true)
                }
            }
        }
    }


    private fun innerRun(
        cartoonDownloadRuntime: CartoonDownloadRuntime
    ) {
        try {
            if (cartoonDownloadRuntime.isCanceled() || cartoonDownloadRuntime.isError()) {
                return
            }
            if (!decrypt(cartoonDownloadRuntime)) {
                if (cartoonDownloadRuntime.isCanceled()) {
                    return
                }
                synchronized(cartoonDownloadRuntime.lock) {
                    cartoonDownloadRuntime.error(
                        errorMsg = stringRes(com.heyanle.easy_i18n.R.string.decrypt_error),
                        error = IOException("Decrypt failed")
                    )
                }
                return
            }
            if (cartoonDownloadRuntime.isCanceled() || cartoonDownloadRuntime.isError()) {
                return
            }
            val transcodeError = ffmpeg(cartoonDownloadRuntime)
            if (cartoonDownloadRuntime.isCanceled()) {
                return
            }
            if (transcodeError != null) {
                synchronized(cartoonDownloadRuntime.lock) {
                    cartoonDownloadRuntime.error(
                        errorMsg = stringRes(com.heyanle.easy_i18n.R.string.transcode_error)
                            .withErrorMessage(transcodeError),
                        error = transcodeError
                    )
                }
                return
            }
            synchronized(cartoonDownloadRuntime.lock) {
                cartoonDownloadRuntime.filePathBeforeCopy =
                    cartoonDownloadRuntime.ffmpegFile?.absolutePath ?: ""
                cartoonDownloadRuntime.stepCompletely(this)
            }
        }catch (e: Throwable){
            synchronized(cartoonDownloadRuntime.lock) {
                cartoonDownloadRuntime.error(
                    errorMsg = stringRes(com.heyanle.easy_i18n.R.string.transcode_error).withErrorMessage(e),
                    error = e
                )
            }
        }

    }

    override suspend fun canResume(cartoonDownloadReq: CartoonDownloadReq): Boolean {
        val realTarget = File(ffmpegCacheFolder, "${cartoonDownloadReq.uuid}.mp4")
        return DownloadFileValidator.validateMedia(realTarget) == null
    }

    // 不支持暂停
    override suspend fun toggle(cartoonDownloadRuntime: CartoonDownloadRuntime): Boolean {
        return false
    }

    override fun push(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        val realTarget = File(ffmpegCacheFolder, "${cartoonDownloadRuntime.req.uuid}.mp4")
        if (DownloadFileValidator.validateMedia(realTarget) == null) {
            cartoonDownloadRuntime.filePathBeforeCopy = realTarget.absolutePath
            cartoonDownloadRuntime.stepCompletely(this)
            return
        }
        cleanupTemporaryFiles(cartoonDownloadRuntime.req.uuid)


        // 非 m3u8 任务不需要转码
        if (cartoonDownloadRuntime.m3u8Entity == null) {
            synchronized(cartoonDownloadRuntime.lock) {
                cartoonDownloadRuntime.filePathBeforeCopy =
                    cartoonDownloadRuntime.ariaDownloadFilePath
                cartoonDownloadRuntime.stepCompletely(this)
            }
            return
        }
        val runnable = TranscodeRunnable(cartoonDownloadRuntime)
        synchronized(cartoonDownloadRuntime.lock) {
            cartoonDownloadRuntime.transcodeRunnable = runnable
        }
        executor.execute(runnable)
    }

    override fun onCancel(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        val removedFromQueue = executor.remove(cartoonDownloadRuntime.transcodeRunnable)
        if (removedFromQueue) {
            cleanupCache(cartoonDownloadRuntime.req.uuid, includeFinalOutput = true)
        }

    }

    /**
     * 最终生成文件路径 File(cacheFolder, "${cartoonDownloadRuntime.req.uuid}.m3u8")
     */
    private fun decrypt(cartoonDownloadRuntime: CartoonDownloadRuntime): Boolean {
        val entity = cartoonDownloadRuntime.m3u8Entity ?: return false
        val localM3U8 = File(entity.filePath)

        if (!localM3U8.exists() || !localM3U8.canRead()) {
            return false
        }
        val target = File(decryptCacheFolder, "${cartoonDownloadRuntime.req.uuid}.m3u8.temp")
        val realTarget = File(decryptCacheFolder, "${cartoonDownloadRuntime.req.uuid}.m3u8")
        decryptCacheFolder.mkdirs()
        if (DownloadFileValidator.validateLocalM3u8(realTarget) == null) {
            synchronized(cartoonDownloadRuntime.lock) {
                cartoonDownloadRuntime.decryptFile = realTarget
            }
            return true
        }
        M3U8Utils.deleteM3U8WithTs(realTarget.absolutePath)
        target.delete()
        if (!target.createNewFile() || !target.canWrite()) {
            return false
        }
        target.deleteOnExit()
        // 这里改名是原子操作，只要文件存在就一定成功
        synchronized(cartoonDownloadRuntime.lock) {
            cartoonDownloadRuntime.decryptFile = realTarget
        }
        val encryptionMethod = entity.method.orEmpty()
        val needDecrypt = encryptionMethod.equals("AES-128", ignoreCase = true)
        val localPlaylistText = localM3U8.readText(Charsets.UTF_8)
        val sourceUrlsFile = File("${entity.filePath}.hls-urls")
        val sourceUrls = sourceUrlsFile.takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)
            ?.filter { it.isNotBlank() }
        val hlsOptions = cartoonDownloadRuntime.playerInfo?.hlsOptions ?: HlsOptions()
        val filteredPlaylist = HlsPlaylistFilter.filter(
            localPlaylistText,
            localM3U8.toURI().toString(),
            hlsOptions,
            sourceUrls,
        )
        if (encryptionMethod.isNotBlank() &&
            !encryptionMethod.equals("NONE", ignoreCase = true) &&
            !needDecrypt
        ) {
            return writePlaylist(filteredPlaylist.playlist, realTarget)
        }
        scope.launch {
            cartoonDownloadRuntime.dispatchToBusThrottled(
                -1f,
                stringRes(com.heyanle.easy_i18n.R.string.decrypting),
                force = true,
            )
        }
        // 将本地 m3u8 文件中的 ts 全都解密改写成 tsh 文件
        // 输出新的 m3u8 文件，去除 key 标签，文件路径改为 tsh
        // 如果 tsh 文件的文件头是 png，则去除文件头
        val it = localPlaylistText.lines().iterator()
        val tsFiles = arrayListOf<File>()
        val targetTsFiles = arrayListOf<File>()
        val segmentSequenceNumbers = arrayListOf<Long>()
        var mediaSequence = 0L
        var segmentIndex = 0
        val memoryInfo = EasyMemoryInfo(application)
        memoryInfo.update()
        // 内存不足直接跳过
        if (memoryInfo.lowMemory) {
            return writePlaylist(filteredPlaylist.playlist, realTarget)
        }
        try {
            target.writer(Charsets.UTF_8).buffered().use { writer ->
                while (it.hasNext()) {
                    val line = it.next()
                    if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true)) {
                        mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                        writer.write(line)
                        writer.newLine()
                    } else if (line.startsWith("#EXTINF")) {
                        if (!it.hasNext()) {
                            return false
                        } else {
                            val ts = it.next()
                            val file = File(ts)
                            if (segmentIndex in filteredPlaylist.removedSegmentIndices) {
                                file.delete()
                                segmentIndex++
                                continue
                            }
                            val targetFile = File("${ts}h")
                            // 如果大于当前可用内存 80% 以上就不解了直接丢 ffmpeg 然后祈祷他没有改文件头
                            memoryInfo.update()
                            if (memoryInfo.availMem * 0.8 <= file.length()
                                || (Runtime.getRuntime()?.freeMemory()
                                    ?: Long.MAX_VALUE) * 0.8 <= file.length()
                            ) {
                                return writePlaylist(filteredPlaylist.playlist, realTarget)
                            }
                            tsFiles.add(file)
                            // ts -> tsh
                            targetTsFiles.add(targetFile)
                            segmentSequenceNumbers.add(mediaSequence + segmentIndex)
                            segmentIndex++
                            writer.write(line)
                            writer.newLine()
                            writer.write(targetFile.absolutePath)
                            writer.newLine()
                        }
                    } else if (line.startsWith("#EXT-X-KEY")) {
                        // 新的 m3u8 文件不用解密了
                        continue
                    } else {
                        writer.write(line)
                        writer.newLine()
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            return writePlaylist(filteredPlaylist.playlist, realTarget)
        } catch (e: IOException) {
            e.printStackTrace()
            // 解密失败
            return false
        }

        // 开始解密咯！
        val keyB = if (needDecrypt) {
            val keyPath = entity.keyPath
            if (keyPath.isNullOrEmpty()) {
                throw IOException("m3u8 key path is empty")
            }
            val keyFile = File(keyPath)
            if (!keyFile.exists() || !keyFile.canRead()) {
                throw IOException("m3u8 key file can not read: $keyPath")
            }
            keyFile.readBytes()
        } else {
            ByteArray(0)
        }

        scope.launch {
            cartoonDownloadRuntime.dispatchToBusThrottled(
                -1f,
                stringRes(com.heyanle.easy_i18n.R.string.decrypting),
                "0/${tsFiles.size}",
                force = true,
            )
        }
        for (i in 0 until tsFiles.size.coerceAtMost(targetTsFiles.size)) {
            if (cartoonDownloadRuntime.isCanceled()) {
                return false
            }
            scope.launch {
                cartoonDownloadRuntime.dispatchToBusThrottled(
                    if (tsFiles.size == 0) 0f else {
                        (i + 1) / (tsFiles.size).toFloat()
                    },
                    stringRes(com.heyanle.easy_i18n.R.string.decrypting),
                    "${i + 1}/${tsFiles.size}",
                )
            }

            val ts = tsFiles[i]
            val tsh = targetTsFiles[i]
            val parent = tsh.parentFile ?: return false

            val tshTemp = File(parent, tsh.name + ".temp")
            // ts 文件不在 tsh && 文件存在 && tsh.temp 文件不存在这说明该文件解密过了，直接跳过
            if (!ts.exists() && tsh.exists() && !tshTemp.exists()) {
                continue
            }
            tsh.delete()
            tshTemp.delete()
            tshTemp.createNewFile()
            if (!tshTemp.canRead() || !tshTemp.canWrite()) {
                return false
            }
            val s = ts.readBytes()
            val segmentIv = entity.iv.takeIf { it.isNotBlank() }
                ?: "0x${segmentSequenceNumbers[i].toString(16).padStart(32, '0')}"
            val res = if (needDecrypt) M3U8Utils.decrypt(
                s,
                s.size,
                keyB,
                segmentIv,
                entity.method
            ) else s
            val decrypted = res ?: return false
            val rr = if (hlsOptions.segmentPayload.equals(
                    HlsOptions.SEGMENT_PAYLOAD_RAW,
                    ignoreCase = true,
                )
            ) decrypted else PngTailPayload.stripPngPrefix(decrypted)
            tshTemp.writeBytes(rr)
            if (!tshTemp.renameTo(tsh)) {
                tshTemp.copyTo(tsh, overwrite = true)
                tshTemp.delete()
            }
            if (!tsh.isFile || !tsh.canRead() || tsh.length() <= 0L) {
                return false
            }
            ts.delete()
        }
        if (!target.renameTo(realTarget)) {
            target.copyTo(realTarget, overwrite = true)
            target.delete()
        }
        return DownloadFileValidator.validateLocalM3u8(realTarget) == null
    }

    private fun writePlaylist(playlist: String, target: File): Boolean {
        return runCatching {
            target.delete()
            target.writeText(playlist, Charsets.UTF_8)
            DownloadFileValidator.validateLocalM3u8(target) == null
        }.getOrDefault(false)
    }

    private fun ffmpeg(cartoonDownloadRuntime: CartoonDownloadRuntime): Exception? {
        val m3u8 = cartoonDownloadRuntime.decryptFile
        if (m3u8 == null || !m3u8.exists() || !m3u8.canRead()) {
            return IOException("Transcode input is missing or unreadable")
        }
        val realTarget = File(ffmpegCacheFolder, "${cartoonDownloadRuntime.req.uuid}.mp4")
        ffmpegCacheFolder.mkdirs()
        synchronized(cartoonDownloadRuntime.lock) {
            cartoonDownloadRuntime.ffmpegFile = realTarget
        }
        val target = File(ffmpegCacheFolder, realTarget.name + ".temp.mp4")
        target.delete()
        realTarget.delete()
        cartoonDownloadRuntime.dispatchToBusThrottled(
            0f,
            stringRes(com.heyanle.easy_i18n.R.string.transcoding),
            "0%",
            force = true,
        )

        val completelyLatch = CountDownLatch(1)
        val transformError = AtomicReference<Exception?>(null)
        cartoonDownloadRuntime.transcodeNativeRunning = true
        try {
            VideoProcessManager.getInstance().transformM3U8ToMp4(
                m3u8.absolutePath,
                target.absolutePath,
                object : IVideoTransformListener {
                override fun onTransformProgress(progress: Float) {
                    val percent = progress.coerceIn(0f, 100f)
                    cartoonDownloadRuntime.dispatchToBusThrottled(
                        DownloadProgressUtils.nativePercentToFraction(percent),
                        stringRes(com.heyanle.easy_i18n.R.string.transcoding),
                        "${percent.toInt()}%"
                    )
                }

                override fun onTransformFailed(e: Exception?) {
                    transformError.set(e ?: IOException("Native transcode failed"))
                    cartoonDownloadRuntime.transcodeNativeRunning = false
                    completelyLatch.countDown()
                }

                override fun onTransformFinished() {
                    scope.launch {
                        try {
                            if (cartoonDownloadRuntime.isCanceled()) {
                                return@launch
                            }
                            if (!target.renameTo(realTarget)) {
                                target.copyTo(realTarget, overwrite = true)
                                target.delete()
                            }
                            DownloadFileValidator.validateMedia(realTarget)?.let {
                                realTarget.delete()
                                throw IllegalStateException(it)
                            }
                            M3U8Utils.deleteM3U8WithTs(m3u8.absolutePath)
                        } catch (e: Throwable) {
                            transformError.set(e.asException())
                        } finally {
                            cartoonDownloadRuntime.transcodeNativeRunning = false
                            completelyLatch.countDown()
                        }
                    }
                }
                }
            )
        } catch (e: Throwable) {
            transformError.set(e.asException())
            cartoonDownloadRuntime.transcodeNativeRunning = false
            completelyLatch.countDown()
        }
        while (!completelyLatch.await(1L, TimeUnit.SECONDS)) {
            if (cartoonDownloadRuntime.isCanceled()) {
                cartoonDownloadRuntime.dispatchToBusThrottled(
                    -1f,
                    stringRes(com.heyanle.easy_i18n.R.string.transcoding),
                    "正在等待原生转码退出",
                )
            }
        }
        if (cartoonDownloadRuntime.isCanceled()) {
            target.delete()
            realTarget.delete()
            return null
        }
        return transformError.get()
    }

    private fun String.withErrorMessage(error: Throwable?): String {
        val message = error?.message?.takeIf { it.isNotBlank() } ?: return this
        return "$this: $message"
    }

    private fun Throwable.asException(): Exception {
        return this as? Exception ?: RuntimeException(this)
    }

    override fun onTaskCompletely(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        cleanupCache(cartoonDownloadRuntime.req.uuid)
    }

    private fun cleanupTemporaryFiles(uuid: String) {
        File(decryptCacheFolder, "$uuid.m3u8.temp").delete()
        File(ffmpegCacheFolder, "$uuid.mp4.temp.mp4").delete()
    }

    private fun cleanupCache(uuid: String, includeFinalOutput: Boolean = false) {
        runCatching {
            M3U8Utils.deleteM3U8WithTs(File(decryptCacheFolder, "$uuid.m3u8").absolutePath)
            M3U8Utils.deleteM3U8WithTs(File(decryptCacheFolder, "$uuid.m3u8.temp").absolutePath)
            File(ffmpegCacheFolder, "$uuid.mp4.temp.mp4").delete()
            if (includeFinalOutput) {
                File(ffmpegCacheFolder, "$uuid.mp4").delete()
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

}
