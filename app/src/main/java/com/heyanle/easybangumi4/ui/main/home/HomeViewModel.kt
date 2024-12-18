package com.heyanle.easybangumi4.ui.main.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.heyanle.easybangumi4.case.SourceStateCase
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.page.SourcePage
import com.heyanle.inject.core.Inject
import com.heyanle.okkv2.core.okkv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Created by HeYanLe on 2023/3/25 14:39.
 * https://github.com/heyanLE
 */
class HomeViewModel : ViewModel() {

    private var selectionKeyOkkv by okkv("home_selection_key", "")

    private val _stateFlow = MutableStateFlow(HomeState(selectionKey = selectionKeyOkkv))
    val stateFlow = _stateFlow.asStateFlow()

    private val sourceStateCase: SourceStateCase by Inject.injectLazy()

    data class HomeState(
        val isLoading: Boolean = true,
        val showError: Boolean = false,
        val errMsg: String = "",
        val pages: List<SourcePage> = emptyList(),
        val selectionIndex: Int = 0,
        //val selectionPage: SourcePage? = null,
        val isShowLabel: Boolean = true,
        val topAppBarTitle: String = "",
        val selectionKey: String,
    )


    init {
        viewModelScope.launch {
            combine(
                sourceStateCase.flowBundle(),
                _stateFlow.map { it.selectionKey }.distinctUntilChanged()
            ) { sourceBundle, s ->
                val sources = sourceBundle.sources()
                if (sources.isEmpty()) {
                    null
                } else sourceBundle.page(s) ?: sourceBundle.page(sources[0].key)
            }.collectLatest { pa ->
                if (pa == null) {
                    _stateFlow.update {
                        it.copy(
                            isLoading = false,
                            showError = false,
                            pages = emptyList(),
                            topAppBarTitle = ""
                        )
                    }
                } else {
                    selectionKeyOkkv = pa.source.key
                    kotlin.runCatching {
                        var index = -1
                        val pages = pa.getPages()
                        for (i in pages.indices) {
                            if (!pages[i].newScreen) {
                                index = i
                                break
                            }
                        }
                        _stateFlow.update {
                            val realIndex =
                                if (it.selectionIndex >= 0 && it.selectionIndex < pages.size && !pages[it.selectionIndex].newScreen)
                                    it.selectionIndex else index
                            it.copy(
                                isLoading = false,
                                showError = false,
                                pages = pages,
                                selectionKey = pa.source.key,
                                isShowLabel = pages !is PageComponent.NonLabelSinglePage,
                                topAppBarTitle = pa.source.label,
                                selectionIndex = realIndex
                            )
                        }
                    }.onFailure { err ->
                        _stateFlow.update {
                            it.copy(
                                isLoading = false,
                                showError = true,
                                errMsg = err.localizedMessage ?: "Error",
                                pages = emptyList(),
                                topAppBarTitle = pa.source.label,
                            )
                        }
                    }
                }
            }
        }

    }

    fun changeSelectionPage(index: Int) {
        _stateFlow.update {
            it.copy(
                selectionIndex = index
            )
        }
    }

    fun changeSelectionSource(key: String) {
        _stateFlow.update {
            if (it.selectionKey != key) {
                it.copy(
                    selectionKey = key,
                    selectionIndex = -1,

                    )
            } else {
                it
            }

        }
    }

    private val viewModelOwnerStore = hashMapOf<SourcePage, ViewModelStore>()

    fun getViewModelStoreOwner(page: SourcePage) = object : ViewModelStoreOwner {

        override val viewModelStore: ViewModelStore
            get() {
                var viewModelStore = viewModelOwnerStore[page]
                if (viewModelStore == null) {
                    viewModelStore = ViewModelStore()
                    viewModelOwnerStore[page] = viewModelStore
                }
                return viewModelStore
            }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelOwnerStore.iterator().forEach {
            it.value.clear()
        }
        viewModelOwnerStore.clear()
    }


}