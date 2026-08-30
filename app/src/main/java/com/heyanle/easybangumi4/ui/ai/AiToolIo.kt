package com.heyanle.easybangumi4.ui.ai

import com.heyanle.easybangumi4.plugin.api.component.preference.SourcePreference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Reader

internal data class AiTextSlice(
    val text: String,
    val startChar: Int,
    val truncated: Boolean,
) {
    val nextStartChar: Int?
        get() = if (truncated) startChar + text.length else null
}

internal fun readTextSlice(reader: Reader, startChar: Int, maxChars: Int): AiTextSlice {
    require(startChar >= 0) { "startChar 不能小于 0" }
    require(maxChars > 0) { "maxChars 必须大于 0" }

    var remaining = startChar.toLong()
    while (remaining > 0) {
        val skipped = reader.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (reader.read() == -1) {
            return AiTextSlice("", startChar, false)
        } else {
            remaining--
        }
    }

    val buffer = CharArray(minOf(8_192, maxChars + 1))
    val result = StringBuilder(minOf(maxChars, 32_768))
    while (result.length <= maxChars) {
        val allowed = minOf(buffer.size, maxChars + 1 - result.length)
        val count = reader.read(buffer, 0, allowed)
        if (count == -1) break
        result.append(buffer, 0, count)
    }
    val truncated = result.length > maxChars
    if (truncated) result.setLength(maxChars)
    return AiTextSlice(result.toString(), startChar, truncated)
}

internal fun sanitizeDebugFields(fields: Map<String, String>): Map<String, String> =
    fields.mapValues { (key, value) ->
        if (key != "请求头") return@mapValues value
        value.lineSequence().joinToString("\n") { line ->
            val headerName = line.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            if (headerName in SENSITIVE_DEBUG_HEADERS) "$headerName: [已脱敏]" else line
        }
    }

private val SENSITIVE_DEBUG_HEADERS = setOf(
    "authorization",
    "cookie",
    "proxy-authorization",
    "set-cookie",
    "x-api-key",
)

internal fun baseUrlPreferenceError(preferences: List<SourcePreference>): String? {
    val baseUrl = preferences.firstOrNull { it.key == "BaseUrl" }
        ?: return "必须提供 key 为 BaseUrl 的站点地址配置"
    if (baseUrl !is SourcePreference.Edit) return "BaseUrl 必须使用 SourcePreference.Edit"
    val defaultUrl = baseUrl.def.trim()
    val parsed = defaultUrl.toHttpUrlOrNull()
    if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
        return "BaseUrl 默认值必须是纯 HTTP/HTTPS URL，不能使用 Markdown 链接"
    }
    return null
}
