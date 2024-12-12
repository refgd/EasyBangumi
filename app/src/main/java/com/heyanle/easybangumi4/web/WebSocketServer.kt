package com.heyanle.easybangumi4.web

import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.web.services.WebService
import fi.iki.elonen.NanoWSD
import com.heyanle.easybangumi4.web.socket.SourceWebSocket

class WebSocketServer(port: Int) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        WebService.serve()


        val jsRuntimeProvider: JSRuntimeProvider by lazy {
            JSRuntimeProvider(1)
        }

        return when (handshake.uri) {
            "/sourceDebug" -> {
                SourceWebSocket(handshake, jsRuntimeProvider)
            }
            else -> null
        }
    }
}
