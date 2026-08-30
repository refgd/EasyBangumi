package com.heyanle.easybangumi4.ui.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal val AI_SOURCE_TOOL_NAMES: Set<String> by lazy {
    buildAiToolDefinitions().mapTo(linkedSetOf()) { definition ->
        definition.getAsJsonObject("function").get("name").asString
    }
}

internal fun aiToolDefinitions(availableNames: Set<String>): JsonArray = JsonArray().apply {
    buildAiToolDefinitions().filter { definition ->
        definition.getAsJsonObject("function").get("name").asString in availableNames
    }.forEach(::add)
}

private fun buildAiToolDefinitions() = listOf(
        tool(
            "source_get",
            "读取当前会话绑定的源代码；较长时根据 nextStartChar 继续读取，修改前必须取得全部分段",
            mapOf(
                "startChar" to property("integer", "源码起始字符，默认 0"),
                "maxChars" to property("integer", "最多返回字符数，默认 80000，范围 1000..100000"),
            ),
        ),
        tool(
            "source_docs",
            "按主题读取组件式 JavaScript 契约。先读 overview，再只读取当前任务需要的主题；避免无条件读取 all",
            mapOf("topic" to property(
                type = "string",
                description = "文档主题，默认 overview",
                enumValues = AiSourceDocTopic.entries.map { it.wireName },
            )),
        ),
        tool(
            "source_replace",
            "用完整代码替换当前会话源码并保存；源码可加载时 $AI_PRODUCT_NAME 会自动添加或覆盖安装",
            mapOf("code" to property("string", "替换后的完整 JavaScript 源码，不能传补丁或省略未修改部分")),
            listOf("code"),
        ),
        tool("source_validate", "使用 $AI_PRODUCT_NAME 当前 JS 插件加载器校验源码元数据、语法和运行时兼容性"),
        tool(
            "source_debug",
            "在 $AI_PRODUCT_NAME 内运行真实组件链路。索引来自上一次调试结果的 options；不传时自动选首项，剧集默认末项",
            mapOf(
                "mainIndex" to property("integer", "主分类 options 中的 index"),
                "subIndex" to property("integer", "副分类 options 中的 index"),
                "contentIndex" to property("integer", "列表或搜索结果 options 中的 index"),
                "playLineIndex" to property("integer", "播放来源 options 中的 index"),
                "episodeIndex" to property("integer", "剧集 options 中的 index"),
                "searchKeyword" to property("string", "非空时调试搜索链路，否则调试首页/分类链路"),
                "pageKey" to property("integer", "分类或搜索的分页键，默认 0；使用上次结果的 nextPageKey"),
                "stopAfter" to property(
                    "string",
                    "验证到此阶段即成功返回；默认根据当前任务自动选择",
                    AiDebugStopAfter.entries.map { it.wireName },
                ),
            ),
        ),
        tool("installed_sources", "列出 $AI_PRODUCT_NAME 中已安装 JS 番源的 key、名称和版本"),
        tool(
            "installed_source_read",
            "按源 key 分段读取已安装的明文 JS 源；修改前必须取得全部分段",
            mapOf(
                "key" to property("string", "installed_sources 返回的源 key"),
                "startChar" to property("integer", "源码起始字符，默认 0"),
                "maxChars" to property("integer", "最多返回字符数，默认 80000，范围 1000..100000"),
            ),
            listOf("key"),
        ),
        tool(
            "http_request",
            "直连目标网站或 API。响应较长时根据 nextStartChar 分段读取，避免一次加载无关页面全文",
            mapOf(
                "url" to property("string", "完整 HTTP/HTTPS URL"),
                "method" to property("string", "请求方法，默认 GET", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                "headers" to property("object", "请求头键值对象；请求体类型由 Content-Type 决定"),
                "body" to property("string", "非 GET/HEAD 请求体"),
                "startChar" to property("integer", "响应正文起始字符，默认 0"),
                "maxChars" to property("integer", "最多返回字符数，默认 30000，范围 1000..80000"),
            ),
            listOf("url"),
        ),
        tool(
            "media_probe",
            "直连探测当前剧集最终媒体地址；HLS 会解析子清单、统计总时长并抽测首尾分片",
            mapOf(
                "url" to property("string", "PlayComponent 为当前剧集返回的最终 HTTP/HTTPS 地址"),
                "headers" to property("object", "播放所需请求头键值对象"),
            ),
            listOf("url"),
        ),
    )

private fun property(
    type: String,
    description: String,
    enumValues: List<String> = emptyList(),
) = JsonObject().apply {
    addProperty("type", type)
    addProperty("description", description)
    if (enumValues.isNotEmpty()) add("enum", JsonArray().apply { enumValues.forEach(::add) })
}

private fun tool(
    name: String,
    description: String,
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
) = JsonObject().apply {
    addProperty("type", "function")
    add("function", JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("parameters", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                properties.forEach { (key, schema) -> add(key, schema) }
            })
            add("required", JsonArray().apply { required.forEach(::add) })
            addProperty("additionalProperties", false)
        })
    })
}
