package com.heyanle.easybangumi4.web.socket

import com.google.gson.Gson
import android.util.Base64
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionInnerLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.source.Debug
import com.heyanle.easybangumi4.utils.isJson
import com.heyanle.easybangumi4.utils.jsonTo
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.printOnDebug
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class SourceWebSocket(
    handshakeRequest: NanoHTTPD.IHTTPSession,
    private val jsRuntime: JSRuntimeProvider,
    private val extensionController: ExtensionController,
) :
    NanoWSD.WebSocket(handshakeRequest),
    CoroutineScope by MainScope(),
    Debug.Callback {

     private val gson = Gson()
     private val outgoing = Channel<String>(Channel.UNLIMITED)
     private val sessionId = UUID.randomUUID().toString()
     @Volatile
     private var requestId: String? = null

     override fun onOpen() {
         launch(IO) {
             for (payload in outgoing) {
                 runCatching { send(payload) }.onFailure { it.printOnDebug() }
             }
         }
         emit(
             Debug.Event(
                 type = "hello",
                 title = "EasyBangumi source debugger",
                 fields = linkedMapOf(
                     "protocolVersion" to PROTOCOL_VERSION.toString(),
                     "capabilities" to "search,stableSelector,paging,requestId,debugCapture,install,localIcon"
                 )
             )
         )
         launch(IO) {
             kotlin.runCatching {
                 while (isOpen) {
                     ping("ping".toByteArray())
                     delay(30000)
                 }
             }
         }
     }

     override fun onClose(
         code: NanoWSD.WebSocketFrame.CloseCode?,
         reason: String?,
         initiatedByRemote: Boolean
     ) {
         outgoing.close()
         cancel()
         if (Debug.callback === this) {
             Debug.cancelDebug(true)
         }
     }

     override fun onMessage(message: NanoWSD.WebSocketFrame) {
         launch(IO) {
             kotlin.runCatching {
                 if (!message.textPayload.isJson()) {
                     emit(Debug.Event(type = "error", title = "命令错误", message = "数据必须为 JSON 格式", errorCode = "invalid_json"))
                     return@launch
                 }

                 val debugBean = message.textPayload.jsonTo<Map<String, String>>()
                 if (debugBean != null) {
                     val tag = debugBean["tag"]
                     requestId = debugBean["requestId"]
                     val key = debugBean["key"]
                     if (tag == "capabilities") {
                         emit(
                             Debug.Event(
                                 type = "capabilities",
                                 title = "调试协议能力",
                                 fields = linkedMapOf(
                                     "protocolVersion" to PROTOCOL_VERSION.toString(),
                                     "commands" to "debug,select,page,search,install,capabilities",
                                     "selectors" to "index,id,label"
                                 )
                             )
                         )
                     } else if(tag == "debug" && key != null){
                         Debug.cancelDebug(true)
                         when(val extension = JSExtensionInnerLoader(key, jsRuntime).load()) {
                                 is ExtensionInfo.InstallError -> {
                                     emit(Debug.Event(type = "error", title = "插件加载失败", message = extension.errMsg, errorCode = "extension_load_failed"))
                                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                                     return@launch
                                 }
                                 is ExtensionInfo.Installed -> {
                                     Debug.callback = this@SourceWebSocket
                                     Debug.startDebug(this, extension)
                                 }
                         }
                     } else if (tag == "install" && key != null) {
                         installSource(key, debugBean["icon"])
                     } else if (tag == "select") {
                         if (Debug.callback !== this@SourceWebSocket) {
                             emit(Debug.Event(type = "error", title = "会话已失效", message = "请重新点击开始调试", errorCode = "session_expired"))
                             return@launch
                         }
                         val stage = debugBean["stage"]
                         val index = debugBean["index"]?.toIntOrNull()
                         val id = debugBean["id"]
                         val label = debugBean["label"]
                         if (stage == null || (index == null && id.isNullOrBlank() && label.isNullOrBlank())) {
                             emit(Debug.Event(type = "error", title = "命令错误", message = "选择命令缺少步骤或选择器", errorCode = "invalid_select_command"))
                         } else {
                             Debug.select(this, stage, index, id, label)
                         }
                     } else if (tag == "search") {
                         if (Debug.callback !== this@SourceWebSocket) {
                             emit(Debug.Event(type = "error", title = "会话已失效", message = "请重新点击开始调试", errorCode = "session_expired"))
                             return@launch
                         }
                         val keyword = debugBean["keyword"]
                         val pageKey = (debugBean["page"] ?: debugBean["key"])?.toIntOrNull() ?: 0
                         if (keyword.isNullOrBlank()) {
                             emit(Debug.Event(type = "error", title = "命令错误", message = "搜索命令缺少关键词", errorCode = "invalid_search_command"))
                         } else {
                             Debug.search(this, keyword, pageKey)
                         }
                     } else if (tag == "page") {
                         if (Debug.callback !== this@SourceWebSocket) {
                             emit(Debug.Event(type = "error", title = "会话已失效", message = "请重新点击开始调试", errorCode = "session_expired"))
                             return@launch
                         }
                         val pageKey = debugBean["key"]?.toIntOrNull()
                         if (pageKey == null) {
                             emit(Debug.Event(type = "error", title = "命令错误", message = "分页参数无效", errorCode = "invalid_page_command"))
                         } else {
                             Debug.loadPage(this, pageKey)
                         }
                     } else {
                         emit(Debug.Event(type = "error", title = "命令错误", message = "未知命令: ${tag.orEmpty()}", errorCode = "unknown_command"))
                     }
                 } else {
                     emit(Debug.Event(type = "error", title = "命令错误", message = "无法解析 JSON 命令", errorCode = "invalid_json_command"))
                     return@launch
                 }
             }.onFailure {
                 emit(
                     Debug.Event(
                         type = "error",
                         title = "调试命令执行失败",
                         message = it.stackTraceToString(),
                         errorCode = "command_failed"
                     )
                 )
             }
         }
     }

    private suspend fun installSource(sourceCode: String, iconData: String?) {
        if (sourceCode.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            emit(Debug.Event(type = "error", title = "添加插件失败", message = "插件源码不能超过 2 MiB", errorCode = "source_too_large"))
            return
        }
        emit(Debug.Event(type = "busy", title = "正在添加插件"))
        when (val extension = JSExtensionInnerLoader(sourceCode, jsRuntime, false).load()) {
            is ExtensionInfo.InstallError -> {
                emit(Debug.Event(type = "error", title = "添加插件失败", message = extension.errMsg, errorCode = "extension_validation_failed"))
            }
            is ExtensionInfo.Installed -> {
                if (!extension.key.matches(SAFE_EXTENSION_KEY)) {
                    emit(Debug.Event(type = "error", title = "添加插件失败", message = "插件 key 只能包含字母、数字、点、下划线和连字符", errorCode = "invalid_extension_key"))
                    return
                }
                val existed = extensionController.hasJsExtension(extension.key)
                val iconBytes = try {
                    iconData?.takeIf { it.isNotBlank() }?.let { encoded ->
                        val payload = encoded.substringAfter(',', encoded)
                        val bytes = Base64.decode(payload, Base64.DEFAULT)
                        if (bytes.size > MAX_ICON_BYTES) throw IOException("图标不能超过 4 MiB")
                        bytes
                    }
                } catch (e: Exception) {
                    emit(Debug.Event(type = "error", title = "添加插件失败", message = "本地图标无效: " + e.message, errorCode = "invalid_local_icon"))
                    return
                }
                val error = extensionController.appendJsExtensionSource(
                    "${extension.key}.ebg.js",
                    extension.key,
                    sourceCode,
                    iconBytes,
                )
                if (error != null) {
                    emit(Debug.Event(type = "error", title = "添加插件失败", message = error.message ?: error.stackTraceToString(), errorCode = "extension_install_failed"))
                    return
                }
                emit(
                    Debug.Event(
                        type = "installed",
                        title = if (existed) "插件已更新" else "插件已添加",
                        message = "${extension.label} ${extension.versionName}",
                        fields = linkedMapOf(
                            "key" to extension.key,
                            "label" to extension.label,
                            "versionName" to extension.versionName,
                            "versionCode" to extension.versionCode.toString(),
                        ),
                    )
                )
            }
        }
    }

     override fun onPong(pong: NanoWSD.WebSocketFrame?) {

     }

     override fun onException(exception: IOException?) {
         if (Debug.callback === this) {
             Debug.cancelDebug(true)
         }
     }

    override fun printLog(state: Int, msg: String) {
        outgoing.trySend(
            gson.toJson(withProtocol(Debug.Event(type = "log", message = msg, fields = mapOf("state" to state.toString()))))
        )
    }

    override fun emit(event: Debug.Event) {
        outgoing.trySend(gson.toJson(withProtocol(event)))
    }

    private fun withProtocol(event: Debug.Event): Debug.Event = event.copy(
        protocolVersion = PROTOCOL_VERSION,
        sessionId = sessionId,
        requestId = requestId,
    )

    companion object {
        const val PROTOCOL_VERSION = 2
        private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
        private const val MAX_ICON_BYTES = 4 * 1024 * 1024
        private val SAFE_EXTENSION_KEY = Regex("[A-Za-z0-9._-]+")
    }

}
