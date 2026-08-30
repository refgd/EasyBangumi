package com.heyanle.easybangumi4.ui.main.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.navigationAiChat
import com.heyanle.easybangumi4.navigationSearch
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.js.source.getIconWithAsyncOrDrawable
import com.heyanle.easybangumi4.plugin.source.LocalSourceBundleController
import com.heyanle.easybangumi4.ui.common.ErrorPage
import com.heyanle.easybangumi4.ui.common.OkImage
import com.heyanle.easybangumi4.ui.common.page.CartoonPageListTab
import com.heyanle.easybangumi4.ui.common.page.CartoonPageUI
import com.heyanle.easybangumi4.ui.common.page.LocalSourcePageErrorHandler
import com.heyanle.easybangumi4.ui.common.page.LocalSourcePageEmptyHandler
import com.heyanle.easybangumi4.ui.common.page.SourcePageEmptyAction
import com.heyanle.easybangumi4.ui.common.page.SourcePageErrorActions
import com.heyanle.easybangumi4.ui.ai.AiWorkspaceStore
import com.heyanle.easybangumi4.ui.ai.AiModelSelectionDialog
import com.heyanle.easybangumi4.ui.ai.AI_PRODUCT_NAME
import com.heyanle.easybangumi4.ui.ai.availableAiModels
import com.heyanle.easybangumi4.ui.ai.AiSkillCapability
import com.heyanle.easybangumi4.ui.main.MainViewModel
import com.heyanle.inject.core.Inject
import kotlinx.coroutines.launch

