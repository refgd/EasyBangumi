package com.heyanle.easybangumi4

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import com.heyanle.easybangumi4.constant.NotificationId.channelIdDownload
import com.heyanle.easybangumi4.constant.NotificationId.channelIdWeb
import com.heyanle.easybangumi4.setting.SettingMMKVPreferences
import com.heyanle.inject.core.Inject
import splitties.systemservices.notificationManager

/**
 * Created by HeYanLe on 2023/2/18 22:47.
 * https://github.com/heyanLE
 */
lateinit var APP: App

class App : Application() {

    companion object {
        const val SPOOF_PACKAGE_NAME = "org.chromium.chrome"
    }

    init {
        Scheduler.runOnAppInit(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        Scheduler.runOnAppAttachBaseContext(this)
    }
    override fun getPackageName(): String {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Override the value passed as X-Requested-With in WebView requests
                val stackTrace = Looper.getMainLooper().thread.stackTrace
                val chromiumElement = stackTrace.find {
                    it.className.equals(
                        "org.chromium.base.BuildInfo",
                        ignoreCase = true,
                    )
                }
                if (chromiumElement?.methodName.equals("getAll", ignoreCase = true)) {
                    val settingPreferences: SettingMMKVPreferences by Inject.injectLazy()
                    if (settingPreferences.webViewCompatible.get()) {
                        // 兼容模式不改写
                        return super.getPackageName()
                    }
                    return SPOOF_PACKAGE_NAME
                }
            } catch (e: Exception) {
            }
        }
        return super.getPackageName()
    }


    override fun onCreate() {

        super.onCreate()
        APP = this
        if (isMainProcess()) {
            Scheduler.runOnAppCreate(this)
            createNotificationChannels()
        }


    }

    private fun isMainProcess(): Boolean {
        return packageName == if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            getProcessName(this) ?: packageName
        }

    }

    private fun getProcessName(cxt: Context): String? {
        val pid = Process.myPid()
        val am = cxt.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val runningApps = am.runningAppProcesses ?: return null
        for (procInfo in runningApps) {
            if (procInfo.pid == pid) {
                return procInfo.processName
            }
        }
        return null
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(com.heyanle.easy_i18n.R.string.download_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(com.heyanle.easy_i18n.R.string.web_server),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                webChannel
            )
        )
    }
}