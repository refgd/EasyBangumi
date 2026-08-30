package com.heyanle.easybangumi4

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.heyanle.easybangumi4.plugin.source.SourcesHost
import com.heyanle.easybangumi4.splash.SplashActivity
import com.heyanle.easybangumi4.theme.EasyTheme
import com.heyanle.easybangumi4.ui.common.LoadingImage
import com.heyanle.easybangumi4.ui.common.MoeDialogHost
import com.heyanle.easybangumi4.ui.common.MoeSnackBar
import com.heyanle.easybangumi4.utils.MediaUtils
import com.heyanle.okkv2.core.okkv
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.heyanle.easybangumi4.pip.PipActionReceiver
import com.heyanle.easybangumi4.pip.PipController

/**
 * Created by HeYanLe on 2023/10/29 21:20.
 * https://github.com/heyanLE
 */

val LocalWindowSizeController = staticCompositionLocalOf<WindowSizeClass> {
    error("AppNavController Not Provide")
}

class MainActivity : ComponentActivity() {

    var first by okkv("first_visible", def = true)
    private val launcherBus = LauncherBus(this)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        PipController.onPictureInPictureModeChanged(isInPictureInPictureMode)

        val state = lifecycle.currentState
        if (state == Lifecycle.State.CREATED) {
            PipController.handlePipClosed()
        } else if (state == Lifecycle.State.STARTED) {
            PipController.handlePipMaximized()
        }

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipController.onUserLeaveHint()
    }

    override fun onDestroy() {
        PipController.detach(this)
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PipController.attach(this)

        setContentView(FrameLayout(this))
        SplashActivity.lastSplashActivity?.get()?.finish()
        Scheduler.runOnMainActivityCreate(this, first)
        first = false
        MediaUtils.setIsDecorFitsSystemWindows(this, false)
        setContent {
            val isMigrating by Migrate.isMigrating.collectAsState()
            val windowClazz = calculateWindowSizeClass(this)
            LaunchedEffect(key1 = Unit){
                Scheduler.runOnComposeLaunch(this@MainActivity)
            }
            CompositionLocalProvider(
                LocalWindowSizeController provides windowClazz
            ) {
                EasyTheme {
                    if(isMigrating){
                        AlertDialog(
                            text = {
                                Row {
                                    LoadingImage()
                                    Text(text = stringResource(id = com.heyanle.easy_i18n.R.string.migrating))
                                }
                            },
                            confirmButton = {},
                            onDismissRequest = {  },
                        )
                    }
                    val focusManager = LocalFocusManager.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { focusManager.clearFocus() })
                    ) {
                        SourcesHost {
                            Nav()
                        }
                        MoeSnackBar(Modifier.statusBarsPadding())
                        MoeDialogHost()
                    }

                }
            }

        }

    }


    override fun onResume() {
        super.onResume()
        LauncherBus.onResume(launcherBus)
    }
}
