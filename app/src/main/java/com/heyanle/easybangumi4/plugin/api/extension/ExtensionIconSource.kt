package com.heyanle.easybangumi4.plugin.api.extension

import android.graphics.drawable.Drawable
import com.heyanle.easybangumi4.plugin.api.IconSource

/**
 * Created by HeYanLe on 2023/2/22 20:14.
 * https://github.com/heyanLE
 */
interface ExtensionIconSource: IconSource {

    fun getIconResourcesId(): Int?
    override fun getIconFactory(): () -> Drawable? {
        return {
            iconFactory.getIcon(this)
        }
    }

}