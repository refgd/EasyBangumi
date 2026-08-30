package com.heyanle.easybangumi4.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiModelsTest {

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
        val session = AiSession(title = "test", messages = messages)

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
}
