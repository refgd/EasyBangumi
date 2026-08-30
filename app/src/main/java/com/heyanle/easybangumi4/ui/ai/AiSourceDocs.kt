package com.heyanle.easybangumi4.ui.ai

internal enum class AiSourceDocTopic(val wireName: String, val label: String) {
    OVERVIEW("overview", "主题索引与最小运行约束"),
    RUNTIME("runtime", "元数据、Rhino 与注入对象"),
    CATALOG("catalog", "首页、分类、列表与分页"),
    SEARCH("search", "搜索"),
    DETAIL("detail", "详情、线路与剧集"),
    PLAYBACK("playback", "播放、HLS 与防串集"),
    DANMAKU("danmaku", "弹幕与偏好"),
    PATTERNS("patterns", "项目源归纳的实现模式"),
    UTILITIES("utilities", "原生摘要、加解密、编码与精确工具"),
    DEBUG("debug", "校验、调试与验证边界"),
    ;

    companion object {
        fun fromWireName(value: String): AiSourceDocTopic =
            entries.firstOrNull { it.wireName == value.lowercase() } ?: OVERVIEW
    }
}

internal fun sourceComponentDocs(topicName: String): String {
    val topic = AiSourceDocTopic.fromWireName(topicName)
    return SOURCE_DOCS.getValue(topic)
}

