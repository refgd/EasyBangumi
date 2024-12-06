@file:OptIn(ExperimentalMaterial3Api::class)

package com.heyanle.easybangumi4.ui.source_manage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.heyanle.easybangumi4.ui.source_manage.source.Source
import com.heyanle.easybangumi4.ui.source_manage.source.SourceTopAppBar
import com.heyanle.okkv2.core.okkv

/**
 * Created by HeYanLe on 2023/2/21 23:20.
 * https://github.com/heyanLE
 */

sealed class ExplorePage constructor(
    val tabLabel: @Composable (() -> Unit),
    val topAppBar: @Composable ((TopAppBarScrollBehavior) -> Unit),
    val content: @Composable (() -> Unit),
) {

    data object SourcePage : ExplorePage(
        tabLabel = {
            Text(stringResource(id = com.heyanle.easy_i18n.R.string.source))
        },
        topAppBar = {
            SourceTopAppBar(it)
        },
        content = {
            Source()
        },
    )

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceManager() {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        ExplorePage.SourcePage.topAppBar(scrollBehavior)
        ExplorePage.SourcePage.content()
    }


}