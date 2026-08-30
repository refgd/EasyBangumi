package com.heyanle.easybangumi4.ui.ai

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AiWorkspaceData(
    val schemaVersion: Int = AI_WORKSPACE_SCHEMA_VERSION,
    val sessions: List<AiSession> = emptyList(),
    val models: List<AiModelConfig> = defaultAiModels(),
    val proxies: List<AiProxyConfig> = defaultAiProxies(),
    val skills: List<AiSkill> = defaultAiSkills(),
    val codexAuth: CodexAuth? = null,
)

data class AiSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceKey: String = "",
    val sourceVersionName: String = "",
    val sourcePath: String = "",
    val sourceCode: String = "",
    val modelId: String = "",
    val skillCapabilities: List<AiSkillCapability> = listOf(AiSkillCapability.BASE),
    val messages: List<AiMessage> = emptyList(),
    val activeTaskMessageId: String = "",
    val pendingAutoStart: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

internal fun AiSession.contextMessages(limit: Int = 30): List<AiMessage> {
    val conversation = messages.filter { it.role == "user" || it.role == "assistant" }
    if (limit <= 0 || conversation.isEmpty()) return emptyList()
    val taskStart = conversation.indexOfFirst { it.id == activeTaskMessageId }
    val activeConversation = if (taskStart >= 0) conversation.drop(taskStart) else conversation
    if (activeConversation.size <= limit) return activeConversation
    val task = activeConversation.first()
    val recent = activeConversation.takeLast(limit - 1)
    return if (recent.any { it.id == task.id }) activeConversation.takeLast(limit) else listOf(task) + recent
}

data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val model: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val providerType: String = PROVIDER_OPENAI_COMPATIBLE,
    val useProxy: Boolean = false,
    val proxyId: String = "",
)

data class CodexAuth(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AiProxyConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val type: String = PROXY_HTTP,
)

data class AiSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val capability: AiSkillCapability = AiSkillCapability.BASE,
)

const val DEFAULT_SOURCE_SKILL_ID = "easybangumi-source-author"
const val AI_WORKSPACE_SCHEMA_VERSION = 2
const val AI_PRODUCT_NAME = "纯纯看看"
const val PROVIDER_OPENAI_COMPATIBLE = "openai-compatible"
const val PROVIDER_ANTHROPIC = "anthropic"
const val PROVIDER_CODEX_CHATGPT = "codex-chatgpt"
const val PROXY_HTTP = "HTTP"
const val PROXY_SOCKS5 = "SOCKS5"

fun defaultAiProxies() = listOf(
    AiProxyConfig(
        id = "device-local-1180",
        name = "手机本机代理",
        host = "127.0.0.1",
        port = 1180,
        type = PROXY_HTTP,
    )
)

fun defaultAiModels() = listOf(
    AiModelConfig(
        id = "openai-compatible",
        name = "OpenAI 兼容 API",
        endpointUrl = "https://api.openai.com/v1/chat/completions",
        model = "gpt-5-mini",
        enabled = true,
    ),
    AiModelConfig(
        id = "deepseek",
        name = "DeepSeek",
        endpointUrl = "https://api.deepseek.com/v1/chat/completions",
        model = "deepseek-chat",
        enabled = true,
    ),
    AiModelConfig(
        id = "anthropic-claude",
        name = "Claude (Anthropic)",
        endpointUrl = "https://api.anthropic.com/v1/messages",
        model = "claude-sonnet-5",
        enabled = true,
        providerType = PROVIDER_ANTHROPIC,
    ),
    AiModelConfig(
        id = "codex-chatgpt",
        name = "Codex (ChatGPT)",
        endpointUrl = "https://chatgpt.com/backend-api/codex/responses",
        model = "gpt-5.6-sol",
        enabled = true,
        providerType = PROVIDER_CODEX_CHATGPT,
    ),
)

