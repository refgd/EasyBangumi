package com.heyanle.easybangumi4.ui.ai

import android.util.AtomicFile
import android.util.Log
import com.google.gson.GsonBuilder
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object AiWorkspaceStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)
    private val workspaceFile: File by lazy { File(APP.filesDir, "ai/workspace.json") }
    private val atomicWorkspaceFile: AtomicFile by lazy { AtomicFile(workspaceFile) }

    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            for (ignored in writeRequests) {
                writeMutex.withLock {
                    runCatching { writeSnapshot(_state.value) }
                        .onFailure { Log.e("AiWorkspaceStore", "Failed to persist AI workspace", it) }
                }
            }
        }
    }

    private fun load(): AiWorkspaceData {
        val parsed = runCatching {
            gson.fromJson(
                String(atomicWorkspaceFile.readFully(), Charsets.UTF_8),
                AiWorkspaceData::class.java,
            )
        }.getOrNull()
        val loaded = parsed?.takeIf { it.schemaVersion == AI_WORKSPACE_SCHEMA_VERSION }
            ?: AiWorkspaceData()
        val normalized = loaded.normalizeBuiltInSkills()
        if (normalized != parsed) {
            runCatching { writeSnapshot(normalized) }
        }
        return normalized
    }

    private fun AiWorkspaceData.normalizeBuiltInSkills(): AiWorkspaceData {
        val defaultSkills = defaultAiSkills()
        val defaultSkillIds = defaultSkills.mapTo(mutableSetOf()) { it.id }
        val existingSkills = skills.associateBy { it.id }
        val builtInSkills = defaultSkills.map { default ->
            default.copy(enabled = existingSkills[default.id]?.enabled ?: default.enabled)
        }
        val customSkills = skills
            .filterNot { it.builtIn || it.id in defaultSkillIds }
        val nextSkills = builtInSkills + customSkills
        return copy(schemaVersion = AI_WORKSPACE_SCHEMA_VERSION, skills = nextSkills)
    }

    private fun update(block: (AiWorkspaceData) -> AiWorkspaceData) {
        _state.update { current -> block(current).normalizeBuiltInSkills() }
        writeRequests.trySend(Unit)
    }

    private fun writeSnapshot(data: AiWorkspaceData) {
        workspaceFile.parentFile?.mkdirs()
        var output: FileOutputStream? = null
        try {
            output = atomicWorkspaceFile.startWrite()
            OutputStreamWriter(output, Charsets.UTF_8).apply {
                write(gson.toJson(data))
                flush()
            }
            atomicWorkspaceFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomicWorkspaceFile::failWrite)
            throw error
        }
    }

    fun session(id: String): AiSession? = state.value.sessions.firstOrNull { it.id == id }

    fun sourceSession(
        sourceKey: String,
        extension: ExtensionInfo.Installed?,
    ): AiSession? = findSourceSession(state.value, sourceKey, extension)

    private fun findSourceSession(
        workspace: AiWorkspaceData,
        sourceKey: String,
        extension: ExtensionInfo.Installed?,
    ): AiSession? = workspace.sessions
        .filter {
            (sourceKey.isNotBlank() && it.sourceKey == sourceKey) ||
                (extension != null && it.sourcePath == extension.sourcePath)
        }
        .maxByOrNull { it.updatedAt }

    fun enqueueSourceTask(
        sourceKey: String,
        task: String,
        extension: ExtensionInfo.Installed?,
        modelId: String = "",
        skillCapabilities: List<AiSkillCapability> = listOf(AiSkillCapability.BASE),
    ): AiSession {
        val taskMessage = AiMessage(role = "user", content = task)
        var result: AiSession? = null
        update { workspace ->
            val existing = findSourceSession(workspace, sourceKey, extension)
            val installedSourceCode = readInstalledSourceCode(extension)
            val session = if (existing != null) {
                existing.copy(
                    title = extension?.label?.ifBlank { existing.title } ?: existing.title,
                    sourceKey = sourceKey,
                    sourceVersionName = extension?.versionName?.ifBlank { existing.sourceVersionName }
                        ?: existing.sourceVersionName,
                    sourcePath = extension?.sourcePath?.ifBlank { existing.sourcePath } ?: existing.sourcePath,
                    sourceCode = installedSourceCode.ifBlank { existing.sourceCode },
                    modelId = selectSessionModelId(modelId, existing.modelId, workspace.models),
                    skillCapabilities = skillCapabilities,
                    messages = existing.messages + taskMessage,
                    activeTaskMessageId = taskMessage.id,
                    pendingAutoStart = true,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                AiSession(
                    title = extension?.label ?: sourceKey,
                    sourceKey = sourceKey,
                    sourceVersionName = extension?.versionName.orEmpty(),
                    sourcePath = extension?.sourcePath.orEmpty(),
                    sourceCode = installedSourceCode,
                    modelId = selectSessionModelId(modelId, "", workspace.models),
                    skillCapabilities = skillCapabilities,
                    messages = listOf(taskMessage),
                    activeTaskMessageId = taskMessage.id,
                    pendingAutoStart = true,
                )
            }
            result = session
            workspace.copy(
                sessions = (workspace.sessions.filterNot { it.id == session.id } + session)
                    .sortedByDescending { it.updatedAt },
            )
        }
        return checkNotNull(result)
    }

    private fun readInstalledSourceCode(extension: ExtensionInfo.Installed?): String = extension?.takeIf {
        it.loadType == ExtensionInfo.TYPE_JS_FILE && it.sourcePath.endsWith(".js", true)
    }?.let {
        runCatching { File(it.sourcePath).readText(Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    fun saveSession(session: AiSession) = update { data ->
        data.copy(
            sessions = (data.sessions.filterNot { it.id == session.id } +
                session.copy(updatedAt = System.currentTimeMillis())).sortedByDescending { it.updatedAt }
        )
    }

    fun updateSession(id: String, block: (AiSession) -> AiSession): AiSession? {
        var result: AiSession? = null
        update { data ->
            val current = data.sessions.firstOrNull { it.id == id } ?: return@update data
            val next = block(current).copy(id = current.id, updatedAt = System.currentTimeMillis())
            result = next
            data.copy(
                sessions = (data.sessions.filterNot { it.id == id } + next)
                    .sortedByDescending { it.updatedAt },
            )
        }
        return result
    }

    fun appendSessionMessages(id: String, messages: List<AiMessage>): AiSession? {
        if (messages.isEmpty()) return session(id)
        return updateSession(id) { current -> current.copy(messages = current.messages + messages) }
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

    fun saveSkill(skill: AiSkill) {
        require(skill.name.isNotBlank()) { "指南名称不能为空" }
        require(skill.prompt.isNotBlank()) { "指南内容不能为空" }
        update { data ->
            data.withSavedSkill(skill)
        }
    }

    fun deleteSkill(id: String) = update { data ->
        data.copy(skills = data.skills.filterNot { it.id == id && !it.builtIn })
    }

    fun saveCodexAuth(auth: CodexAuth?) = update { data ->
        data.copy(codexAuth = auth)
    }

}

internal fun AiWorkspaceData.withSavedSkill(skill: AiSkill): AiWorkspaceData {
    val existing = skills.firstOrNull { it.id == skill.id }
    val saved = if (existing?.builtIn == true) {
        existing.copy(enabled = skill.enabled)
    } else {
        skill.copy(builtIn = false)
    }
    val nextSkills = skills.mapNotNull { current ->
        when {
            current.id == saved.id -> null
            saved.enabled && current.capability == saved.capability -> current.copy(enabled = false)
            else -> current
        }
    } + saved
    return copy(skills = nextSkills)
}