/**
 * Created by HeYanLe on 2023/3/25 15:47.
 * https://github.com/heyanLE
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {

    val vm = viewModel<HomeViewModel>()
    val mainVM = viewModel<MainViewModel>()
    val nav = LocalNavController.current

    val state by vm.stateFlow.collectAsState()
    val extensionController: ExtensionController by Inject.injectLazy()
    val extensionState by extensionController.state.collectAsState()
    val aiWorkspace by AiWorkspaceStore.state.collectAsState()
    var repairErrorToConfirm by remember { mutableStateOf<String?>(null) }
    var repairAwaitingModel by remember { mutableStateOf<HomeRepairRequest?>(null) }

    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(key1 = state.selectionKey){
        scrollBehavior.state.contentOffset = 0F
    }

    val showChangeSheet = remember {
        mutableStateOf(false)
    }

    if(showChangeSheet.value){
        ModalBottomSheet(
            scrimColor = Color.Black.copy(alpha = 0.32f),
            onDismissRequest = {
                showChangeSheet.value = false
            },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    HomeBottomSheet {
                        showChangeSheet.value = false
                    }

                }

            })

    }

    fun askAiToRepair(errorMsg: String) {
        repairErrorToConfirm = errorMsg
    }

    CompositionLocalProvider(
        LocalSourcePageErrorHandler provides ::askAiToRepair,
        LocalSourcePageEmptyHandler provides SourcePageEmptyAction(
            emptyMsg = "当前栏目没有影视内容",
            buttonText = "列表不应为空？使用 AI 修复",
            onClick = {
                val pageLabel = state.pages.getOrNull(state.selectionIndex)?.label.orEmpty()
                askAiToRepair(
                    "首页栏目“${pageLabel.ifBlank { "当前栏目" }}”成功加载但返回空列表，用户确认该栏目正常应有影视内容"
                )
            },
        ),
    ) {
    Column {
        HomeTopAppBar(
            scrollBehavior = scrollBehavior,
            title = state.topAppBarTitle,
            onChangeClick = {
                scope.launch {
                    showChangeSheet.value = true
                }
            },
            onSearchClick = { nav.navigationSearch(state.selectionKey) }
        )

        if (state.isShowLabel) {
            CartoonPageListTab(
                state.pages,
                selectionIndex = state.selectionIndex,
                onPageClick = {
                    vm.changeSelectionPage(it)
                }
            )

            HorizontalDivider()
        }

        if (state.showError) {
            ErrorPage(
                modifier = Modifier
                    .fillMaxSize(),
                errorMsg = state.errMsg,
                clickEnable = false,
                other = {
                    SourcePageErrorActions(state.errMsg, vm::retry)
                },
            )
        }else{
            AnimatedContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .let {
                        if (!state.isShowLabel) {
                            it.nestedScroll(scrollBehavior.nestedScrollConnection)
                        } else {
                            it
                        }
                    },
                targetState = kotlin.runCatching { state.pages[state.selectionIndex] }.getOrNull(),
                transitionSpec = {
                    fadeIn(animationSpec = tween(300, delayMillis = 300)) togetherWith
                            fadeOut(animationSpec = tween(300, delayMillis = 0))
                }, label = ""
            ) {
                it?.let {
                    val listVmOwner = vm.getViewModelStoreOwner(it)
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides listVmOwner
                    ) {
                        CartoonPageUI(cartoonPage = it)
                    }
                }
            }
        }
    }
    }

    repairErrorToConfirm?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { repairErrorToConfirm = null },
            title = { Text("使用 AI 修复首页？") },
            text = {
                Text("将把当前番源、首页栏目和加载错误发送到对应的 AI 会话，并自动开始排查。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val sourceKey = state.selectionKey
                    val extension = extensionState.extensionInfoMap.values
                        .filterIsInstance<ExtensionInfo.Installed>()
                        .firstOrNull { info -> info.sources.any { it.key == sourceKey } }
                    val request = HomeRepairRequest(
                        sourceKey = sourceKey,
                        extension = extension,
                        task = homePageRepairTask(
                            sourceKey = sourceKey,
                            sourceLabel = state.topAppBarTitle,
                            pageLabel = state.pages.getOrNull(state.selectionIndex)?.label.orEmpty(),
                            errorMsg = errorMsg,
                        ),
                    )
                    repairErrorToConfirm = null
                    val existing = AiWorkspaceStore.sourceSession(sourceKey, extension)
                    val canReuseSession = existing != null &&
                        aiWorkspace.availableAiModels().any { it.id == existing.modelId }
                    if (canReuseSession) {
                        val session = AiWorkspaceStore.enqueueSourceTask(
                            sourceKey,
                            request.task,
                            extension,
                            skillCapabilities = listOf(AiSkillCapability.CATALOG),
                        )
                        nav.navigationAiChat(session.id, returnToHome = true)
                    } else {
                        repairAwaitingModel = request
                    }
                }) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { repairErrorToConfirm = null }) {
                    Text("取消")
                }
            },
        )
    }

    repairAwaitingModel?.let { request ->
        AiModelSelectionDialog(
            models = aiWorkspace.availableAiModels(),
            onDismiss = { repairAwaitingModel = null },
            onSelected = { model ->
                val session = AiWorkspaceStore.enqueueSourceTask(
                    request.sourceKey,
                    request.task,
                    request.extension,
                    modelId = model.id,
                    skillCapabilities = listOf(AiSkillCapability.CATALOG),
                )
                repairAwaitingModel = null
                nav.navigationAiChat(session.id, returnToHome = true)
            },
        )
    }
//    HomeBottomSheet(sheetState = sheetState, defSourceKey = state.selectionKey, onSourceClick = {
//        vm.changeSelectionSource(it)
//    })
}

private data class HomeRepairRequest(
    val sourceKey: String,
    val task: String,
    val extension: ExtensionInfo.Installed?,
)

@Composable
fun HomeBottomSheet(
    onDismissRequest: () -> Unit,
) {
    val animSources = LocalSourceBundleController.current
    val vm = viewModel<HomeViewModel>()

    val state = vm.stateFlow.collectAsState()

    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.choose_source)) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
    )
    Divider()
    LazyColumn() {
        items(animSources.sources()) { source ->
            if(source.versionCode > 0){
                ListItem(
                    modifier = Modifier.clickable {
                        vm.changeSelectionSource(source.key)
                        scope.launch {
                            onDismissRequest()
                        }
                    },
                    headlineContent = { Text(text = source.label) },
                    leadingContent = {
                        val icon = remember {
                            animSources.icon(source.key)
                        }
                        OkImage(
                            modifier = Modifier.size(32.dp),
                            image = icon?.getIconWithAsyncOrDrawable(),
                            contentDescription = source.label
                        )
                    },
                    trailingContent = {
                        RadioButton(
                            selected = state.value.selectionKey == source.key,
                            onClick = {
                                vm.changeSelectionSource(source.key)
                                scope.launch {
                                    onDismissRequest()
                                }
                            })
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior?,
    onChangeClick: () -> Unit,
    onSearchClick: () -> Unit,
) {

    TopAppBar(
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = { onChangeClick() }) {
                Icon(
                    Icons.Filled.SyncAlt,
                    stringResource(id = com.heyanle.easy_i18n.R.string.source)
                )
            }
        },
        title = { Text(text = title) },
        actions = {
            IconButton(onClick = { onSearchClick() }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.search)
                )
            }
        }
    )
}

private fun homePageRepairTask(
    sourceKey: String,
    sourceLabel: String,
    pageLabel: String,
    errorMsg: String,
): String = """
    请修复当前 $AI_PRODUCT_NAME 番源的首页栏目或影视列表问题。

    当前信息：
    - 番源：${sourceLabel.ifBlank { sourceKey }}
    - 番源 key：$sourceKey
    - 首页栏目：${pageLabel.ifBlank { "获取首页栏目阶段" }}
    - 加载错误：${errorMsg.ifBlank { "首页列表加载失败" }}

    修复并验证后，用户会返回首页重试。
""".trimIndent()
