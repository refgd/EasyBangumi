package com.heyanle.easybangumi4

import android.app.Application
import android.util.Base64
import com.heyanle.easy_crasher.CrashHandler
import com.heyanle.easybangumi4.cartoon.CartoonModule
import com.heyanle.easybangumi4.case.CaseModule
import com.heyanle.easybangumi4.crash.SourceCrashController
import com.heyanle.easybangumi4.dlna.DlnaModule
import com.heyanle.easybangumi4.exo.MediaModule
import com.heyanle.easybangumi4.plugin.api.extension.IconFactory
import com.heyanle.easybangumi4.plugin.api.extension.iconFactory
import com.heyanle.easybangumi4.plugin.extension.ExtensionModule
import com.heyanle.easybangumi4.plugin.source.SourceModule
import com.heyanle.easybangumi4.setting.SettingModule
import com.heyanle.easybangumi4.splash.SplashActivity
import com.heyanle.easybangumi4.storage.StorageModule
import com.heyanle.easybangumi4.ui.common.dismiss
import com.heyanle.easybangumi4.ui.common.moeDialogAlert
import com.heyanle.easybangumi4.utils.UUIDHelper
import com.heyanle.easybangumi4.utils.exo_ssl.CropUtil
import com.heyanle.easybangumi4.utils.exo_ssl.TrustAllHostnameVerifier
import com.heyanle.easybangumi4.utils.getCachePath
import com.heyanle.easybangumi4.utils.stringRes
import com.heyanle.inject.api.get
import com.heyanle.inject.core.Inject
import com.heyanle.okkv2.MMKVStore
import com.heyanle.okkv2.core.Okkv
import com.heyanle.okkv2.core.okkv
import com.tencent.bugly.crashreport.CrashReport
import java.io.File
import javax.net.ssl.HttpsURLConnection

/**
 * 全局初始化时点分发
 * Created by HeYanLe on 2023/10/29 14:39.
 * https://github.com/heyanLE
 */
object Scheduler {



    /**
     * application#init
     */
    fun runOnAppInit(application: Application) {
        RootModule(application).registerWith(Inject)
    }

    /**
     * application#attachBaseContext
     */
    fun runOnAppAttachBaseContext(application: Application) {
        initCrasher(application)
    }

    /**
     * application#onCreate
     */
    fun runOnAppCreate(application: Application) {

        try {
            File(application.getCachePath("transformer")).deleteRecursively()
            File(application.getCachePath("download")).deleteRecursively()
        }catch (e: Exception) {
            e.printStackTrace()
        }


        // 注册各种 Controller
        SettingModule(application).registerWith(Inject)
        ControllerModule(application).registerWith(Inject)
        CartoonModule(application).registerWith(Inject)
        MediaModule(application).registerWith(Inject)
        CaseModule(application).registerWith(Inject)
        ExtensionModule(application).registerWith(Inject)
        SourceModule(application).registerWith(Inject)
        StorageModule(application).registerWith(Inject)
        DlnaModule(application).registerWith(Inject)
        initOkkv(application)
        initBugly(application)
        initAria(application)

        SourceCrashController.init(application, Inject.get())
        initTrustAllHost()
    }

    var first by okkv("first_visible_version_code", def = 0)

    fun runOnSplashActivityCreate(activity: SplashActivity, isFirst: Boolean) {
        Migrate.update(activity)
    }

