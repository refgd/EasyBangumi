package com.heyanle.easybangumi4.plugin.api.component.page

import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.component.Component
import com.heyanle.easybangumi4.plugin.api.entity.CartoonCover
import com.heyanle.easybangumi4.plugin.js.entity.MainTab
import com.heyanle.easybangumi4.plugin.js.entity.SubTab


/**
 * Created by HeYanLe on 2023/10/18 23:25.
 * https://github.com/heyanLE
 */
interface PageComponent: Component {

    class NonLabelSinglePage(
        cartoonPage: SourcePage
    ) : List<SourcePage> by listOf(cartoonPage)

    suspend fun getPages(): List<SourcePage>

    suspend fun getMainTabs(): ArrayList<MainTab>?
    suspend fun getSubTabs(label: String): ArrayList<SubTab>?
    suspend fun getContent(mainTabLabel: String, subTabLabel: String, key: Int): SourceResult<Pair<Int?, List<CartoonCover>>>?
}