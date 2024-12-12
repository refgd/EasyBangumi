package com.heyanle.easybangumi4.web.controller

import com.heyanle.easybangumi4.plugin.api.SourceResult
import com.heyanle.easybangumi4.plugin.api.entity.Cartoon
import com.heyanle.easybangumi4.plugin.api.entity.CartoonCover
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.source.SourceController
import com.heyanle.easybangumi4.utils.jsonTo
import com.heyanle.easybangumi4.web.utils.ReturnData
import com.heyanle.inject.core.Inject
import com.heyanle.okkv2.core.okkv

object VideoSourceController {

    private val sourceController: SourceController by Inject.injectLazy()

    val sources: ReturnData
        get() {
            val videoSources = sourceController.configSource.value
            val returnData = ReturnData()
            return if (videoSources.isEmpty()) {
                returnData.setErrorMsg("设备源列表为空")
            } else {
                var selectionKeyOkkv by okkv("home_selection_key", "")
                val list = arrayListOf<SourceJson>()
                videoSources.map {
                    list.add(SourceJson(
                        key = it.source.key,
                        label = it.source.label,
                        version = it.source.version,
                        versionCode = it.source.versionCode,
                        hasPref = it.source.hasPref,
                        hasSearch = it.source.hasSearch,
                        describe = it.source.describe,
                        active = selectionKeyOkkv == it.source.key
                    ))
                }
                returnData.setData(list)
            }
        }

    suspend fun getMainTabs(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key")){
            sourceController.sourceBundle.value?.let { sourceBundle ->
                val page = postData["source_key"]?.get(0)?.let { sourceBundle.page(it) }
                page?.let { currentPage ->
                    val mainTabList = currentPage.getMainTabs()?.map {
                        MainTabJson(
                            label = it.label,
                            type = it.type
                        )
                    }.orEmpty()

                    returnData.setData(mainTabList)
                }
            }
        }

