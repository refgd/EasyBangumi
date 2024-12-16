package com.heyanle.easybangumi4.ui.source_manage.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.navigationSourceConfig
import com.heyanle.easybangumi4.plugin.api.IconSource
import com.heyanle.easybangumi4.plugin.js.source.getIconWithAsyncOrDrawable
import com.heyanle.easybangumi4.plugin.source.ConfigSource
import com.heyanle.easybangumi4.plugin.source.SourceInfo
import com.heyanle.easybangumi4.ui.common.EasyDeleteDialog
import com.heyanle.easybangumi4.ui.common.OkImage
import com.heyanle.easybangumi4.ui.common.moeSnackBar
import com.heyanle.easybangumi4.utils.loge
import com.heyanle.easybangumi4.utils.stringRes
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.File

/**
 * Created by HeYanLe on 2023/2/21 23:35.
 * https://github.com/heyanLE
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTopAppBar(behavior: TopAppBarScrollBehavior) {
    val nav = LocalNavController.current
    val svm = viewModel<SelectViewModel>()

    var showMenu by remember {
        mutableStateOf(false)
    }

    var showImport by remember {
        mutableStateOf(false)
    }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = {
                nav.popBackStack()
            }) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack, stringResource(id = R.string.close)
                )
            }
        },
        title = { Text(text = stringResource(id = R.string.source_manage)) },
        scrollBehavior = behavior,
        actions = {
            IconButton(onClick = {
                stringRes(R.string.long_touch_to_drag).moeSnackBar()
            }) {
                Icon(Icons.Filled.Sort, stringResource(id = R.string.long_touch_to_drag))
            }

            IconButton(onClick = {
                showMenu = !showMenu
            }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(id = R.string.more)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }) {

                DropdownMenuItem(
                    text = {
                        Text(text = stringResource(id = R.string.insert_local))
                    },
                    onClick = {
                        showMenu = false
                        svm.chooseJSFile()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(id = R.string.insert_local))
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(text = stringResource(id = R.string.insert_remote))
                    },
                    onClick = {
                        showMenu = false
                        showImport = true
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(id = R.string.insert_remote))
                    }
                )
            }

            ImportDialog(
                showImport = showImport,
                onDismiss = { showImport = false },
                onConfirm = { url ->
                    showImport = false
                    svm.push(url)
                }
            )
        }
    )
}

@Composable
fun Source() {
    val nav = LocalNavController.current

    val vm = viewModel<SourceViewModel>()
    val svm = viewModel<SelectViewModel>()
    val vstate = svm.state.collectAsState()
    val sta = vstate.value
    val dialog = sta.dialog

    val state = rememberReorderableLazyListState(onMove = { from, to ->
        vm.move(from.index, to.index)
    }, onDragEnd = { from, to ->
        vm.onDragEnd()
    })


    LazyColumn(
        state = state.listState,
        modifier = Modifier
            .fillMaxSize()
            .reorderable(state)
            .detectReorderAfterLongPress(state)
    ) {
        items(vm.configSourceList, key = { it.sourceInfo.source.key }) { configSource ->
            val sourceInfo = configSource.sourceInfo
            val source = sourceInfo.source
            if(source.versionCode > 0){
                ReorderableItem(reorderableState = state, key = source.key) { it ->
                    it.loge("Source")
                    Box(
                        modifier = Modifier
                            .run {
                                if (it) {
                                    background(
                                        MaterialTheme.colorScheme.surfaceColorAtElevation(
                                            3.dp
                                        )
                                    )
                                } else {
                                    this
                                }
                            },
                    ) {
                        SourceItem(
                            configSource,
                            showConfig = configSource.sourceInfo.source.hasPref == 1,
                            onCheckedChange = { source: ConfigSource, b: Boolean ->
                                if (b) {
                                    vm.enable(source)
                                } else {
                                    vm.disable(source)
                                }
                            },
                            onClick = {
                                if(it.sourceInfo is SourceInfo.Loaded && it.config.enable && it.sourceInfo.source.hasPref == 1){
                                    nav.navigationSourceConfig(it.sourceInfo.source.key, it.sourceInfo.source.label)
                                }
                            },
                            moveToTop = {
                                val currentIndex = vm.configSourceList.indexOf(configSource)
                                if (currentIndex != -1) {
                                    vm.move(currentIndex, 0)
                                    vm.onDragEnd()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    when(sta.dialog) {
        is SelectViewModel.Dialog.Loading -> {
            AlertDialog(
                onDismissRequest = {
                    //vm.cancelCurrent()
                },
                confirmButton = {
                    TextButton(onClick = {
                        svm.cancelCurrent()
                    }) {
                        Text(text = stringResource(id = R.string.cancel))
                    }
                },
                text = {
                    Text( sta.dialog.msg)
                }
            )
        }
        is SelectViewModel.Dialog.ErrorOrCompletely -> {
            AlertDialog(
                onDismissRequest = {
                    svm.cleanErrorOrCompletely()
                },
                confirmButton = {
                    TextButton(onClick = {
                        svm.cleanErrorOrCompletely()
                    }) {
                        Text(text = stringResource(id = R.string.confirm))
                    }
                },
                text = {
                    Text( sta.dialog.msg)
                }
            )
        }
        else -> {}
    }
}

@Composable
fun SourceItem(
    configSource: ConfigSource,
    showConfig: Boolean,
    onCheckedChange: (ConfigSource, Boolean) -> Unit,
    onClick: (ConfigSource) -> Unit,
    moveToTop: () -> Unit,
) {

    val sourceInfo = configSource.sourceInfo
    val config = configSource.config
    val icon = sourceInfo.source as? IconSource

    var showItMenu by remember {
        mutableStateOf(false)
    }

    var sourceName by remember { mutableStateOf("") }

    EasyDeleteDialog(
        show = sourceName != "",
        message = {
            Text(text = stringResource(id = R.string.delete_confirmation, sourceName))
        },
        onDelete = {
            (sourceInfo as SourceInfo.Loaded).componentBundle.destory()

            sourceName = ""
        }) {
        sourceName = ""
    }

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier.clickable {
            onClick(configSource)
        },
        headlineContent = {
            Text(text = sourceInfo.source.label)
        },
        supportingContent = {
            Text(
                text = sourceInfo.source.version,
            )
        },
        trailingContent = {
            when(sourceInfo){
                is SourceInfo.Loaded -> {
                    Row {
                        if (showConfig){
                            IconButton(
                                onClick = {
                                    onClick(configSource)
                                },
                            ){
                                Icon(Icons.Filled.Settings, contentDescription = sourceInfo.source.label)
                            }
                        }
                        Switch(checked = config.enable, onCheckedChange = {
                            onCheckedChange(configSource, it)
                        })

                        IconButton(onClick = {
                            showItMenu = !showItMenu
                        }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(id = R.string.more)
                            )
                        }

                        DropdownMenu(
                            expanded = showItMenu,
                            onDismissRequest = { showItMenu = false }) {

                            DropdownMenuItem(
                                text = {
                                    Text(text = stringResource(id = R.string.push_pin))
                                },
                                onClick = {
                                    moveToTop()
                                    showItMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.VerticalAlignTop, contentDescription = stringResource(id = R.string.push_pin))
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(text = stringResource(id = R.string.delete))
                                },
                                onClick = {
                                    showItMenu = false
                                    sourceName = sourceInfo.source.label
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(id = R.string.delete))
                                }
                            )
                        }
                    }

                }
                is SourceInfo.Error -> {
                    Text(text = sourceInfo.msg)
                }
            }

        },
        leadingContent = {
            OkImage(
                modifier = Modifier.size(40.dp),
                image = icon?.getIconWithAsyncOrDrawable(),
                contentDescription = sourceInfo.source.label,
                crossFade = false,
                placeholderColor = null,
                errorColor = null,
            )
        }
    )
}

@Composable
fun ImportDialog(
    showImport: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if(showImport){
        var text by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text( stringResource(id = R.string.insert_remote) )
            },
            text = {
                Column {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(id = R.string.js_repo_desc)) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onConfirm(text)
                }) {
                    Text( stringResource(id = R.string.confirm) )
                }
            },
            dismissButton = {
                Button(onClick = onDismiss) {
                    Text( stringResource(id = R.string.cancel) )
                }
            }
        )
    }
}