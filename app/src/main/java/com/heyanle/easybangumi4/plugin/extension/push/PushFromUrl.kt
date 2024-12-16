package com.heyanle.easybangumi4.plugin.extension.push

import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.provider.JsExtensionProvider
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionCryLoader
import com.heyanle.easybangumi4.utils.downloadTo
import com.heyanle.easybangumi4.utils.stringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File

/**
 * 从 url 下载，一行一个
 * Created by heyanlin on 2024/10/29.
 */
class PushFromUrl(
    private val cacheFolder: String,
    private val extensionController: ExtensionController,
) {
    companion object {
        const val CACHE_REPO_JSONL_NAME = "extension_repo.jsonl"
    }

    suspend fun invoke(
        scope: CoroutineScope,
        url: String,
        container: ExtensionPushController.ExtensionPushTaskContainer
    ) {
        scope.load(url, container)
    }

    private suspend fun CoroutineScope.load(
        url: String,
        container: ExtensionPushController.ExtensionPushTaskContainer
    ) {
        if (url.isEmpty()) {
            container.dispatchError(stringRes(R.string.is_empty))
            return
        }
        container.dispatchLoadingMsg(stringRes(R.string.loading))
        val cacheFolderFile = File(cacheFolder)
        cacheFolderFile.mkdirs()

        val repoFile = File(cacheFolder, CACHE_REPO_JSONL_NAME)
        repoFile.delete()
        // 1. 下载 jsonl
        kotlin.runCatching {
            url.downloadTo(repoFile.absolutePath)
        }.onFailure {
            it.printStackTrace()
        }

        if (!repoFile.exists() || repoFile.length().toInt() == 0){
            container.dispatchError(stringRes(R.string.load_fail))
            return
        }

        val taskList = repoFile.bufferedReader().use { reader ->
            val lines = reader.readLines()
            if (lines.isNotEmpty() && lines.first().startsWith("{")) {
                // If the first line begins with "{", process it as JSON
                lines.mapNotNull { line ->
                    val jsonObject = JSONObject(line)
                    val uri = jsonObject.optString("url")
                    val key = jsonObject.optString("key")
                    if (uri.isEmpty() || key.isEmpty()) {
                        null // Skip invalid entries
                    } else {
                        File(cacheFolder, key) to uri
                    }
                }
            } else {
                // If the first line does not begin with "{", return the fallback
                listOf(repoFile to "local")
            }
        }

        val allCount = taskList.size
        var completelyDownloadCount: Int = 0
        var completelyLoadCount: Int = 0

        taskList.map {
            // 下载
            try {
                if(it.second != "local"){
                    it.first.delete()
                    kotlin.runCatching {
                        it.second.downloadTo(it.first.absolutePath)
                    }.onFailure {
                        it.printStackTrace()
                    }
                }

                yield()
                if (it.first.exists() && it.first.length() > 0){
                    completelyDownloadCount ++
                    container.dispatchLoadingMsg(stringRes( R.string.downloading) + "${completelyDownloadCount}/${allCount}")
                    it.first
                } else {
                    null
                }
            } catch (e: Throwable) {
                null
            }
        }.filterIsInstance<File>().map {
            // 根据 Mask 判断是否是加密
            val buffer = ByteArray(JSExtensionCryLoader.FIRST_LINE_MARK.size)

            val size = it.inputStream().use {
                it.read(buffer)
            }
            if (size == JSExtensionCryLoader.FIRST_LINE_MARK.size && buffer.contentEquals(JSExtensionCryLoader.FIRST_LINE_MARK)) {
                it to true
            } else {
                it to false
            }
        }.map {
            // 添加后缀
            val targetFile = if (it.second) {
                File(cacheFolder, it.first.name + ".${JsExtensionProvider.EXTENSION_CRY_SUFFIX}")
            } else {
                File(cacheFolder, it.first.name + ".${JsExtensionProvider.EXTENSION_SUFFIX}")
            }
            it.first.renameTo(targetFile)
            yield()
            targetFile
        }.let {
            // 添加完再扫描
            extensionController.withNoWatching {
                yield()
                it.map {
                    // 加载
                    val e = extensionController.appendExtensionFile(it)
                    yield()
                    if (e == null) {
                        completelyLoadCount ++
                        container.dispatchLoadingMsg(stringRes( R.string.loading) + "${completelyLoadCount}/${completelyDownloadCount}")
                    }
                    e
                }
            }
        }

       cacheFolderFile.deleteRecursively()
       cacheFolderFile.mkdirs()

        val msg = "${stringRes(R.string.succeed)} ${completelyLoadCount}\n" +
                "${stringRes(R.string.download_fail)} ${allCount - completelyDownloadCount}\n" +
                "${stringRes(R.string.load_fail)} ${completelyDownloadCount - completelyLoadCount}"
        yield()
        if (completelyLoadCount == 0) {
            container.dispatchError(msg)
        } else {
            container.dispatchCompletely(msg)
        }
    }
}