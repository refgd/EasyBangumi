package com.heyanle.easybangumi4.web.socket

import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionInnerLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.source.Debug
import com.heyanle.easybangumi4.utils.isJson
import com.heyanle.easybangumi4.utils.jsonTo
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.printOnDebug
import com.heyanle.easybangumi4.utils.runOnIO
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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

     override fun onOpen() {
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
         cancel()
         Debug.cancelDebug(true)
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
                         JSExtensionInnerLoader(key, jsRuntime).load()?.let {
                             when(it) {
                                 is ExtensionInfo.InstallError -> {
                                     send(it.errMsg)
                                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                                     return@launch
                                 }
                                 is ExtensionInfo.Installed -> {
                                     Debug.callback = this@SourceWebSocket
                                     Debug.startDebug(this, it)
                                 }
                                 else -> null
                             }
                         }
                     }
                 } else {
                     send("数据必须为Json格式")
                     close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                     return@launch
                 }
             }
         }
     }

     override fun onPong(pong: NanoWSD.WebSocketFrame?) {

     }

     override fun onException(exception: IOException?) {
         Debug.cancelDebug(true)
     }

    override fun printLog(state: Int, msg: String) {
        runOnIO {
            runCatching {
                send(msg)
                if (state == -1 || state == 1000) {
                    Debug.cancelDebug(true)
                    close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "调试结束", false)
                }
            }.onFailure {
                it.printOnDebug()
            }
        }
    }

}
