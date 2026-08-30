package com.heyanle.easybangumi4.ui.ai

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionInnerLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.source.Debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiAgentEvent(
    val kind: String,
    val title: String,
    val text: String,
    val replaceLatest: Boolean = false,
)

class AiAgent(
    private val extensionController: ExtensionController,
) {
    private val validationRuntime = JSRuntimeProvider(1)

    suspend fun reply(
        sessionId: String,
        onProgress: (String) -> Unit = {},
        onEvent: (AiAgentEvent) -> Unit = {},
        consumePendingPrompts: suspend () -> List<String> = { emptyList() },
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            reportProgress(onProgress, onEvent, "正在准备会话和模型...")
            var session = AiWorkspaceStore.session(sessionId) ?: error("会话不存在")
            val workspace = AiWorkspaceStore.state.value
            val model = workspace.models.firstOrNull { it.id == session.modelId }
                ?: workspace.models.firstOrNull { it.enabled }
                ?: error("请先添加并启用模型")
            if (model.endpointUrl.isBlank() || model.model.isBlank()) error("模型 API 端点和模型名称不能为空")

            val apiMessages = JsonArray()
            val systemPrompt = buildString {
                workspace.skills.filter { it.enabled && it.id in session.skillIds }.forEach {
                    appendLine(it.prompt)
                    appendLine()
                }
                appendLine("当前会话绑定源 key: ${session.sourceKey.ifBlank { "尚未确定" }}")
                appendLine("当前源码由 source_get 工具提供。需要修改时必须调用 source_replace。")
                appendLine("可调用工具及参数由本次 API 请求的 tools JSON Schema 提供；不要编造工具名或只在回复中粘贴源码。")
                appendLine("发现 JSON API 后必须抽样对比官网对应首页、分类页和搜索结果；ID、标题、结果集合或内容口径明显不一致时，该页面不得直接使用此 API。")
                appendLine("source_replace 会在源码可加载时自动更新安装。修改后至少调用 source_validate；需要验证真实数据链路时调用 source_debug，最终媒体地址使用 media_probe 直连验证。")
            }
            val proxy = if (model.useProxy) {
                workspace.proxies.firstOrNull { it.id == model.proxyId }
                    ?: error("模型已开启 AI 代理，但所选代理不存在，请编辑模型")
            } else null
            if (model.providerType == PROVIDER_CODEX_CHATGPT) {
                return@runCatching replyCodex(
                    model,
                    proxy,
                    systemPrompt,
                    session,
                    onProgress,
                    onEvent,
                    consumePendingPrompts,
                )
            }
            if (model.providerType == PROVIDER_ANTHROPIC) {
                if (model.apiKey.isBlank()) error("Anthropic API Key 不能为空")
                return@runCatching replyAnthropic(
                    model,
                    proxy,
                    systemPrompt,
                    session,
                    onProgress,
                    onEvent,
                    consumePendingPrompts,
                )
            }
            apiMessages.add(message("system", systemPrompt))
            session.contextMessages().forEach { apiMessages.add(message(it.role, it.content)) }

            repeat(MAX_TOOL_ROUNDS) { round ->
                reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待模型响应...")
                val response = request(model, proxy, apiMessages, onEvent)
                val assistant = response.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message") ?: error("模型响应缺少 choices[0].message")
                val content = assistant.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val reasoning = assistant.get("reasoning_content")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    .orEmpty()
                if (reasoning.isNotBlank()) reportReasoning(onEvent, reasoning)
                val toolCalls = assistant.getAsJsonArray("tool_calls")
                if (toolCalls == null || toolCalls.size() == 0) {
                    val finalText = content.ifBlank { "模型未返回文本" }
                    val pending = consumePendingPrompts()
                    if (pending.isNotEmpty()) {
                        apiMessages.add(assistant.deepCopy())
                        pending.forEach { apiMessages.add(message("user", it)) }
                        saveIntermediateReply(sessionId, content, pending)
                        session = AiWorkspaceStore.session(sessionId) ?: session
                        reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                        return@repeat
                    }
                    session = AiWorkspaceStore.session(sessionId) ?: session
                    session = session.copy(messages = session.messages + AiMessage(role = "assistant", content = finalText))
                    AiWorkspaceStore.saveSession(session)
                    return@runCatching finalText
                }

                apiMessages.add(assistant.deepCopy())
                toolCalls.forEach { element ->
                    val call = element.asJsonObject
                    val callId = call.get("id")?.asString.orEmpty()
                    val function = call.getAsJsonObject("function")
                    val name = function?.get("name")?.asString.orEmpty()
                    Log.d(TAG, "tool round=${round + 1}/$MAX_TOOL_ROUNDS name=$name")
                    reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(name)}")
                    val arguments = runCatching {
                        JsonParser.parseString(function?.get("arguments")?.asString ?: "{}").asJsonObject
                    }.getOrElse { JsonObject() }
                    val toolResult = runCatching { executeTool(name, arguments, session) }
                        .getOrElse { "工具执行失败: ${it.message ?: it.javaClass.simpleName}" }
                    reportToolResult(onEvent, name, toolResult)
                    session = AiWorkspaceStore.session(sessionId) ?: session
                    apiMessages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", callId)
                        addProperty("content", toolResult.take(MAX_TOOL_OUTPUT_CHARS))
                    })
                }
                val pending = consumePendingPrompts()
                if (pending.isNotEmpty()) {
                    val current = AiWorkspaceStore.session(sessionId) ?: session
                    AiWorkspaceStore.saveSession(
                        current.copy(messages = current.messages + pending.map { AiMessage(role = "user", content = it) })
                    )
                    session = AiWorkspaceStore.session(sessionId) ?: current
                    pending.forEach { apiMessages.add(message("user", it)) }
                    reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
                }
            }
            error("模型连续调用工具次数过多，请缩小任务后重试")
        }
    }

    private suspend fun replyCodex(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        instructions: String,
        initialSession: AiSession,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
        consumePendingPrompts: suspend () -> List<String>,
    ): String {
        var session = initialSession
        val input = JsonArray().apply {
            session.contextMessages().forEach { message ->
                add(responseMessage(message.role, message.content))
            }
        }
        repeat(MAX_TOOL_ROUNDS) { round ->
            reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待 Codex 响应...")
            val result = requestCodex(model, proxyConfig, instructions, input, onEvent)
            result.items.forEach(input::add)
            val calls = result.items.filter { item ->
                item.asJsonObject.get("type")?.asString == "function_call"
            }
            if (calls.isEmpty()) {
                val finalText = result.text.ifBlank { "模型未返回文本" }
                val pending = consumePendingPrompts()
                if (pending.isNotEmpty()) {
                    pending.forEach { input.add(responseMessage("user", it)) }
                    saveIntermediateReply(session.id, result.text, pending)
                    session = AiWorkspaceStore.session(session.id) ?: session
                    reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                    return@repeat
                }
                session = AiWorkspaceStore.session(session.id) ?: session
                session = session.copy(messages = session.messages + AiMessage(role = "assistant", content = finalText))
                AiWorkspaceStore.saveSession(session)
                return finalText
            }
            calls.forEach { element ->
                val call = element.asJsonObject
                val name = call.string("name")
                val callId = call.string("call_id")
                Log.d(TAG, "Codex tool round=${round + 1}/$MAX_TOOL_ROUNDS name=$name")
                reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(name)}")
                val arguments = runCatching {
                    JsonParser.parseString(call.string("arguments").ifBlank { "{}" }).asJsonObject
                }.getOrElse { JsonObject() }
                val toolResult = runCatching { executeTool(name, arguments, session) }
                    .getOrElse { "工具执行失败: ${it.message ?: it.javaClass.simpleName}" }
                reportToolResult(onEvent, name, toolResult)
                session = AiWorkspaceStore.session(session.id) ?: session
                input.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", callId)
                    addProperty("output", toolResult.take(MAX_TOOL_OUTPUT_CHARS))
                })
            }
            val pending = consumePendingPrompts()
            if (pending.isNotEmpty()) {
                val current = AiWorkspaceStore.session(session.id) ?: session
                AiWorkspaceStore.saveSession(
                    current.copy(messages = current.messages + pending.map { AiMessage(role = "user", content = it) })
                )
                session = AiWorkspaceStore.session(session.id) ?: current
                pending.forEach { input.add(responseMessage("user", it)) }
                reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
            }
        }
        error("模型连续调用工具次数过多，请缩小任务后重试")
    }

    private suspend fun replyAnthropic(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        systemPrompt: String,
        initialSession: AiSession,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
        consumePendingPrompts: suspend () -> List<String>,
    ): String {
        var session = initialSession
        val messages = JsonArray().apply {
            session.contextMessages().forEach { saved ->
                add(anthropicTextMessage(saved.role, saved.content))
            }
        }
        repeat(MAX_TOOL_ROUNDS) { round ->
            reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待 Claude 响应...")
            val result = requestAnthropic(model, proxyConfig, systemPrompt, messages, onEvent)
            messages.add(JsonObject().apply {
                addProperty("role", "assistant")
                add("content", result.content.deepCopy())
            })
            if (result.toolCalls.isEmpty()) {
                if (result.stopReason == "max_tokens") error("Claude 输出达到长度上限，请缩小任务后重试")
                val finalText = result.text.ifBlank { "模型未返回文本" }
                val pending = consumePendingPrompts()
                if (pending.isNotEmpty()) {
                    messages.add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", JsonArray().apply {
                            pending.forEach { text ->
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", text)
                                })
                            }
                        })
                    })
                    saveIntermediateReply(session.id, result.text, pending)
                    session = AiWorkspaceStore.session(session.id) ?: session
                    reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                    return@repeat
                }
                session = AiWorkspaceStore.session(session.id) ?: session
                AiWorkspaceStore.saveSession(
                    session.copy(messages = session.messages + AiMessage(role = "assistant", content = finalText))
                )
                return finalText
            }

            val userContent = JsonArray()
            result.toolCalls.forEach { call ->
                Log.d(TAG, "Claude tool round=${round + 1}/$MAX_TOOL_ROUNDS name=${call.name}")
                reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(call.name)}")
                val toolResult = runCatching { executeTool(call.name, call.input, session) }
                    .getOrElse { "工具执行失败: ${it.message ?: it.javaClass.simpleName}" }
                reportToolResult(onEvent, call.name, toolResult)
                session = AiWorkspaceStore.session(session.id) ?: session
                userContent.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", call.id)
                    addProperty("content", toolResult.take(MAX_TOOL_OUTPUT_CHARS))
                })
            }
            val pending = consumePendingPrompts()
            if (pending.isNotEmpty()) {
                val current = AiWorkspaceStore.session(session.id) ?: session
                AiWorkspaceStore.saveSession(
                    current.copy(messages = current.messages + pending.map { AiMessage(role = "user", content = it) })
                )
                session = AiWorkspaceStore.session(session.id) ?: current
                pending.forEach { text ->
                    userContent.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                }
                reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
            }
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }
        error("模型连续调用工具次数过多，请缩小任务后重试")
    }

    private suspend fun reportProgress(
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
        text: String,
    ) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(text)
            onEvent(AiAgentEvent("status", "运行状态", text))
        }
    }

    private suspend fun reportToolResult(
        onEvent: (AiAgentEvent) -> Unit,
        name: String,
        result: String,
    ) {
        val summary = when (name) {
            "source_get" -> "已读取当前完整源码（${result.length} 字符）"
            "source_replace" -> result.lineSequence().firstOrNull().orEmpty()
            else -> result.take(MAX_VISIBLE_EVENT_CHARS)
        }
        withContext(Dispatchers.Main.immediate) {
            onEvent(AiAgentEvent("tool", toolDisplayName(name), summary))
        }
    }

    private fun toolProgressText(name: String): String = when (name) {
        "source_get" -> "读取当前源码..."
        "source_replace" -> "写入生成的源码..."
        "source_validate" -> "校验源码..."
        "source_debug" -> "在 APP 内调试完整数据链路..."
        "installed_sources" -> "读取已安装源列表..."
        "installed_source_read" -> "读取已安装源源码..."
        "http_request" -> "请求并分析目标网站/API..."
        "media_probe" -> "探测最终播放地址和 HLS 分片..."
        else -> "执行工具 $name..."
    }

    private fun toolDisplayName(name: String): String = when (name) {
        "source_get" -> "读取源码"
        "source_replace" -> "写入源码"
        "source_validate" -> "校验源码"
        "source_debug" -> "数据链路调试"
        "installed_sources" -> "已安装源列表"
        "installed_source_read" -> "读取已安装源"
        "http_request" -> "网络请求"
        "media_probe" -> "媒体探测"
        else -> name
    }

    private suspend fun saveIntermediateReply(sessionId: String, assistantText: String, pending: List<String>) {
        val current = AiWorkspaceStore.session(sessionId) ?: return
        val additions = buildList {
            assistantText.takeIf { it.isNotBlank() }?.let { add(AiMessage(role = "assistant", content = it)) }
            pending.forEach { add(AiMessage(role = "user", content = it)) }
        }
        AiWorkspaceStore.saveSession(current.copy(messages = current.messages + additions))
    }

    private suspend fun request(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        messages: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): JsonObject {
        val client = aiClient(proxyConfig)
        val payload = JsonObject().apply {
            addProperty("model", model.model)
            add("messages", messages)
            add("tools", toolDefinitions())
            addProperty("tool_choice", "auto")
            addProperty("stream", true)
        }
        val request = Request.Builder()
            .url(model.endpointUrl.trim())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (model.apiKey.isNotBlank()) header("Authorization", "Bearer ${model.apiKey.trim()}")
            }
            .header("Accept", "text/event-stream")
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error("AI API ${response.code}: ${body.take(2000)}")
            }
            if (!response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                return JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val calls = linkedMapOf<Int, JsonObject>()
            val reasoningThrottle = ReasoningThrottle()
            val source = response.body?.source() ?: error("AI API 响应为空")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                event.getAsJsonObject("error")?.let { apiError ->
                    error(apiError.string("message").ifBlank { "AI 流式响应失败" })
                }
                val delta = event.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("delta") ?: continue
                content.append(delta.string("content"))
                val reasoningDelta = delta.string("reasoning_content")
                if (reasoningDelta.isNotEmpty()) {
                    reasoning.append(reasoningDelta)
                    if (reasoningThrottle.shouldReport()) {
                        reportReasoning(onEvent, reasoning.toString())
                    }
                }
                delta.getAsJsonArray("tool_calls")?.forEachIndexed { position, element ->
                    val part = element.asJsonObject
                    val index = part.intOrNull("index") ?: position
                    val call = calls.getOrPut(index) {
                        JsonObject().apply {
                            addProperty("id", "")
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", "")
                                addProperty("arguments", "")
                            })
                        }
                    }
                    part.string("id").takeIf { it.isNotEmpty() }?.let { call.addProperty("id", it) }
                    val functionPart = part.getAsJsonObject("function")
                    val function = call.getAsJsonObject("function")
                    function.addProperty("name", function.string("name") + functionPart?.string("name").orEmpty())
                    function.addProperty("arguments", function.string("arguments") + functionPart?.string("arguments").orEmpty())
                }
            }
            if (reasoning.isNotBlank()) reportReasoning(onEvent, reasoning.toString())
            val assistant = JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", content.toString())
                if (reasoning.isNotBlank()) addProperty("reasoning_content", reasoning.toString())
                if (calls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                    calls.toSortedMap().values.forEach(::add)
                })
            }
            return JsonObject().apply {
                add("choices", JsonArray().apply {
                    add(JsonObject().apply { add("message", assistant) })
                })
            }
        }
    }

    private suspend fun requestAnthropic(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        systemPrompt: String,
        messages: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): AnthropicResponse {
        val payload = JsonObject().apply {
            addProperty("model", model.model)
            addProperty("max_tokens", ANTHROPIC_MAX_TOKENS)
            addProperty("system", systemPrompt)
            add("messages", messages)
            add("tools", anthropicToolDefinitions())
            add("tool_choice", JsonObject().apply { addProperty("type", "auto") })
            addProperty("stream", true)
        }
        val request = Request.Builder()
            .url(model.endpointUrl.trim())
            .header("x-api-key", model.apiKey.trim())
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        aiClient(proxyConfig).newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val message = runCatching {
                    JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.string("message")
                }.getOrNull().orEmpty()
                error("Claude API ${response.code}: ${message.ifBlank { body.take(2000) }}")
            }

            val blocks = mutableMapOf<Int, JsonObject>()
            val textDeltas = mutableMapOf<Int, StringBuilder>()
            val inputDeltas = mutableMapOf<Int, StringBuilder>()
            val thinkingDeltas = mutableMapOf<Int, StringBuilder>()
            val signatureDeltas = mutableMapOf<Int, StringBuilder>()
            var stopReason = ""
            var lastReasoningUpdate = 0L
            val source = response.body?.source() ?: error("Claude API 响应为空")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                when (event.string("type")) {
                    "content_block_start" -> {
                        val index = event.intOrNull("index") ?: continue
                        val block = event.getAsJsonObject("content_block")?.deepCopy() ?: continue
                        blocks[index] = block
                        when (block.string("type")) {
                            "text" -> textDeltas[index] = StringBuilder(block.string("text"))
                            "tool_use" -> inputDeltas[index] = StringBuilder()
                            "thinking" -> {
                                thinkingDeltas[index] = StringBuilder(block.string("thinking"))
                                signatureDeltas[index] = StringBuilder(block.string("signature"))
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val index = event.intOrNull("index") ?: continue
                        val delta = event.getAsJsonObject("delta") ?: continue
                        when (delta.string("type")) {
                            "text_delta" -> textDeltas.getOrPut(index, ::StringBuilder).append(delta.string("text"))
                            "input_json_delta" -> inputDeltas.getOrPut(index, ::StringBuilder).append(delta.string("partial_json"))
                            "thinking_delta" -> {
                                val thinking = thinkingDeltas.getOrPut(index, ::StringBuilder)
                                thinking.append(delta.string("thinking"))
                                val now = System.currentTimeMillis()
                                if (now - lastReasoningUpdate >= REASONING_UPDATE_INTERVAL_MS) {
                                    lastReasoningUpdate = now
                                    reportReasoning(onEvent, thinking.toString())
                                }
                            }
                            "signature_delta" -> signatureDeltas.getOrPut(index, ::StringBuilder).append(delta.string("signature"))
                        }
                    }
                    "message_delta" -> stopReason = event.getAsJsonObject("delta")?.string("stop_reason").orEmpty()
                    "error" -> {
                        val error = event.getAsJsonObject("error")
                        error(error?.string("message").orEmpty().ifBlank { "Claude 流式响应失败" })
                    }
                }
            }

            val content = JsonArray()
            blocks.keys.sorted().forEach { index ->
                val block = blocks.getValue(index)
                when (block.string("type")) {
                    "text" -> block.addProperty("text", textDeltas[index]?.toString().orEmpty())
                    "tool_use" -> {
                        val input = inputDeltas[index]?.toString().orEmpty()
                        if (input.isNotBlank()) {
                            block.add("input", runCatching { JsonParser.parseString(input).asJsonObject }.getOrElse { JsonObject() })
                        }
                    }
                    "thinking" -> {
                        val thinking = thinkingDeltas[index]?.toString().orEmpty()
                        block.addProperty("thinking", thinking)
                        block.addProperty("signature", signatureDeltas[index]?.toString().orEmpty())
                        if (thinking.isNotBlank()) reportReasoning(onEvent, thinking)
                    }
                }
                content.add(block)
            }
            val calls = content.mapNotNull { element ->
                val block = element.asJsonObject
                if (block.string("type") != "tool_use") null else AnthropicToolCall(
                    id = block.string("id"),
                    name = block.string("name"),
                    input = block.getAsJsonObject("input") ?: JsonObject(),
                )
            }
            val text = content.joinToString("") { element ->
                element.asJsonObject.takeIf { it.string("type") == "text" }?.string("text").orEmpty()
            }
            return AnthropicResponse(content, text, calls, stopReason)
        }
    }

    private suspend fun requestCodex(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        instructions: String,
        input: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): CodexResponse {
        var auth = AiWorkspaceStore.state.value.codexAuth ?: error("请先在模型管理中登录 ChatGPT")
        repeat(2) { attempt ->
            val payload = JsonObject().apply {
                addProperty("model", model.model)
                addProperty("instructions", instructions)
                add("input", input)
                add("tools", responseToolDefinitions())
                addProperty("tool_choice", "auto")
                addProperty("parallel_tool_calls", true)
                addProperty("store", false)
                addProperty("stream", true)
                add("reasoning", JsonObject().apply { addProperty("summary", "auto") })
                add("include", JsonArray().apply { add("reasoning.encrypted_content") })
            }
            val request = Request.Builder()
                .url(model.endpointUrl.trim())
                .header("Authorization", "Bearer ${auth.accessToken}")
                .header("ChatGPT-Account-Id", auth.accountId)
                .header("originator", "easybangumi")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            aiClient(proxyConfig).newCall(request).awaitResponse().use { response ->
                if (response.code == 401 && attempt == 0 && auth.refreshToken.isNotBlank()) {
                    auth = CodexAuthManager.refresh(auth, proxyConfig)
                    return@use
                }
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    error("Codex API ${response.code}: ${body.take(2000)}")
                }
                val items = mutableListOf<JsonElement>()
                val deltaText = StringBuilder()
                val reasoningSummary = StringBuilder()
                var lastReasoningUpdate = 0L
                val source = response.body?.source() ?: error("Codex API 响应为空")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                    when (event.string("type")) {
                        "response.output_item.done" -> event.get("item")?.let(items::add)
                        "response.output_text.delta" -> deltaText.append(event.string("delta"))
                        "response.reasoning_summary_text.delta" -> {
                            reasoningSummary.append(event.string("delta"))
                            val now = System.currentTimeMillis()
                            if (now - lastReasoningUpdate >= REASONING_UPDATE_INTERVAL_MS) {
                                lastReasoningUpdate = now
                                reportReasoning(onEvent, reasoningSummary.toString())
                            }
                        }
                        "response.reasoning_summary_text.done" -> {
                            val text = event.string("text").ifBlank { reasoningSummary.toString() }
                            if (text.isNotBlank()) reportReasoning(onEvent, text)
                            reasoningSummary.clear()
                        }
                        "response.failed", "error" -> {
                            val message = event.getAsJsonObject("error")?.string("message")
                                ?: event.string("message").ifBlank { "Codex 响应失败" }
                            error(message)
                        }
                    }
                }
                if (reasoningSummary.isNotBlank()) {
                    reportReasoning(onEvent, reasoningSummary.toString())
                }
                val itemText = items.joinToString("") { responseItemText(it) }
                return CodexResponse(items, itemText.ifBlank { deltaText.toString() })
            }
        }
        error("Codex 登录已失效，请重新登录")
    }

    private suspend fun reportReasoning(onEvent: (AiAgentEvent) -> Unit, text: String) {
        withContext(Dispatchers.Main.immediate) {
            onEvent(
                AiAgentEvent(
                    kind = "reasoning",
                    title = "思考摘要",
                    text = text.takeLast(MAX_VISIBLE_REASONING_CHARS),
                    replaceLatest = true,
                )
            )
        }
    }

    private fun aiClient(proxyConfig: AiProxyConfig?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (proxyConfig != null && proxyConfig.host.isNotBlank() && proxyConfig.port in 1..65535) {
            builder.proxy(Proxy(proxyType(proxyConfig), InetSocketAddress(proxyConfig.host, proxyConfig.port)))
            if (proxyConfig.username.isNotBlank()) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxyConfig.username, proxyConfig.password))
                        .build()
                }
            }
        }
        return builder.build()
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private suspend fun executeTool(name: String, arguments: JsonObject, session: AiSession): String = when (name) {
        "source_get" -> session.sourceCode.ifBlank { "当前会话还没有源码" }
        "source_replace" -> {
            val code = arguments.string("code")
            require(code.isNotBlank()) { "code 不能为空" }
            val metadata = validate(code)
            val updated = session.copy(
                title = metadata?.label?.takeIf { it.isNotBlank() } ?: session.title,
                sourceCode = code,
                sourceKey = metadata?.key?.removeSuffix(".__debug__") ?: session.sourceKey,
                sourceVersionName = metadata?.versionName ?: session.sourceVersionName.orEmpty(),
            )
            AiWorkspaceStore.saveSession(updated)
            if (metadata == null) {
                "源码已写回会话，但元数据校验未通过；请调用 source_validate 获取错误"
            } else {
                require(metadata.key.matches(SAFE_KEY)) { "自动安装失败: key 只能包含字母、数字、点、下划线和连字符" }
                val error = extensionController.appendJsExtensionSource(
                    "${metadata.key}.ebg.js",
                    metadata.key,
                    code,
                )
                if (error == null) {
                    "源码已写回会话并自动更新安装"
                } else {
                    "源码已写回会话，但自动安装失败: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
        "source_validate" -> validateResult(session.sourceCode)
        "source_debug" -> debugSource(session, arguments)
        "installed_sources" -> installedSources()
        "installed_source_read" -> readInstalledSource(arguments.string("key"))
        "http_request" -> httpRequest(arguments)
        "media_probe" -> mediaProbe(arguments)
        else -> "未知工具: $name"
    }

    private fun validate(code: String): ExtensionInfo.Installed? =
        JSExtensionInnerLoader(code, validationRuntime, false).load() as? ExtensionInfo.Installed

    private fun validateResult(code: String): String {
        if (code.isBlank()) return "校验失败: 源码为空"
        return when (val result = JSExtensionInnerLoader(code, validationRuntime, false).load()) {
            is ExtensionInfo.InstallError -> "校验失败: ${result.errMsg}${result.exception?.message?.let { ": $it" }.orEmpty()}"
            is ExtensionInfo.Installed -> "校验通过: key=${result.key}, label=${result.label}, version=${result.versionName}, versionCode=${result.versionCode}, libVersion=${result.libVersion}"
        }
    }

    private suspend fun debugSource(session: AiSession, arguments: JsonObject): String {
        val extension = validate(session.sourceCode) ?: return validateResult(session.sourceCode)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val completion = CompletableDeferred<String>()
        val events = mutableListOf<String>()
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
                    if (event.message.isNotBlank()) append(": ").append(event.message.take(2000))
                    if (event.fields.isNotEmpty()) append(" ").append(event.fields.entries.joinToString { "${it.key}=${it.value}" })
                    if (event.options.isNotEmpty()) append(" options=").append(event.options.take(20).joinToString { "${it.index}:${it.label}" })
                }
                synchronized(events) { events += summary }
                when (event.type) {
                    "selection" -> {
                        val stage = event.stage ?: return
                        val requested = preferred[stage]
                        val index = requested?.takeIf { value -> event.options.any { it.index == value } }
                            ?: if (stage == "episode") event.options.lastOrNull()?.index else event.options.firstOrNull()?.index
                        if (index == null) completion.complete("调试失败: ${event.title} 没有可选项")
                        else Debug.select(scope, stage, index)
                    }
                    "ready" -> completion.complete("调试通过")
                    "error" -> completion.complete("调试失败: ${event.message.ifBlank { event.title }}")
                }
            }
        }
        return try {
            Debug.cancelDebug(true)
            Debug.callback = callback
            val keyword = arguments.string("searchKeyword")
            Debug.startDebug(scope, extension)
            if (keyword.isNotBlank()) Debug.search(scope, keyword)
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
            .flatMap { extension -> extension.sources.map { "${it.key}\t${it.label}\t${extension.sourcePath}" } }
        return rows.ifEmpty { listOf("没有已安装的 JS 源") }.joinToString("\n")
    }

    private fun readInstalledSource(key: String): String {
        val extension = extensionController.state.value.extensionInfoMap.values
            .filterIsInstance<ExtensionInfo.Installed>()
            .firstOrNull { info -> info.sources.any { it.key == key } }
            ?: return "未找到源: $key"
        val file = File(extension.sourcePath)
        if (!file.isFile || !file.name.endsWith(".js")) return "该源不是可读取的明文 JS 插件"
        return file.readText(Charsets.UTF_8).take(MAX_TOOL_OUTPUT_CHARS)
    }

    private fun httpRequest(arguments: JsonObject): String {
        val url = arguments.string("url")
        require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP/HTTPS URL" }
        val method = arguments.string("method").ifBlank { "GET" }.uppercase()
        val body = arguments.string("body")
        val builder = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        val request = Request.Builder().url(url).apply {
            arguments.getAsJsonObject("headers")?.entrySet()?.forEach { (key, value) -> header(key, value.asString) }
            if (method == "GET" || method == "HEAD") method(method, null)
            else method(method, body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        }.build()
        builder.build().newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty().take(MAX_HTTP_CHARS)
            val headers = response.headers.names().joinToString("\n") { "$it: ${response.header(it).orEmpty()}" }
            return "HTTP ${response.code}\n$headers\n\n$responseBody"
        }
    }

    private fun mediaProbe(arguments: JsonObject): String {
        val rawUrl = arguments.string("url")
        require(rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) { "仅支持 HTTP/HTTPS URL" }
        val headers = arguments.getAsJsonObject("headers")
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        fun fetch(url: String): Triple<Int, okhttp3.HttpUrl, String> {
            val request = Request.Builder().url(url).apply {
                headers?.entrySet()?.forEach { (key, value) -> header(key, value.asString) }
                header("Range", "bytes=0-${MAX_MEDIA_SAMPLE_BYTES - 1}")
            }.build()
            client.newCall(request).execute().use { response ->
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
                return Triple(response.code, response.request.url, bytes.toString(Charsets.UTF_8))
            }
        }

        var (status, finalUrl, body) = fetch(rawUrl)
        if (status !in 200..299) return "媒体探测失败: HTTP $status, url=$finalUrl"
        if (!body.trimStart().startsWith("#EXTM3U")) {
            return "媒体地址可访问: HTTP $status, finalUrl=$finalUrl, sampleBytes=${body.toByteArray().size}"
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
                val fetched = fetch(variantUrl.toString())
                status = fetched.first
                finalUrl = fetched.second
                body = fetched.third
                if (status !in 200..299) return "m3u8 主清单可访问，但子清单失败: HTTP $status, url=$finalUrl"
            }
        }

        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val durations = lines.filter { it.startsWith("#EXTINF:") }.mapNotNull {
            it.substringAfter(':').substringBefore(',').toDoubleOrNull()
        }
        val segments = lines.filter { !it.startsWith("#") }
        val sampleSegments = listOfNotNull(segments.firstOrNull(), segments.lastOrNull()).distinct()
        val segmentResults = sampleSegments.map { segment ->
            val segmentUrl = finalUrl.resolve(segment)
            if (segmentUrl == null) "$segment -> 地址无效" else {
                val result = runCatching { fetch(segmentUrl.toString()) }.getOrNull()
                "$segment -> HTTP ${result?.first ?: "请求失败"}"
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

    private fun toolDefinitions() = JsonArray().apply {
        add(tool("source_get", "读取当前会话绑定的完整源代码"))
        add(tool("source_replace", "用完整代码替换当前会话源码，保存并自动更新安装", mapOf("code" to "string"), listOf("code")))
        add(tool("source_validate", "使用 APP 当前 JS 插件加载器校验源码元数据和兼容性"))
        add(tool("source_debug", "在 APP 内自动调试当前源码，依次验证分类、列表、详情、线路、剧集和播放信息", mapOf(
            "mainIndex" to "integer", "subIndex" to "integer", "contentIndex" to "integer",
            "playLineIndex" to "integer", "episodeIndex" to "integer", "searchKeyword" to "string"
        )))
        add(tool("installed_sources", "列出 APP 中已安装的 JS 番源"))
        add(tool("installed_source_read", "按源 key 读取已安装的明文 JS 源", mapOf("key" to "string"), listOf("key")))
        add(tool("http_request", "请求目标网站/API，检查 JSON、HTML、播放清单或最终媒体地址", mapOf(
            "url" to "string", "method" to "string", "headers" to "object", "body" to "string"
        ), listOf("url")))
        add(tool("media_probe", "直连探测最终媒体地址；HLS 会解析子清单、统计总时长并抽测首尾分片", mapOf(
            "url" to "string", "headers" to "object"
        ), listOf("url")))
    }

    private fun responseToolDefinitions() = JsonArray().apply {
        toolDefinitions().forEach { definition ->
            add(definition.asJsonObject.getAsJsonObject("function").deepCopy().apply {
                addProperty("type", "function")
            })
        }
    }

    private fun anthropicToolDefinitions() = JsonArray().apply {
        toolDefinitions().forEach { definition ->
            val function = definition.asJsonObject.getAsJsonObject("function")
            add(JsonObject().apply {
                addProperty("name", function.string("name"))
                addProperty("description", function.string("description"))
                add("input_schema", function.getAsJsonObject("parameters").deepCopy())
            })
        }
    }

    private fun tool(name: String, description: String, properties: Map<String, String> = emptyMap(), required: List<String> = emptyList()) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    properties.forEach { (key, type) -> add(key, JsonObject().apply { addProperty("type", type) }) }
                })
                add("required", JsonArray().apply { required.forEach(::add) })
                addProperty("additionalProperties", false)
            })
        })
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun anthropicTextMessage(role: String, text: String) = JsonObject().apply {
        addProperty("role", if (role == "assistant") "assistant" else "user")
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
    }

    private fun responseMessage(role: String, text: String) = JsonObject().apply {
        val responseRole = if (role == "assistant") "assistant" else "user"
        addProperty("type", "message")
        addProperty("role", responseRole)
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", if (responseRole == "assistant") "output_text" else "input_text")
                addProperty("text", text)
            })
        })
    }

    private fun responseItemText(element: JsonElement): String {
        val item = element.asJsonObject
        if (item.string("type") != "message") return ""
        return item.getAsJsonArray("content")?.joinToString("") { content ->
            content.asJsonObject.string("text")
        }.orEmpty()
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt

    private fun proxyType(config: AiProxyConfig): Proxy.Type =
        if (config.type.equals(PROXY_SOCKS5, ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP

    private class ReasoningThrottle {
        private var lastUpdateAt = 0L

        fun shouldReport(now: Long = System.currentTimeMillis()): Boolean {
            if (now - lastUpdateAt < REASONING_UPDATE_INTERVAL_MS) return false
            lastUpdateAt = now
            return true
        }
    }

    companion object {
        private const val TAG = "EasyBangumiAiAgent"
        private const val MAX_TOOL_ROUNDS = 32
        private const val MAX_TOOL_OUTPUT_CHARS = 120_000
        private const val MAX_VISIBLE_EVENT_CHARS = 1_200
        private const val MAX_VISIBLE_REASONING_CHARS = 4_000
        private const val MAX_HTTP_CHARS = 200_000
        private const val MAX_MEDIA_SAMPLE_BYTES = 2L * 1024 * 1024
        private const val DEBUG_TIMEOUT_MS = 120_000L
        private const val REASONING_UPDATE_INTERVAL_MS = 500L
        private const val ANTHROPIC_API_VERSION = "2023-06-01"
        private const val ANTHROPIC_MAX_TOKENS = 32_768
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SAFE_KEY = Regex("[A-Za-z0-9._-]+")
    }

    private data class CodexResponse(val items: List<JsonElement>, val text: String)

    private data class AnthropicResponse(
        val content: JsonArray,
        val text: String,
        val toolCalls: List<AnthropicToolCall>,
        val stopReason: String,
    )

    private data class AnthropicToolCall(
        val id: String,
        val name: String,
        val input: JsonObject,
    )
}
