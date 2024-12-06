package com.heyanle.easybangumi4

import com.heyanle.easybangumi4.utils.stringRes

/**
 * Created by HeYanLe on 2023/4/4 21:11.
 * https://github.com/heyanLE
 */
object C {
    sealed class About {

        data class Copy(
            val icon: Any?,
            val title: String,
            val msg: String,
            val copyValue: String,
        ): About()

        data class Url(
            val icon: Any?,
            val title: String,
            val msg: String,
            val url: String,
        ): About()
    }

    val aboutList: List<About> by lazy {
        listOf<About>(
            About.Url(
                icon = R.drawable.github,
                title = stringRes(com.heyanle.easy_i18n.R.string.github),
                msg = stringRes(com.heyanle.easy_i18n.R.string.click_to_explore),
                url = "https://github.com/refgd/easybangumi"
            ),
//            About.Copy(
//                icon = R.drawable.qq,
//                title = stringRes(com.heyanle.easy_i18n.R.string.qq_groud),
//                msg = "729848189",
//                copyValue = "729848189"
//            ),
        )
    }

}