package com.heyanle.easybangumi4.web.socket

import com.google.gson.Gson
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
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

class SourceWebSocket(
    handshakeRequest: NanoHTTPD.IHTTPSession,
    private val jsRuntime: JSRuntimeProvider,
) :
    NanoWSD.WebSocket(handshakeRequest),
    CoroutineScope by MainScope(),
    Debug.Callback {

     private val gson = Gson()
     private val outgoing = Channel<String>(Channel.UNLIMITED)

     override fun onOpen() {
         launch(IO) {
             for (payload in outgoing) {
                 runCatching { send(payload) }.onFailure { it.printOnDebug() }
             }
         }
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
                     send("数据必须为Json格式")
                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                     return@launch
                 }

                 val debugBean = message.textPayload.jsonTo<Map<String, String>>()
                 if (debugBean != null) {
                     val tag = debugBean["tag"]
                     val key = debugBean["key"]
                     if(tag == "debug" && key != null){
                         Debug.cancelDebug(true)
                         when(val extension = JSExtensionInnerLoader(key, jsRuntime).load()) {
                                 is ExtensionInfo.InstallError -> {
                                     send(gson.toJson(Debug.Event(type = "error", title = "插件加载失败", message = extension.errMsg)))
                                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                                     return@launch
                                 }
                                 is ExtensionInfo.Installed -> {
                                     Debug.callback = this@SourceWebSocket
                                     Debug.startDebug(this, extension)
                                 }
                         }
                     } else if (tag == "select") {
                         if (Debug.callback !== this@SourceWebSocket) {
                             emit(Debug.Event(type = "error", title = "会话已失效", message = "请重新点击开始调试"))
                             return@launch
                         }
                         val stage = debugBean["stage"]
                         val index = debugBean["index"]?.toIntOrNull()
                         if (stage == null || index == null) {
                             emit(Debug.Event(type = "error", title = "命令错误", message = "选择命令缺少步骤或序号"))
                         } else {
                             Debug.select(this, stage, index)
                         }
                     } else if (tag == "page") {
                         if (Debug.callback !== this@SourceWebSocket) {
                             emit(Debug.Event(type = "error", title = "会话已失效", message = "请重新点击开始调试"))
                             return@launch
                         }
                         val pageKey = debugBean["key"]?.toIntOrNull()
                         if (pageKey == null) {
                             emit(Debug.Event(type = "error", title = "命令错误", message = "分页参数无效"))
                         } else {
                             Debug.loadPage(this, pageKey)
                         }
                     } else {
                         emit(Debug.Event(type = "error", title = "命令错误", message = "未知命令: ${tag.orEmpty()}"))
                     }
                 } else {
                     send("数据必须为Json格式")
                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                     return@launch
                 }
             }.onFailure {
                 emit(
                     Debug.Event(
                         type = "error",
                         title = "调试命令执行失败",
                         message = it.stackTraceToString()
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
            gson.toJson(Debug.Event(type = "log", message = msg, fields = mapOf("state" to state.toString())))
        )
    }

    override fun emit(event: Debug.Event) {
        outgoing.trySend(gson.toJson(event))
    }

}