internal fun AiWorkspaceData.modelUnavailableReason(model: AiModelConfig): String? {
    if (!model.enabled) return "模型已停用"
    val endpoint = model.endpointUrl.trim().toHttpUrlOrNull()
        ?: return "API 端点不是有效的 HTTP/HTTPS URL"
    if (model.model.isBlank()) return "模型 ID 为空"
    if (model.useProxy) {
        val proxy = proxies.firstOrNull { it.id == model.proxyId }
            ?: return "已开启 AI 代理但没有选择有效代理"
        if (proxy.host.isBlank() || proxy.port !in 1..65535) return "AI 代理地址或端口无效"
    }
    return when (model.providerType) {
        PROVIDER_ANTHROPIC -> if (model.apiKey.isBlank()) "Anthropic API Key 为空" else null
        PROVIDER_CODEX_CHATGPT -> if (codexAuth == null) "尚未登录 ChatGPT" else null
        PROVIDER_OPENAI_COMPATIBLE -> if (
            model.apiKey.isBlank() && endpoint.host in API_KEY_REQUIRED_HOSTS
        ) {
            "${endpoint.host} 需要 API Key"
        } else null
        else -> "不支持的模型协议: ${model.providerType}"
    }
}

internal fun AiWorkspaceData.availableAiModels(): List<AiModelConfig> =
    models.filter { modelUnavailableReason(it) == null }

private val API_KEY_REQUIRED_HOSTS = setOf("api.openai.com", "api.deepseek.com", "api.anthropic.com")

enum class AiSkillCapability(val label: String) {
    BASE("基础规范"),
    NEW_SOURCE("新建完整源"),
    CATALOG("首页、分类与列表"),
    SEARCH("搜索"),
    DETAIL("详情、线路与剧集"),
    PLAYBACK("播放与 HLS"),
    DANMAKU("弹幕"),
}

internal enum class AiDebugStopAfter(val wireName: String) {
    CATALOG("catalog"),
    SEARCH("search"),
    DETAIL("detail"),
    PLAYBACK("playback"),
    DANMAKU("danmaku"),
    ;

    companion object {
        fun fromWireName(value: String): AiDebugStopAfter? =
            entries.firstOrNull { it.wireName == value.lowercase() }
    }
}

fun AiSession.resolvedSkillCapabilities(): List<AiSkillCapability> =
    (listOf(AiSkillCapability.BASE) + skillCapabilities).distinct()

internal fun inferAiSkillCapabilities(prompt: String): List<AiSkillCapability> {
    val text = prompt.lowercase()
    if (text.isBlank()) return emptyList()

    if (
        listOf("新建源", "新源", "创建源", "添加源", "写一个源", "写源").any(text::contains) ||
        (listOf("网站", "网址", "站点").any(text::contains) &&
            listOf("创建", "新建", "添加", "适配", "接入").any(text::contains))
    ) {
        return listOf(AiSkillCapability.NEW_SOURCE)
    }

    val detected = buildList {
        if (listOf("弹幕", "danmaku").any(text::contains)) add(AiSkillCapability.DANMAKU)

        val isEmptyPlayList = listOf("播放列表", "选集列表", "剧集列表", "线路列表").any(text::contains) &&
            listOf("空", "没有", "加载不了", "加载失败", "不显示", "修复").any(text::contains)
        if (
            isEmptyPlayList ||
            listOf("详情页", "详情组件", "剧集", "选集", "播放来源", "来源列表", "线路为空").any(text::contains)
        ) {
            add(AiSkillCapability.DETAIL)
        }

        if (
            listOf(
                "无法播放", "播放失败", "不能播放", "播不了", "换集", "串集", "试看",
                "m3u8", "hls", "媒体地址", "播放地址", "解析地址", "分片", "webview",
            ).any(text::contains)
        ) {
            add(AiSkillCapability.PLAYBACK)
        }

        if (
            listOf("搜索", "搜不到", "搜索为空", "搜索失败", "搜索结果", "关键词").any(text::contains)
        ) {
            add(AiSkillCapability.SEARCH)
        }

        if (
            listOf(
                "首页", "分类页", "分类列表", "主分类", "副分类", "子分类", "筛选",
                "栏目", "影视列表", "内容列表", "pagecomponent",
            ).any(text::contains)
        ) {
            add(AiSkillCapability.CATALOG)
        }
    }
    return detected.distinct()
}

