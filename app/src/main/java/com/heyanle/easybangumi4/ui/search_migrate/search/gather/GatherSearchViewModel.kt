package com.heyanle.easybangumi4.ui.search_migrate.search.gather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.heyanle.easybangumi4.plugin.api.component.search.SearchComponent
import com.heyanle.easybangumi4.plugin.api.entity.CartoonCover
import com.heyanle.easybangumi4.plugin.source.SourceInfo
import com.heyanle.easybangumi4.plugin.source.bundle.getComponentProxy
import com.heyanle.easybangumi4.ui.search_migrate.PagingSearchSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Created by heyanlin on 2023/12/18.
 */
class GatherSearchViewModel(
    private val sourceInfos: List<SourceInfo.Loaded>
): ViewModel() {

    data class GatherSearchItem(
        val searchComponent: SearchComponent,
        val flow: Flow<PagingData<CartoonCover>>
    )

    // 当前搜索的关键字，用于刷新和懒加载判断
    var curKeyWord: String = ""

    private val _searchItemList = MutableStateFlow<List<GatherSearchItem>?>(emptyList())
    val searchItemList = _searchItemList.asStateFlow()

    fun newSearchKey(searchKey: String) {
        viewModelScope.launch {
            if (curKeyWord == searchKey && _searchItemList.value != null) {
                return@launch
            }
            if (searchKey.isEmpty()) {
                curKeyWord = ""
                _searchItemList.value = null
                return@launch
            }
            curKeyWord = searchKey
            _searchItemList.update {
                sourceInfos.mapNotNull {
                    if(it.source.hasSearch == 1){
                        it.componentBundle.getComponentProxy<SearchComponent>()?.let { component ->
                            GatherSearchItem(
                                component,
                                getPager(searchKey, component).flow.cachedIn(viewModelScope)
                            )
                        }
                    }else{
                        null
                    }
                }
            }

        }
    }


    private fun getPager(
        keyword: String,
        searchComponent: SearchComponent
    ): Pager<Int, CartoonCover> {
        return Pager(
            PagingConfig(pageSize = 10),
            initialKey = searchComponent.getFirstSearchKey(keyword)
        ) {
            PagingSearchSource(searchComponent, keyword)
        }
    }


}

class GatherSearchViewModelFactory(
    private val searchComponents: List<SourceInfo.Loaded>
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    @SuppressWarnings("unchecked")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GatherSearchViewModel::class.java))
            return GatherSearchViewModel(searchComponents) as T
        throw RuntimeException("unknown class :" + modelClass.name)
    }
}