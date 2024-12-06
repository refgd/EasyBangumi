package com.heyanle.easybangumi4.plugin.js.component

import com.heyanle.easybangumi4.plugin.api.ParserException
import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.component.ComponentWrapper
import com.heyanle.easybangumi4.plugin.api.component.play.PlayComponent
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.api.entity.PlayerInfo
import com.heyanle.easybangumi4.plugin.api.withResult
import com.heyanle.easybangumi4.plugin.js.runtime.JSScope
import com.heyanle.easybangumi4.plugin.js.utils.JSFunction
import com.heyanle.easybangumi4.plugin.js.utils.jsUnwrap
import kotlinx.coroutines.Dispatchers

/**
 * Created by heyanle on 2024/7/28.
 * https://github.com/heyanLE
 */
class JSPlayComponent(
    private val jsScope: JSScope,
    private val getPlayInfo: JSFunction,
): ComponentWrapper(), PlayComponent, JSBaseComponent {

    companion object {
        const val FUNCTION_NAME_GET_PLAY_INFO = "PlayComponent_getPlayInfo"

        suspend fun of (jsScope: JSScope) : JSPlayComponent ? {
            return jsScope.runWithScope { _, scriptable ->
                val getPlayInfo = scriptable.get(FUNCTION_NAME_GET_PLAY_INFO, scriptable) as? JSFunction
                    ?: return@runWithScope null
                return@runWithScope JSPlayComponent(jsScope, getPlayInfo)
            }
        }

    }

    override suspend fun getPlayInfo(
        summary: CartoonSummary,
        playLine: PlayLine,
        episode: Episode
    ): SourceResult<PlayerInfo> {
        return withResult(Dispatchers.IO) {
            jsScope.requestRunWithScope { context, scriptable ->
                val res = getPlayInfo.call(
                    context, scriptable, scriptable,
                    arrayOf(
                        summary, playLine, episode
                    )
                ).jsUnwrap() as? PlayerInfo
                if (res == null) {
                    throw ParserException("js parse error")
                }
                return@requestRunWithScope res
            }
        }
    }
}