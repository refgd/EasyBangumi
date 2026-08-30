package com.heyanle.easybangumi4.ui.ai

internal enum class AiSourceDocTopic(val wireName: String, val label: String) {
    OVERVIEW("overview", "主题索引与最小运行约束"),
    RUNTIME("runtime", "元数据、Rhino 与注入对象"),
    CATALOG("catalog", "首页、分类、列表与分页"),
    SEARCH("search", "搜索"),
    DETAIL("detail", "详情、线路与剧集"),
    PLAYBACK("playback", "播放、HLS 与防串集"),
    DANMAKU("danmaku", "弹幕与偏好"),
    DEBUG("debug", "校验、调试与验证边界"),
    ALL("all", "完整契约"),
    ;

    companion object {
        fun fromWireName(value: String): AiSourceDocTopic =
            entries.firstOrNull { it.wireName == value.lowercase() } ?: OVERVIEW
    }
}

internal fun sourceComponentDocs(topicName: String): String {
    val topic = AiSourceDocTopic.fromWireName(topicName)
    if (topic == AiSourceDocTopic.ALL) {
        return AiSourceDocTopic.entries
            .filterNot { it == AiSourceDocTopic.ALL }
            .joinToString("\n\n") { sourceComponentDocs(it.wireName) }
    }
    return SOURCE_DOCS.getValue(topic)
}

