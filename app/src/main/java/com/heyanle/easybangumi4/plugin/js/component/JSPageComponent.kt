package com.heyanle.easybangumi4.plugin.js.component

import com.heyanle.easybangumi4.plugin.api.ParserException
import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.component.ComponentWrapper
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.page.SourcePage
import com.heyanle.easybangumi4.plugin.api.entity.CartoonCover
import com.heyanle.easybangumi4.plugin.api.withResult
import com.heyanle.easybangumi4.plugin.js.entity.MainTab
import com.heyanle.easybangumi4.plugin.js.entity.NonLabelMainTab
import com.heyanle.easybangumi4.plugin.js.entity.SubTab
import com.heyanle.easybangumi4.plugin.js.runtime.JSScope
import com.heyanle.easybangumi4.plugin.js.runtime.JSScopeException
import com.heyanle.easybangumi4.plugin.js.utils.JSFunction
import com.heyanle.easybangumi4.plugin.js.utils.jsUnwrap
import com.heyanle.easybangumi4.plugin.source.Debug
import com.heyanle.easybangumi4.utils.logi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Created by heyanle on 2024/7/27.
 * https://github.com/heyanLE
 */
class JSPageComponent(
    private val jsScope: JSScope,
    private val getMainTabs: JSFunction,
    private val getSubTabs: JSFunction,
    private val getContent: JSFunction,
) : ComponentWrapper(), PageComponent, JSBaseComponent {


    companion object {


        const val FUNCTION_NAME_GET_MAIN_TABS = "PageComponent_getMainTabs"
        const val FUNCTION_NAME_GET_SUB_TABS = "PageComponent_getSubTabs"
        const val FUNCTION_NAME_GET_CONTENT = "PageComponent_getContent"

        suspend fun of(jsScope: JSScope): JSPageComponent? {
            return jsScope.runWithScope { _, scriptable ->
                val getMainTabs =
                    scriptable.get(FUNCTION_NAME_GET_MAIN_TABS, scriptable) as? JSFunction
                val getSubTabs = scriptable.get(FUNCTION_NAME_GET_SUB_TABS, scriptable) as? JSFunction
                val getContent = scriptable.get(FUNCTION_NAME_GET_CONTENT, scriptable) as? JSFunction
                if (getMainTabs == null || getSubTabs == null || getContent == null) {
                    return@runWithScope null
                }
                return@runWithScope JSPageComponent(
                    jsScope,
                    getMainTabs,
                    getSubTabs,
                    getContent
                )
            }
        }
    }

    @Volatile
    private var mainTabList = arrayListOf<MainTab>()

    private var SubTabList: MutableMap<String, ArrayList<SubTab>> = HashMap()

    override suspend fun getPages(): List<SourcePage> {
        if(mainTabList.isEmpty()){
            getMainTabs()
        }

        if (mainTabList.size == 1) {
            val f = mainTabList.first()
            if (f.label.isEmpty()) {
                return PageComponent.NonLabelSinglePage(
                    mainTab2SourcePage(f)
                )
            }
        }

        return mainTabList.map { mainTab2SourcePage(it) }.apply {
            this.logi("JSPageComponent")
        }
    }

    override suspend fun getMainTabs(): ArrayList<MainTab>? {
        if(mainTabList.isEmpty()){
            return jsScope.runWithScope { context, scriptable ->
                (getMainTabs.call(
                    context, scriptable, scriptable, arrayOf()
                )?.jsUnwrap() as? ArrayList<*>)?.filterIsInstance<MainTab>()
                    ?.map {
                        mainTabList.add(it)
                    }

                mainTabList
            }
        }
        return mainTabList
    }

    override suspend fun getSubTabs(label: String): ArrayList<SubTab>? {
        if(SubTabList.containsKey(label)) return SubTabList[label]

        val mainTab = mainTabList.find { it.label == label }
        if (mainTab == null) return null

        return jsScope.runWithScope { context, scriptable ->
            val result = arrayListOf<SubTab>()
            (getSubTabs.call(
                context, scriptable, scriptable, arrayOf(mainTab)
            )?.jsUnwrap() as? ArrayList<*>)?.filterIsInstance<SubTab>()
                ?.map {
                    result.add(it)
                }

            SubTabList[label] = result
            result
        }
    }

    override suspend fun getContent(
        mainTabLabel: String,
        subTabLabel: String,
        key: Int
    ): SourceResult<Pair<Int?, List<CartoonCover>>>? {
        val mainTab = mainTabList.find { it.label == mainTabLabel }
        if (mainTab == null) return null

        var subTab: SubTab? = null
        if (subTabLabel.isNotEmpty() && SubTabList.containsKey(mainTabLabel)){
            subTab = SubTabList[mainTabLabel]?.find { it.label == subTabLabel }
        }

        return load(mainTab, subTab, key)
    }

    private fun mainTab2SourcePage(mainTab: MainTab) : SourcePage{
        return if (mainTab.type == MainTab.MAIN_TAB_GROUP) {
            SourcePage.Group(
                label = mainTab.label,
                newScreen = false,
                loadPage = suspend {
                    withResult(Dispatchers.IO) {
                        jsScope.runWithScope { context, scriptable ->
                            ((getSubTabs.call(
                                context, scriptable, scriptable,
                                arrayOf(mainTab)
                            ).jsUnwrap() as? ArrayList<*>) ?: arrayListOf<Any>()).filterIsInstance<SubTab>()
                                .map {
                                    subTab2SourcePage(mainTab, it)
                                }

                        } ?: emptyList()
                    }
                }
            )
        } else {
            if (mainTab.type == MainTab.MAIN_TAB_WITH_COVER) {
                SourcePage.SingleCartoonPage.WithCover(
                    label = mainTab.label,
                    firstKey = { 0 },
                    load = {
                        load(mainTab, null, it)
                    }
                )
            } else {
                SourcePage.SingleCartoonPage.WithoutCover(
                    label = mainTab.label,
                    firstKey = { 0 },
                    load = {
                        load(mainTab, null, it)
                    }
                )
            }
        }
    }

        private fun subTab2SourcePage(mainTab: MainTab, subTab: SubTab): SourcePage.SingleCartoonPage {
            return if (subTab.isCover)
                SourcePage.SingleCartoonPage.WithCover(
                    label = subTab.label,
                    firstKey = { 0 },
                    load = {
                        load(mainTab, subTab, it)
                    }
                )
            else
                SourcePage.SingleCartoonPage.WithoutCover(
                    label = subTab.label,
                    firstKey = { 0 },
                    load = {
                        load(mainTab, subTab, it)
                    }
                )
        }

        private suspend fun load(mainTab: MainTab, subTab: SubTab?, key: Int): SourceResult<Pair<Int?, List<CartoonCover>>> {
            return withResult(Dispatchers.IO) {
                jsScope.requestRunWithScope { context, scriptable ->
                    val source = (getContent.call(
                        context,
                        scriptable,
                        scriptable,
                        arrayOf(
                            mainTab, subTab, key
                        )
                    ))
                    val jsSource = source.jsUnwrap()
                    val res = jsSource as? Pair<*, *>
                    if (res == null) {
                        throw ParserException("js parse error")
                    }
                    val nextKey = res.first as? Int?
                    val data = res.second as? java.util.ArrayList<*> ?: throw ParserException("js parse error")
                    if (data.isNotEmpty() && data.first() !is CartoonCover) {
                        throw ParserException("js parse error")
                    }
                    return@requestRunWithScope nextKey to data.filterIsInstance<CartoonCover>()
                }
            }.apply {
                this.logi("JSPageComponent")
            }
        }
    }