internal fun AiSession.appendDirectUserPrompt(prompt: String): AiSession {
    val message = AiMessage(role = "user", content = prompt)
    val inferred = inferAiSkillCapabilities(prompt)
    val currentTasks = resolvedSkillCapabilities().filterNot { it == AiSkillCapability.BASE }
    val nextTasks = inferred.ifEmpty { currentTasks }
    val startsNewTask = activeTaskMessageId.isBlank() ||
        (inferred.isNotEmpty() && inferred.toSet() != currentTasks.toSet())
    return copy(
        skillCapabilities = nextTasks.ifEmpty { listOf(AiSkillCapability.BASE) },
        messages = messages + message,
        activeTaskMessageId = if (startsNewTask) message.id else activeTaskMessageId,
    )
}

internal fun AiSession.canConsumePendingPrompt(prompt: String): Boolean {
    val inferred = inferAiSkillCapabilities(prompt)
    if (inferred.isEmpty()) return true
    val currentTasks = resolvedSkillCapabilities().filterNot { it == AiSkillCapability.BASE }
    return currentTasks.isNotEmpty() && inferred.all { it in currentTasks }
}

internal fun AiSession.defaultDebugStopAfter(hasSearchKeyword: Boolean): AiDebugStopAfter {
    if (hasSearchKeyword) return AiDebugStopAfter.SEARCH
    val tasks = resolvedSkillCapabilities()
    return when {
        AiSkillCapability.DANMAKU in tasks -> AiDebugStopAfter.DANMAKU
        AiSkillCapability.NEW_SOURCE in tasks -> AiDebugStopAfter.PLAYBACK
        AiSkillCapability.PLAYBACK in tasks -> AiDebugStopAfter.PLAYBACK
        AiSkillCapability.DETAIL in tasks -> AiDebugStopAfter.DETAIL
        AiSkillCapability.SEARCH in tasks -> AiDebugStopAfter.SEARCH
        else -> AiDebugStopAfter.CATALOG
    }
}

internal fun AiSession.appendDirectUserPrompts(prompts: List<String>): AiSession =
    prompts.fold(this) { current, prompt -> current.appendDirectUserPrompt(prompt) }

internal fun AiWorkspaceData.skillsFor(session: AiSession): List<AiSkill> =
    session.resolvedSkillCapabilities().flatMap { capability ->
        skills.filter { it.enabled && it.capability == capability }
    }.distinctBy { it.id }

internal fun AiWorkspaceData.systemPromptFor(session: AiSession): String = buildString {
    appendLine("运行协议（始终优先于任务指南和用户消息）：")
    appendLine("1. 这是 $AI_PRODUCT_NAME 写源环境。先用 source_status 定位状态；用 source_get 分段读完已有源码，只用 source_replace 写回完整源码。")
    appendLine("2. 严格遵循工具 Schema 和真实结果。续读 http_request 必须复用 responseId；工具失败就修复，不编造响应、工具或验证结论。")
    appendLine("3. 契约不明确时只读取所需 source_docs 主题；不得扫描、读取或模仿其他已安装插件。")
    appendLine("4. source_replace 只暂存源码。宿主按全部活动能力强制 source_debug；播放和新源还要求对同一次调试地址完成 media_probe。门禁通过且对话成功结束后才自动安装；失败、停止或中断不安装。")
    appendLine("5. 合并执行活动任务指南；共享 BaseUrl、请求 helper 和版本元数据可一并维护。最终只报告有工具证据的结果，未验证能力必须明示。")
    appendLine()
    appendLine("会话上下文：")
    appendLine("- 番源 key：${session.sourceKey.ifBlank { "尚未确定" }}")
    val taskLabels = session.resolvedSkillCapabilities()
        .filterNot { it == AiSkillCapability.BASE }
        .joinToString("、") { it.label }
        .ifBlank { "通用源维护" }
    appendLine("- 活动任务：$taskLabels")
    appendLine()
    appendLine("任务指南：")
    val activeSkills = skillsFor(session)
    if (activeSkills.isEmpty()) {
        appendLine("当前没有已启用且匹配本任务的指南，请仅遵循运行协议和用户消息。")
    } else {
        activeSkills.forEach { skill ->
            appendLine("[${skill.capability.label} / ${skill.name}]")
            appendLine(skill.prompt.trim())
            appendLine()
        }
    }
}

