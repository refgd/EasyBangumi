package com.heyanle.easybangumi4.plugin.source

import com.heyanle.easybangumi4.base.json.JsonFileProvider
import com.heyanle.easybangumi4.base.preferences.PreferenceStore

/**
 * Created by HeYanLe on 2023/7/29 21:34.
 * https://github.com/heyanLE
 */
class SourcePreferences(
    private val preferenceStore: PreferenceStore,
    private val jsonFileProvider: JsonFileProvider,
) {

    val configs = jsonFileProvider.sourceConfig

}