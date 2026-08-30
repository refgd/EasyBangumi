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

internal fun validateAiToolArguments(name: String, arguments: JsonObject) {
    val function = buildAiToolDefinitions()
        .firstOrNull { it.getAsJsonObject("function").get("name").asString == name }
        ?.getAsJsonObject("function")
        ?: error("未知工具: $name")
    val schema = function.getAsJsonObject("parameters")
    val properties = schema.getAsJsonObject("properties")
    val unexpected = arguments.keySet() - properties.keySet()
    require(unexpected.isEmpty()) { "工具 $name 包含未声明参数: ${unexpected.joinToString()}" }

    schema.getAsJsonArray("required").orEmpty().forEach { required ->
        require(arguments.has(required.asString) && !arguments.get(required.asString).isJsonNull) {
            "工具 $name 缺少必填参数: ${required.asString}"
        }
    }
    schema.getAsJsonArray("anyOf")?.let { alternatives ->
        val matched = alternatives.any { alternative ->
            alternative.asJsonObject.getAsJsonArray("required").all { required ->
                arguments.has(required.asString) && !arguments.get(required.asString).isJsonNull
            }
        }
        require(matched) { "工具 $name 的参数不满足任一必需组合" }
    }

    arguments.entrySet().forEach { (argumentName, value) ->
        require(!value.isJsonNull) { "工具 $name 参数 $argumentName 不能为 null；可选参数应直接省略" }
        val property = properties.getAsJsonObject(argumentName)
        val type = property.get("type").asString
        val validType = when (type) {
            "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString
            "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                runCatching { value.asDouble.isFinite() && value.asDouble % 1.0 == 0.0 }.getOrDefault(false)
            "object" -> value.isJsonObject
            else -> false
        }
        require(validType) { "工具 $name 参数 $argumentName 必须是 $type" }
        property.getAsJsonArray("enum")?.let { allowed ->
            require(allowed.any { it == value }) {
                "工具 $name 参数 $argumentName 不在允许值中"
            }
        }
        if (type == "integer") {
            property.get("minimum")?.let { minimum ->
                require(value.asLong >= minimum.asLong) { "工具 $name 参数 $argumentName 小于最小值 ${minimum.asLong}" }
            }
            property.get("maximum")?.let { maximum ->
                require(value.asLong <= maximum.asLong) { "工具 $name 参数 $argumentName 大于最大值 ${maximum.asLong}" }
            }
        }
        if (type == "object") {
            property.getAsJsonObject("additionalProperties")?.get("type")?.asString?.let { childType ->
                require(childType == "string" && value.asJsonObject.entrySet().all { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }) {
                    "工具 $name 参数 $argumentName 的所有值必须是字符串"
                }
            }
        }
    }
}

private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> =
    this?.map { it } ?: emptyList()

