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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.Reader
import java.io.StringReader
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class AiSourceToolExecutor(
    private val extensionController: ExtensionController,
) : AutoCloseable {
    private val validationRuntime = JSRuntimeProvider(1)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val httpSnapshots = object : LinkedHashMap<String, HttpResponseSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HttpResponseSnapshot>?): Boolean =
            size > MAX_HTTP_SNAPSHOTS
    }
    private var pendingInstall: PendingSourceInstall? = null

    val hasPendingInstall: Boolean
        get() = pendingInstall != null

    val pendingInstallBlocker: String?
        get() = pendingInstall?.blocker

    suspend fun execute(name: String, arguments: JsonObject, session: AiSession): String {
        validateAiToolArguments(name, arguments)
        return when (name) {
        "source_status" -> sourceStatus(session)
        "source_get" -> {
            require(session.sourceCode.isNotBlank()) { "当前会话还没有源码" }
            sourceTextWindow(StringReader(session.sourceCode), arguments, "当前会话源码")
        }
        "source_docs" -> sourceComponentDocs(arguments.string("topic"))
        "source_replace" -> replaceSource(arguments, session).markErrorIf {
            it.startsWith("源码已写回会话，但")
        }
        "source_validate" -> validateResult(session).markErrorIf { it.startsWith("校验失败") }
        "source_debug" -> debugSource(session, arguments).markErrorIf {
            it.startsWith("校验失败") || it.startsWith("调试失败")
        }
        "http_request" -> httpRequest(arguments)
        "media_probe" -> probePendingMedia(arguments, session)
            else -> error("未知工具: $name")
        }
    }

    override fun close() {
        pendingInstall = null
        validationRuntime.release()
        synchronized(httpSnapshots) { httpSnapshots.clear() }
    }

    suspend fun installPendingSource(): String? {
        val pending = pendingInstall ?: return null
        check(pending.blocker == null) { "自动安装门禁未通过: ${pending.blocker}" }
        val current = AiWorkspaceStore.session(pending.sessionId)
            ?: error("自动安装失败: 会话已不存在")
        require(current.sourceCode == pending.code) {
            "自动安装失败: 会话源码已在任务结束前发生变化"
        }
        val error = extensionController.appendJsExtensionSource(
            "${pending.key}.ebg.js",
            pending.key,
            pending.code,
        )
        if (error != null) {
            throw IllegalStateException("自动安装失败: ${error.message ?: error.javaClass.simpleName}", error)
        }
        pendingInstall = null
        return "任务成功结束，已自动${if (pending.wasInstalled) "更新" else "添加"}番源 ${pending.label} " +
            "(key=${pending.key}, file=${pending.key}.ebg.js)"
    }

    private suspend fun replaceSource(arguments: JsonObject, session: AiSession): String {
        val code = arguments.string("code")
        require(code.isNotBlank()) { "code 不能为空" }
        val existingPending = pendingInstall
        pendingInstall = null
        val previousCode = AiWorkspaceStore.session(session.id)?.sourceCode.orEmpty()
        var validationError: Throwable? = null
        val metadata = try {
            validate(
                code,
                requireBaseUrl = true,
                requiredCapabilities = session.resolvedSkillCapabilities().toSet(),
                enforceSafeHelpers = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            validationError = error
            null
        }
        AiWorkspaceStore.updateSession(session.id) { current ->
            current.copy(
                title = metadata?.label?.takeIf { it.isNotBlank() } ?: current.title,
                sourceCode = code,
                sourceKey = metadata?.key?.removeSuffix(".__debug__") ?: current.sourceKey,
                sourceVersionName = metadata?.versionName ?: current.sourceVersionName,
            )
        } ?: error("源码写回时会话已不存在")
        if (metadata == null) {
            return "源码已写回会话，但校验未通过，未进入待安装状态: ${validationError?.message ?: "未知错误"}"
        }
        val installKey = installableSourceKey(metadata.key)
        require(installKey.matches(SAFE_KEY)) {
            "待安装校验失败: key 只能包含字母、数字、点、下划线和连字符"
        }
        if (code == previousCode) {
            if (existingPending?.sessionId == session.id && existingPending.code == code) {
                pendingInstall = existingPending
                return "源码与当前会话一致，校验通过；保留已有待安装状态和验证进度"
            }
        }
        pendingInstall = PendingSourceInstall(
            sessionId = session.id,
            key = installKey,
            label = metadata.label.ifBlank { installKey },
            code = code,
            wasInstalled = session.sourcePath.isNotBlank(),
            requiredCapabilities = requiredDebugCapabilities(session),
        )
        val staged = if (code == previousCode) "源码与当前会话一致，已重新进入待安装流程" else "源码已写回并校验通过"
        return "$staged；${pendingInstall?.blocker}。全部门禁通过且对话成功结束后，" +
            "将自动${if (session.sourcePath.isNotBlank()) "更新" else "添加"}番源"
    }

    private suspend fun validate(
        code: String,
        requireBaseUrl: Boolean = false,
        requiredCapabilities: Set<AiSkillCapability> = emptySet(),
        enforceSafeHelpers: Boolean = false,
    ): ExtensionInfo.Installed {
        val loaded = JSExtensionInnerLoader(code, validationRuntime, false).load()
        if (loaded is ExtensionInfo.InstallError) {
            error("${loaded.errMsg}${loaded.exception?.message?.let { ": $it" }.orEmpty()}")
        }
        val installed = loaded as ExtensionInfo.Installed
        validateSourceAssembly(installed, requireBaseUrl, requiredCapabilities)
        if (enforceSafeHelpers) unsafeEntityConstructionError(code)?.let(::error)
        return installed
    }

    private suspend fun validateResult(session: AiSession): String {
        val code = session.sourceCode
        if (code.isBlank()) return "校验失败: 源码为空"
        return try {
            val result = validate(
                code,
                requiredCapabilities = session.resolvedSkillCapabilities().toSet(),
            )
            "校验通过: key=${result.key}, label=${result.label}, version=${result.versionName}, versionCode=${result.versionCode}, libVersion=${result.libVersion}，Rhino 语法与组件装配通过"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            "校验失败: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private suspend fun debugSource(session: AiSession, arguments: JsonObject): String {
        val extension = try {
            validate(
                session.sourceCode,
                requiredCapabilities = session.resolvedSkillCapabilities().toSet(),
            )
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
        var playbackUrl: String? = null
        val preferredIndexes = mapOf(
            "main" to arguments.intOrNull("mainIndex"),
            "sub" to arguments.intOrNull("subIndex"),
            "content" to arguments.intOrNull("contentIndex"),
            "search" to arguments.intOrNull("contentIndex"),
            "playLine" to arguments.intOrNull("playLineIndex"),
            "episode" to arguments.intOrNull("episodeIndex"),
        )
        val preferredIds = mapOf(
            "main" to arguments.string("mainId"),
            "sub" to arguments.string("subId"),
            "content" to arguments.string("contentId"),
            "search" to arguments.string("contentId"),
            "playLine" to arguments.string("playLineId"),
            "episode" to arguments.string("episodeId"),
        )
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                synchronized(events) { events += "log[$state] $msg" }
            }

            override fun emit(event: Debug.Event) {
                event.fields["播放地址"]?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?.let { playbackUrl = it }
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
                        append(" options=").append(event.options.take(20).joinToString { option ->
                            buildString {
                                append(option.index).append(':').append(option.label)
                                option.id?.takeIf { it.isNotBlank() }?.let { append("[id=").append(it.take(200)).append(']') }
                            }
                        })
                    }
                }
                synchronized(events) { events += summary }
                when (event.type) {
                    "selection" -> {
                        val stage = event.stage ?: return
                        if (stage in REQUIRED_NON_EMPTY_SELECTION_STAGES && event.options.isEmpty()) {
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
                        val reachedTarget = hasReachedDebugTarget(stopAfter, stage)
                        if (reachedTarget) {
                            completion.complete("调试通过: 已验证到 ${stopAfter.wireName} 阶段")
                            return
                        }
                        val requestedId = preferredIds[stage].orEmpty()
                        if (requestedId.isNotBlank()) {
                            if (event.options.none { it.id == requestedId }) {
                                completion.complete("调试失败: ${event.title} 中找不到 id=$requestedId")
                            } else {
                                Debug.select(scope, stage, id = requestedId)
                            }
                            return
                        }
                        val requestedIndex = preferredIndexes[stage]
                        val index = requestedIndex?.takeIf { value -> event.options.any { it.index == value } }
                            ?: if (stage == "episode") event.options.lastOrNull()?.index else event.options.firstOrNull()?.index
                        if (index == null) completion.complete("调试失败: ${event.title} 没有可选项")
                        else Debug.select(scope, stage, index = index)
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
            if (!Debug.beginSession(callback)) {
                return "调试失败: 调试器正在被其他会话使用，请稍后重试"
            }
            Debug.startDebug(scope, extension)
            if (keyword.isNotBlank()) Debug.search(scope, keyword, requestedPageKey)
            val result = try {
                withTimeout(DEBUG_TIMEOUT_MS) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                "调试失败: ${DEBUG_TIMEOUT_MS / 1_000} 秒内未完成，请检查网站响应、选择参数或缩小验证阶段"
            }
            if (result.startsWith("调试通过")) {
                pendingInstall
                    ?.takeIf { it.sessionId == session.id && it.code == session.sourceCode }
                    ?.recordDebug(stopAfter, playbackUrl)
            }
            "$result\n${synchronized(events) { events.joinToString("\n") }}".take(MAX_TOOL_OUTPUT_CHARS)
        } finally {
            Debug.endSession(callback)
            scope.cancel()
        }
    }

    private fun sourceStatus(session: AiSession): String = buildString {
        val tasks = session.resolvedSkillCapabilities()
        val taskLabels = tasks.joinToString(",") { it.name }
        val defaultStopAfter = session.defaultDebugStopAfter(hasSearchKeyword = AiSkillCapability.SEARCH in tasks)
        appendLine("sessionId=${session.id}")
        appendLine("title=${session.title}")
        appendLine("sourceKey=${session.sourceKey.ifBlank { "<empty>" }}")
        appendLine("sourceVersionName=${session.sourceVersionName.ifBlank { "<empty>" }}")
        appendLine("hasSourcePath=${session.sourcePath.isNotBlank()}")
        appendLine("sourceChars=${session.sourceCode.length}")
        appendLine("activeCapabilities=$taskLabels")
        appendLine("activeTaskMessageId=${session.activeTaskMessageId.ifBlank { "<empty>" }}")
        appendLine("contextMessages=${session.contextMessages().size}")
        appendLine("recommendedStopAfter=${defaultStopAfter.wireName}${if (defaultStopAfter == AiDebugStopAfter.SEARCH) " (requires searchKeyword)" else ""}")
        appendLine("pendingInstall=${pendingInstall?.sessionId == session.id}")
        pendingInstall?.takeIf { it.sessionId == session.id }?.blocker?.let {
            appendLine("installBlocker=$it")
        }
        appendLine()
        appendLine("nextActions:")
        appendLine("- sourceCode 为空表示会话未绑定源码；不要扫描其他插件，应让用户从会话入口选择目标源。")
        appendLine("- 修改前用 source_get(startChar=0) 读取当前源码；返回 nextStartChar 时继续读取。")
        appendLine("- 契约不明确时先 source_docs(topic=\"overview\")，再读取最小必要主题。")
        appendLine("- 实现方式不明确时可按需读取 source_docs(topic=\"patterns\")；涉及摘要、加解密或编码时读取 utilities；不得读取其他插件作为样例。")
        appendLine("- 写回只用 source_replace(code=完整源码)；它只暂存有效修改，成功后按任务调用 source_debug，对话成功结束时系统才自动安装。")
        append("- 播放地址和新源任务在 source_debug(stopAfter=\"playback\") 后，必须对同一次返回地址调用 media_probe。")
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
        val responseId = arguments.string("responseId")
        val snapshot = if (responseId.isNotBlank()) {
            synchronized(httpSnapshots) { httpSnapshots[responseId] }
                ?: error("HTTP 响应快照不存在或已过期，请重新发送首次请求")
        } else {
            executeHttpRequest(arguments)
        }
        return renderHttpSnapshot(snapshot, arguments)
    }

    private fun executeHttpRequest(arguments: JsonObject): HttpResponseSnapshot {
        val url = arguments.string("url")
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) {
            "首次请求必须提供完整 HTTP/HTTPS URL"
        }
        val method = arguments.string("method").ifBlank { "GET" }.uppercase()
        val body = arguments.string("body")
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
            val bodySlice = response.body?.charStream()?.use {
                readTextSlice(it, startChar = 0, maxChars = MAX_HTTP_RESPONSE_CHARS)
            } ?: AiTextSlice("", 0, false)
            val headers = response.headers.names()
                .filter { it.lowercase() in SAFE_RESPONSE_HEADERS }
                .joinToString("\n") { "$it: ${response.header(it).orEmpty()}" }
            val snapshot = HttpResponseSnapshot(
                id = UUID.randomUUID().toString(),
                status = response.code,
                finalUrl = response.request.url.toString(),
                headers = headers,
                body = bodySlice.text,
                responseTruncated = bodySlice.truncated,
            )
            synchronized(httpSnapshots) {
                httpSnapshots[snapshot.id] = snapshot
            }
            return snapshot
        }
    }

    private fun renderHttpSnapshot(snapshot: HttpResponseSnapshot, arguments: JsonObject): String {
        val startChar = arguments.intOrNull("startChar")?.coerceAtLeast(0) ?: 0
        require(startChar <= snapshot.body.length) {
            "startChar=$startChar 超出响应快照长度 ${snapshot.body.length}"
        }
        val maxChars = arguments.intOrNull("maxChars")
            ?.coerceIn(MIN_HTTP_CHARS, MAX_HTTP_CHARS)
            ?: DEFAULT_HTTP_CHARS
        val slice = readTextSlice(StringReader(snapshot.body), startChar, maxChars)
        return buildString {
            append("responseId=").append(snapshot.id)
            append("\nHTTP ").append(snapshot.status)
            append("\nfinalUrl=").append(snapshot.finalUrl)
            append("\nstartChar=").append(slice.startChar)
            append(", returnedChars=").append(slice.text.length)
            slice.nextStartChar?.let { append(", truncated=true, nextStartChar=").append(it) }
            if (snapshot.responseTruncated) {
                append("\nresponseTruncated=true, cachedChars=").append(snapshot.body.length)
            }
            if (snapshot.headers.isNotBlank()) append('\n').append(snapshot.headers)
            append("\n\n").append(slice.text)
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

    private fun probePendingMedia(arguments: JsonObject, session: AiSession): String {
        val rawUrl = arguments.string("url")
        val result = mediaProbe(arguments)
        val failed = result.startsWith("媒体探测失败") || result.contains("清单失败")
        if (failed) return result.markErrorIf { true }
        val pending = pendingInstall?.takeIf { it.sessionId == session.id && it.code == session.sourceCode }
        if (pending != null && pending.requiresMediaProbe) {
            if (pending.playbackUrl != rawUrl) {
                return "媒体探测失败: 必须探测最近一次 source_debug 返回的当前剧集地址，expected=${pending.playbackUrl ?: "<missing>"}".markErrorIf { true }
            }
            pending.recordMediaProbe(rawUrl)
        }
        return result
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

    private data class HttpResponseSnapshot(
        val id: String,
        val status: Int,
        val finalUrl: String,
        val headers: String,
        val body: String,
        val responseTruncated: Boolean,
    )

    companion object {
        private const val MAX_TOOL_OUTPUT_CHARS = 120_000
        private const val MIN_HTTP_CHARS = 1_000
        private const val DEFAULT_HTTP_CHARS = 30_000
        private const val MAX_HTTP_CHARS = 80_000
        private const val MAX_HTTP_RESPONSE_CHARS = 1_000_000
        private const val MAX_HTTP_SNAPSHOTS = 4
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
        private val REQUIRED_NON_EMPTY_SELECTION_STAGES = setOf(
            "main", "content", "search", "playLine", "episode",
        )
    }
}

internal fun hasReachedDebugTarget(stopAfter: AiDebugStopAfter, stage: String): Boolean =
    (stopAfter == AiDebugStopAfter.CATALOG && stage == "content") ||
        (stopAfter == AiDebugStopAfter.SEARCH && stage == "search") ||
        (stopAfter == AiDebugStopAfter.DETAIL && stage == "episode")

internal class PendingSourceInstall(
    val sessionId: String,
    val key: String,
    val label: String,
    val code: String,
    val wasInstalled: Boolean,
    private val requiredCapabilities: Set<AiSkillCapability>,
) {
    private val verifiedCapabilities = linkedSetOf<AiSkillCapability>()
    var playbackUrl: String? = null
        private set
    private var mediaProbeUrl: String? = null

    val requiresMediaProbe: Boolean
        get() = AiSkillCapability.PLAYBACK in requiredCapabilities

    val blocker: String?
        get() {
            if (requiredCapabilities.isEmpty() && verifiedCapabilities.isEmpty()) {
                return "源码尚未完成真实调试，必须根据现有组件至少成功调用一次 source_debug"
            }
            val missing = requiredCapabilities - verifiedCapabilities
            if (missing.isNotEmpty()) {
                return "源码尚未完成真实调试，必须继续调用 source_debug 验证: ${missing.joinToString { it.name.lowercase() }}"
            }
            if (requiresMediaProbe && playbackUrl == null) {
                return "播放调试没有返回 HTTP/HTTPS 媒体地址，必须修复后重新调用 source_debug(stopAfter=\"playback\")"
            }
            if (requiresMediaProbe && mediaProbeUrl != playbackUrl) {
                return "必须对最近一次播放调试返回的地址调用 media_probe 并通过"
            }
            return null
        }

    fun recordDebug(stopAfter: AiDebugStopAfter, mediaUrl: String?) {
        verifiedCapabilities += verifiedCapabilitiesFor(stopAfter)
        if (AiSkillCapability.PLAYBACK in verifiedCapabilitiesFor(stopAfter)) {
            playbackUrl = mediaUrl
            mediaProbeUrl = null
        }
    }

    fun recordMediaProbe(url: String) {
        if (url == playbackUrl) mediaProbeUrl = url
    }
}

internal fun requiredDebugCapabilities(session: AiSession): Set<AiSkillCapability> =
    requiredSourceCapabilities(session.resolvedSkillCapabilities().toSet())

internal fun verifiedCapabilitiesFor(stopAfter: AiDebugStopAfter): Set<AiSkillCapability> = when (stopAfter) {
    AiDebugStopAfter.CATALOG -> setOf(AiSkillCapability.CATALOG)
    AiDebugStopAfter.SEARCH -> setOf(AiSkillCapability.SEARCH)
    AiDebugStopAfter.DETAIL -> setOf(AiSkillCapability.CATALOG, AiSkillCapability.DETAIL)
    AiDebugStopAfter.PLAYBACK -> setOf(
        AiSkillCapability.CATALOG,
        AiSkillCapability.DETAIL,
        AiSkillCapability.PLAYBACK,
    )
    AiDebugStopAfter.DANMAKU -> setOf(
        AiSkillCapability.CATALOG,
        AiSkillCapability.DETAIL,
        AiSkillCapability.PLAYBACK,
        AiSkillCapability.DANMAKU,
    )
}

internal fun installableSourceKey(key: String): String {
    require(!key.endsWith(".__debug__")) { "禁止安装带 .__debug__ 后缀的调试番源" }
    return key
}
