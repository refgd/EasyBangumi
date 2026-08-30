package com.heyanle.easybangumi4.ui.ai

import com.google.gson.JsonObject
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionInnerLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.source.Debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class AiSourceToolExecutor(
    private val extensionController: ExtensionController,
) : AutoCloseable {
    private val validationRuntime = JSRuntimeProvider(1)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun execute(name: String, arguments: JsonObject, session: AiSession): String = when (name) {
        "source_get" -> {
            require(session.sourceCode.isNotBlank()) { "当前会话还没有源码" }
            sourceTextWindow(StringReader(session.sourceCode), arguments, "当前会话源码")
        }
        "source_docs" -> sourceComponentDocs(arguments.string("topic"))
        "source_replace" -> replaceSource(arguments, session).markErrorIf {
            it.startsWith("源码已写回会话，但")
        }
        "source_validate" -> validateResult(session.sourceCode).markErrorIf { it.startsWith("校验失败") }
        "source_debug" -> debugSource(session, arguments).markErrorIf {
            it.startsWith("校验失败") || it.startsWith("调试失败")
        }
        "installed_sources" -> installedSources()
        "installed_source_read" -> readInstalledSource(arguments).markErrorIf {
            it.startsWith("未找到源") || it.startsWith("该源不是") || it.endsWith("为空")
        }
        "http_request" -> httpRequest(arguments)
        "media_probe" -> mediaProbe(arguments).markErrorIf {
            it.startsWith("媒体探测失败") || it.contains("清单失败")
        }
        else -> error("未知工具: $name")
    }

    override fun close() {
        validationRuntime.release()
    }

    private suspend fun replaceSource(arguments: JsonObject, session: AiSession): String {
        val code = arguments.string("code")
        require(code.isNotBlank()) { "code 不能为空" }
        var validationError: Throwable? = null
        val metadata = try {
            validate(code, requireBaseUrl = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            validationError = error
            null
        }
        val updated = session.copy(
            title = metadata?.label?.takeIf { it.isNotBlank() } ?: session.title,
            sourceCode = code,
            sourceKey = metadata?.key?.removeSuffix(".__debug__") ?: session.sourceKey,
            sourceVersionName = metadata?.versionName ?: session.sourceVersionName,
        )
        AiWorkspaceStore.saveSession(updated)
        if (metadata == null) {
            return "源码已写回会话，但校验未通过，未自动安装: ${validationError?.message ?: "未知错误"}"
        }
        require(metadata.key.matches(SAFE_KEY)) {
            "自动安装失败: key 只能包含字母、数字、点、下划线和连字符"
        }
        val error = extensionController.appendJsExtensionSource(
            "${metadata.key}.ebg.js",
            metadata.key,
            code,
        )
        return if (error == null) {
            "源码已写回会话并自动更新安装"
        } else {
            "源码已写回会话，但自动安装失败: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private suspend fun validate(
        code: String,
        requireBaseUrl: Boolean = false,
    ): ExtensionInfo.Installed {
        val loaded = JSExtensionInnerLoader(code, validationRuntime, false).load()
        if (loaded is ExtensionInfo.InstallError) {
            error("${loaded.errMsg}${loaded.exception?.message?.let { ": $it" }.orEmpty()}")
        }
        val installed = loaded as ExtensionInfo.Installed
        validateSourceAssembly(installed, requireBaseUrl)
        return installed
    }

    private suspend fun validateResult(code: String): String {
        if (code.isBlank()) return "校验失败: 源码为空"
        return try {
            val result = validate(code)
            "校验通过: key=${result.key}, label=${result.label}, version=${result.versionName}, versionCode=${result.versionCode}, libVersion=${result.libVersion}，Rhino 语法与组件装配通过"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "校验失败: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private suspend fun debugSource(session: AiSession, arguments: JsonObject): String {
        val extension = try {
            validate(session.sourceCode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return "校验失败: ${error.message ?: error.javaClass.simpleName}"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val completion = CompletableDeferred<String>()
        val events = mutableListOf<String>()
        val keyword = arguments.string("searchKeyword")
        val requestedStopAfter = arguments.string("stopAfter")
        val stopAfter = if (requestedStopAfter.isBlank()) {
            session.defaultDebugStopAfter(keyword.isNotBlank())
        } else {
            AiDebugStopAfter.fromWireName(requestedStopAfter)
                ?: return "调试失败: stopAfter 无效: $requestedStopAfter"
        }
        if (stopAfter == AiDebugStopAfter.SEARCH && keyword.isBlank()) {
            return "调试失败: 验证搜索阶段时 searchKeyword 不能为空"
        }
        val requestedPageKey = arguments.intOrNull("pageKey") ?: 0
        if (requestedPageKey < 0) return "调试失败: pageKey 不能小于 0"
        var danmakuStarted = false
        var catalogPageRequested = false
        val preferred = mapOf(
            "main" to arguments.intOrNull("mainIndex"),
            "sub" to arguments.intOrNull("subIndex"),
            "content" to arguments.intOrNull("contentIndex"),
            "search" to arguments.intOrNull("contentIndex"),
            "playLine" to arguments.intOrNull("playLineIndex"),
            "episode" to arguments.intOrNull("episodeIndex"),
        )
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                synchronized(events) { events += "log[$state] $msg" }
            }

            override fun emit(event: Debug.Event) {
                val summary = buildString {
                    append(event.type)
                    event.stage?.let { append(" stage=").append(it) }
                    if (event.title.isNotBlank()) append(" ").append(event.title)
                    if (event.message.isNotBlank()) append(": ").append(event.message.take(2_000))
                    event.pageKey?.let { append(" pageKey=").append(it) }
                    event.nextPageKey?.let { append(" nextPageKey=").append(it) }
                    if (event.fields.isNotEmpty()) {
                        append(" ").append(
                            sanitizeDebugFields(event.fields).entries.joinToString { "${it.key}=${it.value}" },
                        )
                    }
                    if (event.options.isNotEmpty()) {
                        append(" options=").append(event.options.take(20).joinToString { "${it.index}:${it.label}" })
                    }
                }
                synchronized(events) { events += summary }
                when (event.type) {
                    "selection" -> {
                        val stage = event.stage ?: return
                        if ((stage == "content" || stage == "search") && event.options.isEmpty()) {
                            completion.complete("调试失败: ${event.title} 返回空列表")
                            return
                        }
                        if (
                            stage == "content" &&
                            event.pageKey != requestedPageKey &&
                            !catalogPageRequested
                        ) {
                            catalogPageRequested = true
                            Debug.loadPage(scope, requestedPageKey)
                            return
                        }
                        val reachedTarget =
                            (stopAfter == AiDebugStopAfter.CATALOG && stage == "content") ||
                                (stopAfter == AiDebugStopAfter.SEARCH && stage == "search") ||
                                (stopAfter == AiDebugStopAfter.DETAIL && stage == "playLine")
                        if (reachedTarget) {
                            completion.complete("调试通过: 已验证到 ${stopAfter.wireName} 阶段")
                            return
                        }
                        val requested = preferred[stage]
                        val index = requested?.takeIf { value -> event.options.any { it.index == value } }
                            ?: if (stage == "episode") event.options.lastOrNull()?.index else event.options.firstOrNull()?.index
                        if (index == null) completion.complete("调试失败: ${event.title} 没有可选项")
                        else Debug.select(scope, stage, index)
                    }
                    "ready" -> {
                        if (stopAfter == AiDebugStopAfter.DANMAKU && !danmakuStarted) {
                            danmakuStarted = true
                            Debug.loadDanmaku(scope)
                        } else {
                            completion.complete("调试通过: 已验证到 ${stopAfter.wireName} 阶段")
                        }
                    }
                    "error" -> completion.complete("调试失败: ${event.message.ifBlank { event.title }}")
                }
            }
        }
        return try {
            Debug.cancelDebug(true)
            Debug.callback = callback
            Debug.startDebug(scope, extension)
            if (keyword.isNotBlank()) Debug.search(scope, keyword, requestedPageKey)
            val result = withTimeout(DEBUG_TIMEOUT_MS) { completion.await() }
            "$result\n${synchronized(events) { events.joinToString("\n") }}".take(MAX_TOOL_OUTPUT_CHARS)
        } finally {
            if (Debug.callback === callback) Debug.cancelDebug(true)
            scope.cancel()
        }
    }

    private fun installedSources(): String {
        val rows = extensionController.state.value.extensionInfoMap.values
            .filterIsInstance<ExtensionInfo.Installed>()
            .filter { it.loadType == ExtensionInfo.TYPE_JS_FILE }
            .flatMap { extension -> extension.sources.map { "${it.key}\t${it.label}\t${extension.versionName}" } }
        return rows.ifEmpty { listOf("没有已安装的 JS 源") }.joinToString("\n")
    }

    private fun readInstalledSource(arguments: JsonObject): String {
        val key = arguments.string("key")
        val extension = extensionController.state.value.extensionInfoMap.values
            .filterIsInstance<ExtensionInfo.Installed>()
            .firstOrNull { info -> info.sources.any { it.key == key } }
            ?: return "未找到源: $key"
        val file = File(extension.sourcePath)
        if (!file.isFile || !file.name.endsWith(".js")) return "该源不是可读取的明文 JS 插件"
        return file.reader(Charsets.UTF_8).use { sourceTextWindow(it, arguments, "已安装源 $key") }
    }

    private fun sourceTextWindow(reader: Reader, arguments: JsonObject, label: String): String {
        val startChar = arguments.intOrNull("startChar")?.coerceAtLeast(0) ?: 0
        val maxChars = arguments.intOrNull("maxChars")
            ?.coerceIn(MIN_SOURCE_CHARS, MAX_SOURCE_CHARS)
            ?: DEFAULT_SOURCE_CHARS
        val slice = readTextSlice(reader, startChar, maxChars)
        if (slice.text.isEmpty() && startChar == 0) return "$label 为空"
        return buildString {
            append(label)
            append(": startChar=").append(slice.startChar)
            append(", returnedChars=").append(slice.text.length)
            slice.nextStartChar?.let { append(", truncated=true, nextStartChar=").append(it) }
            append("\n\n").append(slice.text)
        }
    }

    private fun httpRequest(arguments: JsonObject): String {
        val url = arguments.string("url")
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "仅支持 HTTP/HTTPS URL" }
        val method = arguments.string("method").ifBlank { "GET" }.uppercase()
        val body = arguments.string("body")
        val startChar = arguments.intOrNull("startChar")?.coerceAtLeast(0) ?: 0
        val maxChars = arguments.intOrNull("maxChars")
            ?.coerceIn(MIN_HTTP_CHARS, MAX_HTTP_CHARS)
            ?: DEFAULT_HTTP_CHARS
        val requestHeaders = arguments.getAsJsonObject("headers")
        val requestMediaType = requestHeaders?.entrySet()
            ?.firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
            ?.value?.asString?.toMediaTypeOrNull()
            ?: "text/plain; charset=utf-8".toMediaType()
        val request = Request.Builder().url(url).apply {
            requestHeaders?.entrySet()?.forEach { (key, value) -> header(key, value.asString) }
            if (method == "GET" || method == "HEAD") method(method, null)
            else method(method, body.toRequestBody(requestMediaType))
        }.build()
        httpClient.newCall(request).execute().use { response ->
            val slice = response.body?.charStream()?.use { readTextSlice(it, startChar, maxChars) }
                ?: AiTextSlice("", startChar, false)
            val headers = response.headers.names()
                .filter { it.lowercase() in SAFE_RESPONSE_HEADERS }
                .joinToString("\n") { "$it: ${response.header(it).orEmpty()}" }
            return buildString {
                append("HTTP ").append(response.code)
                append("\nfinalUrl=").append(response.request.url)
                append("\nstartChar=").append(slice.startChar)
                append(", returnedChars=").append(slice.text.length)
                slice.nextStartChar?.let { append(", truncated=true, nextStartChar=").append(it) }
                if (headers.isNotBlank()) append('\n').append(headers)
                append("\n\n").append(slice.text)
            }
        }
    }

    private fun mediaProbe(arguments: JsonObject): String {
        val rawUrl = arguments.string("url")
        require(rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) { "仅支持 HTTP/HTTPS URL" }
        val headers = arguments.getAsJsonObject("headers")
        fun fetch(url: String): MediaFetch {
            val request = Request.Builder().url(url).apply {
                headers?.entrySet()?.forEach { (key, value) -> header(key, value.asString) }
                header("Range", "bytes=0-${MAX_MEDIA_SAMPLE_BYTES - 1}")
            }.build()
            httpClient.newCall(request).execute().use { response ->
                val bytes = response.body?.source()?.let { source ->
                    val buffer = Buffer()
                    var remaining = MAX_MEDIA_SAMPLE_BYTES
                    while (remaining > 0) {
                        val read = source.read(buffer, remaining)
                        if (read == -1L) break
                        remaining -= read
                    }
                    buffer.readByteArray()
                } ?: byteArrayOf()
                return MediaFetch(
                    status = response.code,
                    finalUrl = response.request.url,
                    bytes = bytes,
                    contentType = response.header("Content-Type").orEmpty(),
                )
            }
        }

        var fetched = fetch(rawUrl)
        var status = fetched.status
        var finalUrl = fetched.finalUrl
        var body = fetched.bytes.toString(Charsets.UTF_8)
        if (status !in 200..299) return "媒体探测失败: HTTP $status, url=$finalUrl"
        if (!body.trimStart().startsWith("#EXTM3U")) {
            val contentType = fetched.contentType.lowercase()
            val expectsHls = finalUrl.encodedPath.contains(".m3u8", ignoreCase = true) ||
                contentType.contains("mpegurl")
            if (expectsHls || contentType.contains("text/html")) {
                return "媒体探测失败: 地址返回的不是有效媒体清单，contentType=${fetched.contentType.ifBlank { "未知" }}, url=$finalUrl"
            }
            return "媒体地址可访问: HTTP $status, finalUrl=$finalUrl, contentType=${fetched.contentType.ifBlank { "未知" }}, sampleBytes=${fetched.bytes.size}"
        }
        val masterLines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (masterLines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variant = masterLines.indices.firstNotNullOfOrNull { index ->
                if (masterLines[index].startsWith("#EXT-X-STREAM-INF")) {
                    masterLines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                } else null
            }
            if (variant != null) {
                val variantUrl = finalUrl.resolve(variant) ?: error("无法解析 m3u8 子清单地址")
                fetched = fetch(variantUrl.toString())
                status = fetched.status
                finalUrl = fetched.finalUrl
                body = fetched.bytes.toString(Charsets.UTF_8)
                if (status !in 200..299) {
                    return "m3u8 主清单可访问，但子清单失败: HTTP $status, url=$finalUrl"
                }
                if (!body.trimStart().startsWith("#EXTM3U")) {
                    return "m3u8 子清单失败: 返回的不是有效 HLS 清单，url=$finalUrl"
                }
            }
        }
        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val durations = lines.filter { it.startsWith("#EXTINF:") }.mapNotNull {
            it.substringAfter(':').substringBefore(',').toDoubleOrNull()
        }
        val segments = lines.filter { !it.startsWith("#") }
        if (segments.isEmpty()) return "m3u8 清单失败: 未发现媒体分片，url=$finalUrl"
        val sampleSegments = listOfNotNull(segments.firstOrNull(), segments.lastOrNull()).distinct()
        val segmentResults = sampleSegments.map { segment ->
            val segmentUrl = finalUrl.resolve(segment)
            if (segmentUrl == null) "$segment -> 地址无效" else {
                val result = runCatching { fetch(segmentUrl.toString()) }.getOrNull()
                "$segment -> HTTP ${result?.status ?: "请求失败"}"
            }
        }
        val duration = durations.sum()
        return buildString {
            append("HLS 可访问: HTTP ").append(status).append(", finalUrl=").append(finalUrl)
            append("\n分片=").append(segments.size)
            append(", 总时长=").append(String.format(Locale.US, "%.1f", duration)).append(" 秒")
            if (duration in 1.0..700.0) append("（疑似短片/试看，请核对正片时长）")
            if (segmentResults.isNotEmpty()) append("\n分片抽测: ").append(segmentResults.joinToString("; "))
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.asInt

    private inline fun String.markErrorIf(predicate: (String) -> Boolean): String =
        if (predicate(this)) "$TOOL_ERROR_PREFIX\n$this" else this

    private data class MediaFetch(
        val status: Int,
        val finalUrl: okhttp3.HttpUrl,
        val bytes: ByteArray,
        val contentType: String,
    )

    companion object {
        private const val MAX_TOOL_OUTPUT_CHARS = 120_000
        private const val MIN_HTTP_CHARS = 1_000
        private const val DEFAULT_HTTP_CHARS = 30_000
        private const val MAX_HTTP_CHARS = 80_000
        private const val MIN_SOURCE_CHARS = 1_000
        private const val DEFAULT_SOURCE_CHARS = 80_000
        private const val MAX_SOURCE_CHARS = 100_000
        private const val MAX_MEDIA_SAMPLE_BYTES = 2L * 1024 * 1024
        private const val DEBUG_TIMEOUT_MS = 120_000L
        const val TOOL_ERROR_PREFIX = "[tool_error]"
        private val SAFE_KEY = Regex("[A-Za-z0-9._-]+")
        private val SAFE_RESPONSE_HEADERS = setOf(
            "content-type", "content-length", "content-encoding", "location", "etag", "last-modified",
        )
    }
}
