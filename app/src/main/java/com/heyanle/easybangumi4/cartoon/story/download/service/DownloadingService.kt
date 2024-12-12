package com.heyanle.easybangumi4.cartoon.story.download.service


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.heyanle.easybangumi4.MainActivity
import com.heyanle.easybangumi4.R
import com.heyanle.easybangumi4.base.BaseService
import com.heyanle.easybangumi4.constant.NotificationId
import com.heyanle.easybangumi4.constant.NotificationId.channelIdDownload
import com.heyanle.easybangumi4.utils.stringRes


/**
 * Created by heyanle on 2024/8/4.
 * https://github.com/heyanLE
 */
class DownloadingService: BaseService() {

    companion object {
        var isRun = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            var notification: Notification? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                notification = Notification.Builder(this, channelIdDownload)
                    .setContentTitle(stringRes(com.heyanle.easy_i18n.R.string.the_app_name))
                    .setContentText(stringRes(com.heyanle.easy_i18n.R.string.downloading))
                    .setSmallIcon(R.mipmap.logo_n)
                    .setContentIntent(pendingIntent)
                    .setTicker(stringRes(com.heyanle.easy_i18n.R.string.downloading))
                    .build()
            }

            startForeground(NotificationId.DownloadService, notification)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        super.onDestroy()
    }
}