package com.heyanle.easybangumi4.plugin.extension.provider

import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.BuildConfig
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.extension.loader.ExtensionLoader
import com.heyanle.easybangumi4.plugin.extension.loader.ExtensionLoaderFactory
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Created by heyanle on 2024/7/29.
 * https://github.com/heyanLE
 */
class JsExtensionProvider(
    private val jsRuntimeProvider: JSRuntimeProvider,
    private val jsFileExtensionFolder: String,
    dispatcher: CoroutineDispatcher,
    cacheFolder: String,
): AbsFolderExtensionProvider(jsFileExtensionFolder, cacheFolder, dispatcher){

    companion object {
        const val TAG = "FileJsExtensionProvider"

        // 扩展名
        const val EXTENSION_SUFFIX = "ebg.js"

        // 加密后的后缀
        const val EXTENSION_CRY_SUFFIX = "ebg.jsc"

        fun isEndWithJsExtensionSuffix(path: String) = path.endsWith(EXTENSION_SUFFIX) || path.endsWith(EXTENSION_CRY_SUFFIX)
    }

    override fun checkName(displayName: String): Boolean {
        return displayName.endsWith(EXTENSION_CRY_SUFFIX)
                || displayName.endsWith(EXTENSION_SUFFIX)

    }

    override fun getNameWhenLoad(displayName: String, time: Long, atomicLong: Long): String {
        val suffix = when  {
            displayName.endsWith(EXTENSION_CRY_SUFFIX) -> EXTENSION_CRY_SUFFIX
            else -> EXTENSION_SUFFIX
        }
        return "${time}-${atomicLong}.${suffix}"
    }

    override fun loadExtensionLoader(fileList: List<File>): List<ExtensionLoader> {
        return ExtensionLoaderFactory.getFileJsExtensionLoaders(fileList, jsRuntimeProvider)
    }

    override fun coverExtensionLoaderList(loaderList: List<ExtensionLoader>): List<ExtensionLoader> {
        if (BuildConfig.DEBUG) {
            val file = APP.assets.open("extension_test.js").use {
                File(cacheFolder).mkdirs()
                val file = File(cacheFolder, "test.js")
                file.outputStream().use { output ->
                    it.copyTo(output)
                }
                file
            }
            return loaderList + JSExtensionLoader(file, jsRuntimeProvider)
        }
        return super.coverExtensionLoaderList(loaderList)
    }

    override fun innerAppendExtension(displayName: String, inputStream: InputStream) {
        fileObserver.stopWatching()
        val fileName = getNameWhenLoad(displayName, System.currentTimeMillis(), atomicLong.getAndIncrement())
        // "${System.currentTimeMillis()}-${atomicLong.getAndIncrement()}${getSuffix()}"
        File(cacheFolder).mkdirs()
        File(folderPath).mkdirs()

        val cacheFile = File(cacheFolder, fileName)

        val targetFileTemp = File(folderPath, "${fileName}.temp")
        cacheFile.createNewFile()
        inputStream.use { input ->
            cacheFile.outputStream().use { out ->
                input.copyTo(out)
            }
        }
        cacheFile.deleteOnExit()
        val loader = loadExtensionLoader(listOf(cacheFile)).firstOrNull()
            ?: throw IOException("无法创建插件加载器")
        if (!loader.canLoad()) throw IOException("插件文件格式不受支持")

        val loaded = loader.load()
        val ext = loaded as? ExtensionInfo.Installed
            ?: throw IOException((loaded as? ExtensionInfo.InstallError)?.errMsg ?: "插件加载失败")
        val source = ext.sources.firstOrNull() ?: throw IOException("插件中没有可安装的番源")
        val suffix = when {
            displayName.endsWith(EXTENSION_CRY_SUFFIX) -> EXTENSION_CRY_SUFFIX
            else -> EXTENSION_SUFFIX
        }
        val targetFile = File(folderPath, source.key + "." + suffix)
        File(folderPath, source.key + "." + EXTENSION_SUFFIX).delete()
        File(folderPath, source.key + "." + EXTENSION_CRY_SUFFIX).delete()
        cacheFile.copyTo(targetFileTemp, overwrite = true)
        if (!targetFileTemp.renameTo(targetFile)) {
            targetFileTemp.copyTo(targetFile, overwrite = true)
            targetFileTemp.delete()
        }
        if (!targetFile.isFile) throw IOException("插件文件写入失败")
        cacheFolderFile.deleteRecursively()
        cacheFolderFile.mkdirs()
        scanFolder()
    }

}
