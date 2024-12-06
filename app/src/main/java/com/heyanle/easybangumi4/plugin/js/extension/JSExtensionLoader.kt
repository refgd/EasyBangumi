package com.heyanle.easybangumi4.plugin.js.extension

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Javascript
import com.heyanle.easybangumi4.crash.SourceCrashController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.extension.loader.AbsExtensionLoader
import com.heyanle.easybangumi4.plugin.extension.loader.ExtensionLoader
import com.heyanle.easybangumi4.plugin.extension.provider.JsExtensionProvider
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.js.runtime.JSScope
import com.heyanle.easybangumi4.plugin.js.source.JsSource
import java.io.File

/**
 * Created by heyanle on 2024/7/27.
 * https://github.com/heyanLE
 */
class JSExtensionLoader(
    private val file: File,
    private val jsRuntime: JSRuntimeProvider,
    private val realPath: String = "",
): ExtensionLoader {

    companion object {
        const val TAG = "JSExtensionLoader"

        const val JS_SOURCE_TAG_KEY = "key"
        const val JS_SOURCE_TAG_LABEL = "label"
        const val JS_SOURCE_TAG_VERSION_NAME = "versionName"
        const val JS_SOURCE_TAG_VERSION_CODE = "versionCode"
        const val JS_SOURCE_TAG_LIB_VERSION = "libVersion"
        const val JS_SOURCE_TAG_COVER = "cover"
        const val JS_SOURCE_HAS_PREF = "hasPreference"
        const val JS_SOURCE_HAS_SEARCH = "hasSearch"

    }

    override val key: String
        get() = "js:${file.path}"

    override fun canLoad(): Boolean {
        return file.isFile && file.exists() && file.canRead() && file.name.endsWith(JsExtensionProvider.EXTENSION_SUFFIX)
    }

    override fun load(): ExtensionInfo? {
        if (!file.exists() || !file.canRead()) {
            return null
        }

        val map = HashMap<String, String>()

        file.reader().buffered().use {
            while(true) {
                val line = it.readLine() ?: break
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
                        map[JS_SOURCE_HAS_PREF] = "1"
                    }
                    if (line.contains("function SearchComponent_search(")) {
                        map[JS_SOURCE_HAS_SEARCH] = "1"
                    }
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
        map["sourcePath"] = if (realPath.isNotEmpty()) realPath else file.absolutePath

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
            sources = listOf(JsSource(map, file, jsScope)) ,
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


}