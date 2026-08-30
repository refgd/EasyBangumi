package com.heyanle.easybangumi4.ui.ai

import com.heyanle.easybangumi4.plugin.api.component.danmaku.DanmakuComponent
import com.heyanle.easybangumi4.plugin.api.component.detailed.DetailedComponent
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.play.PlayComponent
import com.heyanle.easybangumi4.plugin.api.component.preference.PreferenceComponent
import com.heyanle.easybangumi4.plugin.api.component.search.SearchComponent
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.source.JSComponentBundle
import com.heyanle.easybangumi4.plugin.js.source.JsSource
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast.Name
import org.mozilla.javascript.ast.NewExpression
import org.mozilla.javascript.ast.NodeVisitor

internal suspend fun validateSourceAssembly(
    installed: ExtensionInfo.Installed,
    requireBaseUrl: Boolean,
    requiredCapabilities: Set<AiSkillCapability> = emptySet(),
) {
    val source = installed.sources.firstOrNull() as? JsSource
        ?: error("插件没有可装配的 JavaScript 源")
    val bundle = JSComponentBundle(source)
    try {
        val available = buildSet {
            if (bundle.getComponentProxy(PageComponent::class) != null) add(AiSkillCapability.CATALOG)
            if (bundle.getComponentProxy(SearchComponent::class) != null) add(AiSkillCapability.SEARCH)
            if (bundle.getComponentProxy(DetailedComponent::class) != null) add(AiSkillCapability.DETAIL)
            if (bundle.getComponentProxy(PlayComponent::class) != null) add(AiSkillCapability.PLAYBACK)
            if (bundle.getComponentProxy(DanmakuComponent::class) != null) add(AiSkillCapability.DANMAKU)
        }
        if (available.isEmpty()) error("插件没有可用的 Page/Search/Detailed/Play/Danmaku 组件")
        val required = requiredSourceCapabilities(requiredCapabilities)
        val missing = required - available
        if (missing.isNotEmpty()) {
            error("当前任务缺少组件: ${missing.joinToString { it.componentName }}")
        }
        if (requireBaseUrl) {
            val preference = bundle.getComponentProxy(PreferenceComponent::class) as? PreferenceComponent
                ?: error("必须实现 PreferenceComponent_getPreference() 并提供 BaseUrl")
            baseUrlPreferenceError(preference.register())?.let(::error)
        }
    } finally {
        bundle.release()
    }
}

internal fun unsafeEntityConstructionError(code: String): String? {
    val root = Parser().parse(code, "source", 1)
    val violations = mutableListOf<String>()
    root.visit(NodeVisitor { node ->
        if (node is NewExpression) {
            val typeName = when (val target = node.target) {
                is Name -> target.identifier
                else -> target.toSource().substringAfterLast('.')
            }
            SAFE_ENTITY_HELPERS[typeName]?.let { helper ->
                violations += "$typeName@${node.lineno} 应使用 $helper"
            }
        }
        true
    })
    return violations.takeIf { it.isNotEmpty() }?.joinToString(
        prefix = "禁止直接构造高风险运行时实体: ",
        separator = "; ",
    )
}

private val SAFE_ENTITY_HELPERS = mapOf(
    "CartoonCoverImpl" to "makeCartoonCover(map)",
    "CartoonImpl" to "makeCartoon(map)",
    "Episode" to "makeEpisode(map)",
    "PlayLine" to "makePlayLine(map)",
    "PlayerInfo" to "makePlayerInfo(map)",
    "Pair" to "makePageResult(...) 或 makeDetailedResult(...)",
)

internal fun requiredSourceCapabilities(
    capabilities: Set<AiSkillCapability>,
): Set<AiSkillCapability> = buildSet {
    if (AiSkillCapability.NEW_SOURCE in capabilities) {
        add(AiSkillCapability.CATALOG)
        add(AiSkillCapability.DETAIL)
        add(AiSkillCapability.PLAYBACK)
    }
    addAll(capabilities.filterNot { it == AiSkillCapability.BASE || it == AiSkillCapability.NEW_SOURCE })
}

private val AiSkillCapability.componentName: String
    get() = when (this) {
        AiSkillCapability.CATALOG -> "PageComponent"
        AiSkillCapability.SEARCH -> "SearchComponent"
        AiSkillCapability.DETAIL -> "DetailedComponent"
        AiSkillCapability.PLAYBACK -> "PlayComponent"
        AiSkillCapability.DANMAKU -> "DanmakuComponent"
        else -> name
    }
