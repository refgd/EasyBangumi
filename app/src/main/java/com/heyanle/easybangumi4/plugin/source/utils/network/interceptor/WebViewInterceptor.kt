package com.heyanle.easybangumi4.plugin.source.utils.network.interceptor

import android.content.Context
import android.webkit.WebView
import com.heyanle.easybangumi4.plugin.api.utils.api.NetworkHelper
import com.heyanle.easybangumi4.plugin.source.utils.network.WebViewHelperV2Impl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Created by HeYanLe on 2023/2/3 22:11.
 * https://github.com/heyanLE
 */
abstract class WebViewInterceptor(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val webViewHelperV2Impl: WebViewHelperV2Impl,
) : Interceptor {

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldIntercept(response)) {
            return response
        }

//        if (!WebViewUtil.supportsWebView(context)) {
//            Log.d("WebViewInterceptor", "no WebView support ${this::class.java.simpleName}")
//            return response
//        }

        return intercept(chain, request, response)
    }

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            // Keeping unsafe header makes webview throw [net::ERR_INVALID_ARGUMENT]
            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitFor60Seconds() {
        await(60, TimeUnit.SECONDS)
    }

    fun getWebViewOrThrow(request: Request): WebView {
        return webViewHelperV2Impl.getGlobalWebView().apply {
            settings.userAgentString = request.header("User-Agent") ?: networkHelper.defaultLinuxUA
        }
    }
}

// Based on [IsRequestHeaderSafe] in https://source.chromium.org/chromium/chromium/src/+/main:services/network/public/cpp/header_util.cc
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}

private val unsafeHeaderNames = listOf(
    "content-length",
    "host",
    "trailer",
    "te",
    "upgrade",
    "cookie2",
    "keep-alive",
    "transfer-encoding",
    "set-cookie"
)
