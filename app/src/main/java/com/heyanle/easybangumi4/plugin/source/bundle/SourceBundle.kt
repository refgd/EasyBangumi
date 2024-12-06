package com.heyanle.easybangumi4.plugin.source.bundle

import com.heyanle.easybangumi4.plugin.api.IconSource
import com.heyanle.easybangumi4.plugin.api.Source
import com.heyanle.easybangumi4.plugin.api.component.detailed.DetailedComponent
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.play.PlayComponent
import com.heyanle.easybangumi4.plugin.api.component.preference.PreferenceComponent
import com.heyanle.easybangumi4.plugin.api.component.search.SearchComponent
import com.heyanle.easybangumi4.plugin.source.ConfigSource
import com.heyanle.easybangumi4.plugin.source.SourceInfo


/**
 * Created by HeYanLe on 2023/2/22 20:41.
 * https://github.com/heyanLE
 */
class SourceBundle(
    list: List<ConfigSource>
) {

    companion object {
        val NONE = SourceBundle(emptyList())
    }

    private val sourceMap = linkedMapOf<String, SourceInfo.Loaded>()

    private val iconMap = linkedMapOf<String, IconSource>()


    //private val migrateMap = linkedMapOf<String, MiSou>()

    init {
        list.filter {
            it.config.enable
        }.sortedBy {
            it.config.order
        }.map {
            it.sourceInfo
        }.filterIsInstance<SourceInfo.Loaded>().forEach {
            register(it)
        }
    }

    private fun register(sourceInfo: SourceInfo.Loaded) {
        val source = sourceInfo.source
        if (!sourceMap.containsKey(source.key)
            || sourceMap[source.key]!!.source.versionCode < source.versionCode
        ) {
            sourceMap[source.key] = sourceInfo
            iconMap.remove(source.key)

            if (source is IconSource) {
                iconMap[source.key] = source
            }
        }
    }


    fun sourceInfo(key: String) : SourceInfo.Loaded? {
        return sourceMap[key]
    }

    fun sourceInfos(): List<SourceInfo.Loaded> {
        return sourceMap.toList().map {
            it.second
        }
    }

    fun searchAbles(): List<SourceInfo.Loaded> {
        return sourceMap.filter { it.value.source.hasSearch == 1 }
            .map { it.value }
    }

    fun sources(): List<Source> {
        val res = ArrayList<Source>()
        res.addAll(sourceMap.values.map { it.source })
        return res
    }

    fun source(key: String): Source? {
        return sourceMap[key]?.source
    }

    suspend fun page(key: String): PageComponent? {
        return sourceMap[key]?.componentBundle?.getComponentProxy<PageComponent>()
    }

    suspend fun search(key: String): SearchComponent? {
        return sourceMap[key]?.componentBundle?.getComponentProxy<SearchComponent>()
    }

    suspend fun preference(key: String): PreferenceComponent? {
        return sourceMap[key]?.componentBundle?.getComponentProxy<PreferenceComponent>()
    }

    fun icon(key: String): IconSource? {
        return iconMap[key]
    }

    suspend fun play(key: String): PlayComponent? {
        return sourceMap[key]?.componentBundle?.getComponentProxy<PlayComponent>()
    }

    suspend fun detailed(key: String): DetailedComponent? {
        return sourceMap[key]?.componentBundle?.getComponentProxy<DetailedComponent>()
    }

    fun empty(): Boolean {
        return sourceMap.isEmpty()
    }

}