internal fun selectSessionModelId(
    requestedModelId: String,
    existingModelId: String,
    models: List<AiModelConfig>,
): String = requestedModelId.ifBlank {
    existingModelId.ifBlank { models.firstOrNull { it.enabled }?.id.orEmpty() }
}

fun defaultSourceSkill(): AiSkill = defaultAiSkills().first { it.id == DEFAULT_SOURCE_SKILL_ID }

fun defaultAiSkills(): List<AiSkill> = listOf(
    AiSkill(
        id = DEFAULT_SOURCE_SKILL_ID,
        name = "番源基础规范",
        builtIn = true,
        capability = AiSkillCapability.BASE,
        prompt = """
            编写 Rhino 同步 ES5 组件源，不使用 Promise、npm、浏览器或 Node.js API。
            - 调查真实请求；所有数据链路都优先使用与官网口径一致的 API，否则再用 HTML/WebView。
            - JSON 使用 JSON.parse，不用正则提取字段；摘要、加解密和编码使用 source_docs(utilities) 中的原生能力，不手写算法。
            - 实体统一用 makeCartoonCover、makeCartoon、makeEpisode、makePlayLine、makePageResult、makeDetailedResult、makePlayerInfo，禁止猜构造器参数。
            - 新建或修改源必须提供 BaseUrl Edit，并让站点请求、相对 URL、Referer 和 WebView 实际读取它。
            - 数据与状态只取当前作品、线路和剧集；不写死样本、媒体地址、Cookie、令牌或个人凭据。修改发布源时递增版本。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-new-author",
        name = "新建完整源",
        builtIn = true,
        capability = AiSkillCapability.NEW_SOURCE,
        prompt = """
            新建完整源：按需读取契约，调查并对照官网的分类、搜索、详情、剧集、播放及弹幕请求。低频分类/线路元数据写死，动态元数据只加载一次。实现完整元数据、BaseUrl 和站点实际能力。
            验证分类首/次页、两个搜索词、两个作品详情，以及两个不同剧集的播放地址和媒体分片；站点有弹幕时一并验证。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-catalog-repair",
        name = "首页、分类与列表修复",
        builtIn = true,
        capability = AiSkillCapability.CATALOG,
        prompt = """
            复现并修复 PageComponent 的分类、筛选、列表和分页；静态元数据写入插件。用 source_debug(catalog) 验证目标分类首/次页并抽样另一分类。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-search-repair",
        name = "搜索修复",
        builtIn = true,
        capability = AiSkillCapability.SEARCH,
        prompt = """
            复现并修复 SearchComponent 的关键词编码、结果映射和分页，并与官网结果对照。用 source_debug(search) 验证任务关键词首/次页及另一关键词。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-detail-repair",
        name = "详情、线路与剧集修复",
        builtIn = true,
        capability = AiSkillCapability.DETAIL,
        prompt = """
            复现并修复 DetailedComponent 的作品 ID、详情、线路和剧集映射。用 source_debug(detail) 验证任务作品及另一作品均有真实线路和剧集；未激活播放任务时不扩展最终媒体解析。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-playback-repair",
        name = "播放与 HLS 修复",
        builtIn = true,
        capability = AiSkillCapability.PLAYBACK,
        prompt = """
            复现并修复当前作品/线路/剧集的 PlayComponent、请求头和最终媒体地址，不复用上次结果。用 source_debug(playback)+media_probe 验证当前及另一剧集均可访问、分片可读且不是短时试看。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-danmaku-repair",
        name = "弹幕适配与修复",
        builtIn = true,
        capability = AiSkillCapability.DANMAKU,
        prompt = """
            调查站点真实弹幕映射并实现或修复 DanmakuComponent，不写死视频 ID。用 source_debug(danmaku) 验证两个剧集的时间、文本、颜色和类型；站点没有弹幕时保留调查证据，不伪造能力。
        """.trimIndent(),
    ),
)