        return returnData
    }

    suspend fun getSubTabs(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key") && postData.containsKey("main_tab")){
            sourceController.sourceBundle.value?.let { sourceBundle ->
                val page = postData["source_key"]?.get(0)?.let { sourceBundle.page(it) }
                page?.let { currentPage ->
                    val label = postData["main_tab"]?.getOrNull(0)
                    val subTabList = label?.let { lbl ->
                        currentPage.getSubTabs(lbl)?.map {
                            SubTabJson(
                                label = it.label,
                                isCover = it.isCover
                            )
                        }
                    }.orEmpty()

                    returnData.setData(subTabList)
                }
            }
        }

        return returnData
    }

    suspend fun getContent(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key") && postData.containsKey("main_tab") && postData.containsKey("sub_tab") && postData.containsKey("page")){
            sourceController.sourceBundle.value?.let { sourceBundle ->
                val page = postData["source_key"]?.getOrNull(0)?.let { sourceBundle.page(it) }

                page?.let { currentPage ->
                    val mainTab = postData["main_tab"]?.getOrNull(0)
                    val subTab = postData["sub_tab"]?.getOrNull(0)
                    val pageKey = postData["page"]?.getOrNull(0)?.toIntOrNull()

                    mainTab?.takeIf { subTab != null && pageKey != null }?.let {
                        currentPage.getContent(mainTab, subTab!!, pageKey!!)?.complete { result ->
                            returnData.setData(
                                SourceResultJson(
                                    nextKey = result.data.first,
                                    data = result.data.second.map { cartoon ->
                                        CartoonCoverJson(
                                            id = cartoon.id,
                                            source = cartoon.source,
                                            url = cartoon.url,
                                            title = cartoon.title,
                                            coverUrl = cartoon.coverUrl,
                                            intro = cartoon.intro
                                        )
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }

        return returnData
    }

    suspend fun getDetailed(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key") && postData.containsKey("video_id")){
            sourceController.sourceBundle.value?.let { sourceBundle ->
                val sourceKey = postData["source_key"]?.getOrNull(0)
                val detailed = sourceKey?.let { sourceBundle.detailed(it) }
                detailed?.let { currentPage ->
                    val videoId = postData["video_id"]?.getOrNull(0)
                    videoId?.let {
                        currentPage.getAll(CartoonSummary(it, sourceKey))
                    }?.complete { result ->
                        returnData.setData(
                            DetailResultJson(
                                cartoon = CartoonJson(
                                    id = result.data.first.id,
                                    source = result.data.first.source,
                                    url = result.data.first.url,
                                    title = result.data.first.title,
                                    genre = result.data.first.genre,
                                    coverUrl = result.data.first.coverUrl,
                                    intro = result.data.first.intro,
                                    description = result.data.first.description,
                                    updateStrategy = result.data.first.updateStrategy,
                                    isUpdate = result.data.first.isUpdate,
                                    status = result.data.first.status
                                ),
                                data = result.data.second
                            )
                        )
                    }
                }
            }
        }
        return returnData
    }

    suspend fun getPlayInfo(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key") && postData.containsKey("video_id")
            && postData.containsKey("episode")
            ){
            val episode = postData["episode"]?.getOrNull(0)?.jsonTo<Episode>()

            episode?.let { currentEpisode ->
                sourceController.sourceBundle.value?.let { sourceBundle ->
                    val sourceKey = postData["source_key"]?.getOrNull(0)
                    val play = sourceKey?.let { sourceBundle.play(it) }

                    play?.let { currentPage ->
                        val videoId = postData["video_id"]?.getOrNull(0)

                        videoId?.let {
                            currentPage.getPlayInfo(
                                CartoonSummary(it, sourceKey),
                                PlayLine("", "", arrayListOf<Episode>()),
                                currentEpisode
                            )
                        }?.complete { result ->
                            returnData.setData(result.data)
                        }
                    }
                }
            }

        }
        return returnData
    }

    suspend fun search(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()
        if(postData.containsKey("source_key") && postData.containsKey("key_word") && postData.containsKey("page")){
            sourceController.sourceBundle.value?.let { sourceBundle ->
                val page = postData["source_key"]?.getOrNull(0)?.let { sourceBundle.search(it) }

                page?.let { currentPage ->
                    val keyWord = postData["key_word"]?.getOrNull(0)
                    val pageKey = postData["page"]?.getOrNull(0)?.toIntOrNull()

                    keyWord?.takeIf { pageKey != null }?.let {
                        currentPage.search(pageKey!!, keyWord).complete { result ->
                            returnData.setData(
                                SourceResultJson(
                                    nextKey = result.data.first,
                                    data = result.data.second.map { cartoon ->
                                        CartoonCoverJson(
                                            id = cartoon.id,
                                            source = cartoon.source,
                                            url = cartoon.url,
                                            title = cartoon.title,
                                            coverUrl = cartoon.coverUrl,
                                            intro = cartoon.intro
                                        )
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }

        return returnData
    }
}

class SourceJson(
     val key: String,
     val label: String,
     val version: String,
     val versionCode: Int,
     val hasPref: Int,
     val hasSearch: Int,
     val describe: String?,
     val active:Boolean,
)

class MainTabJson (
    val label: String,
    val type: Int,
)

class SubTabJson (
    val label: String,
    val isCover: Boolean,
)


class SourceResultJson (
    val nextKey: Int?,
    val data: List<CartoonCoverJson>,
)

class DetailResultJson (
    var cartoon: CartoonJson,
    val data: List<PlayLine>,
)

class CartoonCoverJson(
    var id: String,
    var source: String,
    var url: String,
    var title: String,
    var coverUrl: String?,
    var intro: String?,
)

class CartoonJson(
    var id: String,
    var source: String,
    var url: String,
    var title: String,
    var genre: String?,
    var coverUrl: String?,
    var intro: String?,
    var description: String?,
    var updateStrategy: Int,
    var isUpdate: Boolean,
    var status: Int,
)