    /**
     * MainActivity#onCreate
     */
    fun runOnMainActivityCreate(activity: MainActivity, isFirst: Boolean) {
        Migrate.update(activity)
        val extensionController: com.heyanle.easybangumi4.plugin.extension.ExtensionController by Inject.injectLazy()
        val extensionIconFactory: IconFactory by Inject.injectLazy()
        iconFactory = extensionIconFactory
        extensionController.init()
        if (isFirst) {
            try {
                // 启动须知
                val firstAnnoBase = """
               ICAgMS4g57qv57qv55yL55yL5piv5Li65LqG5a2m5LmgIEppdHBhY2sgY29tcG9zZSDlkozpn7Pop4bpopHnm7jlhbPmioDmnK=ov5vooYzlvIDlj5HnmoTkuIDkuKrpobnnm67vvIzlhbbmupDku6PnoIHku4XkvpvkuqTmtYHlrabkuaDjgILlm6Dlhbbku5bkurrnp4Hoh6rmiZPljIXlj5HooYzlkI7pgKDmiJDnmoTkuIDliIflkI7mnpzmnKzmlrnmpoLkuI3otJ=otKPjgIIKICAgMi4g57qv57qv55yL55yL5omT5YyF5ZCO5LiN5o-Q5L6b5Lu75L2V6KeG6aKR5YaF5a6577yM6ZyA6KaB55So5oi36Ieq5bex5omL5Yqo5re75Yqg44CC55So5oi36Ieq6KGM5a-85YWl55qE5YaF5a655ZKM5pys6L2v5Lu25peg5YWz44CCCiAgIDMuIOe6r-e6r-eci-eci-a6kOeggeWujOWFqOWFjei0ue-8jOWcqCBHaXRodWIg5byA5rqQ44CC55So5oi35Y-v6Ieq6KGM5LiL6L295omT5YyF44CC5aaC5p6c5L2g5piv5pS26LS56LSt5Lmw55qE5pys6L2v5Lu277yM5YiZ5pys5pa55qaC5LiN6LSf6LSj44CC
            """.trimIndent().replace("=", "/").replace("-", "+")
                Base64.decode(firstAnnoBase, Base64.DEFAULT).toString(Charsets.UTF_8).moeDialogAlert(
                    stringRes(com.heyanle.easy_i18n.R.string.first_anno),
                    dismissLabel = stringRes(com.heyanle.easy_i18n.R.string.confirm),
                    onDismiss = {
                        it.dismiss()
                    }
                )
            }catch (e: Throwable){
                e.printStackTrace()
            }

        }
    }

    fun runOnComposeLaunch(activity: MainActivity) {
//        if (first != BuildConfig.VERSION_CODE) {
//            try {
//                // 更新日志
//                val scope = MainScope()
//                scope.launch(Dispatchers.IO) {
//                    activity.assets?.open("update_log.txt")?.bufferedReader()?.use {
//                        it.readText().moeDialogAlert(
//                            stringRes(com.heyanle.easy_i18n.R.string.version) + ": " + BuildConfig.VERSION_NAME,
//                            dismissLabel = stringRes(com.heyanle.easy_i18n.R.string.confirm),
//                            onDismiss = {
//                                it.dismiss()
//                            }
//                        )
//                    }
//                }
//            }catch (e: Throwable){
//                e.printStackTrace()
//            }
//        }
        first = BuildConfig.VERSION_CODE
    }

    /**
     * 全局异常捕获 + crash 界面
     */
    private fun initCrasher(application: Application) {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(application))
    }

    /**
     * 允许 http 链接
     */
    private fun initTrustAllHost() {
        HttpsURLConnection.setDefaultSSLSocketFactory(CropUtil.getUnsafeSslSocketFactory())
        HttpsURLConnection.setDefaultHostnameVerifier(TrustAllHostnameVerifier())
    }

    private fun initBugly(application: Application) {
        if (!BuildConfig.DEBUG) {
            CrashReport.initCrashReport(application)
            CrashReport.setDeviceModel(application, android.os.Build.MODEL)
            CrashReport.setDeviceId(application, UUIDHelper.getUUID())

        }
    }

    /**
     * 初始化 okkv
     */
    private fun initOkkv(application: Application) {
        Okkv.Builder(MMKVStore(application)).cache().build().init().default()
        // 如果不使用缓存，请手动指定 key
        Okkv.Builder(MMKVStore(application)).build().init().default("no_cache")
    }

    /**
     * 初始化 aria
     */
    private fun initAria(application: Application) {
//        runCatching {
//            Aria.init(application)
//            Aria.get(application).downloadConfig.isConvertSpeed = true
//        }.onFailure {
//            it.printStackTrace()
//        }
    }
}