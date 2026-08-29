package com.heyanle.easybangumi4.cartoon.story.download.action

import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.cartoon.entity.CartoonDownloadReq
import com.heyanle.easybangumi4.cartoon.story.download.runtime.CartoonDownloadRuntime
import com.heyanle.easybangumi4.cartoon.story.local.LocalCartoonPreference
import com.heyanle.easybangumi4.utils.stringRes
import com.heyanle.inject.api.get
import com.heyanle.inject.core.Inject
import com.hippo.unifile.UniFile
import org.jsoup.nodes.Element
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Created by heyanle on 2024/8/4.
 * https://github.com/heyanLE
 */
class CopyAndNfoAction: BaseAction {

    companion object {
        const val NAME = "CopyAndNfoAction"
        private const val COPY_BUFFER_SIZE = 256 * 1024
    }

    override fun isAsyncAction(): Boolean {
        return false
    }

    override suspend fun canResume(cartoonDownloadReq: CartoonDownloadReq): Boolean {
        return false
    }

    override suspend fun toggle(cartoonDownloadRuntime: CartoonDownloadRuntime): Boolean {
        return false
    }

    override fun push(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        val runtime = cartoonDownloadRuntime
        val localPref: LocalCartoonPreference = Inject.get()
        cartoonDownloadRuntime.dispatchToBus(
            -1f,
            stringRes(R.string.copying),
            "准备复制"
        )



        val sourcePath = runtime.filePathBeforeCopy
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile || !sourceFile.canRead() || sourceFile.length() <= 0L) {
            throw IllegalStateException("source file is not exists, empty or can not read: $sourcePath")
        }

        val targetFolder = UniFile.fromUri(APP, localPref.realBangumiLocalUri.value)
            ?: throw IllegalStateException("target folder is null")

        val targetCartoonFolder = targetFolder.findFile(runtime.req.toLocalItemId)
            ?.let { if (it.isDirectory) it else null }
            ?: targetFolder.createDirectory(runtime.req.toLocalItemId)
            ?: throw IllegalStateException("target cartoon folder is null")
        val mediaNameP =
            "${runtime.req.toLocalItemId} ${runtime.req.toEpisodeTitle} S1E${runtime.req.toEpisode}"
        val mediaName = "${mediaNameP}.mp4"
        val mediaTempName = "$mediaName.temp"
        val nfoName = "$mediaNameP.nfo"
        val nfoTempName = "$nfoName.temp"
        targetCartoonFolder.findFile(mediaTempName)?.delete()
        targetCartoonFolder.findFile(nfoTempName)?.delete()
        val existingMedia = targetCartoonFolder.findFile(mediaName)
        if (existingMedia != null) {
            if (!existingMedia.canRead() || existingMedia.length() != sourceFile.length()) {
                throw IllegalStateException("目标视频文件已存在且与当前任务不一致：$mediaName")
            }
            val recoveryNfo = targetCartoonFolder.createFile(nfoTempName)
                ?: throw IllegalStateException("无法创建 NFO 临时文件")
            try {
                recoveryNfo.openOutputStream().bufferedWriter().use {
                    it.write(runtime.createNfoText())
                }
                targetCartoonFolder.findFile(nfoName)?.delete()
                if (!recoveryNfo.renameTo(nfoName)) {
                    throw IllegalStateException("NFO 文件提交失败")
                }
                sourceFile.delete()
                runtime.stepCompletely(this)
                return
            } finally {
                targetCartoonFolder.findFile(nfoTempName)?.delete()
            }
        }

        val targetMediaFile = targetCartoonFolder.createFile(mediaTempName)
            ?: throw IllegalStateException("无法创建目标视频临时文件")
        val nfoTempFile = targetCartoonFolder.createFile(nfoTempName)
            ?: run {
                targetMediaFile.delete()
                throw IllegalStateException("无法创建 NFO 临时文件")
            }
        var nfoCommitted = false
        try {
            if (!targetMediaFile.canWrite() || !nfoTempFile.canWrite()) {
                throw IllegalStateException("目标文件不可写")
            }
            val totalBytes = sourceFile.length()
            var copiedBytes = 0L
            var lastDispatchTime = 0L
            sourceFile.inputStream().buffered(COPY_BUFFER_SIZE).use { inp ->
                targetMediaFile.openOutputStream().buffered(COPY_BUFFER_SIZE).use { outp ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        if (runtime.isCanceled()) {
                            throw CancellationException("copy canceled")
                        }
                        val read = inp.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        outp.write(buffer, 0, read)
                        copiedBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastDispatchTime >= 500L || copiedBytes >= totalBytes) {
                            lastDispatchTime = now
                            val progressBytes = copiedBytes
                            cartoonDownloadRuntime.dispatchToBusThrottled(
                                progressBytes / totalBytes.toFloat(),
                                stringRes(R.string.copying),
                                "${progressBytes.formatBytes()}/${totalBytes.formatBytes()}",
                                force = progressBytes >= totalBytes
                            )
                        }
                    }
                    outp.flush()
                }
            }
            if (targetMediaFile.length() != totalBytes) {
                throw IllegalStateException("视频复制不完整：${targetMediaFile.length()}/$totalBytes bytes")
            }

            nfoTempFile.openOutputStream().bufferedWriter().use {
                it.write(runtime.createNfoText())
            }

            targetCartoonFolder.findFile(nfoName)?.delete()
            if (!nfoTempFile.renameTo(nfoName)) {
                throw IllegalStateException("NFO 文件提交失败")
            }
            nfoCommitted = true
            if (!targetMediaFile.renameTo(mediaName)) {
                throw IllegalStateException("视频文件提交失败")
            }

            sourceFile.delete()
            runtime.stepCompletely(this)
        } finally {
            targetCartoonFolder.findFile(mediaTempName)?.delete()
            targetCartoonFolder.findFile(nfoTempName)?.delete()
            if (nfoCommitted && targetCartoonFolder.findFile(mediaName) == null) {
                targetCartoonFolder.findFile(nfoName)?.delete()
            }
        }
    }

    override fun onCancel(cartoonDownloadRuntime: CartoonDownloadRuntime) {

    }

    private fun CartoonDownloadRuntime.createNfoText(): String {
        val details = Element("episodedetails")
        details.appendElement("title").text(req.toEpisodeTitle)
        details.appendElement("season").text("1")
        details.appendElement("episode").text(req.toEpisode.toString())
        return details.outerHtml()
    }

    private fun Long.formatBytes(): String {
        if (this < 1024L) {
            return "$this B"
        }
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = this.toDouble()
        var unitIndex = -1
        do {
            value /= 1024.0
            unitIndex++
        } while (value >= 1024.0 && unitIndex < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
