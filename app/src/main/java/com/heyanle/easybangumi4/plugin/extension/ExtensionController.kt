package com.heyanle.easybangumi4.plugin.extension

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.heyanle.easybangumi4.crash.SourceCrashController
import com.heyanle.easybangumi4.plugin.extension.provider.JsExtensionProvider
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionCryLoader
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionLoader
import com.heyanle.easybangumi4.plugin.js.extension.JsExtensionPackage
import com.heyanle.easybangumi4.plugin.js.source.JsSource
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * InstalledAppExtensionProvider    ↘
 * FileApkExtensionProvider         → ExtensionController
 * FileJsExtensionProvider          ↗
 * Created by heyanlin on 2023/10/24.
 */
class ExtensionController(
    private val context: Context,
    val jsExtensionFolder: String,
    private val cacheFolder: String,
) {

    data class ExportPackageTarget(
        val uri: Uri,
        val exists: Boolean,
    )

    companion object {
        private const val TAG = "ExtensionController"

    }

    private val dispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)


    data class ExtensionState(
        val loading: Boolean = true,
        val extensionInfoMap: Map<String, ExtensionInfo> = emptyMap()
    )
    private val _state = MutableStateFlow<ExtensionState>(
        ExtensionState()
    )
    val state = _state.asStateFlow()

    private var firstLoad = true

    // ============================ 插件 Provider ============================

    // js 文件
    private val jsRuntimeProvider = JSRuntimeProvider(2)
    private val jsExtensionProvider: JsExtensionProvider by lazy {
        JsExtensionProvider(
            jsRuntimeProvider,
            jsExtensionFolder,
            dispatcher,
            cacheFolder
        )
    }



    fun init() {
        SourceCrashController.onExtensionStart()
        jsExtensionProvider.init()
        SourceCrashController.onExtensionEnd()

        scope.launch {
            combine(
                jsExtensionProvider.flow
            ) { (fileJsExtensionProviderState) ->
                // 首次必须所有 Provider 都加载完才算加载完
                if (firstLoad &&
                    (fileJsExtensionProviderState.loading)) {
                    return@combine ExtensionState(
                        loading = true,
                        extensionInfoMap = emptyMap()
                    )
                }
                firstLoad = false
                val map = mutableMapOf<String, ExtensionInfo>()
                fileJsExtensionProviderState.extensionMap.forEach {
                    map[it.key] = it.value
                }
                ExtensionState(
                    loading = fileJsExtensionProviderState.loading,
                    extensionInfoMap = map
                )
            }.collectLatest { ext ->
                _state.update {
                    ext
                }
            }
        }

    }

    fun scanFolder() {
        jsExtensionProvider.scanFolder()
    }

    suspend fun <R> withNoWatching(block:suspend  ()-> R): R? {
        jsExtensionProvider.stopWatching()
        val r = try {
            block()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }

        delay(500)
        jsExtensionProvider.startWatching()

        return r
    }


    // 如果 type 没指定，会根据文件后缀名判断
    suspend fun appendExtensionUri(uri: Uri, type: Int = -1) : Exception? {
        return withContext(dispatcher) {
            try {
                val uniFile = UniFile.fromUri(context, uri)
                if (uniFile?.exists() != true || !uniFile.canRead()){
                    return@withContext IOException("文件不存在或无法读取")
                }

                val name = uniFile.name ?: ""
                if (JsExtensionPackage.isPackageName(name)) {
                    return@withContext appendJsPackage(uniFile.openInputStream())
                } else if (JsExtensionProvider.isEndWithJsExtensionSuffix(name) || type == ExtensionInfo.TYPE_JS_FILE) {
                    return@withContext appendJsExtensionStream(name, uniFile.openInputStream())
                } else {
                    return@withContext IOException("不支持的文件类型")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext e
            }
        }
    }

    suspend fun appendExtensionFile(file: File, type: Int = -1) : Exception? {
        return withContext(dispatcher) {
            try {
                if (!file.exists() || !file.canRead()){
                    return@withContext IOException("文件不存在或无法读取")
                }

                val name = file.name ?: ""
                if (JsExtensionPackage.isPackageName(name)) {
                    return@withContext appendJsPackage(file.inputStream())
                } else if (JsExtensionProvider.isEndWithJsExtensionSuffix(name) || type == ExtensionInfo.TYPE_JS_FILE) {
                    return@withContext appendJsExtensionStream(name, file.inputStream())
                } else {
                    return@withContext IOException("不支持的文件类型")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext e
            }
        }
    }

    suspend fun appendJsExtensionSource(
        displayName: String,
        extensionKey: String,
        source: String,
        iconBytes: ByteArray? = null,
    ): Exception? {
        val error = appendJsExtensionStream(
            displayName,
            ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)),
        )
        if (error == null && iconBytes != null) {
            return runCatching {
                installLocalIcon(extensionKey, iconBytes)
                jsExtensionProvider.scanFolder()
                jsExtensionProvider.awaitScanFolder()
                null
            }.getOrElse { it as? Exception ?: IOException(it) }
        }
        return error
    }

    suspend fun exportJsExtensionPackage(sourceKey: String, targetUri: Uri): Exception? =
        withContext(dispatcher) {
            runCatching {
                val extension = state.value.extensionInfoMap.values
                    .filterIsInstance<ExtensionInfo.Installed>()
                    .firstOrNull { info -> info.sources.any { it.key == sourceKey } }
                    ?: throw IOException("找不到番源插件")
                if (extension.loadType != ExtensionInfo.TYPE_JS_FILE) throw IOException("仅 JS 番源支持导出")
                val source = extension.sources.firstOrNull { it.key == sourceKey } as? JsSource
                    ?: throw IOException("番源不是 JS 插件")
                val sourceFile = File(extension.sourcePath)
                if (!sourceFile.isFile) throw IOException("插件源文件不存在")

                val exportFolder = File(cacheFolder, "package_export").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val encryptedFile = File(exportFolder, "extension.ebg.jsc")
                JsExtensionPackage.encrypt(sourceFile, encryptedFile)
                val iconBytes = loadExportIcon(source, sourceFile)
                val outputStream = context.contentResolver.openOutputStream(targetUri, "wt")
                    ?: throw IOException("无法创建或覆盖导出文件")
                outputStream.use { output ->
                    JsExtensionPackage.write(
                        output,
                        JsExtensionPackage.Manifest(
                            key = source.key,
                            label = source.label,
                            versionName = source.version,
                            versionCode = source.versionCode.toLong(),
                        ),
                        encryptedFile,
                        iconBytes,
                    )
                }
                exportFolder.deleteRecursively()
            }.exceptionOrNull() as? Exception
        }

    suspend fun prepareJsExtensionPackageTarget(
        sourceKey: String,
        folderUri: Uri,
    ): ExportPackageTarget = withContext(dispatcher) {
        val folder = UniFile.fromUri(context, folderUri)
            ?: throw IOException("无法访问导出目录")
        if (!folder.isDirectory || !folder.canWrite()) throw IOException("导出目录不可写")
        val fileName = "$sourceKey.${JsExtensionPackage.SUFFIX}"
        val existing = folder.findFile(fileName)
        if (existing != null) {
            if (!existing.canWrite()) throw IOException("同名插件包不可覆盖")
            ExportPackageTarget(existing.uri, true)
        } else {
            val created = folder.createFile(fileName) ?: throw IOException("无法创建导出文件")
            ExportPackageTarget(created.uri, false)
        }
    }

    private suspend fun appendJsExtensionStream(displayName: String, inputStream: InputStream): Exception? {
        val error = suspendCancellableCoroutine { continuation ->
            jsExtensionProvider.appendExtensionStream(
                displayName,
                inputStream,
            ) { error ->
                if (continuation.isActive) continuation.resume(error)
            }
        }
        if (error == null) {
            jsExtensionProvider.awaitScanFolder()
            val providerState = jsExtensionProvider.flow.value
            _state.value = ExtensionState(
                loading = providerState.loading,
                extensionInfoMap = providerState.extensionMap,
            )
        }
        return error
    }

    private suspend fun appendJsPackage(inputStream: InputStream): Exception? {
        return try {
            val packageFolder = File(cacheFolder, "package_import").apply {
                deleteRecursively()
                mkdirs()
            }
            val extracted = inputStream.use { JsExtensionPackage.extract(it, packageFolder) }
            val loaded = JSExtensionCryLoader(extracted.extensionFile, jsRuntimeProvider).load()
            val extension = loaded as? ExtensionInfo.Installed
                ?: throw IOException((loaded as? ExtensionInfo.InstallError)?.errMsg ?: "包内插件加载失败")
            if (extension.key != extracted.manifest.key ||
                extension.label != extracted.manifest.label ||
                extension.versionName != extracted.manifest.versionName ||
                extension.versionCode != extracted.manifest.versionCode
            ) {
                throw IOException("插件包清单与插件元数据不一致")
            }
            val iconBytes = extracted.iconFile.readBytes()
            val error = appendJsExtensionStream("${extension.key}.ebg.jsc", extracted.extensionFile.inputStream())
            if (error != null) return error
            installLocalIcon(extension.key, iconBytes)
            jsExtensionProvider.scanFolder()
            jsExtensionProvider.awaitScanFolder()
            packageFolder.deleteRecursively()
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun installLocalIcon(key: String, bytes: ByteArray) {
        JsExtensionPackage.validateIcon(bytes)
        val folder = File(jsExtensionFolder).apply { mkdirs() }
        val target = JsExtensionPackage.localIconFile(folder, key)
        val temp = File(folder, "${target.name}.temp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (!target.isFile) throw IOException("本地图标写入失败")
    }

    private fun loadExportIcon(source: JsSource, sourceFile: File): ByteArray {
        val localIcon = JsExtensionPackage.localIconFile(sourceFile.parentFile ?: File(jsExtensionFolder), source.key)
        if (localIcon.isFile) return localIcon.readBytes().also(JsExtensionPackage::validateIcon)
        val cover = source.map[JSExtensionLoader.JS_SOURCE_TAG_COVER].orEmpty()
        val bytes = runCatching { readCoverBytes(cover, sourceFile) }.getOrNull()
        return if (bytes != null) {
            runCatching { JsExtensionPackage.validateIcon(bytes); bytes }
                .getOrElse { JsExtensionPackage.defaultIcon(source.label) }
        } else {
            JsExtensionPackage.defaultIcon(source.label)
        }
    }

    private fun readCoverBytes(cover: String, sourceFile: File): ByteArray? {
        if (cover.isBlank()) return null
        if (cover.startsWith("data:", ignoreCase = true)) {
            return Base64.decode(cover.substringAfter(','), Base64.DEFAULT)
        }
        val uri = Uri.parse(cover)
        val input = when (uri.scheme?.lowercase()) {
            "http", "https" -> URL(cover).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "EasyBangumi")
            }.getInputStream()
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> File(uri.path.orEmpty()).takeIf { it.isFile }?.inputStream()
            null -> File(sourceFile.parentFile, cover).takeIf { it.isFile }?.inputStream()
            else -> null
        } ?: return runCatching { Base64.decode(cover, Base64.DEFAULT) }.getOrNull()
        return input.use { readLimited(it, JsExtensionPackage.MAX_ICON_BYTES) }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBytes) throw IOException("图标超过 4 MiB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun hasJsExtension(key: String): Boolean {
        return File(jsExtensionFolder, "$key.ebg.js").isFile ||
            File(jsExtensionFolder, "$key.ebg.jsc").isFile
    }


    fun appendExtensionPath(path: String, callback: ((Exception?) -> Unit)? = null) {
        scope.launch {
            try {

                val file = File(path)
                if (!file.exists() || !file.canRead()) {
                    callback?.invoke(IOException("文件不存在或无法读取"))
                    return@launch
                }

                if (JsExtensionProvider.isEndWithJsExtensionSuffix(file.name)) {
                    jsExtensionProvider.appendExtensionPath(path)
                } else {
                    callback?.invoke(IOException("不支持的文件类型"))
                }
                callback?.invoke(null)
            } catch (e: IOException) {
                e.printStackTrace()
                callback?.invoke(e)
            }
        }
    }
}
