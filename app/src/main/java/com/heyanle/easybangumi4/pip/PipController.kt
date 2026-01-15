package com.heyanle.easybangumi4.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

object PipController {

    // ===== State =====
    private val _pipEnabled = MutableStateFlow(false)
    val pipEnabled: StateFlow<Boolean> = _pipEnabled.asStateFlow()

    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    // ===== Activity Host (avoid leak) =====
    private var activityRef: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    private fun activityOrNull(): Activity? = activityRef?.get()

    // ===== Player binding =====
    private var player: ExoPlayer? = null

    fun bindPlayer(p: ExoPlayer) {
        player = p
    }

    fun unbindPlayer(p: ExoPlayer) {
        if (player === p) player = null
    }

    private inline fun withPlayer(block: (ExoPlayer) -> Unit) {
        player?.let(block)
    }

    fun play() = withPlayer {
        it.playWhenReady = true
    }

    fun pause() = withPlayer {
        it.playWhenReady = false
    }

    fun togglePlayPause() = withPlayer {
        it.playWhenReady = !it.playWhenReady
    }

    fun rewind() = withPlayer {
        it.seekBack()
    }

    fun forward() = withPlayer {
        it.seekForward()
    }

    fun setEnabled(enabled: Boolean) {
        _pipEnabled.value = enabled
    }

    private fun isPlaying(): Boolean = player?.playWhenReady == true

    // ===== Optional callbacks for your business =====
    // user clicked PiP close (you detect via Lifecycle.State.CREATED)
    var onPipClosed: (() -> Unit)? = null

    // user maximized PiP back to full screen
    var onPipMaximized: (() -> Unit)? = null

    // ===== PiP control =====

    fun onUserLeaveHint() {
        tryEnterPip()
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        _isInPip.value = isInPictureInPictureMode
    }

    fun tryEnterPip(): Boolean {
        val act = activityOrNull() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!_pipEnabled.value) return false
        if (!act.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (act.isInPictureInPictureMode) return true

        _isInPip.value = true
        val params = buildParams(act, isPlaying())
        val ok = runCatching { act.enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!ok) _isInPip.value = false
        return ok
    }

    fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val act = activityOrNull() ?: return
        if (!act.isInPictureInPictureMode) return
        act.setPictureInPictureParams(buildParams(act, isPlaying()))
    }

    // You can call this when you consider PiP closed
    fun handlePipClosed() {
        // 按你原逻辑：关闭 PiP 时停播
        player?.playWhenReady = false
        onPipClosed?.invoke()
    }

    // You can call this when PiP maximized
    fun handlePipMaximized() {
        onPipMaximized?.invoke()
    }

    // ===== params builder =====

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildParams(context: Context, isPlaying: Boolean): PictureInPictureParams {
        val rewindIntent = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, PipActionReceiver::class.java).setAction(PipActionReceiver.ACTION_REWIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, PipActionReceiver::class.java).setAction(PipActionReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val forwardIntent = PendingIntent.getBroadcast(
            context,
            103,
            Intent(context, PipActionReceiver::class.java).setAction(PipActionReceiver.ACTION_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            Icon.createWithResource(context, android.R.drawable.ic_media_pause)
        } else {
            Icon.createWithResource(context, android.R.drawable.ic_media_play)
        }
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val actions = arrayListOf(
            RemoteAction(
                Icon.createWithResource(context, android.R.drawable.ic_media_rew),
                "Rewind",
                "Rewind",
                rewindIntent
            ),
            RemoteAction(
                playPauseIcon,
                playPauseTitle,
                playPauseTitle,
                toggleIntent
            ),
            RemoteAction(
                Icon.createWithResource(context, android.R.drawable.ic_media_ff),
                "Forward",
                "Forward",
                forwardIntent
            )
        )

        return PictureInPictureParams.Builder()
            .setActions(actions)
            .build()
    }
}