private val SOURCE_DOCS = mapOf(
    AiSourceDocTopic.OVERVIEW to """
        [overview] $AI_PRODUCT_NAME 组件式 JavaScript 番源契约（libVersion 11）主题索引

        按当前问题继续读取最小必要主题：
        ${AiSourceDocTopic.entries.filterNot { it == AiSourceDocTopic.OVERVIEW }.joinToString("\n") { "- ${it.wireName}: ${it.label}" }}

        最小约束：运行时是 Android Rhino 同步 ES5；Hook 使用标准 function 声明。修改前读取源码，修改后写回、校验并调试真实链路。不得猜测接口、伪造验证结果或复用上一次作品、线路、剧集的状态。
    """.trimIndent(),
    AiSourceDocTopic.RUNTIME to """
        [runtime] 元数据、Rhino 与注入对象
        - 顶部必须声明：@key、@label、@versionName、整数 @versionCode、整数 @libVersion；@cover 可为网络地址、URI 或 Base64。
        - Hook 必须使用 `function Name(...)` 标准声明。使用 var、普通函数和传统循环，不使用 Promise、async/await、ES Module、CommonJS、npm、DOM 或浏览器 fetch。
        - 可使用已注入的 Java 包、Jsoup、OkHttp，以及 Inject_NetworkHelper、Inject_OkhttpHelper、Inject_PreferenceHelper、Inject_StringHelper、Inject_WebViewHelperV2 等对象。
        - 新建源以及本次被更新、修复的源必须实现 PreferenceComponent_getPreference()，提供 `new SourcePreference.Edit("站点地址", "BaseUrl", "https://example.com")`。通过 Inject_PreferenceHelper.get("BaseUrl", defaultValue) 统一读取并清理末尾 `/`；站点请求、相对 URL、Referer 和 WebView 入口不得绕过配置。默认值必须是纯 URL，不是 Markdown 链接。
        - Rhino 拼接字符串写入 Java 泛型容器前可用 new Packages.java.lang.String(value) 转换，避免 ConsString 类型错误。
    """.trimIndent(),
    AiSourceDocTopic.CATALOG to """
        [catalog] 首页、分类、列表与分页
        - Page 三个 Hook 必须同时存在：PageComponent_getMainTabs()、PageComponent_getSubTabs(mainTab)、PageComponent_getContent(mainTab, subTab, key)。
        - getContent 返回 Pair<Integer|null, ArrayList<CartoonCover>>；列表使用 makeCartoonCover，id 必须稳定，不能使用列表序号。
        - 分页初始 key 为 0；有下一页返回可解包整数，末页返回 null；无结果返回类型正确的空 ArrayList。
        - 相对 URL 补全为绝对地址，HTML 字段判空后再解析。经验证且低频变化的分类与筛选可写入插件；动态元数据一次获取并复用。
    """.trimIndent(),
    AiSourceDocTopic.SEARCH to """
        [search] 搜索
        - SearchComponent_search(page, keyword) -> Pair<Integer|null, ArrayList<CartoonCover>>。
        - page、keyword 必须来自当前调用；正确编码关键词并保持分页键语义。无结果返回空 ArrayList，失败抛带上下文的异常。
        - JSON API 必须与官网相同关键词的当前结果抽样对照；口径不一致时使用官网真实接口或 HTML。
    """.trimIndent(),
    AiSourceDocTopic.DETAIL to """
        [detail] 详情、线路与剧集
        - DetailedComponent_getDetailed(summary) -> Pair<Cartoon, ArrayList<PlayLine>>；详情使用 makeCartoon。
        - 列表、详情、收藏更新中的作品 id 必须稳定且可互相转换。
        - Episode.id 优先保存真实剧集 URL 或稳定标识；PlayLine.id 保存稳定线路标识。不得按线路或剧集序号拼播放 URL，否则顺序变化会串线串集。
        - 结构异常时抛带作品、线路上下文的 ParserException，不返回伪造占位数据。
    """.trimIndent(),
    AiSourceDocTopic.PLAYBACK to """
        [playback] 播放、HLS 与防串集
        - PlayComponent_getPlayInfo(summary, playLine, episode) -> PlayerInfo。DASH=0、HLS=2、OTHER=4；需要 Referer、UA 或 Cookie 时写入 player.header。
        - 判断 HLS 时检查 URL 路径是否包含 .m3u8，不能只用 endsWith；保留必要查询参数。
        - player.hlsOptions 默认：segmentPayload="auto"、filterMinorityHosts=true、minorityHostThreshold=0.15、maxAdDurationSeconds=180。
        - segmentPayload="auto" 可识别普通分片和 PNG 尾部附加的 MPEG-TS；明确必须保留原始字节时才设为 "raw"。
        - 只有稳定广告 URL 特征才设置 blockedSegmentRegex。不要按节目名、分片序号或 #EXT-X-DISCONTINUITY 猜广告；正片混用多个内容 CDN 时设 filterMinorityHosts=false。
        - 完全基于本次 summary、playLine、episode 解析，不缓存或复用上次地址或 WebView 捕获结果。连续测试两个不同作品或剧集，确认 URL、时长和内容分别对应。
    """.trimIndent(),
    AiSourceDocTopic.DANMAKU to """
        [danmaku] 弹幕与偏好
        - PlayComponent_getDanmakuInfo(summary, playLine, episode) -> ArrayList<DanmakuData>。
        - 文本弹幕使用 makeTextDanmaku({ text, showAtTime, textColor })；showAtTime 单位为毫秒，颜色为 Android 整数；合法无弹幕返回空 ArrayList。
        - PreferenceComponent_getPreference() -> ArrayList<SourcePreference>，必须同步返回。SourcePreference 支持 Edit、Switch、Selection，key 必须唯一。
        - 通过 Inject_PreferenceHelper.get(key, defaultValue) 读取；Switch 值是字符串 "true"/"false"。
    """.trimIndent(),
    AiSourceDocTopic.DEBUG to """
        [debug] 校验、调试与验证边界
        - 可临时使用 Log(...) 和 DebugCapture(label, data) 查看真实 HTML/JSON；不得捕获 Cookie、Authorization 或令牌，解决后移除 DebugCapture。
        - source_validate 只证明元数据、语法和运行时装配可加载；source_debug 才验证真实组件链路，选择项必须来自工具返回。
        - source_debug 的 stopAfter 可为 catalog、search、detail、playback、danmaku；按当前任务尽早停止，避免无关下游组件影响验证。search 阶段必须传 searchKeyword；分页复测将上次返回的 nextPageKey 作为 pageKey。
        - danmaku 阶段会在当前作品、线路和剧集上真实调用 DanmakuComponent，并返回弹幕条数。
        - media_probe 只验证媒体地址、清单、时长和分片可读，不能替代播放器实际播放结论。
        - 每项结论标明“已验证”或“未验证”；失败结果不能描述为完成。
    """.trimIndent(),
)
