package com.heyanle.easybangumi4.splash.step

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.heyanle.easybangumi4.ui.setting.DarkModeItem
import com.heyanle.easybangumi4.ui.setting.ThemeModeItem

/**
 * Created by heyanlin on 2024/7/4.
 */
class ThemeStep : BaseStep {

    override val name: String
        get() = "Theme"
    override val version: Int
        get() = 0

    @Composable
    override fun Compose() {
        Column (
            modifier = Modifier.fillMaxWidth()
                .padding(16.dp, 0.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(0.dp, 16.dp)
        ) {


            DarkModeItem()
            Spacer(modifier = Modifier.size(16.dp))
            ThemeModeItem()
        }
    }
}