package com.heyanle.easybangumi4.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> PipController.togglePlayPause()
            ACTION_REWIND -> PipController.rewind()
            ACTION_FORWARD -> PipController.forward()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "easybangumi.pip.TOGGLE"
        const val ACTION_REWIND = "easybangumi.pip.REWIND"
        const val ACTION_FORWARD = "easybangumi.pip.FORWARD"
    }
}