package com.heyanle.easybangumi4.plugin.js.component

import com.bytedance.danmaku.render.engine.data.DanmakuData
import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.component.ComponentWrapper
import com.heyanle.easybangumi4.plugin.api.component.danmaku.DanmakuComponent
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.api.withResult
import com.heyanle.easybangumi4.plugin.js.runtime.JSScope
import com.heyanle.easybangumi4.plugin.js.utils.JSFunction
import com.heyanle.easybangumi4.plugin.js.utils.jsUnwrap
import kotlinx.coroutines.Dispatchers

/**
 * Created by heyanle on 2024/7/28.
 * https://github.com/heyanLE
 */
class JSDanmakuComponent(
    private val jsScope: JSScope,
    private val getDanmakuInfo: JSFunction,
): ComponentWrapper(), DanmakuComponent, JSBaseComponent {

    companion object {
        const val FUNCTION_NAME_GET_DANMAKU_INFO = "PlayComponent_getDanmakuInfo"

        suspend fun of (jsScope: JSScope) : JSDanmakuComponent ? {
            return jsScope.runWithScope { _, scriptable ->
                val getDanmakuInfo = scriptable.get(FUNCTION_NAME_GET_DANMAKU_INFO, scriptable) as? JSFunction
                    ?: return@runWithScope null

                return@runWithScope JSDanmakuComponent(jsScope, getDanmakuInfo)
            }
        }

    }

    override suspend fun getDanmakuInfo(
        summary: CartoonSummary,
        playLine: PlayLine,
        episode: Episode
    ): SourceResult<List<DanmakuData>> {
        return withResult(Dispatchers.IO) {
            jsScope.requestRunWithScope { context, scriptable ->
                val result = arrayListOf<DanmakuData>()
                (getDanmakuInfo.call(
                    context, scriptable, scriptable,
                    arrayOf(
                        summary, playLine, episode
                    )
                )?.jsUnwrap() as? ArrayList<*>)?.filterIsInstance<DanmakuData>()
                    ?.map {
                        result.add(it)
                    }

                result.sortedBy { it.showAtTime }
            }
        }
    }
}