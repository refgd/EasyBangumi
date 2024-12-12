package com.heyanle.easybangumi4.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.heyanle.easybangumi4.base.BaseService
import java.lang.ref.WeakReference

/**
 * Activity管理器,管理项目中Activity的状态
 */
@Suppress("unused")
object LifecycleHelp : Application.ActivityLifecycleCallbacks {

    private const val TAG = "LifecycleHelp"

    private val activities: MutableList<WeakReference<Activity>> = arrayListOf()
    private val services: MutableList<WeakReference<BaseService>> = arrayListOf()
    private var appFinishedListener: (() -> Unit)? = null

    fun activitySize(): Int {
        return activities.size
    }

    /**
     * 判断指定Activity是否存在
     */
    fun isExistActivity(activityClass: Class<*>): Boolean {
        activities.forEach { item ->
            if (item.get()?.javaClass == activityClass) {
                return true
            }
        }
        return false
    }

    /**
     * 关闭指定 activity(class)
     */
    fun finishActivity(vararg activityClasses: Class<*>) {
        val waitFinish = ArrayList<WeakReference<Activity>>()
        for (temp in activities) {
            for (activityClass in activityClasses) {
                if (temp.get()?.javaClass == activityClass) {
                    waitFinish.add(temp)
                    break
                }
            }
        }
        waitFinish.forEach {
            it.get()?.finish()
        }
    }

    fun setOnAppFinishedListener(appFinishedListener: (() -> Unit)) {
        this.appFinishedListener = appFinishedListener
    }

    override fun onActivityPaused(activity: Activity) {
        Log.d(TAG, "${activity::class.simpleName} onPause")
    }

    override fun onActivityResumed(activity: Activity) {
        Log.d(TAG, "${activity::class.simpleName} onResume")
    }

    override fun onActivityStarted(activity: Activity) {
        Log.d(TAG, "${activity::class.simpleName} onStart")
    }

    override fun onActivityDestroyed(activity: Activity) {
        Log.d(TAG, "${activity::class.simpleName} onDestroy")
        for (temp in activities) {
            if (temp.get() != null && temp.get() === activity) {
                activities.remove(temp)
                if (services.size == 0 && activities.size == 0) {
                    onAppFinished()
                }
                break
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d(TAG, "${activity::class.simpleName} onSaveInstanceState")
    }

    override fun onActivityStopped(activity: Activity) {
        Log.d(TAG, "${activity::class.simpleName} onStop")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d(TAG, "${activity::class.simpleName} onCreate")
        activities.add(WeakReference(activity))
    }

    @Synchronized
    fun onServiceCreate(service: BaseService) {
        Log.d(TAG, "${service::class.simpleName} onCreate")
        services.add(WeakReference(service))
    }

    @Synchronized
    fun onServiceDestroy(service: BaseService) {
        Log.d(TAG, "${service::class.simpleName} onDestroy")
        for (temp in services) {
            if (temp.get() != null && temp.get() === service) {
                services.remove(temp)
                if (services.size == 0 && activities.size == 0) {
                    onAppFinished()
                }
                break
            }
        }
    }

    private fun onAppFinished() {
        appFinishedListener?.invoke()
    }
}