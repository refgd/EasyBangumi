package com.heyanle.easybangumi4.ui.ai

import java.util.UUID

data class AiWorkspaceData(
    val sessions: List<AiSession> = emptyList(),
    val models: List<AiModelConfig> = defaultAiModels(),
    val proxies: List<AiProxyConfig> = defaultAiProxies(),
    val skills: List<AiSkill> = listOf(defaultSourceSkill()),
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
    val proxyId: String = "",
    val skillIds: List<String> = listOf(DEFAULT_SOURCE_SKILL_ID),
    val messages: List<AiMessage> = emptyList(),
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
    if (conversation.size <= limit) return conversation
    val firstTask = conversation.firstOrNull { it.role == "user" } ?: return conversation.takeLast(limit)
    val recent = conversation.takeLast(limit - 1)
    return if (recent.any { it.id == firstTask.id }) conversation.takeLast(limit) else listOf(firstTask) + recent
}

data class AiModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val model: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val providerType: String? = PROVIDER_OPENAI_COMPATIBLE,
    val useProxy: Boolean = false,
    val proxyId: String? = "",
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
)

const val DEFAULT_SOURCE_SKILL_ID = "easybangumi-source-author"
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

fun defaultSourceSkill() = AiSkill(
    id = DEFAULT_SOURCE_SKILL_ID,
    name = "EasyBangumi 写源",
    builtIn = true,
    prompt = """
        你是 EasyBangumi 组件式 JavaScript 番源工程师。你的任务是添加、更新和调试源，并最终给出可安装的完整源码。

        原则：
        1. 先读取当前源码和目标网站真实响应，不猜测 API、选择器或播放地址。
        2. 有稳定 JSON API 时优先使用 JSON，但必须抽样对比官网对应首页、分类页和搜索结果的 ID/标题/顺序。API 与官网内容口径不一致时，不得将该 API 用于对应页面，应改用官网 HTML 或页面真实接口；只有 API 缺少字段或必须执行页面脚本时才解析 HTML/WebView。
        3. 效率优先。经验证且低频变化的主分类、子分类、线路映射默认写入插件；动态元数据只获取一次并缓存，翻页不得重复请求。
        4. 保持 Rhino 兼容的同步 ES5 语法，不使用 npm 依赖、Promise、async/await 或浏览器专属 API。
        5. 覆盖主/副分类、列表与分页、搜索、详情、线路、剧集、最终播放地址。网站有弹幕时尽量实现弹幕。
        6. 修改已发布源时递增 versionName 和 versionCode。禁止写入 Cookie、令牌和个人凭据。
        7. 使用工具读取、修改、校验和测试源码。source_replace 会自动更新安装；播放链路必须确认最终地址可访问且不是试看。
        8. 每次修改使用 source_replace 写回会话并更新安装，不要只把代码贴在回复中。完成前调用 source_validate 和 source_debug，并对最终播放地址调用 media_probe。
    """.trimIndent(),
)
