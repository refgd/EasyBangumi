package com.heyanle.easybangumi4.plugin.api.component.danmaku


import com.bytedance.danmaku.render.engine.data.DanmakuData
import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.component.Component
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.api.entity.PlayerInfo

/**
 * Created by HeYanLe on 2023/10/18 23:28.
 * https://github.com/heyanLE
 */
interface DanmakuComponent: Component {
    /**
     * 获取弹幕信息
     * @param playLine 对应的播放线路
     * @param episode 集
     */
    suspend fun getDanmakuInfo(
        summary: CartoonSummary,
        playLine: PlayLine,
        episode: Episode,
    ): SourceResult<List<DanmakuData>>
}