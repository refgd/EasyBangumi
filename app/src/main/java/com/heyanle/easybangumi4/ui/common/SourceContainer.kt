package com.heyanle.easybangumi4.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.case.SourceStateCase
import com.heyanle.easybangumi4.plugin.api.Source
import com.heyanle.easybangumi4.plugin.api.component.detailed.DetailedComponent
import com.heyanle.easybangumi4.plugin.api.component.page.PageComponent
import com.heyanle.easybangumi4.plugin.api.component.page.SourcePage
import com.heyanle.easybangumi4.plugin.source.LocalSourceBundleController
import com.heyanle.easybangumi4.plugin.source.SourceController
import com.heyanle.easybangumi4.plugin.source.bundle.SourceBundle
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.inject.core.Inject

/**
 * Created by HeYanLe on 2023/2/22 23:53.
 * https://github.com/heyanLE
 */
@Composable
fun SourceContainer(
    modifier: Modifier = Modifier,
    errorContainerColor: Color = Color.Transparent,
    content: @Composable (SourceBundle) -> Unit,
) {
    SourceContainerBase(modifier, errorContainerColor, {true}) { bundle, _ ->
        content(bundle)
    }
}

@Composable
fun <T> SourceContainerBase(
    modifier: Modifier = Modifier,
    errorContainerColor: Color = Color.Transparent,
    resolve: suspend (SourceBundle) -> T?,
    content: @Composable (SourceBundle, T) -> Unit,
) {
    val sourceBundle = LocalSourceBundleController.current
    val sourceStateCase: SourceStateCase by Inject.injectLazy()
    val state by sourceStateCase.flowState().collectAsState()

    // State to manage loading, error, and resolved content
    var result by remember { mutableStateOf<T?>(null) }
    var errorOccurred by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(state) {
        state.logi("SourceController")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
    ) {
        when (state) {
            is SourceController.SourceInfoState.Loading -> {
                LoadingPage(
                    modifier = Modifier.fillMaxSize(),
                    loadingMsg = stringResource(id = R.string.source_loading)
                )
            }
            is SourceController.SourceInfoState.Info -> {
                if (sourceBundle.sources().isEmpty()) {
                    ErrorPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(errorContainerColor),
                        errorMsg = stringResource(id = R.string.no_source),
                        clickEnable = false
                    )
                } else {
                    // Perform the suspend operation
                    LaunchedEffect(sourceBundle) {
                        isLoading = true
                        errorOccurred = false
                        try {
                            result = resolve(sourceBundle)
                        } catch (e: Exception) {
                            errorOccurred = true
                        } finally {
                            isLoading = false
                        }
                    }

                    when {
                        isLoading -> {
                            LoadingPage(
                                modifier = Modifier.fillMaxSize(),
                                loadingMsg = stringResource(id = R.string.source_loading)
                            )
                        }
                        errorOccurred -> {
                            ErrorPage(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(errorContainerColor),
                                errorMsg = stringResource(id = R.string.load_error),
                                clickEnable = false
                            )
                        }
                        result != null -> {
                            content(sourceBundle, result!!)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageContainer(
    sourceKey: String,
    modifier: Modifier = Modifier,
    errorContainerColor: Color = Color.Transparent,
    content: @Composable (SourceBundle, Source, List<SourcePage>) -> Unit,
) {
    SourceContainerBase(
        modifier = modifier,
        errorContainerColor = errorContainerColor,
        resolve = { bundle -> bundle.page(sourceKey) } // Resolve page
    ) { bundle, page ->
        content(bundle, page.source, page.getPages())
    }
}

@Composable
fun DetailedContainer(
    sourceKey: String,
    modifier: Modifier = Modifier,
    errorContainerColor: Color = Color.Transparent,
    content: @Composable (SourceBundle, Source, DetailedComponent) -> Unit,
) {
    SourceContainerBase(
        modifier = modifier,
        errorContainerColor = errorContainerColor,
        resolve = { bundle -> bundle.detailed(sourceKey) } // Resolve detailed component
    ) { bundle, detailed ->
        content(bundle, detailed.source, detailed)
    }
}