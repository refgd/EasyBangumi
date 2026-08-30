package com.heyanle.easybangumi4.ui.ai

import com.heyanle.easybangumi4.plugin.api.component.preference.SourcePreference
import com.heyanle.easybangumi4.plugin.source.Debug
import com.google.gson.JsonObject
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {

    @Test
    fun defaultSkills_areSplitByTaskAndUseProductName() {
        val skills = defaultAiSkills()
        val base = defaultSourceSkill()

        assertEquals(7, skills.size)
        assertEquals("番源基础规范", base.name)
        assertFalse(skills.any { it.prompt.contains("EasyBangumi") })
        assertEquals(AiSkillCapability.entries.toSet(), skills.map { it.capability }.toSet())
        assertTrue(skills.first { it.capability == AiSkillCapability.PLAYBACK }.prompt.contains("media_probe"))
        assertTrue(base.prompt.contains("所有数据链路都优先使用"))
        assertTrue(base.prompt.contains("BaseUrl"))
        assertTrue(base.prompt.contains("不用正则提取字段"))
        assertTrue(base.prompt.contains("不手写算法"))
        assertTrue(base.prompt.contains("makePageResult"))
        assertTrue(base.prompt.contains("makePlayerInfo"))
        assertTrue(base.prompt.contains("禁止猜构造器参数"))
        assertFalse(base.prompt.contains("source_status"))
        assertTrue(base.prompt.length < 1_200)
    }

    @Test
    fun skillsFor_loadsBaseAndOnlyRequestedTaskCapability() {
        val workspace = AiWorkspaceData(skills = defaultAiSkills())
        val session = AiSession(
            title = "playback repair",
            skillCapabilities = listOf(AiSkillCapability.PLAYBACK),
        )

        val selected = workspace.skillsFor(session)

        assertEquals(
            listOf(AiSkillCapability.BASE, AiSkillCapability.PLAYBACK),
            selected.map { it.capability },
        )
    }

    @Test
    fun skillsFor_allowsCustomSkillToReplaceDisabledBuiltInCapability() {
        val custom = AiSkill(
            id = "custom-playback",
            name = "自定义播放修复",
            prompt = "custom",
            capability = AiSkillCapability.PLAYBACK,
        )
        val skills = defaultAiSkills().map {
            if (it.capability == AiSkillCapability.PLAYBACK) it.copy(enabled = false) else it
        } + custom
        val workspace = AiWorkspaceData(skills = skills)
        val session = AiSession(
            title = "playback repair",
            skillCapabilities = listOf(AiSkillCapability.PLAYBACK),
        )

        val selected = workspace.skillsFor(session)

        assertTrue(selected.any { it.id == DEFAULT_SOURCE_SKILL_ID })
        assertTrue(selected.any { it.id == custom.id })
        assertFalse(selected.any { it.id == "source-playback-repair" })
    }

    @Test
    fun savingEnabledSkill_disablesOtherGuidesForSameCapability() {
        val builtIns = defaultAiSkills()
        val custom = AiSkill(
            id = "custom-playback",
            name = "自定义播放",
            prompt = "custom",
            enabled = true,
            capability = AiSkillCapability.PLAYBACK,
        )

        val saved = AiWorkspaceData(skills = builtIns).withSavedSkill(custom)

        assertEquals(
            listOf("custom-playback"),
            saved.skills.filter { it.enabled && it.capability == AiSkillCapability.PLAYBACK }.map { it.id },
        )
        assertTrue(saved.skills.first { it.id == DEFAULT_SOURCE_SKILL_ID }.enabled)
    }

    @Test
    fun systemPrompt_placesRuntimeProtocolBeforeOnlyActiveSkills() {
        val workspace = AiWorkspaceData(skills = defaultAiSkills())
        val session = AiSession(
            title = "search repair",
            sourceKey = "test.source",
            skillCapabilities = listOf(AiSkillCapability.SEARCH),
        )

        val prompt = workspace.systemPromptFor(session)

        assertTrue(prompt.indexOf("运行协议") < prompt.indexOf("任务指南"))
        assertTrue(prompt.contains("番源 key：test.source"))
        assertTrue(prompt.contains("搜索修复"))
        assertFalse(prompt.contains("播放与 HLS 修复"))
        assertTrue(prompt.contains("纯纯看看"))
        assertTrue(prompt.contains("复用 responseId"))
        assertTrue(prompt.contains("门禁通过且对话成功结束后才自动安装"))
        assertTrue(prompt.contains("失败、停止或中断不安装"))
        assertTrue(prompt.contains("未验证能力必须明示"))
    }

    @Test
    fun unsafeEntityConstruction_reportsDirectRuntimeEntityConstruction() {
        val playerError = unsafeEntityConstructionError(
            "function play(url, type) { return new PlayerInfo(url, type); }",
        )
        assertNotNull(playerError)
        assertTrue(playerError!!.contains("PlayerInfo@1"))
        assertTrue(playerError.contains("makePlayerInfo(map)"))

        val nestedError = unsafeEntityConstructionError(
            "function detail() { var episode = new Episode('1', '1'); return new Pair(null, episode); }",
        )
        assertTrue(nestedError!!.contains("makeEpisode(map)"))
        assertTrue(nestedError.contains("makePageResult"))
    }

    @Test
    fun unsafeEntityConstruction_acceptsRuntimeHelpers() {
        assertNull(
            unsafeEntityConstructionError(
                "function play(uri) { return makePlayerInfo({ decodeType: 2, uri: uri }); }",
            ),
        )
    }

    @Test
    fun availableTools_areAlwaysDiscoverableLikeMcpTools() {
        assertEquals(
            setOf(
                "source_status",
                "source_get",
                "source_docs",
                "source_replace",
                "source_validate",
                "source_debug",
                "http_request",
                "media_probe",
            ),
            AI_SOURCE_TOOL_NAMES,
        )
    }

    @Test
    fun inferAiSkillCapabilities_routesStrongUserIntentWithoutModelRequest() {
        assertEquals(
            listOf(AiSkillCapability.NEW_SOURCE),
            inferAiSkillCapabilities("帮我适配网站 https://example.com 并创建新源"),
        )
        assertEquals(
            listOf(AiSkillCapability.DETAIL),
            inferAiSkillCapabilities("播放列表为空，帮我修复"),
        )
        assertEquals(
            listOf(AiSkillCapability.PLAYBACK),
            inferAiSkillCapabilities("这个 m3u8 只有十分钟，换集后还是原来的内容"),
        )
        assertEquals(
            listOf(AiSkillCapability.SEARCH),
            inferAiSkillCapabilities("搜索海贼王没有结果"),
        )
        assertTrue(inferAiSkillCapabilities("继续检查一下").isEmpty())
        assertEquals(
            listOf(AiSkillCapability.PLAYBACK, AiSkillCapability.SEARCH, AiSkillCapability.CATALOG),
            inferAiSkillCapabilities("首页、搜索和播放地址都失败了"),
        )
    }

    @Test
    fun appendDirectUserPrompt_switchesTaskOnlyForExplicitNewCapability() {
        val first = AiSession(title = "test").appendDirectUserPrompt("搜索结果为空")
        val followUp = first.appendDirectUserPrompt("继续检查一下")
        val switched = followUp.appendDirectUserPrompt("现在播放地址无法播放")

        assertEquals(listOf(AiSkillCapability.SEARCH), first.skillCapabilities)
        assertEquals(first.activeTaskMessageId, followUp.activeTaskMessageId)
        assertEquals(listOf(AiSkillCapability.SEARCH), followUp.skillCapabilities)
        assertEquals(listOf(AiSkillCapability.PLAYBACK), switched.skillCapabilities)
        assertEquals(switched.messages.last().id, switched.activeTaskMessageId)
    }

    @Test
    fun pendingPrompt_isConsumedOnlyWhenItBelongsToCurrentTask() {
        val session = AiSession(
            title = "search",
            skillCapabilities = listOf(AiSkillCapability.SEARCH),
        )

        assertTrue(session.canConsumePendingPrompt("再检查一下分页"))
        assertTrue(session.canConsumePendingPrompt("搜索另一个关键词"))
        assertFalse(session.canConsumePendingPrompt("这个 m3u8 无法播放"))
    }

    @Test
    fun debugStopStage_matchesTaskAndSearchInvocation() {
        val catalog = AiSession(title = "catalog", skillCapabilities = listOf(AiSkillCapability.CATALOG))
        val detail = AiSession(title = "detail", skillCapabilities = listOf(AiSkillCapability.DETAIL))
        val danmaku = AiSession(title = "danmaku", skillCapabilities = listOf(AiSkillCapability.DANMAKU))
        val newSource = AiSession(title = "new", skillCapabilities = listOf(AiSkillCapability.NEW_SOURCE))

        assertEquals(AiDebugStopAfter.CATALOG, catalog.defaultDebugStopAfter(false))
        assertEquals(AiDebugStopAfter.SEARCH, catalog.defaultDebugStopAfter(true))
        assertEquals(AiDebugStopAfter.DETAIL, detail.defaultDebugStopAfter(false))
        assertEquals(AiDebugStopAfter.DANMAKU, danmaku.defaultDebugStopAfter(false))
        assertEquals(AiDebugStopAfter.PLAYBACK, newSource.defaultDebugStopAfter(false))
        assertEquals(
            AiDebugStopAfter.PLAYBACK,
            AiSession(
                title = "mixed",
                skillCapabilities = listOf(AiSkillCapability.CATALOG, AiSkillCapability.PLAYBACK),
            ).defaultDebugStopAfter(false),
        )
    }

    @Test
    fun pendingInstall_requiresAllTaskDebugStagesAndMatchingMediaProbe() {
        val pending = PendingSourceInstall(
            sessionId = "session",
            key = "source.test",
            label = "Test",
            code = "code",
            wasInstalled = false,
            requiredCapabilities = setOf(AiSkillCapability.SEARCH, AiSkillCapability.PLAYBACK),
        )

        assertTrue(pending.blocker.orEmpty().contains("search"))
        assertTrue(pending.blocker.orEmpty().contains("playback"))

        pending.recordDebug(AiDebugStopAfter.PLAYBACK, "https://example.com/video.m3u8")
        assertTrue(pending.blocker.orEmpty().contains("search"))

        pending.recordDebug(AiDebugStopAfter.SEARCH, null)
        assertTrue(pending.blocker.orEmpty().contains("media_probe"))

        pending.recordMediaProbe("https://example.com/other.m3u8")
        assertNotNull(pending.blocker)
        pending.recordMediaProbe("https://example.com/video.m3u8")
        assertEquals(null, pending.blocker)
    }

    @Test
    fun pendingInstall_baseTaskAcceptsAnySuccessfulComponentDebug() {
        val pending = PendingSourceInstall(
            sessionId = "session",
            key = "source.search-only",
            label = "Search only",
            code = "code",
            wasInstalled = false,
            requiredCapabilities = emptySet(),
        )

        assertTrue(pending.blocker.orEmpty().contains("至少成功调用一次"))
        pending.recordDebug(AiDebugStopAfter.SEARCH, null)
        assertEquals(null, pending.blocker)
    }

    @Test
    fun requiredDebugCapabilities_requiresAnyStageForBaseAndExpandsNewSource() {
        assertEquals(
            emptySet<AiSkillCapability>(),
            requiredDebugCapabilities(AiSession(title = "base")),
        )
        assertEquals(
            setOf(AiSkillCapability.CATALOG, AiSkillCapability.DETAIL, AiSkillCapability.PLAYBACK),
            requiredDebugCapabilities(
                AiSession(title = "new", skillCapabilities = listOf(AiSkillCapability.NEW_SOURCE)),
            ),
        )
    }

    @Test
    fun detailDebugTarget_isReachedOnlyAfterEpisodeSelection() {
        assertFalse(hasReachedDebugTarget(AiDebugStopAfter.DETAIL, "playLine"))
        assertTrue(hasReachedDebugTarget(AiDebugStopAfter.DETAIL, "episode"))
        assertTrue(hasReachedDebugTarget(AiDebugStopAfter.CATALOG, "content"))
        assertTrue(hasReachedDebugTarget(AiDebugStopAfter.SEARCH, "search"))
    }

    @Test
    fun sourceDocs_supportProgressiveTopicDiscovery() {
        val overview = sourceComponentDocs("overview")
        val playback = sourceComponentDocs("playback")
        val runtime = sourceComponentDocs("runtime")
        val catalog = sourceComponentDocs("catalog")
        val detail = sourceComponentDocs("detail")
        val patterns = sourceComponentDocs("patterns")
        val utilities = sourceComponentDocs("utilities")
        val debug = sourceComponentDocs("debug")

        assertTrue(overview.contains("playback: 播放、HLS 与防串集"))
        assertTrue(overview.contains("所有数据链路优先使用 API"))
        assertFalse(overview.contains("blockedSegmentRegex"))
        assertTrue(playback.contains("blockedSegmentRegex"))
        assertTrue(playback.contains("播放解析 API 时优先使用 API"))
        assertTrue(runtime.contains("makeEpisode(map)"))
        assertTrue(catalog.contains("不要把 Rhino Number 直接作为 Pair 的 nextKey"))
        assertTrue(detail.contains("不要直接调用 Episode、PlayLine 或 Pair 构造器"))
        assertTrue(patterns.contains("不读取或模仿其他已安装插件"))
        assertTrue(patterns.contains("API -> HTML -> WebView"))
        assertTrue(patterns.contains("function getJson(path)"))
        assertTrue(patterns.contains("return JSON.parse(value)"))
        assertTrue(patterns.contains("不得用正则提取 JSON"))
        assertTrue(utilities.contains("Packages.java.security.MessageDigest"))
        assertTrue(utilities.contains("Packages.javax.crypto.Cipher"))
        assertTrue(utilities.contains("Packages.android.util.Base64"))
        assertTrue(utilities.contains("不得靠轮流尝试算法"))
        assertTrue(debug.contains("source_status"))
        assertTrue(sourceComponentDocs("unknown").contains("主题索引"))
    }

    @Test
    fun toolCatalog_exposesDescribedStrictSchemas() {
        val tools = aiToolDefinitions(AI_SOURCE_TOOL_NAMES)
        val docs = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_docs" }
            .asJsonObject.getAsJsonObject("function")
        val parameters = docs.getAsJsonObject("parameters")
        val topic = parameters.getAsJsonObject("properties").getAsJsonObject("topic")

        assertEquals(false, parameters.get("additionalProperties").asBoolean)
        assertTrue(topic.get("description").asString.contains("默认 overview"))
        assertTrue(topic.getAsJsonArray("enum").any { it.asString == "playback" })
        assertTrue(topic.getAsJsonArray("enum").any { it.asString == "patterns" })
        assertTrue(topic.getAsJsonArray("enum").any { it.asString == "utilities" })
        assertFalse(topic.getAsJsonArray("enum").any { it.asString == "all" })

        val replace = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_replace" }
            .asJsonObject.getAsJsonObject("function")
        assertTrue(replace.get("description").asString.contains("只在对话成功结束后"))

        val debug = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_debug" }
            .asJsonObject.getAsJsonObject("function")
        val debugProperties = debug.getAsJsonObject("parameters").getAsJsonObject("properties")
        assertTrue(debugProperties.has("pageKey"))
        assertTrue(debugProperties.has("contentId"))
        assertEquals(0, debugProperties.getAsJsonObject("contentIndex").get("minimum").asInt)
        assertEquals(0, debugProperties.getAsJsonObject("pageKey").get("minimum").asInt)
        val sourceGet = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_get" }
            .asJsonObject.getAsJsonObject("function")
            .getAsJsonObject("parameters").getAsJsonObject("properties")
        assertEquals(1000, sourceGet.getAsJsonObject("maxChars").get("minimum").asInt)
        assertEquals(100000, sourceGet.getAsJsonObject("maxChars").get("maximum").asInt)
        val http = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "http_request" }
            .asJsonObject.getAsJsonObject("function")
        assertTrue(http.get("description").asString.contains("不会重复发送网络请求"))
        assertTrue(
            http.getAsJsonObject("parameters").getAsJsonObject("properties").has("responseId"),
        )
        assertEquals(0, http.getAsJsonObject("parameters").getAsJsonArray("required").size())
        assertEquals(2, http.getAsJsonObject("parameters").getAsJsonArray("anyOf").size())
        assertEquals(
            "string",
            http.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("headers").getAsJsonObject("additionalProperties").get("type").asString,
        )
        val status = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_status" }
            .asJsonObject.getAsJsonObject("function")
        assertEquals(0, status.getAsJsonObject("parameters").getAsJsonObject("properties").size())
    }

    @Test
    fun toolArgumentValidation_enforcesSchemaBeforeExecution() {
        validateAiToolArguments("source_status", JsonObject())
        validateAiToolArguments("source_get", JsonObject().apply {
            addProperty("startChar", 0)
            addProperty("maxChars", 1_000)
        })
        validateAiToolArguments("http_request", JsonObject().apply {
            addProperty("url", "https://example.com")
            add("headers", JsonObject().apply { addProperty("Referer", "https://example.com/") })
        })

        assertTrue(runCatching {
            validateAiToolArguments("source_status", JsonObject().apply { addProperty("extra", true) })
        }.exceptionOrNull()?.message.orEmpty().contains("未声明参数"))
        assertTrue(runCatching {
            validateAiToolArguments("source_replace", JsonObject())
        }.exceptionOrNull()?.message.orEmpty().contains("缺少必填参数"))
        assertTrue(runCatching {
            validateAiToolArguments("source_get", JsonObject().apply { addProperty("startChar", "0") })
        }.exceptionOrNull()?.message.orEmpty().contains("必须是 integer"))
        assertTrue(runCatching {
            validateAiToolArguments("source_get", JsonObject().apply { add("startChar", com.google.gson.JsonNull.INSTANCE) })
        }.exceptionOrNull()?.message.orEmpty().contains("不能为 null"))
        assertTrue(runCatching {
            validateAiToolArguments("http_request", JsonObject())
        }.exceptionOrNull()?.message.orEmpty().contains("必需组合"))
    }

    @Test
    fun readTextSlice_stopsAtRequestedWindowAndReportsContinuation() {
        val first = readTextSlice(StringReader("0123456789"), startChar = 2, maxChars = 4)
        val last = readTextSlice(StringReader("0123456789"), startChar = 6, maxChars = 8)

        assertEquals("2345", first.text)
        assertEquals(6, first.nextStartChar)
        assertEquals("6789", last.text)
        assertEquals(null, last.nextStartChar)
    }

    @Test
    fun sanitizeDebugFields_redactsCredentialsButPreservesPlaybackHeaders() {
        val sanitized = sanitizeDebugFields(
            mapOf(
                "播放地址" to "https://example.com/video.m3u8?token=required",
                "请求头" to "Referer: https://example.com/\nCookie: session=secret\nAuthorization: Bearer secret\nX-Api-Key: secret",
            ),
        )

        assertEquals("https://example.com/video.m3u8?token=required", sanitized["播放地址"])
        assertTrue(sanitized["请求头"].orEmpty().contains("Referer: https://example.com/"))
        assertFalse(sanitized["请求头"].orEmpty().contains("session=secret"))
        assertFalse(sanitized["请求头"].orEmpty().contains("Bearer secret"))
        assertFalse(sanitized["请求头"].orEmpty().contains("X-Api-Key: secret"))
    }

    @Test
    fun baseUrlPreferenceValidation_requiresEditablePlainHttpUrl() {
        assertNotNull(baseUrlPreferenceError(emptyList()))
        assertNotNull(
            baseUrlPreferenceError(
                listOf(SourcePreference.Switch("站点地址", "BaseUrl", true)),
            ),
        )
        assertNotNull(
            baseUrlPreferenceError(
                listOf(SourcePreference.Edit("站点地址", "BaseUrl", "[站点](https://example.com)")),
            ),
        )
        assertEquals(
            null,
            baseUrlPreferenceError(
                listOf(SourcePreference.Edit("站点地址", "BaseUrl", "https://example.com")),
            ),
        )
    }

    @Test
    fun selectSessionModelId_explicitSelectionOverridesOldBinding() {
        val models = defaultAiModels()

        assertEquals("deepseek", selectSessionModelId("deepseek", "removed-model", models))
        assertEquals("codex-chatgpt", selectSessionModelId("", "codex-chatgpt", models))
    }

    @Test
    fun defaultModels_includeNativeAnthropicClaude() {
        val claude = defaultAiModels().firstOrNull { it.id == "anthropic-claude" }

        assertNotNull(claude)
        assertEquals(PROVIDER_ANTHROPIC, claude?.providerType)
        assertEquals("https://api.anthropic.com/v1/messages", claude?.endpointUrl)
        assertEquals("claude-sonnet-5", claude?.model)
    }

    @Test
    fun availableModels_excludesIncompleteRemoteConfigurationButAllowsLocalNoKeyEndpoint() {
        val local = AiModelConfig(
            id = "local",
            name = "Local",
            endpointUrl = "http://127.0.0.1:8080/v1/chat/completions",
            model = "local-model",
        )
        val workspace = AiWorkspaceData(models = defaultAiModels() + local)

        assertEquals(listOf("local"), workspace.availableAiModels().map { it.id })
        assertTrue(workspace.modelUnavailableReason(defaultAiModels().first { it.id == "deepseek" }).orEmpty().contains("API Key"))

        val loggedIn = workspace.copy(
            codexAuth = CodexAuth("access", "refresh", "id", "account"),
        )
        assertTrue(loggedIn.availableAiModels().any { it.id == "codex-chatgpt" })
    }

    @Test
    fun contextMessages_preservesInitialTaskAndRecentConversation() {
        val messages = (0 until 35).map { index ->
            AiMessage(id = index.toString(), role = if (index % 2 == 0) "user" else "assistant", content = "message-$index")
        }
        val session = AiSession(title = "test", messages = messages, activeTaskMessageId = "0")

        val context = session.contextMessages(30)

        assertEquals(30, context.size)
        assertEquals("0", context.first().id)
        assertEquals("34", context.last().id)
    }

    @Test
    fun contextMessages_excludesDisplayedReasoning() {
        val session = AiSession(
            title = "test",
            messages = listOf(
                AiMessage(id = "1", role = "user", content = "task"),
                AiMessage(id = "2", role = "reasoning", content = "summary"),
                AiMessage(id = "3", role = "assistant", content = "answer"),
            ),
        )

        assertEquals(listOf("1", "3"), session.contextMessages().map { it.id })
    }

    @Test
    fun contextMessages_excludesConversationBeforeCurrentTask() {
        val session = AiSession(
            title = "test",
            messages = listOf(
                AiMessage(id = "1", role = "user", content = "old task"),
                AiMessage(id = "2", role = "assistant", content = "old answer"),
                AiMessage(id = "3", role = "user", content = "current task"),
                AiMessage(id = "4", role = "assistant", content = "current answer"),
            ),
            activeTaskMessageId = "3",
        )

        assertEquals(listOf("3", "4"), session.contextMessages().map { it.id })
    }

    @Test
    fun toolRoundBudget_extendsPastBaseWhenToolResultsProgress() {
        val budget = AiToolRoundBudget(baseRounds = 2, maxRounds = 5, maxStalledRounds = 1)

        assertEquals(0, budget.nextRoundOrNull())
        budget.recordToolResult("http_request", AiAgentToolResult("page-1", isError = false))
        assertEquals(1, budget.nextRoundOrNull())
        budget.recordToolResult("http_request", AiAgentToolResult("page-2", isError = false))
        assertEquals(2, budget.nextRoundOrNull())
    }

    @Test
    fun toolRoundBudget_stopsAfterBaseWhenToolResultsRepeat() {
        val budget = AiToolRoundBudget(baseRounds = 2, maxRounds = 5, maxStalledRounds = 1)

        assertEquals(0, budget.nextRoundOrNull())
        budget.recordToolResult("source_validate", AiAgentToolResult("same-error", isError = true))
        assertEquals(1, budget.nextRoundOrNull())
        budget.recordToolResult("source_validate", AiAgentToolResult("same-error", isError = true))

        assertEquals(null, budget.nextRoundOrNull())
        assertTrue(budget.exhaustedMessage().contains("连续重复相同结果"))
    }

    @Test
    fun toolRoundBudget_ignoresVolatileHttpIdsAndDebugTimestamps() {
        val httpBudget = AiToolRoundBudget(baseRounds = 2, maxRounds = 5, maxStalledRounds = 1)
        assertEquals(0, httpBudget.nextRoundOrNull())
        httpBudget.recordToolResult("http_request", AiAgentToolResult("responseId=one\nHTTP 200\nbody", false))
        assertEquals(1, httpBudget.nextRoundOrNull())
        httpBudget.recordToolResult("http_request", AiAgentToolResult("responseId=two\nHTTP 200\nbody", false))
        assertEquals(null, httpBudget.nextRoundOrNull())

        val debugBudget = AiToolRoundBudget(baseRounds = 2, maxRounds = 5, maxStalledRounds = 1)
        assertEquals(0, debugBudget.nextRoundOrNull())
        debugBudget.recordToolResult("source_debug", AiAgentToolResult("[00:01.100] same", false))
        assertEquals(1, debugBudget.nextRoundOrNull())
        debugBudget.recordToolResult("source_debug", AiAgentToolResult("[00:02.900] same", false))
        assertEquals(null, debugBudget.nextRoundOrNull())
    }

    @Test
    fun streamGuard_rejectsInterruptedResponsesAndAcceptsCompletion() {
        val interrupted = AiStreamGuard("Test API")
        val error = runCatching { interrupted.requireComplete() }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("未收到完成标记"))

        val completed = AiStreamGuard("Test API")
        completed.markComplete()
        completed.requireComplete()
    }

    @Test
    fun parseAiSseEvent_rejectsMalformedPayloadsWithProviderContext() {
        assertEquals("ok", parseAiSseEvent("Test API", "{\"type\":\"ok\"}").get("type").asString)

        val error = runCatching { parseAiSseEvent("Test API", "not-json") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("Test API"))
        assertTrue(error?.message.orEmpty().contains("无效的流式事件"))
    }

    @Test
    fun parseToolArguments_rejectsIncompleteJsonInsteadOfUsingEmptyInput() {
        assertEquals("value", parseToolArguments("Claude", "{\"key\":\"value\"}").get("key").asString)

        val error = runCatching { parseToolArguments("Claude", "{\"key\":" ) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("Claude"))
        assertTrue(error?.message.orEmpty().contains("JSON"))
    }

    @Test
    fun installableSourceKey_rejectsDebugLoaderSuffix() {
        assertEquals("source.normal", installableSourceKey("source.normal"))

        val error = runCatching { installableSourceKey("source.normal.__debug__") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains(".__debug__"))
    }

    @Test
    fun debugSessionOwnership_preventsOneClientFromReplacingAnother() {
        val first = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) = Unit
            override fun emit(event: Debug.Event) = Unit
        }
        val second = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) = Unit
            override fun emit(event: Debug.Event) = Unit
        }

        try {
            assertTrue(Debug.beginSession(first))
            assertFalse(Debug.beginSession(second))
            Debug.endSession(second)
            assertTrue(Debug.callback === first)
        } finally {
            Debug.endSession(first)
            Debug.endSession(second)
        }
        assertEquals(null, Debug.callback)
    }

    @Test
    fun requiredSourceCapabilities_expandsNewSourceToPlayableCoreChain() {
        assertEquals(
            setOf(AiSkillCapability.CATALOG, AiSkillCapability.DETAIL, AiSkillCapability.PLAYBACK),
            requiredSourceCapabilities(setOf(AiSkillCapability.BASE, AiSkillCapability.NEW_SOURCE)),
        )
        assertEquals(
            setOf(AiSkillCapability.SEARCH, AiSkillCapability.DANMAKU),
            requiredSourceCapabilities(setOf(AiSkillCapability.SEARCH, AiSkillCapability.DANMAKU)),
        )
    }
}
