package com.heyanle.easybangumi4.plugin.extension.loader

import android.content.Context

/**
 * Created by heyanlin on 2023/10/25.
 */
abstract class AbsExtensionLoader(
    protected val context: Context
): ExtensionLoader {

    companion object {
        const val TAG = "AbsExtensionLoader"

        // 当前容器支持的 扩展库 版本区间
        const val LIB_VERSION_MIN = 6
        const val LIB_VERSION_MAX = 11
    }

}