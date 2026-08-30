package com.heyanle.easybangumi4.ui.ai

import com.google.gson.GsonBuilder
import com.heyanle.easybangumi4.APP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object AiWorkspaceStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val workspaceFile: File by lazy { File(APP.filesDir, "ai/workspace.json") }

    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()

    private fun load(): AiWorkspaceData {
        val loaded = runCatching {
            workspaceFile.takeIf { it.isFile }?.reader()?.use {
                gson.fromJson(it, AiWorkspaceData::class.java)
            }
        }.getOrNull() ?: AiWorkspaceData()
        val normalized = loaded.ensureDefaults()
        if (normalized != loaded) {
            runCatching {
                workspaceFile.parentFile?.mkdirs()
                workspaceFile.writeText(gson.toJson(normalized), Charsets.UTF_8)
            }
        }
        return normalized
    }

    private fun AiWorkspaceData.ensureDefaults(): AiWorkspaceData {
        val nextSessions = sessions.map { session ->
            session.copy(sourceVersionName = session.sourceVersionName.orEmpty())
        }
        val builtInModels = defaultAiModels().associateBy { it.id }
        val nextModels = if (models.isEmpty()) defaultAiModels() else {
            val normalized = models.map {
                it.copy(
                    providerType = it.providerType ?: PROVIDER_OPENAI_COMPATIBLE,
                    endpointUrl = it.endpointUrl.orEmpty().ifBlank { builtInModels[it.id]?.endpointUrl.orEmpty() },
                    model = if (it.id == "codex-chatgpt" && it.model == "gpt-5.1-codex-mini") "gpt-5.3-codex" else it.model,
                )
            }
            val defaultsById = defaultAiModels().associateBy { it.id }
            normalized + listOf("anthropic-claude", "codex-chatgpt")
                .filterNot { id -> normalized.any { it.id == id } }
                .mapNotNull(defaultsById::get)
        }
        val nextSkills = if (skills.none { it.id == DEFAULT_SOURCE_SKILL_ID }) {
            skills + defaultSourceSkill()
        } else skills.map { skill ->
            if (skill.id != DEFAULT_SOURCE_SKILL_ID) return@map skill
            var prompt = skill.prompt
                .replace("只有校验成功后才安装；", "source_replace 会自动更新安装；")
                .replace("；需要用户实测时再调用 source_install。", "。")
            if (!prompt.contains(API_CONSISTENCY_RULE_MARKER)) {
                prompt = prompt.trimEnd() + "\n\n$API_CONSISTENCY_RULE"
            }
            if (!prompt.contains(AUTO_INSTALL_RULE_MARKER)) {
                prompt = prompt.trimEnd() + "\n\n$AUTO_INSTALL_RULE"
            }
            prompt = prompt.replace("\n\n$REMOVED_PLAYBACK_ISOLATION_RULE", "")
            skill.copy(prompt = prompt)
        }
        val nextProxies = if (proxies.isEmpty()) defaultAiProxies() else proxies
        return copy(sessions = nextSessions, models = nextModels, proxies = nextProxies, skills = nextSkills)
    }

    private fun update(block: (AiWorkspaceData) -> AiWorkspaceData) {
        val next = block(_state.value).ensureDefaults()
        _state.value = next
        scope.launch {
            writeMutex.withLock {
                workspaceFile.parentFile?.mkdirs()
                val temporary = File(workspaceFile.parentFile, "${workspaceFile.name}.tmp")
                temporary.writeText(gson.toJson(_state.value), Charsets.UTF_8)
                if (!temporary.renameTo(workspaceFile)) {
                    temporary.copyTo(workspaceFile, overwrite = true)
                    temporary.delete()
                }
            }
        }
    }

    fun session(id: String): AiSession? = state.value.sessions.firstOrNull { it.id == id }

    fun saveSession(session: AiSession) = update { data ->
        data.copy(
            sessions = (data.sessions.filterNot { it.id == session.id } +
                session.copy(updatedAt = System.currentTimeMillis())).sortedByDescending { it.updatedAt }
        )
    }

    fun deleteSession(id: String) = update { data ->
        data.copy(sessions = data.sessions.filterNot { it.id == id })
    }

    fun saveModel(model: AiModelConfig) = update { data ->
        data.copy(models = data.models.filterNot { it.id == model.id } + model)
    }

    fun deleteModel(id: String) = update { data ->
        data.copy(models = data.models.filterNot { it.id == id })
    }

    fun saveProxy(proxy: AiProxyConfig) = update { data ->
        data.copy(proxies = data.proxies.filterNot { it.id == proxy.id } + proxy)
    }

    fun deleteProxy(id: String) = update { data ->
        data.copy(proxies = data.proxies.filterNot { it.id == id })
    }

    fun saveSkill(skill: AiSkill) = update { data ->
        data.copy(skills = data.skills.filterNot { it.id == skill.id } + skill)
    }

    fun deleteSkill(id: String) = update { data ->
        data.copy(skills = data.skills.filterNot { it.id == id || it.builtIn })
    }

    fun saveCodexAuth(auth: CodexAuth?) = update { data ->
        data.copy(codexAuth = auth)
    }

    private const val API_CONSISTENCY_RULE_MARKER = "必须抽样对比官网对应首页、分类页和搜索结果"
    private const val API_CONSISTENCY_RULE =
        "使用 JSON API 前必须抽样对比官网对应首页、分类页和搜索结果的 ID、标题、顺序与结果集合；内容口径明显不一致的页面必须改用官网 HTML 或页面真实接口。"
    private const val AUTO_INSTALL_RULE_MARKER = "source_replace 会自动更新安装"
    private const val AUTO_INSTALL_RULE =
        "每次修改必须调用 source_replace 写回完整源码；源码可加载时 APP 会自动添加或覆盖安装，不再调用 source_install。"
    private const val REMOVED_PLAYBACK_ISOLATION_RULE =
        "PlayComponent_getPlayInfo 必须完全基于本次 summary、playLine 和 episode，不得缓存或复用上一次最终媒体地址或 WebView 拦截结果；连续测试两个不同影片或剧集，确认最终 URL 不同且各自对应当前输入。"
}
