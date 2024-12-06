package com.heyanle.easybangumi4.plugin.extension.loader

import com.heyanle.easybangumi4.plugin.extension.provider.JsExtensionProvider
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionCryLoader
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.utils.logi
import java.io.File

/**
 * Created by heyanlin on 2023/10/25.
 */
object ExtensionLoaderFactory {
    fun getFileJsExtensionLoaders(
        fileList: List<File>,
        jsRuntime: JSRuntimeProvider
    ): List<ExtensionLoader> {
        return try {
            fileList.map {
                if (it.name.endsWith(JsExtensionProvider.EXTENSION_CRY_SUFFIX)) {
                    logi("load js file: ${it.name}")
                    JSExtensionCryLoader(it, jsRuntime)
                } else {
                    logi("load js file: ${it.name}")
                    JSExtensionLoader(it, jsRuntime)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

}