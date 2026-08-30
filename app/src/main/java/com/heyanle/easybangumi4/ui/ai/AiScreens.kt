package com.heyanle.easybangumi4.ui.ai

import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heyanle.easybangumi4.AI_MODELS
import com.heyanle.easybangumi4.AI_PROXIES
import com.heyanle.easybangumi4.AI_SKILLS
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.navigationAiChat
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionInnerLoader
import com.heyanle.easybangumi4.plugin.js.runtime.JSRuntimeProvider
import com.heyanle.easybangumi4.plugin.source.Debug
import com.heyanle.inject.core.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHome() {
    val nav = LocalNavController.current
    val workspace by AiWorkspaceStore.state.collectAsState()
    val extensionController: ExtensionController by Inject.injectLazy()
    val extensionState by extensionController.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 写源") },
                navigationIcon = {
                    IconButton(onClick = nav::popBackStack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "管理")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("模型管理") },
                                leadingIcon = { Icon(Icons.Filled.Memory, null) },
                                onClick = { menuOpen = false; nav.navigate(AI_MODELS) },
                            )
                            DropdownMenuItem(
                                text = { Text("代理管理") },
                                leadingIcon = { Icon(Icons.Filled.Hub, null) },
                                onClick = { menuOpen = false; nav.navigate(AI_PROXIES) },
                            )
                            DropdownMenuItem(
                                text = { Text("任务指南") },
                                leadingIcon = { Icon(Icons.Filled.Psychology, null) },
                                onClick = { menuOpen = false; nav.navigate(AI_SKILLS) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (workspace.sessions.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("添加会话") },
                )
            } else {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, "添加会话")
                }
            }
        },
    ) { padding ->
        if (workspace.sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SmartToy, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有写源会话", style = MaterialTheme.typography.titleMedium)
                    Text("加载现有源或创建新源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(workspace.sessions, key = { it.id }) { session ->
                    var itemMenu by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clickable { nav.navigationAiChat(session.id) },
                        headlineContent = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(session.sourceKey.ifBlank { "新源" })
                                    if (session.sourceVersionName.orEmpty().isNotBlank()) {
                                        append(" · v")
                                        append(session.sourceVersionName.orEmpty())
                                    }
                                    append(" · ")
                                    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.updatedAt)))
                                },
                                maxLines = 1,
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Code, null) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { itemMenu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                                DropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("删除会话") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { itemMenu = false; AiWorkspaceStore.deleteSession(session.id) },
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        AddSessionDialog(
            extensions = extensionState.extensionInfoMap.values.filterIsInstance<ExtensionInfo.Installed>(),
            models = workspace.models.filter { it.enabled },
            onDismiss = { adding = false },
            onCreated = { session ->
                AiWorkspaceStore.saveSession(session)
                adding = false
                nav.navigationAiChat(session.id)
            },
        )
    }
}

