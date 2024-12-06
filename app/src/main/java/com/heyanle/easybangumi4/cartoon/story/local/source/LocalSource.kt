package com.heyanle.easybangumi4.cartoon.story.local.source

import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.R
import com.heyanle.easybangumi4.plugin.api.Source
import com.heyanle.easybangumi4.plugin.api.extension.ExtensionIconSource
import com.heyanle.easybangumi4.utils.stringRes
import kotlin.reflect.KClass

/**
 * Created by heyanle on 2024/7/7.
 * https://github.com/heyanLE
 */
object LocalSource: Source, ExtensionIconSource {

    const val LOCAL_SOURCE_KEY = "easybangumi_local"

    override fun getIconResourcesId(): Int? = null
    override fun getIconFactory(): () -> Drawable? {
        return {
            ResourcesCompat.getDrawable(APP.resources, R.mipmap.logo_n, null)
        }
    }

    override val describe: String?
        get() = stringRes(com.heyanle.easy_i18n.R.string.local_source_desc)
    override val key: String
        get() = LOCAL_SOURCE_KEY
    override val label: String
        get() = stringRes(com.heyanle.easy_i18n.R.string.local_source)
    override val version: String
        get() = "1.0.0"
    override val versionCode: Int
        get() = 0
    override val hasPref: Int
        get() = 0
    override val hasSearch: Int
        get() = 0
    override val sourcePath: String
        get() = ""

    override fun register(): List<KClass<*>> {
        return listOf(
            LocalSourceComponent::class
        )
    }




}