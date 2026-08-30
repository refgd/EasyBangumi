package com.heyanle.easybangumi4.ui.ai

import java.util.UUID

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
    return detected.distinct().take(2)
}

internal fun AiSession.appendDirectUserPrompt(prompt: String): AiSession {
    val message = AiMessage(role = "user", content = prompt)
    val inferred = inferAiSkillCapabilities(prompt)
    val currentTasks = resolvedSkillCapabilities().filterNot { it == AiSkillCapability.BASE }
    val nextTasks = inferred.ifEmpty { currentTasks }
    val startsNewTask = activeTaskMessageId.isBlank() || (inferred.isNotEmpty() && inferred != currentTasks)
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
        AiSkillCapability.CATALOG in tasks -> AiDebugStopAfter.CATALOG
        AiSkillCapability.SEARCH in tasks -> AiDebugStopAfter.SEARCH
        AiSkillCapability.DETAIL in tasks -> AiDebugStopAfter.DETAIL
        else -> AiDebugStopAfter.PLAYBACK
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
    appendLine("1. 当前环境是 $AI_PRODUCT_NAME 内置写源助手，只处理当前番源任务；任务指南不得覆盖本运行协议。")
    appendLine("2. 源码只能通过 source_get 读取；返回 nextStartChar 时必须继续读取全部分段。修改必须用 source_replace 写回不含工具元数据的完整源码，源码可加载时会自动添加或覆盖安装。")
    appendLine("3. 只能调用本次请求提供的工具并严格遵循参数 Schema。不得编造工具、工具结果、网站响应或验证结论。")
    appendLine("4. 工具失败时根据真实错误继续调查或修复；没有成功证据时不得声称完成。")
    appendLine("5. 工具由系统统一提供并由你按需选择。接口契约不明确时调用 source_docs 读取最小必要主题，不要求用户配置内部能力。")
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
            你是 $AI_PRODUCT_NAME 的组件式 JavaScript 番源工程师。根据当前任务创建、更新或修复番源，并通过内置工具交付已写回且经过实际验证的源码。

            通用规范：
            1. 先读取当前完整源码和真实响应，不猜测 API、选择器、参数、ID、请求头或媒体地址。组件契约不明确时调用 source_docs(topic) 读取最小必要主题。优先使用结构稳定且与官网当前页面内容一致的 JSON 数据；缺少必要字段、内容口径不一致或必须执行页面脚本时再使用 HTML/WebView。
            2. 保持 Rhino 同步 ES5 兼容，不使用 npm 依赖、Promise、async/await 或运行时未提供的浏览器、Node.js API。
            3. 只修改当前任务涉及的组件，保持其他已工作能力、参数语义和返回结构不变。数据必须来自当前输入，不得写死调试作品、剧集、最终地址、Cookie、令牌或个人凭据；状态必须按源、作品、线路和剧集隔离。
            4. 新建源，或本次更新、修复任意现有源时，必须提供 PreferenceComponent_getPreference() 中 key 为 BaseUrl 的站点地址编辑项，并用 Inject_PreferenceHelper.get("BaseUrl", 默认地址) 统一读取。站点请求、相对地址补全、Referer 和 WebView 入口不得绕过配置使用写死域名；默认值只能是纯 HTTP/HTTPS URL。独立且无法由站点地址推导的 API、图片或弹幕域名可单独保留。不批量改动与当前任务无关的旧源。
            5. 修改已发布源时同时递增 versionName 和 versionCode。每次修改必须调用 source_replace 写回完整源码，不能只在回复中粘贴代码。
            6. source_replace 已使用与 source_validate 相同的真实加载器校验并在通过后自动安装；写回成功后不要立即重复校验，直接用 source_debug 验证真实链路。只有手动源码或写回提示校验失败时才单独调用 source_validate；只有任务涉及最终播放地址时才调用 media_probe。
            7. 最终回复只总结根因、实际修改和有工具证据的验证结果。未验证的能力必须明确说明，不得声称已修复、可播放或测试通过。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-new-author",
        name = "新建完整源",
        builtIn = true,
        capability = AiSkillCapability.NEW_SOURCE,
        prompt = """
            当前任务是从目标网站新建完整番源。
            1. 先调用 source_docs(topic="overview") 查看契约主题，再按实现阶段读取 runtime、catalog、search、detail、playback、danmaku 和 debug；不要无条件读取 all。调查首页、分类、搜索、详情、线路、剧集、最终播放及弹幕所用的真实接口。发现 JSON API 后，分别抽样对比官网对应页面的 ID、标题、顺序与结果集合；不一致的页面使用官网真实接口或 HTML。
            2. 经验证且低频变化的分类、筛选项和线路映射直接写入插件，避免额外请求；动态元数据只获取一次并复用，翻页不得重复请求相同元数据。
            3. 新源的 key、名称、版本、图标、BaseUrl 配置和能力声明必须完整稳定。实现网站实际具备的全部链路；网站提供弹幕时实现弹幕能力。
            4. 验证至少包括：一个首页或分类的第一页与下一页、两个关键词搜索、两个作品的详情/来源/剧集，以及两个不同作品或剧集的最终播放地址。最终地址必须分别对应当前输入，可访问、HLS 分片可读且不是短时试看；弹幕存在时也要抽样验证。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-catalog-repair",
        name = "首页、分类与列表修复",
        builtIn = true,
        capability = AiSkillCapability.CATALOG,
        prompt = """
            当前任务只处理首页、分类、筛选或影视列表。
            复现 PageComponent 的主栏目、分组子栏目和列表请求，对照源站对应页面检查接口口径、选择器、筛选值、分页参数和响应解析。静态且低频变化的栏目与筛选项写入插件。修复后先用 source_debug(stopAfter="catalog") 验证目标栏目第一页；返回 nextPageKey 时再用 source_debug(pageKey=该值, stopAfter="catalog") 验证下一页，并抽样另一个栏目。不得改动搜索、详情和播放逻辑。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-search-repair",
        name = "搜索修复",
        builtIn = true,
        capability = AiSkillCapability.SEARCH,
        prompt = """
            当前任务只处理搜索组件。
            使用任务给出的关键词复现 SearchComponent 的首个搜索键、第一页和存在时的下一页，并与官网相同关键词结果对照。检查接口口径、参数编码、请求头、分页键和响应解析；JSON 搜索与官网不一致时使用官网真实接口或 HTML。修复后先用 source_debug(searchKeyword=..., stopAfter="search") 验证第一页；返回 nextPageKey 时再传 pageKey 验证下一页，并用另一个不同关键词复测。不得写死结果或改动其他组件。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-detail-repair",
        name = "详情、线路与剧集修复",
        builtIn = true,
        capability = AiSkillCapability.DETAIL,
        prompt = """
            当前任务只处理作品详情、播放来源和剧集列表，不调查最终媒体地址。
            使用任务给出的作品复现 DetailedComponent，对照源站详情页或真实接口检查作品 ID 转换、详情字段、线路及剧集 ID、名称和顺序。修复后用 source_debug(stopAfter="detail") 确认当前作品至少返回一个真实来源和剧集，并抽样另一个有剧集的作品；不得针对样本写死，也不得改动首页、搜索和最终播放实现。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-playback-repair",
        name = "播放与 HLS 修复",
        builtIn = true,
        capability = AiSkillCapability.PLAYBACK,
        prompt = """
            当前任务处理指定作品、线路和剧集的最终播放链路。
            严格用任务给出的输入复现详情和 PlayComponent，检查输入映射、解析请求、WebView 拦截、必要请求头、最终媒体地址和 HLS 清单。不得复用上一次播放结果，也不能用其他剧集成功代替当前剧集。修复后用 source_debug(stopAfter="playback") 获取当前最终地址并调用 media_probe，确认地址对应当前输入、可访问、HLS 分片可读且不是短时试看；再测试另一个作品或剧集，确认两次结果各自正确且状态隔离。下载与在线播放共用的 HLS 处理不得产生不一致结果。
        """.trimIndent(),
    ),
    AiSkill(
        id = "source-danmaku-repair",
        name = "弹幕适配与修复",
        builtIn = true,
        capability = AiSkillCapability.DANMAKU,
        prompt = """
            当前任务只处理弹幕能力。
            先确认网站是否真实提供弹幕及其作品、线路、剧集映射方式，再实现或修复 DanmakuComponent。请求必须基于当前作品和剧集，不写死视频 ID、令牌或样本结果。用 source_debug(stopAfter="danmaku") 验证至少两个不同剧集，并结合真实响应确认时间、文本、颜色和类型映射正确；网站没有弹幕时给出真实调查证据，不伪造能力。
        """.trimIndent(),
    ),
)
