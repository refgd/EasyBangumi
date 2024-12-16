package com.heyanle.easybangumi4.plugin.extension

import android.content.Context
import android.net.Uri
import com.heyanle.easybangumi4.crash.SourceCrashController
import com.heyanle.easybangumi4.plugin.extension.provider.JsExtensionProvider
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * InstalledAppExtensionProvider    ↘
 * FileApkExtensionProvider         → ExtensionController
 * FileJsExtensionProvider          ↗
 * Created by heyanlin on 2023/10/24.
 */
class ExtensionController(
    private val context: Context,
    val jsExtensionFolder: String,
    private val cacheFolder: String,
) {

    companion object {
        private const val TAG = "ExtensionController"

    }

    private val dispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)


    data class ExtensionState(
        val loading: Boolean = true,
        val extensionInfoMap: Map<String, ExtensionInfo> = emptyMap()
    )
    private val _state = MutableStateFlow<ExtensionState>(
        ExtensionState()
    )
    val state = _state.asStateFlow()

    private var firstLoad = true

    // ============================ 插件 Provider ============================

    // js 文件
    private val jsRuntimeProvider = JSRuntimeProvider(2)
    private val jsExtensionProvider: JsExtensionProvider by lazy {
        JsExtensionProvider(
            jsRuntimeProvider,
            jsExtensionFolder,
            dispatcher,
            cacheFolder
        )
    }



    fun init() {
        SourceCrashController.onExtensionStart()
        jsExtensionProvider.init()
        SourceCrashController.onExtensionEnd()

        scope.launch {
            combine(
                jsExtensionProvider.flow
            ) { (fileJsExtensionProviderState) ->
                // 首次必须所有 Provider 都加载完才算加载完
                if (firstLoad &&
                    (fileJsExtensionProviderState.loading)) {
                    return@combine ExtensionState(
                        loading = true,
                        extensionInfoMap = emptyMap()
                    )
                }
                firstLoad = false
                val map = mutableMapOf<String, ExtensionInfo>()
                fileJsExtensionProviderState.extensionMap.forEach {
                    map[it.key] = it.value
                }
                ExtensionState(
                    loading = fileJsExtensionProviderState.loading,
                    extensionInfoMap = map
                )
            }.collectLatest { ext ->
                _state.update {
                    ext
                }
            }
        }

    }

    fun scanFolder() {
        jsExtensionProvider.scanFolder()
    }

    suspend fun <R> withNoWatching(block:suspend  ()-> R): R? {
        jsExtensionProvider.stopWatching()
        val r = try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }

        delay(500)
        jsExtensionProvider.startWatching()

        return r
    }


    // 如果 type 没指定，会根据文件后缀名判断
    suspend fun appendExtensionUri(uri: Uri, type: Int = -1) : Exception? {
        return withContext(dispatcher) {
            try {
                val uniFile = UniFile.fromUri(context, uri)
                if (uniFile?.exists() != true || !uniFile.canRead()){
                    return@withContext IOException("文件不存在或无法读取")
                }

                val name = uniFile.name ?: ""
                if (JsExtensionProvider.isEndWithJsExtensionSuffix(name) || type == ExtensionInfo.TYPE_JS_FILE) {
                    jsExtensionProvider.appendExtensionStream(name, uniFile.openInputStream())
                } else {
                    return@withContext IOException("不支持的文件类型")
                }
                return@withContext null
            } catch (e: IOException) {
                e.printStackTrace()
                return@withContext e
            }
        }
    }

    suspend fun appendExtensionFile(file: File, type: Int = -1) : Exception? {
        return withContext(dispatcher) {
            try {
                if (!file.exists() || !file.canRead()){
                    return@withContext IOException("文件不存在或无法读取")
                }

                val name = file.name ?: ""
                if (JsExtensionProvider.isEndWithJsExtensionSuffix(name) || type == ExtensionInfo.TYPE_JS_FILE) {
                    jsExtensionProvider.appendExtensionStream(name, file.inputStream())
                } else {
                    return@withContext IOException("不支持的文件类型")
                }
                return@withContext null
            } catch (e: IOException) {
                e.printStackTrace()
                return@withContext e
            }
        }
    }


    fun appendExtensionPath(path: String, callback: ((Exception?) -> Unit)? = null) {
        scope.launch {
            try {

                val file = File(path)
                if (!file.exists() || !file.canRead()) {
                    callback?.invoke(IOException("文件不存在或无法读取"))
                    return@launch
                }

                if (JsExtensionProvider.isEndWithJsExtensionSuffix(file.name)) {
                    jsExtensionProvider.appendExtensionPath(path)
                } else {
                    callback?.invoke(IOException("不支持的文件类型"))
                }
                callback?.invoke(null)
            } catch (e: IOException) {
                e.printStackTrace()
                callback?.invoke(e)
            }
        }
    }
}