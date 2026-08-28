package com.heyanle.easybangumi4.cartoon.story.download.action

import android.app.Application
import com.arialyy.aria.core.Aria
import com.arialyy.aria.core.common.HttpOption
import com.arialyy.aria.core.download.DownloadEntity
import com.arialyy.aria.core.download.DownloadReceiver
import com.arialyy.aria.core.download.DownloadTaskListener
import com.arialyy.aria.core.download.m3u8.M3U8VodOption
import com.arialyy.aria.core.inf.IEntity
import com.arialyy.aria.core.task.DownloadTask
import com.arialyy.aria.orm.DbEntity
import com.heyanle.easybangumi4.cartoon.entity.CartoonDownloadReq
import com.heyanle.easybangumi4.cartoon.story.download.CartoonDownloadPreference
import com.heyanle.easybangumi4.cartoon.story.download.runtime.CartoonDownloadRuntime
import com.heyanle.easybangumi4.plugin.api.entity.PlayerInfo
import com.heyanle.easybangumi4.ui.common.moeSnackBar
import com.heyanle.easybangumi4.utils.getCachePath
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by heyanle on 2024/8/3.
 * https://github.com/heyanLE
 */
class AriaAction(
    application: Application,
    downloadPreference: CartoonDownloadPreference
) : BaseAction, DownloadTaskListener {

    companion object {
        const val NAME = "AriaAction"
    }

    private val aria: DownloadReceiver by lazy {
        Aria.download(this@AriaAction).apply {
            register()
        }
    }

    private val ariaId2Runtime = ConcurrentHashMap<Long, CartoonDownloadRuntime>()
    private val downloadFolder = application.getCachePath("aria_download")

    init {
        Aria.init(application)
        Aria.get(application).apply {
            downloadConfig.apply {
                maxTaskNum = downloadPreference.downloadMaxCountPref.get().toInt()
                isConvertSpeed = true
            }
        }
    }

    // action
    override suspend fun canResume(cartoonDownloadReq: CartoonDownloadReq): Boolean {
        return withContext(Dispatchers.IO) {
            val task = aria.getFirstTaskWithExt(cartoonDownloadReq.uuid) ?: return@withContext false
            if (task.isComplete) {
                return@withContext true
            }
            (task.state == DownloadEntity.STATE_WAIT ||
                    task.state == DownloadEntity.STATE_COMPLETE ||
                    task.state == DownloadEntity.STATE_POST_PRE ||
                    task.state == DownloadEntity.STATE_RUNNING ||
                    task.state == DownloadEntity.STATE_STOP).apply {
                        // 不能恢复直接删除
                        if (!this) {
                            aria.load(task.id).cancel(true)
                        }
            }
        }
    }


    override suspend fun toggle(cartoonDownloadRuntime: CartoonDownloadRuntime): Boolean {
        val entity = aria.getDownloadEntity(cartoonDownloadRuntime.ariaId)
            ?: return false
        when(entity.state){
            IEntity.STATE_RUNNING, IEntity.STATE_WAIT -> {
                aria.load(cartoonDownloadRuntime.ariaId).ignoreCheckPermissions().stop()
            }
            IEntity.STATE_STOP -> {

                aria.load(cartoonDownloadRuntime.ariaId).ignoreCheckPermissions().resume()
            }
            else -> return false
        }
        return true
    }

    override fun push(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        "push aria action".logi("Action")
        val entity = aria.getFirstTaskWithExt(cartoonDownloadRuntime.req.uuid)?.let {
            if (it.canReuse()) {
                it
            } else {
                aria.load(it.id).cancel(true)
                null
            }
        }

        File(downloadFolder).mkdirs()
        if (entity != null) {
            cartoonDownloadRuntime.ariaId = entity.id
            cartoonDownloadRuntime.m3u8Entity = entity.m3U8Entity
            cartoonDownloadRuntime.ariaDownloadFilePath = entity.filePath
            ariaId2Runtime[entity.id] = cartoonDownloadRuntime
            if (entity.state == IEntity.STATE_STOP) {
                aria.load(cartoonDownloadRuntime.ariaId).ignoreCheckPermissions().resume()
            } else if (entity.state == IEntity.STATE_COMPLETE) {
                cartoonDownloadRuntime.m3u8Entity = entity.m3U8Entity
                cartoonDownloadRuntime.ariaDownloadFilePath = entity.filePath
                cartoonDownloadRuntime.stepCompletely(this)
            }
        } else {
            val playerInfo = cartoonDownloadRuntime.playerInfo ?: throw IllegalStateException("playerInfo is null")
            when (playerInfo.decodeType) {
                PlayerInfo.DECODE_TYPE_OTHER -> {
                    val file = File(downloadFolder, cartoonDownloadRuntime.req.uuid + ".mp4")
                    val path = file.absolutePath
                    val taskId = aria.load(playerInfo.uri)
                        .setExtendField(cartoonDownloadRuntime.req.uuid)
                        .option(HttpOption().apply {
                            playerInfo.header?.iterator()?.forEach {
                                addHeader(it.key, it.value)
                            }
                        })
                        .setFilePath(path)
                        .ignoreCheckPermissions()
                        .ignoreFilePathOccupy()
                        .create()
                    cartoonDownloadRuntime.ariaId = taskId
                    if (taskId != -1L) {
                        ariaId2Runtime[taskId] = cartoonDownloadRuntime
                    }
                    // pushCompletely(downloadItem, taskId)
                }

                PlayerInfo.DECODE_TYPE_HLS -> {
                    val path = File(downloadFolder, cartoonDownloadRuntime.req.uuid).absolutePath
                    val taskId = aria.load(playerInfo.uri)
                        .setExtendField(cartoonDownloadRuntime.req.uuid)
                        .option(HttpOption().apply {
                            playerInfo.header?.iterator()?.forEach {
                                addHeader(it.key, it.value)
                            }
                        })
                        .setFilePath(path)
                        .m3u8VodOption(playerInfo.uri.buildM3u8Option())
                        .ignoreFilePathOccupy()
                        .ignoreCheckPermissions()
                        .create()
                    cartoonDownloadRuntime.ariaId = taskId
                    if (taskId != -1L) {
                        ariaId2Runtime[taskId] = cartoonDownloadRuntime
                    }
                    // pushCompletely(downloadItem, taskId)
                }

                else -> {
                    throw IllegalStateException("unknown decodeType")
                    // error(downloadItem.uuid, stringRes(com.heyanle.easy_i18n.R.string.download_error))
                }
            }
            if (cartoonDownloadRuntime.ariaId == -1L) {
                throw IllegalStateException("new task error")
            }
        }
    }

    override fun onCancel(cartoonDownloadRuntime: CartoonDownloadRuntime) {
        aria.load(cartoonDownloadRuntime.ariaId)?.cancel(true)
    }


    // aria callback
    override fun onWait(task: DownloadTask?) {
        val entity = task?.entity ?: return
        val runtime = ariaId2Runtime[entity.id] ?: return
        runtime.dispatchProcessToBus(
            task,
            stringRes(com.heyanle.easy_i18n.R.string.waiting),
        )
    }

    override fun onPre(task: DownloadTask?) {
        //TODO("Not yet implemented")
    }

    override fun onTaskPre(task: DownloadTask?) {
        //TODO("Not yet implemented")
    }

    override fun onTaskResume(task: DownloadTask?) {
        onTaskRunning(task)
    }

    override fun onTaskStart(task: DownloadTask?) {
        //TODO("Not yet implemented")
    }

    override fun onTaskStop(task: DownloadTask?) {
        val entity = task?.entity ?: return
        val runtime = ariaId2Runtime[entity.id] ?: return
        runtime.dispatchProcessToBus(
            task,
            stringRes(com.heyanle.easy_i18n.R.string.pausing),
        )
    }

    override fun onTaskCancel(task: DownloadTask?) {

    }

    override fun onTaskFail(task: DownloadTask?, e: Exception?) {
        val entity = task?.entity ?: return
        val runtime = ariaId2Runtime[entity.id] ?: return
        synchronized(runtime.lock) {
            val detail = task.downloadDetail()
            val errorMsg = e?.downloadErrorMessage()
                ?: stringRes(com.heyanle.easy_i18n.R.string.download_error)
            runtime.error(
                errorMsg = if (detail.isBlank()) errorMsg else "$errorMsg | $detail",
                error = e
            )
        }
    }

    override fun onTaskComplete(task: DownloadTask?) {
        val entity = task?.entity ?: return
        val runtime = ariaId2Runtime[entity.id] ?: return
        synchronized(runtime.lock) {
            runtime.ariaDownloadFilePath = task.filePath
            runtime.m3u8Entity = task.entity.m3U8Entity
            runtime.stepCompletely(this)
        }

    }

    override fun onTaskRunning(task: DownloadTask?) {
        val entity = task?.entity ?: return
        val runtime = ariaId2Runtime[entity.id] ?: return
        runtime.dispatchProcessToBus(
            task,
            stringRes(com.heyanle.easy_i18n.R.string.downloading),
            throttle = true,
        )
    }

    override fun onNoSupportBreakPoint(task: DownloadTask?) {
        stringRes(com.heyanle.easy_i18n.R.string.no_support_break_point).moeSnackBar()
    }

    private fun CartoonDownloadRuntime.dispatchProcessToBus(
        task: DownloadTask,
        status: String,
        // Null 则展示网速
        subStatus: String? = null,
        throttle: Boolean = false,
    ) {

        val entity = task.entity
        val process = when {
            entity == null -> -1f
            entity.percent in 0..100 -> entity.percent / 100f
            entity.fileSize > 0L -> entity.currentProgress / entity.fileSize.toFloat()
            else -> -1f
        }

        val detail = subStatus ?: task.downloadDetail()
        if (throttle) {
            dispatchToBusThrottled(process, status, detail)
        } else {
            dispatchToBus(process, status, detail)
        }
    }

    private fun DownloadTask.downloadDetail(): String {
        val entity = this.entity ?: return ""
        val detail = arrayListOf<String>()
        if (entity.m3U8Entity != null) {
            detail.add("HLS/m3u8")
            val peerNum = entity.m3U8Entity.peerNum
            val peerIndex = entity.m3U8Entity.peerIndex
            if (peerNum > 0) {
                detail.add("分片 $peerIndex/$peerNum")
            }
            val method = entity.m3U8Entity.method
            if (!method.isNullOrBlank()) {
                detail.add(method)
            }
        } else {
            detail.add("MP4")
        }
        val current = entity.currentProgress
        val total = entity.fileSize
        if (total > 0L) {
            detail.add("${current.formatBytes()}/${total.formatBytes()}")
        } else if (current > 0L) {
            detail.add(current.formatBytes())
        } else {
            convertCurrentProgress?.takeIf { it.isNotBlank() }?.let(detail::add)
        }
        val speed = convertSpeed?.takeIf { it.isNotBlank() } ?: "${entity.speed.formatBytes()}/s"
        detail.add(speed)
        if (entity.percent in 0..100) {
            detail.add("${entity.percent}%")
        }
        return detail.joinToString(" | ")
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

    private fun String.buildM3u8Option(): M3U8VodOption {
        return M3U8VodOption().apply {
            setVodTsUrlConvert { m3u8Url, tsUrls ->
                val baseUrl = resolveM3u8Base(m3u8Url)
                tsUrls.map { baseUrl.resolveM3u8Url(it) }
            }
            setBandWidthUrlConverter { m3u8Url, bandWidthUrl ->
                resolveM3u8Base(m3u8Url).resolveM3u8Url(bandWidthUrl)
            }
            setUseDefConvert(false)
            generateIndexFile()
        }
    }

    private fun String.resolveM3u8Base(baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) {
            return this
        }
        return if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            baseUrl
        } else {
            resolveM3u8Url(baseUrl)
        }
    }

    private fun String.resolveM3u8Url(path: String): String {
        val trimmedPath = path.trim()
        if (trimmedPath.startsWith("http://") || trimmedPath.startsWith("https://")) {
            return trimmedPath
        }
        return try {
            URI(this).resolve(trimmedPath).toString()
        } catch (e: Throwable) {
            e.printStackTrace()
            trimmedPath
        }
    }

    private fun Throwable.downloadErrorMessage(): String {
        val messages = generateSequence(this as Throwable?) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        return messages.joinToString(": ").ifBlank {
            stringRes(com.heyanle.easy_i18n.R.string.download_error)
        }
    }

    private fun DownloadReceiver.getFirstTaskWithExt(
        ext: String
    ): DownloadEntity? {
        return DbEntity.findFirst<DownloadEntity>(
            DownloadEntity::class.java,
            "str=?",
            ext
        )
    }

    private fun DownloadEntity.canReuse(): Boolean {
        return state == DownloadEntity.STATE_WAIT ||
                state == DownloadEntity.STATE_COMPLETE ||
                state == DownloadEntity.STATE_POST_PRE ||
                state == DownloadEntity.STATE_RUNNING ||
                state == DownloadEntity.STATE_STOP
    }
}
