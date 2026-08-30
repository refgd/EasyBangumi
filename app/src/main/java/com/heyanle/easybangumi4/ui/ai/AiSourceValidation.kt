package com.heyanle.easybangumi4.ui.ai

import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.preference.PreferenceComponent
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.source.JSComponentBundle
import com.heyanle.easybangumi4.plugin.js.source.JsSource

internal suspend fun validateSourceAssembly(
    installed: ExtensionInfo.Installed,
    requireBaseUrl: Boolean,
) {
    val source = installed.sources.firstOrNull() as? JsSource
        ?: error("插件没有可装配的 JavaScript 源")
    val bundle = JSComponentBundle(source)
    try {
        bundle.getComponentProxy(PageComponent::class)
        if (requireBaseUrl) {
            val preference = bundle.getComponentProxy(PreferenceComponent::class) as? PreferenceComponent
                ?: error("必须实现 PreferenceComponent_getPreference() 并提供 BaseUrl")
            baseUrlPreferenceError(preference.register())?.let(::error)
        }
    } finally {
        bundle.release()
    }
}
