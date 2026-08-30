package com.heyanle.easybangumi4.ui.ai

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiAgentEvent(
    val kind: String,
    val title: String,
    val text: String,
    val replaceLatest: Boolean = false,
)

internal class AiStreamGuard(private val provider: String) {
    private var completed = false

    fun markComplete() {
        completed = true
    }

    fun requireComplete() {
        check(completed) { "$provider 流式响应中断：未收到完成标记，请重试" }
    }
}

internal fun parseAiSseEvent(provider: String, data: String): JsonObject = try {
    JsonParser.parseString(data).asJsonObject
} catch (error: Throwable) {
    throw IllegalStateException("$provider 返回了无效的流式事件", error)
}

internal class AiToolRoundBudget(
    private val baseRounds: Int = 32,
    private val maxRounds: Int = 96,
    private val maxStalledRounds: Int = 6,
) {
    private val seenToolResults = linkedSetOf<String>()
    private var roundCount = 0
    private var stalledRounds = 0

    val displayMaxRounds: Int
        get() = maxRounds

    fun nextRoundOrNull(): Int? {
        if (roundCount >= maxRounds) return null
        if (roundCount >= baseRounds && stalledRounds >= maxStalledRounds) return null
        return roundCount++
    }

    fun recordToolResult(name: String, result: AiAgentToolResult) {
        val stableContent = when (name) {
            "http_request" -> result.content.replaceFirst(HTTP_RESPONSE_ID, "responseId=<snapshot>")
            "source_debug" -> result.content.replace(DEBUG_LOG_TIME, "[time]")
            else -> result.content
        }
        val fingerprint = buildString {
            append(name)
            append('\n')
            append(result.isError)
            append('\n')
            append(stableContent.length)
            append(':')
            append(stableContent.hashCode())
        }
        if (seenToolResults.add(fingerprint)) {
            stalledRounds = 0
        } else {
            stalledRounds++
        }
    }

    fun recordPendingPrompts(count: Int) {
        if (count > 0) stalledRounds = 0
    }

    fun exhaustedMessage(): String =
        if (roundCount >= maxRounds) {
            "模型连续调用工具已达到 $maxRounds 轮上限，请缩小任务后重试"
        } else {
            "模型在 $roundCount 轮工具调用后连续重复相同结果，请补充更具体目标或调整提示后重试"
        }

    companion object {
        private val HTTP_RESPONSE_ID = Regex("(?m)^responseId=[^\\r\\n]+")
        private val DEBUG_LOG_TIME = Regex("\\[\\d{2}:\\d{2}\\.\\d{3}]")
    }
}

internal data class AiAgentToolResult(
    val content: String,
    val isError: Boolean,
) {
    val modelContent: String
        get() = if (isError) "[tool_error]\n$content" else content
}

