package com.heyanle.easybangumi4.plugin.js.extension

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Javascript
import com.heyanle.easybangumi4.crash.SourceCrashController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.extension.loader.AbsExtensionLoader
import com.heyanle.easybangumi4.plugin.extension.loader.ExtensionLoader
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_HAS_PREF
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_HAS_SEARCH
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_TAG_KEY
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_TAG_LABEL
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_TAG_LIB_VERSION
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_TAG_VERSION_CODE
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader.Companion.JS_SOURCE_TAG_VERSION_NAME
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.js.runtime.JSScope
import com.heyanle.easybangumi4.plugin.js.source.JsSource
import com.heyanle.easybangumi4.utils.logi

/**
 * Created by heyanle on 2024/7/30.
 * https://github.com/heyanLE
 */
class JSExtensionInnerLoader(
    val js: String,
    val jsRuntime: JSRuntimeProvider,
): ExtensionLoader {

    override val key: String
        get() = "js:inner"

    override fun load(): ExtensionInfo {

        val map = HashMap<String, String>()

        val lineList = js.split("\n")
        for (line in lineList) {
            if (line.isEmpty()){
                continue
            }
            if (line.startsWith("//")){
                var firstAtIndex = -1
                var spacerAfterAtIndex = -1

                line.forEachIndexed { index, c ->
                    if (firstAtIndex == -1 && c == '@'){
                        firstAtIndex = index
                    }
                    if (firstAtIndex != -1 && spacerAfterAtIndex == -1 && c == ' '){
                        spacerAfterAtIndex = index
                    }
                    if (firstAtIndex != -1 && spacerAfterAtIndex != -1){
                        return@forEachIndexed
                    }
                }

                if (firstAtIndex == -1 || spacerAfterAtIndex == -1){
                    continue
                }

                val key = line.substring(firstAtIndex + 1, spacerAfterAtIndex)
                val value = line.substring(spacerAfterAtIndex + 1)
                map[key] = value
            }else{
                if (line.contains("function PreferenceComponent_getPreference(")) {
                    map[JSExtensionLoader.JS_SOURCE_HAS_PREF] = "1"
                }
                if (line.contains("function SearchComponent_search(")) {
                    map[JSExtensionLoader.JS_SOURCE_HAS_SEARCH] = "1"
                }
            }
        }

        val jsScope = JSScope(jsRuntime.getRuntime())

        val label = map[JS_SOURCE_TAG_LABEL] ?: ""
        val key = map[JS_SOURCE_TAG_KEY] ?: ""
        val versionName = map[JS_SOURCE_TAG_VERSION_NAME] ?: ""
        val versionCode = map[JS_SOURCE_TAG_VERSION_CODE]?.toLongOrNull() ?: -1L
        val libVersion = map[JS_SOURCE_TAG_LIB_VERSION]?.toIntOrNull() ?: -1
        val hasPref = map[JS_SOURCE_HAS_PREF]?.toIntOrNull() ?: 0
        val hasSearch = map[JS_SOURCE_HAS_SEARCH]?.toIntOrNull() ?: 0
        map["sourcePath"] = ""

        val libErrorMsg = if (SourceCrashController.needBlock) {
            "安全模式阻断"
        } else if (libVersion == -1 || versionCode == -1L
            || key.isBlank() || label.isBlank() || versionName.isBlank()
        ) {
            "元数据错误"
        } else if (libVersion > AbsExtensionLoader.LIB_VERSION_MAX) {
            "纯纯看看版本过低"
        } else if (libVersion < AbsExtensionLoader.LIB_VERSION_MIN) {
            "插件版本过低"
        } else {
            null
        }

        if (libErrorMsg != null) {
            return ExtensionInfo.InstallError(
                key = key,
                label = label,
                pkgName = key,
                versionName = versionName,
                versionCode = versionCode,
                libVersion = libVersion,
                readme = "",
                icon = Icons.Filled.Javascript,
                errMsg = libErrorMsg,
                loadType = ExtensionInfo.TYPE_JS_FILE,
                hasPref = hasPref,
                hasSearch = hasSearch,
                sourcePath = map["sourcePath"] ?: "",
                publicPath = map["sourcePath"] ?: "",
                folderPath = map["sourcePath"] ?: "",
                exception = null
            )
        }

        return ExtensionInfo.Installed(
            key = key,
            label = label,
            pkgName = key,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            readme = "",
            icon = Icons.Filled.Javascript,
            sources = listOf(JsSource(map, js, jsScope)) ,
            resources = null,
            loadType = ExtensionInfo.TYPE_JS_FILE,
            hasPref = hasPref,
            hasSearch = hasSearch,
            sourcePath = map["sourcePath"] ?: "",
            publicPath = map["sourcePath"] ?: "",
            folderPath = map["sourcePath"] ?: "",
            extension = null,
        )

    }

    override fun canLoad(): Boolean {
        return true
    }
}