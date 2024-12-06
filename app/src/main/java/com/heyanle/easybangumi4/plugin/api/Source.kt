package com.heyanle.easybangumi4.plugin.api

import kotlin.reflect.KClass


/**
 * Created by Refgd on 2024/12/03 18:51.
 * https://github.com/refgd
 */
interface Source {
    /**
     * Must be unique
     */
    val key: String

    val label: String

    val version: String

    val versionCode: Int

    val hasPref: Int

    val hasSearch: Int

    val describe: String?

    val sourcePath: String

    fun register(): List<KClass<*>>


}