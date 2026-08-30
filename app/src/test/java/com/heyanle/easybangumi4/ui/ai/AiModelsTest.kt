package com.heyanle.easybangumi4.ui.ai

import com.heyanle.easybangumi4.plugin.api.component.preference.SourcePreference
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertTrue(base.prompt.contains("纯纯看看"))
        assertFalse(skills.any { it.prompt.contains("EasyBangumi") })
        assertEquals(AiSkillCapability.entries.toSet(), skills.map { it.capability }.toSet())
        assertTrue(skills.first { it.capability == AiSkillCapability.PLAYBACK }.prompt.contains("media_probe"))
        assertTrue(base.prompt.contains("未验证的能力必须明确说明"))
        assertTrue(base.prompt.contains("PreferenceComponent_getPreference()"))
        assertTrue(base.prompt.contains("BaseUrl"))
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
    }

    @Test
    fun availableTools_areAlwaysDiscoverableLikeMcpTools() {
        assertEquals(
            setOf(
                "source_get",
                "source_docs",
                "source_replace",
                "source_validate",
                "source_debug",
                "installed_sources",
                "installed_source_read",
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

        assertEquals(AiDebugStopAfter.CATALOG, catalog.defaultDebugStopAfter(false))
        assertEquals(AiDebugStopAfter.SEARCH, catalog.defaultDebugStopAfter(true))
        assertEquals(AiDebugStopAfter.DETAIL, detail.defaultDebugStopAfter(false))
        assertEquals(AiDebugStopAfter.DANMAKU, danmaku.defaultDebugStopAfter(false))
    }

    @Test
    fun sourceDocs_supportProgressiveTopicDiscovery() {
        val overview = sourceComponentDocs("overview")
        val playback = sourceComponentDocs("playback")

        assertTrue(overview.contains("playback: 播放、HLS 与防串集"))
        assertFalse(overview.contains("blockedSegmentRegex"))
        assertTrue(playback.contains("blockedSegmentRegex"))
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
        assertTrue(topic.getAsJsonArray("enum").any { it.asString == "all" })

        val debug = tools.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "source_debug" }
            .asJsonObject.getAsJsonObject("function")
        assertTrue(debug.getAsJsonObject("parameters").getAsJsonObject("properties").has("pageKey"))
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
}
