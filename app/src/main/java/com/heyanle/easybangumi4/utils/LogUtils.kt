package com.heyanle.easybangumi4.utils

import android.util.Log
import com.heyanle.easybangumi4.BuildConfig

@Suppress("unused")
object LogUtils {
    @JvmStatic
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    inline fun d(tag: String, lazyMsg: () -> String) {
        Log.d(tag, lazyMsg())
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        Log.w(tag, msg)
    }

}

fun Throwable.printOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}

val Throwable.stackTraceStr: String
    get() {
        val stackTrace = stackTraceToString()
        val lMsg = this.localizedMessage ?: "noErrorMsg"
        return when {
            stackTrace.isNotEmpty() -> stackTrace
            else -> lMsg
        }
    }