class AiAgent(
    private val extensionController: ExtensionController,
) {
    private var cachedProxyConfig: AiProxyConfig? = null
    private var cachedAiClient: OkHttpClient? = null

    suspend fun reply(
        sessionId: String,
        onProgress: (String) -> Unit = {},
        onEvent: (AiAgentEvent) -> Unit = {},
        consumePendingPrompts: suspend () -> List<String> = { emptyList() },
    ): Result<String> {
        val sourceTools = AiSourceToolExecutor(extensionController)
        return try {
            withContext(Dispatchers.IO) {
                val result = runCatching {
                    reportProgress(onProgress, onEvent, "正在准备会话和模型...")
                    var session = AiWorkspaceStore.session(sessionId) ?: error("会话不存在")
                    val workspace = AiWorkspaceStore.state.value
                    val model = workspace.models.firstOrNull { it.id == session.modelId }
                        ?: error("当前会话选择的模型不存在或已停用，请重新选择模型")
                    workspace.modelUnavailableReason(model)?.let { error("模型不可用: $it") }
                    val apiMessages = JsonArray()
                    val systemPrompt = workspace.systemPromptFor(session)
                    val tools = aiToolDefinitions(AI_SOURCE_TOOL_NAMES)
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
                            sourceTools,
                            onProgress,
                            onEvent,
                            consumePendingPrompts,
                            tools,
                        )
                    }
                    if (model.providerType == PROVIDER_ANTHROPIC) {
                        if (model.apiKey.isBlank()) error("Anthropic API Key 不能为空")
                        return@runCatching replyAnthropic(
                            model,
                            proxy,
                            systemPrompt,
                            session,
                            sourceTools,
                            onProgress,
                            onEvent,
                            consumePendingPrompts,
                            tools,
                        )
                    }
                    apiMessages.add(message("system", systemPrompt))
                    session.contextMessages().forEach { apiMessages.add(message(it.role, it.content)) }

                    val budget = AiToolRoundBudget()
                    while (true) {
                        val round = budget.nextRoundOrNull() ?: break
                        reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待模型响应...")
                        val response = request(model, proxy, apiMessages, tools, onEvent)
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
                                budget.recordPendingPrompts(pending.size)
                                reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                                continue
                            }
                            val installBlocker = sourceTools.pendingInstallBlocker
                            if (installBlocker != null) {
                                apiMessages.add(assistant.deepCopy())
                                apiMessages.add(message("user", installGuardInstruction(installBlocker)))
                                budget.recordToolResult("install_guard", AiAgentToolResult(installBlocker, true))
                                reportInstallGuard(installBlocker, onProgress, onEvent)
                                continue
                            }
                            installPendingSource(sourceTools, onProgress, onEvent)
                            session = AiWorkspaceStore.appendSessionMessages(
                                sessionId,
                                listOf(AiMessage(role = "assistant", content = finalText)),
                            ) ?: session
                            return@runCatching finalText
                        }

                        apiMessages.add(assistant.deepCopy())
                        toolCalls.forEach { element ->
                            val call = element.asJsonObject
                            val callId = call.get("id")?.asString.orEmpty()
                            val function = call.getAsJsonObject("function")
                            val name = function?.get("name")?.asString.orEmpty()
                            Log.d(TAG, "tool round=${round + 1}/${budget.displayMaxRounds} name=$name")
                            reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(name)}")
                            val toolResult = executeToolCall(
                                sourceTools,
                                name,
                                function?.get("arguments")?.asString ?: "{}",
                                session,
                            )
                            budget.recordToolResult(name, toolResult)
                            reportToolResult(onEvent, name, toolResult.content)
                            session = AiWorkspaceStore.session(sessionId) ?: session
                            apiMessages.add(JsonObject().apply {
                                addProperty("role", "tool")
                                addProperty("tool_call_id", callId)
                                addProperty("content", toolResult.modelContent.take(MAX_TOOL_OUTPUT_CHARS))
                            })
                        }
                        val pending = consumePendingPrompts()
                        if (pending.isNotEmpty()) {
                            session = AiWorkspaceStore.appendSessionMessages(
                                sessionId,
                                pending.map { AiMessage(role = "user", content = it) },
                            ) ?: session
                            pending.forEach { apiMessages.add(message("user", it)) }
                            budget.recordPendingPrompts(pending.size)
                            reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
                        }
                    }
                    error(budget.exhaustedMessage())
                }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                result
            }
        } finally {
            sourceTools.close()
        }
    }

    private suspend fun replyCodex(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        instructions: String,
        initialSession: AiSession,
        sourceTools: AiSourceToolExecutor,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
        consumePendingPrompts: suspend () -> List<String>,
        tools: JsonArray,
    ): String {
        var session = initialSession
        val input = JsonArray().apply {
            session.contextMessages().forEach { message ->
                add(responseMessage(message.role, message.content))
            }
        }
        val budget = AiToolRoundBudget()
        while (true) {
            val round = budget.nextRoundOrNull() ?: break
            reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待 Codex 响应...")
            val result = requestCodex(model, proxyConfig, instructions, input, tools, onEvent)
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
                    budget.recordPendingPrompts(pending.size)
                    reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                    continue
                }
                val installBlocker = sourceTools.pendingInstallBlocker
                if (installBlocker != null) {
                    input.add(responseMessage("user", installGuardInstruction(installBlocker)))
                    budget.recordToolResult("install_guard", AiAgentToolResult(installBlocker, true))
                    reportInstallGuard(installBlocker, onProgress, onEvent)
                    continue
                }
                installPendingSource(sourceTools, onProgress, onEvent)
                session = AiWorkspaceStore.appendSessionMessages(
                    session.id,
                    listOf(AiMessage(role = "assistant", content = finalText)),
                ) ?: session
                return finalText
            }
            calls.forEach { element ->
                val call = element.asJsonObject
                val name = call.string("name")
                val callId = call.string("call_id")
                Log.d(TAG, "Codex tool round=${round + 1}/${budget.displayMaxRounds} name=$name")
                reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(name)}")
                val toolResult = executeToolCall(
                    sourceTools,
                    name,
                    call.string("arguments").ifBlank { "{}" },
                    session,
                )
                budget.recordToolResult(name, toolResult)
                reportToolResult(onEvent, name, toolResult.content)
                session = AiWorkspaceStore.session(session.id) ?: session
                input.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", callId)
                    addProperty("output", toolResult.modelContent.take(MAX_TOOL_OUTPUT_CHARS))
                })
            }
            val pending = consumePendingPrompts()
            if (pending.isNotEmpty()) {
                session = AiWorkspaceStore.appendSessionMessages(
                    session.id,
                    pending.map { AiMessage(role = "user", content = it) },
                ) ?: session
                pending.forEach { input.add(responseMessage("user", it)) }
                budget.recordPendingPrompts(pending.size)
                reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
            }
        }
        error(budget.exhaustedMessage())
    }

    private suspend fun replyAnthropic(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        systemPrompt: String,
        initialSession: AiSession,
        sourceTools: AiSourceToolExecutor,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
        consumePendingPrompts: suspend () -> List<String>,
        tools: JsonArray,
    ): String {
        var session = initialSession
        val messages = JsonArray().apply {
            session.contextMessages().forEach { saved ->
                add(anthropicTextMessage(saved.role, saved.content))
            }
        }
        val budget = AiToolRoundBudget()
        while (true) {
            val round = budget.nextRoundOrNull() ?: break
            reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：等待 Claude 响应...")
            val result = requestAnthropic(model, proxyConfig, systemPrompt, messages, tools, onEvent)
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
                    budget.recordPendingPrompts(pending.size)
                    reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，继续处理...")
                    continue
                }
                val installBlocker = sourceTools.pendingInstallBlocker
                if (installBlocker != null) {
                    messages.add(anthropicTextMessage("user", installGuardInstruction(installBlocker)))
                    budget.recordToolResult("install_guard", AiAgentToolResult(installBlocker, true))
                    reportInstallGuard(installBlocker, onProgress, onEvent)
                    continue
                }
                installPendingSource(sourceTools, onProgress, onEvent)
                session = AiWorkspaceStore.appendSessionMessages(
                    session.id,
                    listOf(AiMessage(role = "assistant", content = finalText)),
                ) ?: session
                return finalText
            }

            val userContent = JsonArray()
            result.toolCalls.forEach { call ->
                Log.d(TAG, "Claude tool round=${round + 1}/${budget.displayMaxRounds} name=${call.name}")
                reportProgress(onProgress, onEvent, "第 ${round + 1} 轮：${toolProgressText(call.name)}")
                val toolResult = executeToolCall(sourceTools, call.name, call.input, session)
                budget.recordToolResult(call.name, toolResult)
                reportToolResult(onEvent, call.name, toolResult.content)
                session = AiWorkspaceStore.session(session.id) ?: session
                userContent.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", call.id)
                    addProperty("content", toolResult.content.take(MAX_TOOL_OUTPUT_CHARS))
                    if (toolResult.isError) addProperty("is_error", true)
                })
            }
            val pending = consumePendingPrompts()
            if (pending.isNotEmpty()) {
                session = AiWorkspaceStore.appendSessionMessages(
                    session.id,
                    pending.map { AiMessage(role = "user", content = it) },
                ) ?: session
                pending.forEach { text ->
                    userContent.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                }
                budget.recordPendingPrompts(pending.size)
                reportProgress(onProgress, onEvent, "已接收 ${pending.size} 条追加消息，转入下一轮...")
            }
            messages.add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }
        error(budget.exhaustedMessage())
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
            "source_status" -> result
                .lineSequence()
                .take(12)
                .joinToString("\n")
            "source_get" -> "已读取当前源码片段（${result.length} 字符）"
            "source_replace" -> result.lineSequence().firstOrNull().orEmpty()
            else -> result.take(MAX_VISIBLE_EVENT_CHARS)
        }
        withContext(Dispatchers.Main.immediate) {
            onEvent(AiAgentEvent("tool", toolDisplayName(name), summary))
        }
    }

    private suspend fun installPendingSource(
        sourceTools: AiSourceToolExecutor,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
    ) {
        if (!sourceTools.hasPendingInstall) return
        reportProgress(onProgress, onEvent, "源码修改已完成，正在自动安装...")
        sourceTools.installPendingSource()?.let { result ->
            reportToolResult(onEvent, "source_install", result)
        }
    }

    private suspend fun reportInstallGuard(
        blocker: String,
        onProgress: (String) -> Unit,
        onEvent: (AiAgentEvent) -> Unit,
    ) {
        reportProgress(onProgress, onEvent, "源码尚未满足自动安装条件，要求模型继续验证...")
        reportToolResult(onEvent, "install_guard", blocker)
    }

    private fun installGuardInstruction(blocker: String): String =
        "[host_validation_required]\n$blocker\n这是宿主强制安装门禁。继续使用工具完成验证；门禁清除前不要输出最终结论。"

    private fun toolProgressText(name: String): String = when (name) {
        "source_status" -> "读取会话状态和建议流程..."
        "source_get" -> "读取当前源码..."
        "source_docs" -> "读取组件 API 契约..."
        "source_replace" -> "写入生成的源码..."
        "source_install" -> "安装最终源码..."
        "install_guard" -> "检查自动安装条件..."
        "source_validate" -> "校验源码..."
        "source_debug" -> "在 $AI_PRODUCT_NAME 内调试完整数据链路..."
        "http_request" -> "请求并分析目标网站/API..."
        "media_probe" -> "探测最终播放地址和 HLS 分片..."
        else -> "执行工具 $name..."
    }

    private fun toolDisplayName(name: String): String = when (name) {
        "source_status" -> "会话状态"
        "source_get" -> "读取源码"
        "source_docs" -> "组件 API 契约"
        "source_replace" -> "写入源码"
        "source_install" -> "自动安装"
        "install_guard" -> "自动安装门禁"
        "source_validate" -> "校验源码"
        "source_debug" -> "数据链路调试"
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
        AiWorkspaceStore.appendSessionMessages(current.id, additions)
    }

    private suspend fun request(
        model: AiModelConfig,
        proxyConfig: AiProxyConfig?,
        messages: JsonArray,
        tools: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): JsonObject {
        val client = aiClient(proxyConfig)
        val payload = JsonObject().apply {
            addProperty("model", model.model)
            add("messages", messages)
            add("tools", tools.deepCopy())
            addProperty("tool_choice", "auto")
            addProperty("parallel_tool_calls", false)
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
            val stream = AiStreamGuard("AI API")
            val source = response.body?.source() ?: error("AI API 响应为空")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                if (data == "[DONE]") {
                    stream.markComplete()
                    continue
                }
                val event = parseAiSseEvent("AI API", data)
                event.getAsJsonObject("error")?.let { apiError ->
                    error(apiError.string("message").ifBlank { "AI 流式响应失败" })
                }
                val choice = event.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                if (choice.get("finish_reason")?.takeUnless { it.isJsonNull } != null) stream.markComplete()
                val delta = choice.getAsJsonObject("delta") ?: continue
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
            stream.requireComplete()
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
        tools: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): AnthropicResponse {
        val payload = JsonObject().apply {
            addProperty("model", model.model)
            addProperty("max_tokens", ANTHROPIC_MAX_TOKENS)
            addProperty("system", systemPrompt)
            add("messages", messages)
            add("tools", anthropicToolDefinitions(tools))
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
            val stream = AiStreamGuard("Claude API")
            val source = response.body?.source() ?: error("Claude API 响应为空")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                val event = parseAiSseEvent("Claude API", data)
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
                    "message_stop" -> stream.markComplete()
                }
            }
            stream.requireComplete()

            val content = JsonArray()
            blocks.keys.sorted().forEach { index ->
                val block = blocks.getValue(index)
                when (block.string("type")) {
                    "text" -> block.addProperty("text", textDeltas[index]?.toString().orEmpty())
                    "tool_use" -> {
                        val input = inputDeltas[index]?.toString().orEmpty()
                        if (input.isNotBlank()) {
                            block.add("input", parseToolArguments("Claude", input))
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
        tools: JsonArray,
        onEvent: (AiAgentEvent) -> Unit,
    ): CodexResponse {
        var auth = AiWorkspaceStore.state.value.codexAuth ?: error("请先在模型管理中登录 ChatGPT")
        repeat(2) { attempt ->
            val payload = JsonObject().apply {
                addProperty("model", model.model)
                addProperty("instructions", instructions)
                add("input", input)
                add("tools", responseToolDefinitions(tools))
                addProperty("tool_choice", "auto")
                addProperty("parallel_tool_calls", false)
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
                val stream = AiStreamGuard("Codex API")
                val source = response.body?.source() ?: error("Codex API 响应为空")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val event = parseAiSseEvent("Codex API", data)
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
                                ?: event.getAsJsonObject("response")
                                    ?.getAsJsonObject("error")
                                    ?.string("message")
                                ?: event.string("message").ifBlank { "Codex 响应失败" }
                            error(message)
                        }
                        "response.incomplete" -> error(
                            event.getAsJsonObject("response")
                                ?.getAsJsonObject("incomplete_details")
                                ?.string("reason")
                                ?.let { "Codex 响应未完成: $it" }
                                ?: "Codex 响应未完成",
                        )
                        "response.completed" -> stream.markComplete()
                    }
                }
                stream.requireComplete()
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

    @Synchronized
    private fun aiClient(proxyConfig: AiProxyConfig?): OkHttpClient {
        cachedAiClient?.takeIf { cachedProxyConfig == proxyConfig }?.let { return it }
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
        return builder.build().also {
            cachedProxyConfig = proxyConfig
            cachedAiClient = it
        }
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

    private suspend fun executeToolCall(
        sourceTools: AiSourceToolExecutor,
        name: String,
        rawArguments: String,
        session: AiSession,
    ): AiAgentToolResult = try {
        val arguments = JsonParser.parseString(rawArguments).asJsonObject
        toolCallResult(sourceTools.execute(name, arguments, session))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AiAgentToolResult("工具执行失败: ${error.message ?: error.javaClass.simpleName}", true)
    }

    private suspend fun executeToolCall(
        sourceTools: AiSourceToolExecutor,
        name: String,
        arguments: JsonObject,
        session: AiSession,
    ): AiAgentToolResult = try {
        toolCallResult(sourceTools.execute(name, arguments, session))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AiAgentToolResult("工具执行失败: ${error.message ?: error.javaClass.simpleName}", true)
    }

    private fun toolCallResult(content: String): AiAgentToolResult {
        val isError = content.startsWith(AiSourceToolExecutor.TOOL_ERROR_PREFIX)
        return AiAgentToolResult(
            content = content.removePrefix(AiSourceToolExecutor.TOOL_ERROR_PREFIX).trimStart(),
            isError = isError,
        )
    }

    private fun responseToolDefinitions(toolDefinitions: JsonArray) = JsonArray().apply {
        toolDefinitions.forEach { definition ->
            add(definition.asJsonObject.getAsJsonObject("function").deepCopy().apply {
                addProperty("type", "function")
            })
        }
    }

    private fun anthropicToolDefinitions(toolDefinitions: JsonArray) = JsonArray().apply {
        toolDefinitions.forEach { definition ->
            val function = definition.asJsonObject.getAsJsonObject("function")
            add(JsonObject().apply {
                addProperty("name", function.string("name"))
                addProperty("description", function.string("description"))
                add("input_schema", function.getAsJsonObject("parameters").deepCopy())
            })
        }
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
        private const val TAG = "AiAgent"
        private const val MAX_TOOL_OUTPUT_CHARS = 120_000
        private const val MAX_VISIBLE_EVENT_CHARS = 1_200
        private const val MAX_VISIBLE_REASONING_CHARS = 4_000
        private const val REASONING_UPDATE_INTERVAL_MS = 500L
        private const val ANTHROPIC_API_VERSION = "2023-06-01"
        private const val ANTHROPIC_MAX_TOKENS = 32_768
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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

internal fun parseToolArguments(provider: String, input: String): JsonObject = try {
    JsonParser.parseString(input).asJsonObject
} catch (error: Throwable) {
    throw IllegalStateException("$provider 工具参数不是完整 JSON", error)
}
