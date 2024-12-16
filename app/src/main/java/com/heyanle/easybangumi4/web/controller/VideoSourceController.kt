package com.heyanle.easybangumi4.web.controller

import com.heyanle.easybangumi4.base.DataResult
import com.heyanle.easybangumi4.cartoon.entity.CartoonStoryItem
import com.heyanle.easybangumi4.cartoon.story.CartoonStoryController
import com.heyanle.easybangumi4.cartoon.story.local.source.LocalSource
import com.heyanle.easybangumi4.plugin.api.entity.CartoonSummary
import com.heyanle.easybangumi4.plugin.api.entity.Episode
import com.heyanle.easybangumi4.plugin.api.entity.PlayLine
import com.heyanle.easybangumi4.plugin.source.SourceController
import com.heyanle.easybangumi4.utils.jsonTo
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.web.utils.ReturnData
import com.heyanle.inject.core.Inject
import com.heyanle.okkv2.core.okkv
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull

object VideoSourceController {

    private val sourceController: SourceController by Inject.injectLazy()
    private val cartoonStoryController: CartoonStoryController by Inject.injectLazy()

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

        val sourceKey = postData["source_key"]?.firstOrNull()
        when {
            sourceKey == LocalSource.LOCAL_SOURCE_KEY -> {
                returnData.setData(
                    listOf(
                        MainTabJson(
                            label = "全部",
                            type = 1
                        )
                    )
                )
            }
            sourceKey != null -> {
                sourceController.sourceBundle.value?.page(sourceKey)?.getMainTabs()?.let { mainTabs ->
                    returnData.setData(mainTabs.map {
                        MainTabJson(
                            label = it.label,
                            type = it.type
                        )
                    })
                }
            }
        }

        return returnData
    }

    suspend fun getSubTabs(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()

        val sourceKey = postData["source_key"]?.firstOrNull()
        val mainTabLabel = postData["main_tab"]?.firstOrNull()

        if (sourceKey != null && mainTabLabel != null) {
            sourceController.sourceBundle.value?.page(sourceKey)?.let { currentPage ->
                returnData.setData(currentPage.getSubTabs(mainTabLabel)?.map {
                    SubTabJson(
                        label = it.label,
                        isCover = it.isCover
                    )
                }.orEmpty())
            }
        }

        return returnData
    }

    suspend fun getContent(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()

        val sourceKey = postData["source_key"]?.firstOrNull()
        val mainTab = postData["main_tab"]?.firstOrNull()
        val subTab = postData["sub_tab"]?.firstOrNull()
        val pageKey = postData["page"]?.firstOrNull()?.toIntOrNull()

        if (sourceKey != null && mainTab != null && subTab != null && pageKey != null) {
            if (sourceKey == LocalSource.LOCAL_SOURCE_KEY) {
                val list = cartoonStoryController.storyItemList
                    .filterIsInstance<DataResult.Ok<List<CartoonStoryItem>>>()
                    .firstOrNull()
                    ?.okOrNull()
                    .orEmpty()

                returnData.setData(
                    SourceResultJson(
                        nextKey = null,
                        data = list.map {
                            CartoonCoverJson(
                                id = it.cartoonLocalItem.itemId,
                                source = LocalSource.LOCAL_SOURCE_KEY,
                                url = it.cartoonLocalItem.itemId,
                                title = it.cartoonLocalItem.title,
                                coverUrl = it.cartoonLocalItem.cartoonCover.coverUrl,
                                intro = ""
                            )
                        }
                    )
                )
            } else {
                sourceController.sourceBundle.value?.page(sourceKey)?.let { currentPage ->
                    currentPage.getContent(mainTab, subTab, pageKey)?.complete { result ->
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

        return returnData
    }

    suspend fun getDetailed(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()

        val sourceKey = postData["source_key"]?.firstOrNull()
        val videoId = postData["video_id"]?.firstOrNull()

        if (sourceKey != null && videoId != null) {
            sourceController.sourceBundle.value?.detailed(sourceKey)?.let { currentPage ->
                currentPage.getAll(CartoonSummary(videoId, sourceKey)).complete { result ->
                    returnData.setData(
                        DetailResultJson(
                            cartoon = result.data.first.run {
                                CartoonJson(
                                    id = id,
                                    source = source,
                                    url = url,
                                    title = title,
                                    genre = genre,
                                    coverUrl = coverUrl,
                                    intro = intro,
                                    description = description,
                                    updateStrategy = updateStrategy,
                                    isUpdate = isUpdate,
                                    status = status
                                )
                            },
                            data = result.data.second
                        )
                    )
                }
            }
        }

        return returnData
    }

    suspend fun getPlayInfo(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()

        val sourceKey = postData["source_key"]?.firstOrNull()
        val videoId = postData["video_id"]?.firstOrNull()
        val episode = postData["episode"]?.firstOrNull()?.jsonTo<Episode>()

        if (sourceKey != null && videoId != null && episode != null) {
            sourceController.sourceBundle.value?.play(sourceKey)?.let { currentPage ->
                currentPage.getPlayInfo(
                    CartoonSummary(videoId, sourceKey),
                    PlayLine("", "", arrayListOf()),
                    episode
                ).complete { result ->
                    returnData.setData(result.data)
                }
            }
        }

        return returnData
    }

    suspend fun search(postData: MutableMap<String, MutableList<String>>): ReturnData {
        val returnData = ReturnData()

        val sourceKey = postData["source_key"]?.firstOrNull()
        val keyWord = postData["key_word"]?.firstOrNull()
        val pageKey = postData["page"]?.firstOrNull()?.toIntOrNull()

        if (sourceKey != null && keyWord != null && pageKey != null) {
            sourceController.sourceBundle.value?.search(sourceKey)?.let { currentPage ->
                currentPage.search(pageKey, keyWord).complete { result ->
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