private val SOURCE_DOCS = mapOf(
    AiSourceDocTopic.OVERVIEW to """
        [overview] $AI_PRODUCT_NAME 组件式 JavaScript 番源契约（libVersion 11）主题索引

        按当前问题继续读取最小必要主题：
        ${AiSourceDocTopic.entries.filterNot { it == AiSourceDocTopic.OVERVIEW }.joinToString("\n") { "- ${it.wireName}: ${it.label}" }}

        最小约束：运行时是 Android Rhino 同步 ES5；Hook 使用标准 function 声明。修改前读取源码，修改后写回、校验并调试真实链路。若网站存在可用 API，所有数据链路优先使用 API；只有 API 缺字段、口径不一致、受动态脚本保护或不稳定时才退回 HTML/WebView。不得猜测接口、伪造验证结果或复用上一次作品、线路、剧集的状态。
    """.trimIndent(),
    AiSourceDocTopic.RUNTIME to """
        [runtime] 元数据、Rhino 与注入对象
        - 顶部必须声明：@key、@label、@versionName、整数 @versionCode、整数 @libVersion；@cover 可为网络地址、URI 或 Base64。
        - Hook 必须使用 `function Name(...)` 标准声明。使用 var、普通函数和传统循环，不使用 Promise、async/await、ES Module、CommonJS、npm、DOM 或浏览器 fetch。
        - 可使用已注入的 Java 包、Jsoup、OkHttp，以及 Inject_NetworkHelper、Inject_OkhttpHelper、Inject_PreferenceHelper、Inject_StringHelper、Inject_WebViewHelperV2 等对象。
        - 实体和返回值统一使用运行时 helper：makeCartoonCover(map)、makeCartoon(map)、makeEpisode(map)、makePlayLine(map)、makePageResult(nextKey, items)、makeDetailedResult(cartoon, playLines)、makePlayerInfo(map)。helper 会统一 Rhino 与 Java 的类型边界；不要直接构造这些实体。
        - 新建源以及本次被更新、修复的源必须实现 PreferenceComponent_getPreference()，提供 `new SourcePreference.Edit("站点地址", "BaseUrl", "https://example.com")`。通过 Inject_PreferenceHelper.get("BaseUrl", defaultValue) 统一读取并清理末尾 `/`；站点请求、相对 URL、Referer 和 WebView 入口不得绕过配置。默认值必须是纯 URL，不是 Markdown 链接。
        - Rhino 拼接字符串写入 Java 泛型容器前可用 new Packages.java.lang.String(value) 转换，避免 ConsString 类型错误。
    """.trimIndent(),
    AiSourceDocTopic.CATALOG to """
        [catalog] 首页、分类、列表与分页
        - Page 三个 Hook 必须同时存在：PageComponent_getMainTabs()、PageComponent_getSubTabs(mainTab)、PageComponent_getContent(mainTab, subTab, key)。
        - getContent 返回 Pair<Integer|null, ArrayList<CartoonCover>>；列表使用 makeCartoonCover，返回值使用 makePageResult(nextKey, items)，id 必须稳定，不能使用列表序号。
        - 分页初始 key 为 0；有下一页把普通数值交给 makePageResult 装箱，末页传 null；无结果传类型正确的空 ArrayList。不要把 Rhino Number 直接作为 Pair 的 nextKey。
        - 网站提供栏目、筛选、列表或分页 API 时优先使用 API 获取数据；API 不完整或与官网页面不一致时才解析 HTML。
        - 相对 URL 补全为绝对地址，HTML 字段判空后再解析。经验证且低频变化的分类与筛选可写入插件；动态元数据一次获取并复用。
    """.trimIndent(),
    AiSourceDocTopic.SEARCH to """
        [search] 搜索
        - SearchComponent_search(page, keyword) -> Pair<Integer|null, ArrayList<CartoonCover>>，使用 makePageResult(nextKey, items) 返回。
        - page、keyword 必须来自当前调用；正确编码关键词并保持分页键语义。无结果返回空 ArrayList，失败抛带上下文的异常。
        - 网站提供搜索 API 时优先使用 API；API 必须与官网相同关键词的当前结果抽样对照，口径不一致时使用官网真实接口或 HTML。
    """.trimIndent(),
    AiSourceDocTopic.DETAIL to """
        [detail] 详情、线路与剧集
        - DetailedComponent_getDetailed(summary) -> Pair<Cartoon, ArrayList<PlayLine>>；详情使用 makeCartoon，剧集使用 makeEpisode({ id, label, order })，线路使用 makePlayLine({ id, label, episodes })，最终使用 makeDetailedResult(cartoon, playLines)。
        - makeEpisode 的 order 缺省为 0，但应传真实排序值。不要直接调用 Episode、PlayLine 或 Pair 构造器。
        - 列表、详情、收藏更新中的作品 id 必须稳定且可互相转换。
        - 网站提供详情、线路或剧集 API 时优先使用 API 获取全部详情数据；API 缺字段或与官网顺序、可见性不一致时才解析 HTML。
        - Episode.id 优先保存真实剧集 URL 或稳定标识；PlayLine.id 保存稳定线路标识。不得按线路或剧集序号拼播放 URL，否则顺序变化会串线串集。
        - 结构异常时抛带作品、线路上下文的 ParserException，不返回伪造占位数据。
    """.trimIndent(),
    AiSourceDocTopic.PLAYBACK to """
        [playback] 播放、HLS 与防串集
        - PlayComponent_getPlayInfo(summary, playLine, episode) 使用 `makePlayerInfo({ decodeType, uri, headers, hlsOptions })` 返回 PlayerInfo。DASH=0、HLS=2、OTHER=4；不要直接调用 PlayerInfo 构造器。
        - 网站提供播放解析 API 时优先使用 API 获取最终媒体地址、请求头和 HLS 清单入口；只有解析必须执行页面脚本、API 不稳定或返回与当前剧集不匹配时才使用 WebView 拦截。
        - 判断 HLS 时检查 URL 路径是否包含 .m3u8，不能只用 endsWith；保留必要查询参数。
        - player.hlsOptions 默认：segmentPayload="auto"、filterMinorityHosts=true、minorityHostThreshold=0.15、maxAdDurationSeconds=180。
        - segmentPayload="auto" 可识别普通分片和 PNG 尾部附加的 MPEG-TS；明确必须保留原始字节时才设为 "raw"。
        - 只有稳定广告 URL 特征才设置 blockedSegmentRegex。不要按节目名、分片序号或 #EXT-X-DISCONTINUITY 猜广告；正片混用多个内容 CDN 时设 filterMinorityHosts=false。
        - 完全基于本次 summary、playLine、episode 解析，不缓存或复用上次地址或 WebView 捕获结果。连续测试两个不同作品或剧集，确认 URL、时长和内容分别对应。
    """.trimIndent(),
    AiSourceDocTopic.DANMAKU to """
        [danmaku] 弹幕与偏好
        - PlayComponent_getDanmakuInfo(summary, playLine, episode) -> ArrayList<DanmakuData>。
        - 网站提供弹幕 API 时优先使用 API 获取弹幕数据；只有 API 不存在或映射不完整时才使用其他可验证入口。
        - 文本弹幕使用 makeTextDanmaku({ text, showAtTime, textColor })；showAtTime 单位为毫秒，颜色为 Android 整数；合法无弹幕返回空 ArrayList。
        - PreferenceComponent_getPreference() -> ArrayList<SourcePreference>，必须同步返回。SourcePreference 支持 Edit、Switch、Selection，key 必须唯一。
        - 通过 Inject_PreferenceHelper.get(key, defaultValue) 读取；Switch 值是字符串 "true"/"false"。
    """.trimIndent(),
    AiSourceDocTopic.PATTERNS to """
        [patterns] 当前项目源归纳的实现模式（仅在实现方式不明确时按需读取）
        - 不读取或模仿其他已安装插件。本参考只保留跨站稳定模式；具体接口、字段、选择器和参数必须用目标站真实响应确认。
        - 结构统一采用少量 ES5 Hook 加一个工具对象：Hook 只做参数转换和实体组装；请求、URL 补全、JSON/HTML 解析与播放解析放入工具对象，避免跨组件复制逻辑。
        - 数据入口按 API -> HTML -> WebView 分层。API 与官网口径一致时用于列表、搜索、详情和播放；HTML 只补 API 缺失字段；WebView 仅用于确实必须执行脚本或拦截最终媒体请求的末段。
        - JSON 响应和页面内嵌 JSON 必须交给 JSON.parse。不得用正则提取 JSON 字段、字符串、数组或嵌套对象；正则最多用于定位内联脚本的标记或完整 JSON 块边界，随后仍解析完整 JSON 文本。
        - 通用 JSON 请求可采用下面的 ES5 骨架，并按目标站补充已验证的 UA、Referer 或其他请求头：

        ```javascript
        var DEFAULT_BASE_URL = "https://example.com";

        function getBaseUrl() {
            var value = new String(Inject_PreferenceHelper.get("BaseUrl", DEFAULT_BASE_URL)).trim();
            return value.replace(/\/+${'$'}/, "");
        }

        function toAbsoluteUrl(path) {
            var value = new String(path || "").trim();
            if (value.indexOf("http://") === 0 || value.indexOf("https://") === 0) return value;
            if (value.indexOf("//") === 0) return "https:" + value;
            return getBaseUrl() + (value.indexOf("/") === 0 ? value : "/" + value);
        }

        function parseJson(text, context) {
            var value = new String(text || "").trim();
            if (value.length > 0 && value.charCodeAt(0) === 0xFEFF) value = value.substring(1);
            try {
                return JSON.parse(value);
            } catch (error) {
                throw new ParserException((context || "JSON") + " 解析失败: " + error);
            }
        }

        function getJson(path) {
            var response = Jsoup.connect(toAbsoluteUrl(path))
                .ignoreContentType(true)
                .timeout(15000)
                .execute();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ParserException("请求失败: HTTP " + response.statusCode() + " " + path);
            }
            return parseJson(response.body(), "接口 " + path);
        }
        ```
        - 主/副分类等低频小数据使用经验证的静态表，并用 MainTab/SubTab.ext 传稳定站点 ID。动态分类只取一次并缓存；翻页不重复加载元数据。
        - 列表和搜索共用 CartoonCover 映射，返回稳定作品 ID、绝对详情 URL 和绝对封面 URL。分页初始值为 0，通过 makePageResult(nextKey, items) 返回，末页 nextKey 传 null。
        - 详情使用当前 summary 定位作品；通过 makeEpisode({ id, label, order })、makePlayLine({ id, label, episodes }) 和 makeDetailedResult(cartoon, playLines) 组装。PlayLine.id 保存真实线路标识，Episode.id 保存完整播放页 URL 或包含当前作品、线路、剧集标识的序列化上下文。不要用数组下标重建播放地址。
        - 播放严格从本次 summary/playLine/episode 解析。直链或解析 API 使用 makePlayerInfo 返回；必须执行脚本时使用 RenderedStrategy，并限定当前页面和媒体拦截正则。
        - HLS 保留查询参数和必要 Referer/UA；只为已确认的广告特征添加 blockedSegmentRegex。连续用两个不同剧集验证，防止缓存捕获结果导致串集。
        - 弹幕只在站点存在稳定映射时实现，基于当前剧集标识请求；时间统一为毫秒，单条坏数据跳过，合法空结果返回空 ArrayList。
        - 所有新建或被修改的源提供 BaseUrl Edit 配置，并让页面请求、相对 URL、Referer 和 WebView 入口实际读取它；独立 API/CDN 域名无法推导时再单独保留。
    """.trimIndent(),
    AiSourceDocTopic.UTILITIES to """
        [utilities] 原生摘要、加解密、编码与精确工具（仅在目标站确实需要时按需读取）
        - 优先调用 Java/Android 原生实现，不手写 MD5、SHA、HMAC、AES、Base64、URL 编解码、随机数或 JSON 解析算法。HTML 使用 Jsoup 选择器；JSON 使用 JSON.parse；网络使用 Jsoup 或 Inject_OkhttpHelper.client。
        - 必须从目标站脚本或真实请求确认完整参数：算法、AES mode/padding、key/IV 的原始编码、字符集、Base64 标准/URL-safe/是否换行、十六进制大小写和 URL 编码语义。不得靠轮流尝试算法或自动补 key/IV 猜结果。
        - Java 类未在默认 importPackage 中时，通过 Packages 使用完整类名。文本与字节转换显式指定 UTF-8；加密结果、签名和时间戳必须与站点前端逐字节对照。

        ```javascript
        function utf8Bytes(value) {
            return new Packages.java.lang.String(new String(value || "")).getBytes("UTF-8");
        }

        function bytesToHex(bytes) {
            var out = new Packages.java.lang.StringBuilder(bytes.length * 2);
            for (var i = 0; i < bytes.length; i++) {
                var value = bytes[i] & 255;
                if (value < 16) out.append("0");
                out.append(Packages.java.lang.Integer.toHexString(value));
            }
            return new String(out.toString());
        }

        function digestHex(algorithm, text) {
            var digest = Packages.java.security.MessageDigest.getInstance(algorithm);
            return bytesToHex(digest.digest(utf8Bytes(text)));
        }

        function hmacHex(algorithm, keyText, text) {
            var mac = Packages.javax.crypto.Mac.getInstance(algorithm);
            var key = new Packages.javax.crypto.spec.SecretKeySpec(utf8Bytes(keyText), algorithm);
            mac.init(key);
            return bytesToHex(mac.doFinal(utf8Bytes(text)));
        }

        function decodeBase64(text, flags) {
            var Base64 = Packages.android.util.Base64;
            return Base64.decode(new String(text), flags == null ? Base64.DEFAULT : flags);
        }

        function aesDecrypt(cipherBytes, keyBytes, ivBytes, transformation) {
            var Cipher = Packages.javax.crypto.Cipher;
            var cipher = Cipher.getInstance(transformation);
            var key = new Packages.javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            if (ivBytes == null) {
                cipher.init(Cipher.DECRYPT_MODE, key);
            } else {
                var iv = new Packages.javax.crypto.spec.IvParameterSpec(ivBytes);
                cipher.init(Cipher.DECRYPT_MODE, key, iv);
            }
            return cipher.doFinal(cipherBytes);
        }
        ```

        - 示例：MD5 使用 digestHex("MD5", text)，SHA-256 使用 digestHex("SHA-256", text)，HMAC-SHA256 使用 hmacHex("HmacSHA256", key, text)。不要把这些名称替换成未经站点确认的算法。
        - AES/CBC/Base64 场景可将 decodeBase64 的结果、经确认编码的 keyBytes/ivBytes 和精确 transformation（如 "AES/CBC/PKCS5Padding"）传给 aesDecrypt，再用 new Packages.java.lang.String(clearBytes, "UTF-8") 解码明文。ECB 才传 null IV；二进制或 hex/Base64 key 不得当普通文本处理。
        - Base64 标志使用 Packages.android.util.Base64.DEFAULT、NO_WRAP、URL_SAFE 等站点所需组合；URL 查询表单使用 URLEncoder.encode(value, "UTF-8")，解码使用 URLDecoder.decode。若签名要求 RFC 3986 编码，必须按站点规则处理空格、+、~ 和参数排序，不能把表单编码直接视为等价。
        - 时间使用 Packages.java.lang.System.currentTimeMillis()；安全随机值使用 Packages.java.security.SecureRandom，不使用 Math.random() 生成密钥、IV、nonce 或签名盐。URL 补全优先使用 java.net.URI/JSSourceUtils.urlParser 或 patterns 中经过验证的 helper，不用正则拼复杂 URL。
    """.trimIndent(),
    AiSourceDocTopic.DEBUG to """
        [debug] 校验、调试与验证边界
        - source_status 返回当前会话、活动能力、源码长度和推荐验证阶段；新任务或状态不确定时先读取它，再决定是否读取源码、契约或运行调试。
        - 可临时使用 Log(...) 和 DebugCapture(label, data) 查看真实 HTML/JSON；不得捕获 Cookie、Authorization 或令牌，解决后移除 DebugCapture。
        - source_validate 证明元数据、语法、运行时装配及当前活动任务所需组件可加载；新建完整源至少要求 Page、Detailed、Play 主链路。source_debug 才验证真实组件数据链路。选择项必须来自工具返回，优先回传 options 中的稳定 id；没有可靠 id 时才使用同一次结果里的 index。
        - source_debug 的 stopAfter 可为 catalog、search、detail、playback、danmaku；detail 只有取得非空剧集列表后才算通过。按当前任务尽早停止，避免无关下游组件影响验证。search 阶段必须传 searchKeyword；分页复测将上次返回的 nextPageKey 作为 pageKey。
        - danmaku 阶段会在当前作品、线路和剧集上真实调用 DanmakuComponent，并返回弹幕条数。
        - media_probe 只验证媒体地址、清单、时长和分片可读，不能替代播放器实际播放结论。
        - source_replace 后，宿主会按全部活动能力记录 source_debug 通过情况；播放及新源任务还要求 media_probe 的 URL 与最近一次播放调试结果完全一致。门禁未全部通过时不会自动安装，模型提前结束会收到 host_validation_required 并应继续调用工具。
        - 每项结论标明“已验证”或“未验证”；失败结果不能描述为完成。
    """.trimIndent(),
)
