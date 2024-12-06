package com.heyanle.easybangumi4.ui.main.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heyanle.easybangumi4.ABOUT
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.R
import com.heyanle.easybangumi4.SOURCE_MANAGER
import com.heyanle.easybangumi4.STORAGE
import com.heyanle.easybangumi4.STORY
import com.heyanle.easybangumi4.navigationCartoonTag
import com.heyanle.easybangumi4.navigationSetting
import com.heyanle.easybangumi4.setting.SettingPreferences
import com.heyanle.easybangumi4.ui.common.BooleanPreferenceItem
import com.heyanle.easybangumi4.ui.common.OkImage
import com.heyanle.easybangumi4.ui.setting.SettingPage
import com.heyanle.inject.core.Inject

/**
 * Created by HeYanLe on 2023/3/22 15:29.
 * https://github.com/heyanLE
 */

@Composable
fun More() {

    val nav = LocalNavController.current

    val settingPreferences: SettingPreferences by Inject.injectLazy()
    var webEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        EasyBangumiCard()
        Divider()
        BooleanPreferenceItem(
            title = {
                Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.in_private))
            },
            subtitle = {
                Text(
                    text = stringResource(id = com.heyanle.easy_i18n.R.string.in_private_msg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            icon = {
                Icon(
                    Icons.Filled.HistoryToggleOff,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.in_private)
                )
            },
            preference = settingPreferences.isInPrivate
        )
        Divider()

        ListItem(
            modifier = Modifier.clickable {
                nav.navigate(SOURCE_MANAGER)
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.source_manage)) },
            leadingContent = {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.source_manage)
                )
            }
        )

        ListItem(
            modifier = Modifier.clickable {
                nav.navigationCartoonTag()
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.tag_manage)) },
            leadingContent = {
                Icon(
                    Icons.Filled.Tag,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.tag_manage)
                )
            }
        )

        ListItem(
            modifier = Modifier.clickable {
                nav.navigate(STORAGE)
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.backup_and_store)) },
            leadingContent = {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.backup_and_store)
                )
            }
        )

        ListItem(
            modifier = Modifier.clickable {
                nav.navigate(STORY)
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.local_download)) },
            leadingContent = {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.local_download)
                )
            }
        )

        BooleanItem(
            icon = {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.web_server)
                )
            },
            title = {
                Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.web_server))
            },
            subtitle = {
                Text(
                    text = stringResource(id = com.heyanle.easy_i18n.R.string.web_server_msg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            isChecked = webEnabled,
            onCheckedChange = { webEnabled = it }
        )

        Divider()



        ListItem(
            modifier = Modifier.clickable {
                nav.navigationSetting(SettingPage.First)
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.setting)) },
            leadingContent = {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.setting)
                )
            }
        )
        ListItem(
            modifier = Modifier.clickable {
                nav.navigate(ABOUT)
            },
            headlineContent = { Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.about)) },
            leadingContent = {
                Icon(
                    Icons.Outlined.Report,
                    contentDescription = stringResource(id = com.heyanle.easy_i18n.R.string.about)
                )
            }
        )
    }

}

@Composable
fun EasyBangumiCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp, 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OkImage(
            modifier = Modifier.size(64.dp),
            image = R.mipmap.logo_n,
            contentDescription = stringResource(com.heyanle.easy_i18n.R.string.app_name)
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.app_name))
    }

}

@Composable
fun BooleanItem(
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp, 0.dp, 18.dp, 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon at the start if provided
        icon?.let {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                it()
            }
        }

        // Title and subtitle
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                title()
            }
            if (subtitle != null) {
                subtitle()
            }
        }

        // Switch at the end
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}


