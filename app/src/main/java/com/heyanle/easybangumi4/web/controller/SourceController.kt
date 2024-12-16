package com.heyanle.easybangumi4.web.controller

import com.heyanle.easybangumi4.BuildConfig
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionCryLoader
import com.heyanle.easybangumi4.plugin.js.extension.JSExtensionCryLoader.Companion.FIRST_LINE_MARK
import com.heyanle.easybangumi4.utils.aesEncryptTo
import com.heyanle.easybangumi4.web.utils.ReturnData
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64

object SourceController {

    fun downCode(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("code")){
            val code = postData["code"]?.getOrNull(0)
            if(code != null){
                code.decodeBase64String().aesEncryptTo(BuildConfig.ENC_KEY, JSExtensionCryLoader.CHUNK_SIZE)?.let {
                    val result = FIRST_LINE_MARK + it

                    returnData.setData(result.encodeBase64())
                }
            }
        }

        return returnData
    }

}