@Composable
private fun AddSessionDialog(
    extensions: List<ExtensionInfo.Installed>,
    models: List<AiModelConfig>,
    onDismiss: () -> Unit,
    onCreated: (AiSession) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedModelId by remember(models) { mutableStateOf(models.firstOrNull()?.id.orEmpty()) }
    var selectedExtension by remember { mutableStateOf<ExtensionInfo.Installed?>(null) }
    var targetUrl by remember { mutableStateOf("") }
    val available = remember(extensions) {
        extensions.filter { it.loadType == ExtensionInfo.TYPE_JS_FILE && File(it.sourcePath).name.endsWith(".js") }
    }
    val normalizedUrl = remember(targetUrl) { normalizeTargetUrl(targetUrl) }
    val validTargetUrl = remember(normalizedUrl) {
        runCatching { Uri.parse(normalizedUrl).host?.isNotBlank() == true }.getOrDefault(false)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (step) {
            0 -> "选择模型"
            1 -> "添加写源会话"
            2 -> "选择现有源"
            else -> "输入目标网址"
        }) },
        text = {
            when (step) {
                0 -> LazyColumn(Modifier.height(300.dp)) {
                    if (models.isEmpty()) item {
                        Text("没有已启用的模型，请先到模型管理中启用或添加模型。")
                    }
                    items(models, key = { it.id }) { model ->
                        ListItem(
                            modifier = Modifier.clickable { selectedModelId = model.id },
                            headlineContent = { Text(model.name) },
                            supportingContent = { Text(model.model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(if (selectedModelId == model.id) Icons.Filled.Check else Icons.Filled.Memory, null) },
                        )
                    }
                }
                1 -> Column {
                    ListItem(
                        modifier = Modifier.clickable { step = 3 },
                        headlineContent = { Text("添加新源") },
                        supportingContent = { Text("输入目标网站，AI 自动分析并创建") },
                        leadingContent = { Icon(Icons.Filled.Add, null) },
                    )
                    ListItem(
                        modifier = Modifier.clickable { step = 2 },
                        headlineContent = { Text("选择现有源") },
                        supportingContent = { Text("加载已安装的明文 JavaScript 源") },
                        leadingContent = { Icon(Icons.Filled.Code, null) },
                    )
                }
                2 -> LazyColumn(Modifier.height(320.dp)) {
                    if (available.isEmpty()) item { Text("没有可读取的明文 JavaScript 源") }
                    items(available, key = { it.key }) { extension ->
                        ListItem(
                            modifier = Modifier.clickable { selectedExtension = extension },
                            headlineContent = { Text(extension.label) },
                            supportingContent = { Text(extension.sources.firstOrNull()?.key ?: extension.key) },
                            leadingContent = { Icon(if (selectedExtension?.key == extension.key) Icons.Filled.Check else Icons.Filled.Code, null) },
                        )
                    }
                }
                else -> Column {
                    OutlinedTextField(
                        value = targetUrl,
                        onValueChange = { targetUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目标网站") },
                        placeholder = { Text("https://example.com/") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(enabled = selectedModelId.isNotBlank(), onClick = { step = 1 }) { Text("下一步") }
                1 -> Unit
                2 -> TextButton(enabled = selectedExtension != null, onClick = {
                    val extension = selectedExtension ?: return@TextButton
                    val source = extension.sources.firstOrNull()
                    val code = runCatching { File(extension.sourcePath).readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                    onCreated(AiSession(
                        title = extension.label,
                        sourceKey = source?.key.orEmpty(),
                        sourceVersionName = extension.versionName,
                        sourcePath = extension.sourcePath,
                        sourceCode = code.ifBlank { NEW_SOURCE_TEMPLATE },
                        modelId = selectedModelId,
                    ))
                }) { Text("加载") }
                else -> TextButton(enabled = validTargetUrl, onClick = {
                    val host = Uri.parse(normalizedUrl).host.orEmpty().removePrefix("www.")
                    val task = newSourceTask(normalizedUrl)
                    val taskMessage = AiMessage(role = "user", content = task)
                    onCreated(AiSession(
                        title = host.ifBlank { "新源" },
                        sourceCode = NEW_SOURCE_TEMPLATE,
                        modelId = selectedModelId,
                        skillCapabilities = listOf(AiSkillCapability.NEW_SOURCE),
                        messages = listOf(taskMessage),
                        activeTaskMessageId = taskMessage.id,
                        pendingAutoStart = true,
                    ))
                }) { Text("开始创建") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> onDismiss()
                    1 -> step = 0
                    else -> step = 1
                }
            }) { Text(if (step == 0) "取消" else "上一步") }
        },
    )
}

private fun normalizeTargetUrl(input: String): String {
    val value = input.trim()
    return if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "https://$value"
}

private fun newSourceTask(url: String): String = """
    请为目标网站 $url 创建新的 $AI_PRODUCT_NAME 组件式 JavaScript 番源，完成写回、自动安装和实际验证。
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChat(
    sessionId: String,
    returnToPlayer: Boolean = false,
    returnToHome: Boolean = false,
    returnToSearch: Boolean = false,
) {
    val nav = LocalNavController.current
    val workspace by AiWorkspaceStore.state.collectAsState()
    val extensionController: ExtensionController by Inject.injectLazy()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val session = workspace.sessions.firstOrNull { it.id == sessionId }
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("会话不存在") }
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var sourceDraft by remember(session.sourceCode) { mutableStateOf(session.sourceCode) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var currentRound by remember { mutableIntStateOf(0) }
    var agentJob by remember(session.id) { mutableStateOf<Job?>(null) }
    var runInitialMessageIds by remember(session.id) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingPrompts by remember(session.id) { mutableStateOf<List<String>>(emptyList()) }
    var runEvents by remember(session.id) { mutableStateOf<List<AiAgentEvent>>(emptyList()) }
    var validationCode by remember { mutableStateOf<String?>(null) }
    val chatListState = rememberLazyListState()

    suspend fun runAgent(initialStatus: String) {
        try {
            sending = true
            status = initialStatus
            currentRound = 0
            runInitialMessageIds = AiWorkspaceStore.session(session.id)?.messages.orEmpty().map { it.id }.toSet()
            runEvents = emptyList()
            while (true) {
                val result = AiAgent(extensionController).reply(
                    sessionId = session.id,
                    onProgress = { progress ->
                        status = progress
                        parseAgentRound(progress)?.let { currentRound = it }
                    },
                    onEvent = { event ->
                        runEvents = if (
                            event.replaceLatest &&
                            runEvents.lastOrNull()?.kind == event.kind &&
                            runEvents.lastOrNull()?.title == event.title
                        ) {
                            runEvents.dropLast(1) + event
                        } else {
                            (runEvents + event).takeLast(80)
                        }
                    },
                    consumePendingPrompts = {
                        withContext(Dispatchers.Main.immediate) {
                            val queued = pendingPrompts
                            val current = AiWorkspaceStore.session(session.id) ?: session
                            val (currentTask, nextTask) = queued.partition(current::canConsumePendingPrompt)
                            pendingPrompts = nextTask
                            currentTask
                        }
                    },
                )
                status = result.exceptionOrNull()?.message ?: "任务已完成"
                if (result.isSuccess) {
                    persistReasoningSummary(session.id, runEvents)
                    runEvents = runEvents.filterNot { it.kind == "reasoning" }
                }
                if (result.isFailure) {
                    Log.e("AiChat", "AI request failed", result.exceptionOrNull())
                    val current = AiWorkspaceStore.session(session.id) ?: session
                    val failed = current.copy(
                        messages = current.messages + AiMessage(role = "assistant", content = "请求失败: $status")
                    )
                    AiWorkspaceStore.saveSession(failed)
                    persistReasoningSummary(session.id, runEvents)
                    runEvents = runEvents.filterNot { it.kind == "reasoning" }
                    val queued = pendingPrompts
                    if (queued.isNotEmpty()) {
                        pendingPrompts = emptyList()
                        val failedSession = AiWorkspaceStore.session(session.id) ?: failed
                        AiWorkspaceStore.saveSession(failedSession.appendDirectUserPrompts(queued))
                    }
                    break
                }

                val queued = pendingPrompts
                if (queued.isEmpty()) break
                pendingPrompts = emptyList()
                val current = AiWorkspaceStore.session(session.id) ?: session
                AiWorkspaceStore.saveSession(current.appendDirectUserPrompts(queued))
                status = "正在处理 ${queued.size} 条追加消息..."
                runEvents = (runEvents + AiAgentEvent("status", "追加消息", status)).takeLast(80)
            }
        } finally {
            val queued = pendingPrompts
            if (queued.isNotEmpty()) {
                pendingPrompts = emptyList()
                val current = AiWorkspaceStore.session(session.id) ?: session
                AiWorkspaceStore.saveSession(current.appendDirectUserPrompts(queued))
            }
            sending = false
            agentJob = null
        }
    }

    val showReturnAction = (returnToPlayer || returnToHome || returnToSearch) && !sending &&
        session.messages.lastOrNull()?.role == "assistant"

    val autoScrollRevision = listOf(
        session.messages.lastOrNull()?.id,
        session.messages.lastOrNull()?.content?.hashCode(),
        pendingPrompts.lastOrNull()?.hashCode(),
        runEvents.lastOrNull()?.text?.hashCode(),
        showReturnAction,
    )
    LaunchedEffect(autoScrollRevision, sending, tab) {
        if (tab != 0) return@LaunchedEffect
        val extraItems = (if (pendingPrompts.isNotEmpty()) 1 else 0) +
            (if (runEvents.any { it.kind == "reasoning" || it.kind == "tool" }) 1 else 0) +
            (if (showReturnAction) 1 else 0)
        val lastIndex = session.messages.size + extraItems - 1
        if (lastIndex >= 0) {
            chatListState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        elapsedSeconds = 0
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }

    LaunchedEffect(session.id) {
        if (!session.pendingAutoStart) return@LaunchedEffect
        AiWorkspaceStore.saveSession(session.copy(pendingAutoStart = false))
        agentJob = currentCoroutineContext()[Job]
        runAgent("正在启动自动写源任务...")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = nav::popBackStack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("对话") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("源码") })
            }
            SessionModelSelector(session, workspace)
            if (tab == 0) {
                val hasRunTimeline = sending || runEvents.isNotEmpty()
                val historicalMessages = if (hasRunTimeline) {
                    session.messages.filter { it.id in runInitialMessageIds }
                } else {
                    session.messages
                }
                val currentRunMessages = if (hasRunTimeline) {
                    session.messages.filterNot { it.id in runInitialMessageIds }
                } else {
                    emptyList()
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = chatListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(historicalMessages, key = { it.id }) { message ->
                        ChatMessage(message)
                    }
                    if (runEvents.any { it.kind == "reasoning" || it.kind == "tool" }) item(key = "agent-activity") {
                        AgentConversationActivity(runEvents)
                    }
                    items(currentRunMessages, key = { it.id }) { message ->
                        ChatMessage(message)
                    }
                    if (pendingPrompts.isNotEmpty()) item(key = "pending-prompts") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                            Text("你 · 已追加", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                            pendingPrompts.forEach { prompt ->
                                Text(prompt, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (showReturnAction) item(key = "return-to-previous") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            OutlinedButton(onClick = nav::popBackStack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        returnToHome -> "返回首页重试"
                                        returnToSearch -> "返回搜索页重试"
                                        else -> "返回播放页测试"
                                    }
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().imePadding()) {
                    if (sending) {
                        AgentRunStatus(
                            round = currentRound,
                            elapsedSeconds = elapsedSeconds,
                            status = status,
                            onStop = {
                                val queued = pendingPrompts
                                pendingPrompts = emptyList()
                                if (queued.isNotEmpty()) {
                                    val current = AiWorkspaceStore.session(session.id) ?: session
                                    AiWorkspaceStore.saveSession(current.appendDirectUserPrompts(queued))
                                }
                                status = "任务已停止"
                                agentJob?.cancel()
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(if (sending) "追加指令，将在当前响应后继续..." else "描述你的问题...") },
                            maxLines = 5,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            enabled = input.isNotBlank(),
                            onClick = {
                                val prompt = input.trim()
                                input = ""
                                focusManager.clearFocus(force = true)
                                if (sending) {
                                    pendingPrompts = pendingPrompts + prompt
                                    status = "已追加 ${pendingPrompts.size} 条消息，将在当前响应后继续"
                                } else {
                                    val current = AiWorkspaceStore.session(session.id) ?: session
                                    AiWorkspaceStore.saveSession(current.appendDirectUserPrompt(prompt))
                                    agentJob = scope.launch {
                                        runAgent("正在发送请求...")
                                    }
                                }
                            },
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                    }
                }
            } else {
                OutlinedTextField(
                    value = sourceDraft,
                    onValueChange = { sourceDraft = it },
                    readOnly = sending,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("完整 JS 源码") },
                )
                if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !sending, onClick = {
                        val code = sourceDraft
                        AiWorkspaceStore.saveSession(session.copy(sourceCode = code))
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                val runtime = JSRuntimeProvider(1)
                                try {
                                    when (val loaded = JSExtensionInnerLoader(code, runtime, false).load()) {
                                        is ExtensionInfo.InstallError -> "源码已保存，但安装前校验失败：${loaded.errMsg}"
                                        is ExtensionInfo.Installed -> {
                                            val contractError = runCatching {
                                                validateSourceAssembly(loaded, requireBaseUrl = true)
                                            }.exceptionOrNull()
                                            val error = contractError ?: if (loaded.key.matches(Regex("[A-Za-z0-9._-]+"))) {
                                                extensionController.appendJsExtensionSource(
                                                    "${loaded.key}.ebg.js",
                                                    loaded.key,
                                                    code,
                                                )
                                            } else {
                                                IllegalArgumentException("key 只能包含字母、数字、点、下划线和连字符")
                                            }
                                            if (error == null) {
                                                val current = AiWorkspaceStore.session(session.id) ?: session.copy(sourceCode = code)
                                                AiWorkspaceStore.saveSession(current.copy(
                                                    title = loaded.label.ifBlank { current.title },
                                                    sourceKey = loaded.key,
                                                    sourceVersionName = loaded.versionName,
                                                ))
                                                "源码已保存并安装到番源管理"
                                            } else {
                                                "源码已保存，但自动安装失败：${error.message ?: error.javaClass.simpleName}"
                                            }
                                        }
                                    }
                                } finally {
                                    runtime.release()
                                }
                            }
                        }
                    }) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text("保存并安装") }
                    OutlinedButton(enabled = !sending, onClick = {
                        AiWorkspaceStore.saveSession(session.copy(sourceCode = sourceDraft))
                        validationCode = sourceDraft
                    }) { Icon(Icons.Filled.Code, null); Spacer(Modifier.width(6.dp)); Text("校验") }
                }
            }
        }
    }

    validationCode?.let { code ->
        SourceValidationDialog(
            code = code,
            onDismiss = { validationCode = null },
        )
    }
}

@Composable
private fun ChatMessage(message: AiMessage) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
    ) {
        Text(
            when (message.role) {
                "user" -> "你"
                "reasoning" -> "AI · 思考摘要"
                else -> "AI"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (message.role == "reasoning") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Text(
            message.content,
            style = if (message.role == "reasoning") MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (message.role == "reasoning") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AgentConversationActivity(events: List<AiAgentEvent>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        events.filter { it.kind == "reasoning" || it.kind == "tool" }.takeLast(10).forEach { event ->
            Text(
                "AI · ${event.title}",
                style = MaterialTheme.typography.labelMedium,
                color = if (event.kind == "reasoning") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(
                event.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (event.kind == "reasoning") 8 else 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AgentRunStatus(
    round: Int,
    elapsedSeconds: Int,
    status: String,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (round > 0) "第 $round 轮 · ${formatElapsed(elapsedSeconds)}" else "准备中 · ${formatElapsed(elapsedSeconds)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    status.ifBlank { "正在分析并操作源..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "停止") }
        }
    }
}

private fun parseAgentRound(status: String): Int? =
    Regex("第\\s*(\\d+)\\s*轮").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun formatElapsed(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

private fun persistReasoningSummary(sessionId: String, events: List<AiAgentEvent>) {
    val compact = mutableListOf<String>()
    events.asSequence()
        .filter { it.kind == "reasoning" }
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { text ->
            val previous = compact.lastOrNull()
            when {
                previous == null -> compact += text
                text.startsWith(previous) -> compact[compact.lastIndex] = text
                previous.startsWith(text) -> Unit
                else -> compact += text
            }
        }
    val summary = compact.takeLast(6).joinToString("\n\n").takeLast(MAX_REASONING_SUMMARY_CHARS)
    if (summary.isBlank()) return
    val session = AiWorkspaceStore.session(sessionId) ?: return
    val finalAnswerIndex = session.messages.indexOfLast { it.role == "assistant" }
    if (finalAnswerIndex < 0) return
    val messages = session.messages.toMutableList().apply {
        add(finalAnswerIndex, AiMessage(role = "reasoning", content = summary))
    }
    AiWorkspaceStore.saveSession(session.copy(messages = messages))
}

private data class ValidationLog(val state: Int, val text: String)

@Composable
private fun SourceValidationDialog(
    code: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val validationRuntime = remember(code) { JSRuntimeProvider(1) }
    var busy by remember { mutableStateOf(true) }
    var logs by remember { mutableStateOf(listOf(ValidationLog(1, "正在加载并校验插件..."))) }
    var selection by remember { mutableStateOf<Debug.Event?>(null) }
    var pageHistory by remember { mutableStateOf<List<Int>>(emptyList()) }
    val logListState = rememberLazyListState()

    fun appendLog(state: Int, text: String) {
        logs = (logs + ValidationLog(state, text)).takeLast(300)
    }

    val callback = remember(scope) {
        object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                scope.launch { appendLog(state, msg) }
            }

            override fun emit(event: Debug.Event) {
                scope.launch {
                    when (event.type) {
                        "busy" -> {
                            busy = true
                            event.title.takeIf { it.isNotBlank() }?.let { appendLog(1, it) }
                        }
                        "selection" -> {
                            busy = false
                            selection = event
                            appendLog(1, "等待选择：${event.title}（${event.options.size} 项）")
                        }
                        "context" -> if (event.fields.isNotEmpty()) {
                            appendLog(1, "当前步骤：${event.fields.entries.joinToString(" · ") { "${it.key}=${it.value}" }}")
                        }
                        "result", "capture" -> {
                            appendLog(1, buildString {
                                append(event.title)
                                if (event.fields.isNotEmpty()) {
                                    append("\n")
                                    append(event.fields.entries.joinToString(" · ") { "${it.key}=${it.value}" })
                                }
                                if (event.message.isNotBlank()) {
                                    append("\n")
                                    append(event.message.take(MAX_VALIDATION_LOG_CHARS))
                                }
                            })
                        }
                        "ready" -> {
                            busy = false
                            appendLog(1, event.title.ifBlank { "调试完成" })
                        }
                        "error" -> {
                            busy = false
                            appendLog(-1, event.message.ifBlank { event.title })
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.scrollToItem(logs.lastIndex)
    }

    LaunchedEffect(code, callback, validationRuntime) {
        val loaded = try {
            withContext(Dispatchers.IO) {
                JSExtensionInnerLoader(code, validationRuntime, false).load()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            busy = false
            appendLog(-1, error.stackTraceToString().take(MAX_VALIDATION_LOG_CHARS))
            return@LaunchedEffect
        }
        when (loaded) {
            is ExtensionInfo.InstallError -> {
                busy = false
                appendLog(-1, loaded.errMsg)
                loaded.exception?.stackTraceToString()?.takeIf { it.isNotBlank() }?.let {
                    appendLog(-1, it.take(MAX_VALIDATION_LOG_CHARS))
                }
            }
            is ExtensionInfo.Installed -> {
                appendLog(
                    1,
                    "加载校验通过：${loaded.label} ${loaded.versionName}，key=${loaded.key}，libVersion=${loaded.libVersion}"
                )
                runCatching {
                    Debug.cancelDebug(true)
                    Debug.callback = callback
                    Debug.startDebug(scope, loaded)
                }.onFailure {
                    busy = false
                    appendLog(-1, it.stackTraceToString().take(MAX_VALIDATION_LOG_CHARS))
                }
            }
        }
    }

    DisposableEffect(callback, validationRuntime) {
        onDispose {
            if (Debug.callback === callback) Debug.cancelDebug(true)
            validationRuntime.release()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("源码校验与调试") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 620.dp),
                state = logListState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(logs) { log ->
                    Text(
                        log.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (log.state < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    selection?.let { event ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(event.title.ifBlank { "请选择调试数据" }) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(event.options, key = { "${event.stage}-${it.index}-${it.id}" }) { option ->
                            ListItem(
                                modifier = Modifier.clickable(enabled = !busy) {
                                    val stage = event.stage ?: return@clickable
                                    if (stage == "main" || stage == "sub") pageHistory = emptyList()
                                    appendLog(1, "选择了：${option.label}")
                                    selection = null
                                    busy = true
                                    Debug.select(scope, stage, index = option.index)
                                },
                                headlineContent = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = option.detail.takeIf { it.isNotBlank() }?.let { detail ->
                                    { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (event.stage == "content" || event.stage == "search") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !busy && pageHistory.isNotEmpty(),
                                onClick = {
                                    val previous = pageHistory.lastOrNull() ?: return@TextButton
                                    pageHistory = pageHistory.dropLast(1)
                                    appendLog(1, "加载上一页：$previous")
                                    selection = null
                                    busy = true
                                    Debug.loadPage(scope, previous)
                                },
                            ) { Text("上一页") }
                            Text("页参数 ${event.pageKey ?: 0}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                enabled = !busy && event.nextPageKey != null,
                                onClick = {
                                    val next = event.nextPageKey ?: return@TextButton
                                    pageHistory = pageHistory + (event.pageKey ?: 0)
                                    appendLog(1, "加载下一页：$next")
                                    selection = null
                                    busy = true
                                    Debug.loadPage(scope, next)
                                },
                            ) { Text("下一页") }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭校验") }
            },
        )
    }
}

private const val MAX_VALIDATION_LOG_CHARS = 12_000
private const val MAX_REASONING_SUMMARY_CHARS = 8_000

@Composable
private fun SessionModelSelector(session: AiSession, workspace: AiWorkspaceData) {
    var modelMenu by remember { mutableStateOf(false) }
    val model = workspace.models.firstOrNull { it.id == session.modelId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Memory, null); Spacer(Modifier.width(6.dp)); Text(model?.name ?: "选择模型", maxLines = 1)
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                workspace.models.filter { it.enabled }.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = {
                        modelMenu = false
                        AiWorkspaceStore.saveSession(session.copy(modelId = item.id))
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModels() {
    val workspace by AiWorkspaceStore.state.collectAsState()
    val loginState by CodexAuthManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AiModelConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    val codexModel = workspace.models.firstOrNull { it.providerType == PROVIDER_CODEX_CHATGPT }
    val codexProxy = if (codexModel?.useProxy == true) workspace.proxies.firstOrNull { it.id == codexModel.proxyId } else null
    ManagementScaffold("模型管理", onAdd = { adding = true }) {
        item {
            ListItem(
                headlineContent = { Text("Codex ChatGPT 登录") },
                supportingContent = {
                    when (val state = loginState) {
                        CodexAuthManager.LoginState.Idle -> Text(if (workspace.codexAuth == null) "使用 ChatGPT 订阅调用 Codex" else "已登录 · ${workspace.codexAuth?.accountId.orEmpty()}")
                        CodexAuthManager.LoginState.Requesting -> Text("正在申请设备授权码...")
                        is CodexAuthManager.LoginState.Waiting -> Text("授权码：${state.userCode}")
                        is CodexAuthManager.LoginState.Success -> Text("已登录 · ${state.accountId}")
                        is CodexAuthManager.LoginState.Error -> Text("登录失败：${state.message}")
                    }
                },
                leadingContent = { Icon(Icons.Filled.SmartToy, null) },
                trailingContent = {
                    when (val state = loginState) {
                        is CodexAuthManager.LoginState.Waiting -> OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUrl)))
                        }) { Text("打开登录") }
                        else -> if (workspace.codexAuth == null) {
                            Button(
                                enabled = state !is CodexAuthManager.LoginState.Requesting && (codexModel?.useProxy != true || codexProxy != null),
                                onClick = { scope.launch { CodexAuthManager.login(codexProxy) } },
                            ) { Text("登录") }
                        } else {
                            TextButton(onClick = CodexAuthManager::logout) { Text("退出") }
                        }
                    }
                },
            )
            HorizontalDivider()
        }
        items(workspace.models, key = { it.id }) { model ->
            ListItem(
                headlineContent = { Text(model.name) },
                supportingContent = {
                    val proxy = workspace.proxies.firstOrNull { it.id == model.proxyId }
                    val proxyText = if (model.useProxy) " · ${proxy?.name ?: "代理未选择"}" else ""
                    val providerText = when (model.providerType) {
                        PROVIDER_ANTHROPIC -> "Anthropic"
                        PROVIDER_CODEX_CHATGPT -> "Codex"
                        else -> "OpenAI 兼容"
                    }
                    Text("$providerText · ${model.model}$proxyText\n${model.endpointUrl}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Filled.Memory, null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = model.enabled, onCheckedChange = { AiWorkspaceStore.saveModel(model.copy(enabled = it)) })
                        IconButton(onClick = { editing = model }) { Icon(Icons.Filled.Edit, "编辑") }
                        IconButton(onClick = { AiWorkspaceStore.deleteModel(model.id) }) { Icon(Icons.Filled.Delete, "删除") }
                    }
                },
            )
            HorizontalDivider()
        }
    }
    if (adding || editing != null) ModelDialog(editing, workspace.proxies, { adding = false; editing = null }) {
        AiWorkspaceStore.saveModel(it); adding = false; editing = null
    }
}

@Composable
private fun ModelDialog(model: AiModelConfig?, proxies: List<AiProxyConfig>, onDismiss: () -> Unit, onSave: (AiModelConfig) -> Unit) {
    var name by remember { mutableStateOf(model?.name.orEmpty()) }
    var endpointUrl by remember { mutableStateOf(model?.endpointUrl.orEmpty()) }
    var modelName by remember { mutableStateOf(model?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(model?.apiKey.orEmpty()) }
    var useProxy by remember { mutableStateOf(model?.useProxy ?: false) }
    var proxyId by remember { mutableStateOf(model?.proxyId.orEmpty()) }
    var proxyMenu by remember { mutableStateOf(false) }
    var providerType by remember { mutableStateOf(model?.providerType ?: PROVIDER_OPENAI_COMPATIBLE) }
    var providerMenu by remember { mutableStateOf(false) }
    val selectedProxy = proxies.firstOrNull { it.id == proxyId }
    val providerOptions = listOf(
        PROVIDER_OPENAI_COMPATIBLE to "OpenAI 兼容 API",
        PROVIDER_ANTHROPIC to "Anthropic Claude API",
        PROVIDER_CODEX_CHATGPT to "Codex (ChatGPT)",
    )
    val providerName = providerOptions.firstOrNull { it.first == providerType }?.second ?: "OpenAI 兼容 API"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model == null) "添加模型" else "编辑模型") },
        text = { Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(providerName)
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    providerOptions.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            if (value != providerType) when (value) {
                                PROVIDER_OPENAI_COMPATIBLE -> {
                                    endpointUrl = "https://api.openai.com/v1/chat/completions"
                                    if (modelName.isBlank()) modelName = "gpt-5-mini"
                                    if (name.isBlank()) name = "OpenAI 兼容 API"
                                }
                                PROVIDER_ANTHROPIC -> {
                                    endpointUrl = "https://api.anthropic.com/v1/messages"
                                    if (modelName.isBlank() || modelName == "gpt-5-mini") modelName = "claude-sonnet-5"
                                    if (name.isBlank()) name = "Claude (Anthropic)"
                                }
                                PROVIDER_CODEX_CHATGPT -> {
                                    endpointUrl = "https://chatgpt.com/backend-api/codex/responses"
                                    if (modelName.isBlank() || modelName == "gpt-5-mini") modelName = "gpt-5.6-sol"
                                    if (name.isBlank()) name = "Codex (ChatGPT)"
                                }
                            }
                            providerType = value
                            providerMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(endpointUrl, { endpointUrl = it }, label = { Text("完整 API 端点 URL") }, singleLine = true)
            OutlinedTextField(modelName, { modelName = it }, label = { Text("模型 ID") }, singleLine = true)
            OutlinedTextField(
                apiKey,
                { apiKey = it },
                label = { Text(if (providerType == PROVIDER_ANTHROPIC) "Anthropic API Key（必填）" else "API Key，可留空") },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("使用 AI 代理", Modifier.weight(1f))
                Switch(checked = useProxy, onCheckedChange = { useProxy = it })
            }
            if (useProxy) Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { proxyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Hub, null)
                    Spacer(Modifier.width(6.dp))
                    Text(selectedProxy?.name ?: "选择 AI 代理")
                }
                DropdownMenu(expanded = proxyMenu, onDismissRequest = { proxyMenu = false }) {
                    proxies.forEach { proxy ->
                        DropdownMenuItem(text = { Text(proxy.name) }, onClick = {
                            proxyId = proxy.id
                            proxyMenu = false
                        })
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && endpointUrl.isNotBlank() && modelName.isNotBlank() &&
            (providerType != PROVIDER_ANTHROPIC || apiKey.isNotBlank()) && (!useProxy || selectedProxy != null), onClick = {
            onSave(AiModelConfig(
                id = model?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                endpointUrl = endpointUrl.trim(),
                model = modelName.trim(),
                apiKey = apiKey.trim(),
                enabled = model?.enabled ?: true,
                providerType = providerType,
                useProxy = useProxy,
                proxyId = proxyId,
            ))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProxies() {
    val workspace by AiWorkspaceStore.state.collectAsState()
    var editing by remember { mutableStateOf<AiProxyConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    ManagementScaffold("AI 代理管理", onAdd = { adding = true }) {
        items(workspace.proxies, key = { it.id }) { proxy ->
            ListItem(
                headlineContent = { Text(proxy.name) },
                supportingContent = { Text("${proxy.type} · ${proxy.host}:${proxy.port}") },
                leadingContent = { Icon(Icons.Filled.Hub, null) },
                trailingContent = { Row {
                    IconButton(onClick = { editing = proxy }) { Icon(Icons.Filled.Edit, "编辑") }
                    IconButton(onClick = { AiWorkspaceStore.deleteProxy(proxy.id) }) { Icon(Icons.Filled.Delete, "删除") }
                } },
            )
            HorizontalDivider()
        }
    }
    if (adding || editing != null) ProxyDialog(editing, { adding = false; editing = null }) {
        AiWorkspaceStore.saveProxy(it); adding = false; editing = null
    }
}

@Composable
private fun ProxyDialog(proxy: AiProxyConfig?, onDismiss: () -> Unit, onSave: (AiProxyConfig) -> Unit) {
    var name by remember { mutableStateOf(proxy?.name.orEmpty()) }
    var host by remember { mutableStateOf(proxy?.host.orEmpty()) }
    var port by remember { mutableStateOf(proxy?.port?.toString().orEmpty()) }
    var username by remember { mutableStateOf(proxy?.username.orEmpty()) }
    var password by remember { mutableStateOf(proxy?.password.orEmpty()) }
    var type by remember { mutableStateOf(proxy?.type ?: PROXY_HTTP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (proxy == null) "添加 AI 代理" else "编辑 AI 代理") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { type = PROXY_HTTP }, enabled = type != PROXY_HTTP) { Text("HTTP") }
                OutlinedButton(onClick = { type = PROXY_SOCKS5 }, enabled = type != PROXY_SOCKS5) { Text("SOCKS5") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("主机") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.width(96.dp), label = { Text("端口") }, singleLine = true)
            }
            OutlinedTextField(username, { username = it }, label = { Text("用户名，可留空") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("密码，可留空") }, singleLine = true)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() in 1..65535, onClick = {
            onSave(AiProxyConfig(proxy?.id ?: UUID.randomUUID().toString(), name.trim(), host.trim(), port.toInt(), username, password, type))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSkills() {
    val workspace by AiWorkspaceStore.state.collectAsState()
    var editing by remember { mutableStateOf<AiSkill?>(null) }
    var viewing by remember { mutableStateOf<AiSkill?>(null) }
    var deleting by remember { mutableStateOf<AiSkill?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun copyForEditing(skill: AiSkill) {
        viewing = null
        editing = skill.copy(
            id = UUID.randomUUID().toString(),
            name = "${skill.name} 副本",
            enabled = false,
            builtIn = false,
        )
    }
    ManagementScaffold("任务指南", onAdd = { adding = true }) {
        items(workspace.skills, key = { it.id }) { skill ->
            ListItem(
                modifier = Modifier.clickable {
                    if (skill.builtIn) viewing = skill else editing = skill
                },
                headlineContent = { Text(skill.name) },
                supportingContent = {
                    Text("${if (skill.builtIn) "内置，只读，可复制" else "自定义指南"} · ${skill.capability.label}")
                },
                leadingContent = { Icon(Icons.Filled.Psychology, null) },
                trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = skill.enabled, onCheckedChange = { AiWorkspaceStore.saveSkill(skill.copy(enabled = it)) })
                    if (skill.builtIn) {
                        IconButton(onClick = { copyForEditing(skill) }) {
                            Icon(Icons.Filled.ContentCopy, "复制为自定义指南")
                        }
                    } else {
                        IconButton(onClick = { deleting = skill }) {
                            Icon(Icons.Filled.Delete, "删除")
                        }
                    }
                } },
            )
            HorizontalDivider()
        }
    }
    if (adding || editing != null) SkillDialog(editing, { adding = false; editing = null }) {
        AiWorkspaceStore.saveSkill(it); adding = false; editing = null
    }
    viewing?.let { skill ->
        BuiltInSkillDialog(
            skill = skill,
            onDismiss = { viewing = null },
            onCopy = { copyForEditing(skill) },
        )
    }
    deleting?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除自定义指南？") },
            text = { Text("将删除“${skill.name}”，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    AiWorkspaceStore.deleteSkill(skill.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SkillDialog(skill: AiSkill?, onDismiss: () -> Unit, onSave: (AiSkill) -> Unit) {
    var name by remember { mutableStateOf(skill?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(skill?.prompt.orEmpty()) }
    var capability by remember { mutableStateOf(skill?.capability ?: AiSkillCapability.BASE) }
    var capabilityMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (skill == null) "添加任务指南" else "编辑任务指南") },
        text = { Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { capabilityMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Psychology, null)
                    Spacer(Modifier.width(6.dp))
                    Text(capability.label)
                }
                DropdownMenu(expanded = capabilityMenu, onDismissRequest = { capabilityMenu = false }) {
                    AiSkillCapability.entries.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(value.label) },
                            leadingIcon = { if (value == capability) Icon(Icons.Filled.Check, null) },
                            onClick = { capability = value; capabilityMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().height(320.dp), label = { Text("系统指令") })
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
            onSave(AiSkill(
                id = skill?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                prompt = prompt.trim(),
                enabled = skill?.enabled ?: true,
                builtIn = false,
                capability = capability,
            ))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BuiltInSkillDialog(skill: AiSkill, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查看内置任务指南") },
        text = { Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = skill.name,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("名称") },
                singleLine = true,
                readOnly = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = skill.capability.label,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("适用任务") },
                singleLine = true,
                readOnly = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = skill.prompt,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().height(320.dp),
                label = { Text("系统指令") },
                readOnly = true,
            )
        } },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("复制为自定义指南")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagementScaffold(title: String, onAdd: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val nav = LocalNavController.current
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = nav::popBackStack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        ) },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, "添加") } },
    ) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding), content = content) }
}

private val NEW_SOURCE_TEMPLATE = """
    // @key example.source
    // @label 新番源
    // @versionName 1.0
    // @versionCode 1
    // @libVersion 11
    // @hasSearch true

    var preferenceHelper = Inject_PreferenceHelper;
    var DEFAULT_BASE_URL = "https://example.com";

    function PreferenceComponent_getPreference() {
        var result = new ArrayList();
        result.add(new SourcePreference.Edit("站点地址", "BaseUrl", DEFAULT_BASE_URL));
        return result;
    }

    function getBaseUrl() {
        var value = new String(preferenceHelper.get("BaseUrl", DEFAULT_BASE_URL)).trim();
        while (value.length > 0 && value.substring(value.length - 1) == "/") {
            value = value.substring(0, value.length - 1);
        }
        return value || DEFAULT_BASE_URL;
    }

    function PageComponent_getMainTabs() {
        var result = new ArrayList();
        result.add(new MainTab("首页", MainTab.MAIN_TAB_GROUP, "home"));
        return result;
    }

    function PageComponent_getSubTabs(mainTab) {
        var result = new ArrayList();
        result.add(new SubTab("全部", true, ""));
        return result;
    }

    function PageComponent_getContent(mainTab, subTab, key) {
        return new Pair(null, new ArrayList());
    }
""".trimIndent()
