package com.heyanle.easybangumi4.plugin.source

import android.annotation.SuppressLint
import com.heyanle.easybangumi4.BuildConfig
import com.heyanle.easybangumi4.plugin.api.component.detailed.DetailedComponent
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.play.PlayComponent
import com.heyanle.easybangumi4.plugin.api.entity.CartoonCover
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.entity.MainTab
import com.heyanle.easybangumi4.plugin.js.entity.SubTab
import com.heyanle.easybangumi4.plugin.js.source.JSComponentBundle
import com.heyanle.easybangumi4.plugin.js.source.JsSource
import com.heyanle.easybangumi4.plugin.source.bundle.getComponentProxy
import com.heyanle.easybangumi4.utils.coroutine.CompositeCoroutine
import com.heyanle.easybangumi4.utils.coroutine.Coroutine
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (BuildConfig.DEBUG) {
            msg.logi("sourceDebug")
        }
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = msg

            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    fun cancelDebug(destroy: Boolean = false) {
        tasks.clear()

        if (destroy) {
            debugSource = null
            callback = null
        }
    }

    fun startDebug(scope: CoroutineScope, ext: ExtensionInfo.Installed) {
        cancelDebug()
        startTime = System.currentTimeMillis()
        debugSource = ext.key

        log(ext.key, "⇒开始加载插件:${ext.key}")
        val bundle = JSComponentBundle(ext.sources[0] as JsSource)
        getMainTabs(scope, bundle)
    }

    private fun getMainTabs(scope: CoroutineScope, bundle: JSComponentBundle) {
        log(debugSource, "︾开始获取主分类")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PageComponent>()?.getMainTabs()
        }
        .onSuccess { mainTabs ->
            if (!mainTabs.isNullOrEmpty()) {
                val firstTab = mainTabs.firstOrNull()
                if(firstTab != null){
                    log(debugSource, "┌获取主分类")
                    mainTabs.map {
                        log(debugSource, " ${it.label}")
                    }
                    log(debugSource, "└分类数量:${mainTabs.size}")
                    log(debugSource, showTime = false)

                    if(firstTab.type == MainTab.MAIN_TAB_WITH_COVER){
                        getContent(scope, bundle, firstTab, null)
                    }else{
                        getSubTabs(scope, bundle, firstTab)
                    }
                }else{
                    log(debugSource, "︽未获取到主分类", state = -1)
                }
            }else{
                log(debugSource, "︽未获取到主分类", state = -1)
            }
        }
        .onError {
            log(debugSource, it.stackTraceStr, state = -1)
        }
        tasks.add(task)
    }

    private fun getSubTabs(scope: CoroutineScope, bundle: JSComponentBundle, mainTab: MainTab) {
        log(debugSource, "︾获取分类[${mainTab.label}]的次分类")
        val task = Coroutine.async(scope, Dispatchers.IO) {
                bundle.getComponentProxy<PageComponent>()?.getSubTabs(mainTab.label)
            }
            .onSuccess { subTabs ->
                if (!subTabs.isNullOrEmpty()) {
                    val firstTab = subTabs.firstOrNull()
                    if(firstTab != null){
                        log(debugSource, "┌获取次分类")
                        subTabs.map {
                            log(debugSource, " ${it.label}")
                        }
                        log(debugSource, "└分类数量:${subTabs.size}")
                        log(debugSource, showTime = false)

                        getContent(scope, bundle, mainTab, firstTab)
                    }else{
                        log(debugSource, "︽未获取到次分类", state = -1)
                    }
                }else{
                    log(debugSource, "︽未获取到次分类", state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(task)
    }

    private fun getContent(scope: CoroutineScope, bundle: JSComponentBundle, mainTab: MainTab, subTab: SubTab?) {
        val subName = if(subTab != null) ".${subTab.label}" else ""
        log(debugSource, "︾获取分类[${mainTab.label}${subName}]内容")
        val task = Coroutine.async(scope, Dispatchers.IO) {
                bundle.getComponentProxy<PageComponent>()
                    ?.getContent(mainTab.label, subTab?.label ?: "", 0)
            }
            .onSuccess { results ->
                results?.complete { result ->
                    log(debugSource, "┌分类内容")
                    result.data.second.map {
                        log(debugSource, " ${it.title}")
                    }
                    log(debugSource, "└下一页:${result.data.first}")
                    log(debugSource, showTime = false)

                    getDetailed(scope, bundle, result.data.second[0])
                }?.error {
                    log(debugSource, it.throwable.stackTraceStr, state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(task)
    }

    private fun getDetailed(scope: CoroutineScope, bundle: JSComponentBundle, cartoonCover: CartoonCover) {
        log(debugSource, "︾获取详情[${cartoonCover.title}]")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<DetailedComponent>()
                ?.getAll(CartoonSummary(cartoonCover.id, debugSource!!))
        }
            .onSuccess { results ->
                results?.complete { result ->
                    log(debugSource, "封面: ${result.data.first.coverUrl}")
                    log(debugSource, "介绍: ${result.data.first.description}")
                    log(debugSource, "┌播放源")
                    result.data.second.map {
                        log(debugSource, " ${it.label}")
                    }
                    log(debugSource, "└总数:${result.data.second.size}")
                    log(debugSource, "┌播放列表")
                    result.data.second[0].episode.map {
                        log(debugSource, " ${it.label}")
                    }
                    log(debugSource, "└总数:${result.data.second[0].episode.size}")
                    log(debugSource, showTime = false)

                    getPlayInfo(scope, bundle, cartoonCover, result.data.second[0], result.data.second[0].episode[0])
                }?.error {
                    log(debugSource, it.throwable.stackTraceStr, state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(task)
    }

    private fun getPlayInfo(scope: CoroutineScope, bundle: JSComponentBundle, cartoonCover: CartoonCover, playLine: PlayLine, episode: Episode) {
        log(debugSource, "︾获取视频地址[${episode.label}]")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PlayComponent>()
                ?.getPlayInfo(CartoonSummary(cartoonCover.id, debugSource!!), playLine, episode)
        }
            .onSuccess { results ->
                results?.complete { result ->
                    log(debugSource, "播放地址:${result.data.uri}")
                    log(debugSource, showTime = false)

                    log(debugSource, "︽获取视频地址完成", state = 1000)
                }?.error {
                    log(debugSource, it.throwable.stackTraceStr, state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(task)
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}