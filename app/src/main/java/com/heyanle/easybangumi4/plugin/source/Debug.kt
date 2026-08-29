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
    private var debugBundle: JSComponentBundle? = null
    private val tasks = CompositeCoroutine()

    private var requestVersion = 0L
    private var mainTabs: List<MainTab> = emptyList()
    private var subTabs: List<SubTab> = emptyList()
    private var contents: List<CartoonCover> = emptyList()
    private var playLines: List<PlayLine> = emptyList()
    private var selectedMainTab: MainTab? = null
    private var selectedSubTab: SubTab? = null
    private var selectedCover: CartoonCover? = null
    private var selectedPlayLine: PlayLine? = null
    private var currentPageKey = 0

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
        callback?.let {
            if (debugSource != sourceUrl || !print) return
            val printMsg = if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                "$time $msg"
            } else {
                msg
            }
            it.printLog(state, printMsg)
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    @Synchronized
    fun cancelDebug(destroy: Boolean = false) {
        requestVersion++
        tasks.clear()
        clearSelection()

        if (destroy) {
            debugBundle?.destory()
            debugBundle = null
            debugSource = null
            callback = null
        }
    }

    fun startDebug(scope: CoroutineScope, ext: ExtensionInfo.Installed) {
        cancelDebug()
        startTime = System.currentTimeMillis()
        debugSource = ext.key

        log(ext.key, "=> 开始加载插件:${ext.key}")
        val source = ext.sources.firstOrNull() as? JsSource
        if (source == null) {
            fail("插件中没有可调试的 JS 数据源")
            return
        }
        debugBundle = JSComponentBundle(source)
        getMainTabs(scope)
    }

    fun select(scope: CoroutineScope, stage: String, index: Int) {
        when (stage) {
            STAGE_MAIN -> selectMainTab(scope, index)
            STAGE_SUB -> selectSubTab(scope, index)
            STAGE_CONTENT -> selectContent(scope, index)
            STAGE_PLAY_LINE -> selectPlayLine(index)
            STAGE_EPISODE -> selectEpisode(scope, index)
            else -> fail("未知调试步骤: $stage")
        }
    }

    fun loadPage(scope: CoroutineScope, key: Int) {
        if (selectedMainTab == null) {
            fail("请先选择分类")
            return
        }
        getContent(scope, key)
    }

    private fun clearSelection() {
        mainTabs = emptyList()
        subTabs = emptyList()
        contents = emptyList()
        playLines = emptyList()
        selectedMainTab = null
        selectedSubTab = null
        selectedCover = null
        selectedPlayLine = null
        currentPageKey = 0
    }

    private fun nextRequest(title: String): Long {
        val version = ++requestVersion
        callback?.emit(Event(type = TYPE_BUSY, title = title))
        return version
    }

    private fun isCurrent(version: Long): Boolean = version == requestVersion

    private fun getMainTabs(scope: CoroutineScope) {
        log(debugSource, "获取主分类")
        val version = nextRequest("正在获取主分类")
        val bundle = debugBundle ?: return fail("调试器尚未加载插件")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PageComponent>()?.getMainTabs()
        }.onSuccess { result ->
            if (!isCurrent(version)) return@onSuccess
            mainTabs = result.orEmpty()
            if (mainTabs.isEmpty()) {
                fail("未获取到主分类")
                return@onSuccess
            }
            log(debugSource, "获取主分类完成，共 ${mainTabs.size} 个")
            callback?.emit(
                Event(
                    type = TYPE_SELECTION,
                    stage = STAGE_MAIN,
                    title = "选择主分类",
                    options = mainTabs.mapIndexed { index, tab ->
                        Option(index, tab.label, mainTabType(tab.type))
                    }
                )
            )
        }.onError {
            if (isCurrent(version)) fail(it.stackTraceStr)
        }
        tasks.add(task)
    }

    private fun selectMainTab(scope: CoroutineScope, index: Int) {
        val mainTab = mainTabs.getOrNull(index) ?: return fail("主分类序号无效: $index")
        selectedMainTab = mainTab
        selectedSubTab = null
        selectedCover = null
        selectedPlayLine = null
        subTabs = emptyList()
        contents = emptyList()
        playLines = emptyList()
        currentPageKey = 0
        callback?.emit(contextEvent())

        if (mainTab.type == MainTab.MAIN_TAB_GROUP) {
            getSubTabs(scope, mainTab)
        } else {
            getContent(scope, 0)
        }
    }

    private fun getSubTabs(scope: CoroutineScope, mainTab: MainTab) {
        log(debugSource, "获取分类[${mainTab.label}]的次分类")
        val version = nextRequest("正在获取次分类")
        val bundle = debugBundle ?: return fail("调试器尚未加载插件")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PageComponent>()?.getSubTabs(mainTab.label)
        }.onSuccess { result ->
            if (!isCurrent(version)) return@onSuccess
            subTabs = result.orEmpty()
            if (subTabs.isEmpty()) {
                fail("分类[${mainTab.label}]未获取到次分类")
                return@onSuccess
            }
            log(debugSource, "获取次分类完成，共 ${subTabs.size} 个")
            callback?.emit(
                Event(
                    type = TYPE_SELECTION,
                    stage = STAGE_SUB,
                    title = "选择次分类",
                    options = subTabs.mapIndexed { index, tab ->
                        Option(index, tab.label, if (tab.isCover) "封面列表" else "文字列表")
                    }
                )
            )
        }.onError {
            if (isCurrent(version)) fail(it.stackTraceStr)
        }
        tasks.add(task)
    }

    private fun selectSubTab(scope: CoroutineScope, index: Int) {
        selectedSubTab = subTabs.getOrNull(index) ?: return fail("次分类序号无效: $index")
        selectedCover = null
        selectedPlayLine = null
        contents = emptyList()
        playLines = emptyList()
        currentPageKey = 0
        callback?.emit(contextEvent())
        getContent(scope, 0)
    }

    private fun getContent(scope: CoroutineScope, key: Int) {
        val mainTab = selectedMainTab ?: return fail("请先选择主分类")
        val subTab = selectedSubTab
        val name = listOfNotNull(mainTab.label, subTab?.label).joinToString(" / ")
        log(debugSource, "获取分类[$name]内容，页参数=$key")
        val version = nextRequest("正在获取分类内容")
        val bundle = debugBundle ?: return fail("调试器尚未加载插件")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PageComponent>()
                ?.getContent(mainTab.label, subTab?.label.orEmpty(), key)
        }.onSuccess { results ->
            if (!isCurrent(version)) return@onSuccess
            if (results == null) {
                fail("分类内容接口不可用")
                return@onSuccess
            }
            results.complete { result ->
                if (!isCurrent(version)) return@complete
                currentPageKey = key
                contents = result.data.second
                selectedCover = null
                selectedPlayLine = null
                playLines = emptyList()
                log(debugSource, "获取分类内容完成，共 ${contents.size} 条，下一页=${result.data.first}")
                callback?.emit(contextEvent())
                callback?.emit(
                    Event(
                        type = TYPE_SELECTION,
                        stage = STAGE_CONTENT,
                        title = "选择数据",
                        options = contents.mapIndexed { index, cover ->
                            Option(index, cover.title, cover.intro.orEmpty(), cover.coverUrl)
                        },
                        pageKey = key,
                        nextPageKey = result.data.first
                    )
                )
            }.error {
                if (isCurrent(version)) fail(it.throwable.stackTraceStr)
            }
        }.onError {
            if (isCurrent(version)) fail(it.stackTraceStr)
        }
        tasks.add(task)
    }

    private fun selectContent(scope: CoroutineScope, index: Int) {
        val cover = contents.getOrNull(index) ?: return fail("数据序号无效: $index")
        selectedCover = cover
        selectedPlayLine = null
        playLines = emptyList()
        callback?.emit(contextEvent())
        getDetailed(scope, cover)
    }

    private fun getDetailed(scope: CoroutineScope, cartoonCover: CartoonCover) {
        log(debugSource, "获取详情[${cartoonCover.title}]")
        val version = nextRequest("正在获取详情")
        val bundle = debugBundle ?: return fail("调试器尚未加载插件")
        val source = debugSource ?: return fail("调试数据源无效")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<DetailedComponent>()
                ?.getAll(CartoonSummary(cartoonCover.id, source, cartoonCover.title))
        }.onSuccess { results ->
            if (!isCurrent(version)) return@onSuccess
            if (results == null) {
                fail("详情接口不可用")
                return@onSuccess
            }
            results.complete { result ->
                if (!isCurrent(version)) return@complete
                val cartoon = result.data.first
                playLines = result.data.second
                callback?.emit(
                    Event(
                        type = TYPE_RESULT,
                        stage = STAGE_DETAIL,
                        title = cartoon.title,
                        fields = linkedMapOf(
                            "ID" to cartoon.id,
                            "地址" to cartoon.url,
                            "封面" to cartoon.coverUrl.orEmpty(),
                            "简介" to cartoon.intro.orEmpty(),
                            "描述" to cartoon.description.orEmpty(),
                            "标签" to cartoon.genre.orEmpty(),
                            "播放线路" to playLines.size.toString()
                        )
                    )
                )
                if (playLines.isEmpty()) {
                    fail("详情获取成功，但没有播放线路")
                    return@complete
                }
                log(debugSource, "获取详情完成，共 ${playLines.size} 条播放线路")
                callback?.emit(
                    Event(
                        type = TYPE_SELECTION,
                        stage = STAGE_PLAY_LINE,
                        title = "选择播放线路",
                        options = playLines.mapIndexed { index, line ->
                            Option(index, line.label, "${line.episode.size} 集")
                        }
                    )
                )
            }.error {
                if (isCurrent(version)) fail(it.throwable.stackTraceStr)
            }
        }.onError {
            if (isCurrent(version)) fail(it.stackTraceStr)
        }
        tasks.add(task)
    }

    private fun selectPlayLine(index: Int) {
        val line = playLines.getOrNull(index) ?: return fail("播放线路序号无效: $index")
        selectedPlayLine = line
        callback?.emit(contextEvent())
        callback?.emit(
            Event(
                type = TYPE_SELECTION,
                stage = STAGE_EPISODE,
                title = "选择剧集",
                options = line.episode.mapIndexed { episodeIndex, episode ->
                    Option(episodeIndex, episode.label, "顺序 ${episode.order}")
                }
            )
        )
    }

    private fun selectEpisode(scope: CoroutineScope, index: Int) {
        val cover = selectedCover ?: return fail("请先选择数据")
        val line = selectedPlayLine ?: return fail("请先选择播放线路")
        val episode = line.episode.getOrNull(index) ?: return fail("剧集序号无效: $index")
        callback?.emit(contextEvent(episode.label))
        getPlayInfo(scope, cover, line, episode)
    }

    private fun getPlayInfo(
        scope: CoroutineScope,
        cartoonCover: CartoonCover,
        playLine: PlayLine,
        episode: Episode
    ) {
        log(debugSource, "获取视频地址[${playLine.label} / ${episode.label}]")
        val version = nextRequest("正在获取播放信息")
        val bundle = debugBundle ?: return fail("调试器尚未加载插件")
        val source = debugSource ?: return fail("调试数据源无效")
        val task = Coroutine.async(scope, Dispatchers.IO) {
            bundle.getComponentProxy<PlayComponent>()?.getPlayInfo(
                CartoonSummary(cartoonCover.id, source, cartoonCover.title),
                playLine,
                episode
            )
        }.onSuccess { results ->
            if (!isCurrent(version)) return@onSuccess
            if (results == null) {
                fail("播放接口不可用")
                return@onSuccess
            }
            results.complete { result ->
                if (!isCurrent(version)) return@complete
                val info = result.data
                log(debugSource, "获取视频地址完成: ${info.uri}", state = 1000)
                callback?.emit(
                    Event(
                        type = TYPE_RESULT,
                        stage = STAGE_PLAY_INFO,
                        title = "${playLine.label} / ${episode.label}",
                        fields = linkedMapOf(
                            "播放地址" to info.uri,
                            "解码类型" to decodeType(info.decodeType),
                            "请求头" to info.header.orEmpty().entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        )
                    )
                )
                callback?.emit(Event(type = TYPE_READY, title = "调试完成，可继续选择其他数据"))
            }.error {
                if (isCurrent(version)) fail(it.throwable.stackTraceStr)
            }
        }.onError {
            if (isCurrent(version)) fail(it.stackTraceStr)
        }
        tasks.add(task)
    }

    private fun contextEvent(episode: String? = null): Event {
        return Event(
            type = TYPE_CONTEXT,
            fields = linkedMapOf(
                "主分类" to selectedMainTab?.label.orEmpty(),
                "次分类" to selectedSubTab?.label.orEmpty(),
                "数据" to selectedCover?.title.orEmpty(),
                "播放线路" to selectedPlayLine?.label.orEmpty(),
                "剧集" to episode.orEmpty(),
                "页参数" to currentPageKey.toString()
            ).filterValues { it.isNotEmpty() }
        )
    }

    private fun fail(message: String) {
        log(debugSource, message, state = -1)
        callback?.emit(Event(type = TYPE_ERROR, title = "调试失败", message = message))
    }

    private fun mainTabType(type: Int): String = when (type) {
        MainTab.MAIN_TAB_GROUP -> "分组分类"
        MainTab.MAIN_TAB_WITH_COVER -> "封面列表"
        MainTab.MAIN_TAB_WITHOUT_COVER -> "文字列表"
        else -> "未知类型 $type"
    }

    private fun decodeType(type: Int): String = when (type) {
        0 -> "DASH"
        2 -> "HLS"
        4 -> "自动识别"
        else -> "其他 ($type)"
    }

    data class Option(
        val index: Int,
        val label: String,
        val detail: String = "",
        val image: String? = null,
    )

    data class Event(
        val type: String,
        val stage: String? = null,
        val title: String = "",
        val message: String = "",
        val options: List<Option> = emptyList(),
        val fields: Map<String, String> = emptyMap(),
        val pageKey: Int? = null,
        val nextPageKey: Int? = null,
    )

    interface Callback {
        fun printLog(state: Int, msg: String)
        fun emit(event: Event)
    }

    private const val TYPE_BUSY = "busy"
    private const val TYPE_READY = "ready"
    private const val TYPE_ERROR = "error"
    private const val TYPE_SELECTION = "selection"
    private const val TYPE_RESULT = "result"
    private const val TYPE_CONTEXT = "context"

    private const val STAGE_MAIN = "main"
    private const val STAGE_SUB = "sub"
    private const val STAGE_CONTENT = "content"
    private const val STAGE_DETAIL = "detail"
    private const val STAGE_PLAY_LINE = "playLine"
    private const val STAGE_EPISODE = "episode"
    private const val STAGE_PLAY_INFO = "playInfo"
}