private fun buildAiToolDefinitions() = listOf(
        tool("source_status", "读取当前 AI 会话状态、源码长度、活动任务、建议调试阶段和下一步工具调用建议"),
        tool(
            "source_get",
            "读取当前会话绑定的源代码；较长时根据 nextStartChar 继续读取，修改前必须取得全部分段",
            mapOf(
                "startChar" to property("integer", "源码起始字符，默认 0", minimum = 0),
                "maxChars" to property("integer", "最多返回字符数，默认 80000", minimum = 1000, maximum = 100000),
            ),
        ),
        tool(
            "source_docs",
            "按主题读取组件式 JavaScript 契约和项目归纳模式。先读 overview，再只读取当前任务需要的主题；实现方式不明确时读取 patterns，加密或编码时读取 utilities",
            mapOf("topic" to property(
                type = "string",
                description = "文档主题，默认 overview",
                enumValues = AiSourceDocTopic.entries.map { it.wireName },
            )),
        ),
        tool(
            "source_replace",
            "用完整代码替换当前会话源码并保存、校验；之后必须按活动能力完成真实调试门禁，最后一次有效修改只在对话成功结束后由 $AI_PRODUCT_NAME 自动添加或覆盖安装",
            mapOf("code" to property("string", "替换后的完整 JavaScript 源码，不能传补丁或省略未修改部分")),
            listOf("code"),
        ),
        tool("source_validate", "使用 $AI_PRODUCT_NAME 当前 JS 插件加载器校验源码元数据、语法、运行时兼容性和当前活动任务所需组件"),
        tool(
            "source_debug",
            "在 $AI_PRODUCT_NAME 内运行真实组件链路。优先使用上次 options 返回的稳定 id 选择；也可使用 index，不传时自动选首项，剧集默认末项",
            mapOf(
                "mainId" to property("string", "主分类 options 中的稳定 id，优先于 mainIndex"),
                "subId" to property("string", "副分类 options 中的稳定 id，优先于 subIndex"),
                "contentId" to property("string", "列表或搜索结果 options 中的稳定作品 id，优先于 contentIndex"),
                "playLineId" to property("string", "播放来源 options 中的稳定线路 id，优先于 playLineIndex"),
                "episodeId" to property("string", "剧集 options 中的稳定剧集 id，优先于 episodeIndex"),
                "mainIndex" to property("integer", "主分类 options 中的 index", minimum = 0),
                "subIndex" to property("integer", "副分类 options 中的 index", minimum = 0),
                "contentIndex" to property("integer", "列表或搜索结果 options 中的 index", minimum = 0),
                "playLineIndex" to property("integer", "播放来源 options 中的 index", minimum = 0),
                "episodeIndex" to property("integer", "剧集 options 中的 index", minimum = 0),
                "searchKeyword" to property("string", "非空时调试搜索链路，否则调试首页/分类链路"),
                "pageKey" to property("integer", "分类或搜索的分页键，默认 0；使用上次结果的 nextPageKey", minimum = 0),
                "stopAfter" to property(
                    "string",
                    "验证到此阶段即成功返回；默认根据当前任务自动选择",
                    AiDebugStopAfter.entries.map { it.wireName },
                ),
            ),
        ),
        tool(
            "http_request",
            "直连目标网站或 API。首次传 url 并获得 responseId；响应较长时用 responseId 和 nextStartChar 读取同一响应快照，不会重复发送网络请求",
            mapOf(
                "url" to property("string", "首次请求的完整 HTTP/HTTPS URL；读取已有快照时可省略"),
                "responseId" to property("string", "上一次响应返回的快照 ID；提供后不会再次请求 url"),
                "method" to property("string", "首次请求的方法，默认 GET", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                "headers" to stringMapProperty("首次请求的请求头键值对象；请求体类型由 Content-Type 决定"),
                "body" to property("string", "首次请求的非 GET/HEAD 请求体"),
                "startChar" to property("integer", "响应正文起始字符，默认 0", minimum = 0),
                "maxChars" to property("integer", "最多返回字符数，默认 30000", minimum = 1000, maximum = 80000),
            ),
            anyOfRequired = listOf(listOf("url"), listOf("responseId")),
        ),
        tool(
            "media_probe",
            "直连探测当前剧集最终媒体地址；HLS 会解析子清单、统计总时长并抽测首尾分片",
            mapOf(
                "url" to property("string", "PlayComponent 为当前剧集返回的最终 HTTP/HTTPS 地址"),
                "headers" to stringMapProperty("播放所需请求头键值对象"),
            ),
            listOf("url"),
        ),
    )

private fun property(
    type: String,
    description: String,
    enumValues: List<String> = emptyList(),
    minimum: Int? = null,
    maximum: Int? = null,
) = JsonObject().apply {
    addProperty("type", type)
    addProperty("description", description)
    if (enumValues.isNotEmpty()) add("enum", JsonArray().apply { enumValues.forEach(::add) })
    minimum?.let { addProperty("minimum", it) }
    maximum?.let { addProperty("maximum", it) }
}

private fun stringMapProperty(description: String) = JsonObject().apply {
    addProperty("type", "object")
    addProperty("description", description)
    add("additionalProperties", JsonObject().apply { addProperty("type", "string") })
}

private fun tool(
    name: String,
    description: String,
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
    anyOfRequired: List<List<String>> = emptyList(),
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
            if (anyOfRequired.isNotEmpty()) {
                add("anyOf", JsonArray().apply {
                    anyOfRequired.forEach { alternative ->
                        add(JsonObject().apply {
                            add("required", JsonArray().apply { alternative.forEach(::add) })
                        })
                    }
                })
            }
            addProperty("additionalProperties", false)
        })
    })
}
