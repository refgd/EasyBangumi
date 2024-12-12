package com.heyanle.easybangumi4.web.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.base.BaseService
import com.heyanle.easybangumi4.constant.EventBus
import com.heyanle.easybangumi4.constant.NotificationId
import com.heyanle.easybangumi4.constant.PreferKey
import com.heyanle.easybangumi4.utils.NetworkUtils
import com.heyanle.easybangumi4.utils.getPrefBoolean
import com.heyanle.easybangumi4.utils.getPrefInt
import com.heyanle.easybangumi4.utils.postEvent
import com.heyanle.easybangumi4.utils.printOnDebug
import com.heyanle.easybangumi4.utils.sendToClip
import com.heyanle.easybangumi4.utils.servicePendingIntent
import com.heyanle.easybangumi4.utils.startForegroundServiceCompat
import com.heyanle.easybangumi4.utils.startService
import com.heyanle.easybangumi4.utils.stopService
import com.heyanle.easybangumi4.utils.toastOnUi
import com.heyanle.easybangumi4.web.HttpServer
import com.heyanle.easybangumi4.web.IntentAction
import com.heyanle.easybangumi4.web.WebSocketServer
import com.heyanle.easybangumi4.web.utils.NetworkChangedListener
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import java.io.IOException

class WebService : BaseService() {

    companion object {
        var isRun = mutableStateOf(false)
        var hostAddress = ""

        fun start(context: Context) {
            context.startService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.stopService<WebService>()
        }

        fun serve() {
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:webService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun.value = true
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            val addressList = NetworkUtils.getLocalIPAddress()
            notificationList.clear()
            if (addressList.any()) {
                notificationList.addAll(addressList.map { address ->
                    getString(
                        R.string.http_ip,
                        address.hostAddress,
                        getPort()
                    )
                })
                hostAddress = notificationList.first()
            } else {
                hostAddress = getString(R.string.network_connection_unavailable)
                notificationList.add(hostAddress)
            }
            startForegroundNotification()
            postEvent(EventBus.WEB_SERVICE, hostAddress)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> if (useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun.value = false
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
    }

    private fun upWebServer() {
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.any()) {
            val port = getPort()
            httpServer = HttpServer(port)
            webSocketServer = WebSocketServer(port + 1)
            try {
                httpServer?.start()
                webSocketServer?.start(1000 * 30) // 通信超时设置
                notificationList.clear()
                notificationList.addAll(addressList.map { address ->
                    getString(
                        R.string.http_ip,
                        address.hostAddress,
                        getPort()
                    )
                })
                hostAddress = notificationList.first()
                isRun.value = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: IOException) {
                toastOnUi(e.localizedMessage ?: "")
                e.printOnDebug()
                stopSelf()
            }
        } else {
            toastOnUi("web service cant start, no ip address")
            stopSelf()
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 2211)
        if (port > 65530 || port < 1024) {
            port = 2211
        }
        return port
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, NotificationId.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(com.heyanle.easybangumi4.R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_server))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            com.heyanle.easybangumi4.R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        val notification = builder.build()
        startForeground(NotificationId.WebService, notification)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}