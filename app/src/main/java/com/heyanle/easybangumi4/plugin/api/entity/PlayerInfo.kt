package com.heyanle.easybangumi4.plugin.api.entity

import androidx.annotation.Keep

@Keep
class PlayerInfo(
    val decodeType: Int = DECODE_TYPE_OTHER,
    val uri: String = "",

) {

    var header: Map<String, String>? = null
    var hlsOptions: HlsOptions = HlsOptions()

    fun normalizedHeaders(): Map<String, String> {
        val rawHeaders = header as? Map<*, *> ?: return emptyMap()
        return rawHeaders.entries.associate { entry ->
            entry.key.toString() to entry.value.toString()
        }
    }

    companion object {
        // 这里跟 exoplayer 对应的类型需要对应

        const val DECODE_TYPE_DASH = 0
        const val DECODE_TYPE_HLS = 2
        const val DECODE_TYPE_OTHER = 4
    }
}

@Keep
class HlsOptions {
    var blockedSegmentRegex: ArrayList<String> = arrayListOf()
    var segmentPayload: String = SEGMENT_PAYLOAD_AUTO
    var filterMinorityHosts: Boolean = true
    var minorityHostThreshold: Double = 0.15
    var maxAdDurationSeconds: Double = 180.0

    companion object {
        const val SEGMENT_PAYLOAD_AUTO = "auto"
        const val SEGMENT_PAYLOAD_RAW = "raw"
    }
}
