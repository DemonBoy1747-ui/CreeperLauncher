/*
 * Zalith Launcher Lite - Recorder prototype
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.movtery.zalithlauncher.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal video-only MediaProjection recorder. Audio capture is intentionally
 * deferred until the video path has been tested on real devices.
 */
class RecorderService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null
    private var isPaused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing screen recording…"))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: run {
            stopSelf()
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

            val sourceWidth = metrics.widthPixels.coerceAtLeast(2)
            val sourceHeight = metrics.heightPixels.coerceAtLeast(2)
            val configWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
            val configHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
            val scale = minOf(configWidth.toFloat() / sourceWidth, configHeight.toFloat() / sourceHeight)
            val width = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val height = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(30, 120)

            val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "ZalithRecorder")
            if (!dir.exists() && !dir.mkdirs()) error("Cannot create recording folder")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(dir, "Zalith_$stamp.mp4")

            val videoRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = videoRecorder
            videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            videoRecorder.setVideoSize(width, height)
            videoRecorder.setVideoFrameRate(fps)
            videoRecorder.setVideoEncodingBitRate((width * height * fps * 0.12).toInt().coerceAtLeast(2_000_000))
            videoRecorder.setOutputFile(outputFile!!.absolutePath)
            videoRecorder.prepare()
            val surface = videoRecorder.surface
            virtualDisplay = projection?.createVirtualDisplay(
                "ZalithRecorder", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
            videoRecorder.start()
            updateNotification("Recording screen")
        } catch (error: Exception) {
            stopRecording()
            android.util.Log.e(TAG, "Unable to start recording", error)
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && recorder != null && !isPaused) {
            runCatching { recorder?.pause(); isPaused = true; updateNotification("Recording paused") }
                .onFailure { android.util.Log.e(TAG, "Pause failed", it) }
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && recorder != null && isPaused) {
            runCatching { recorder?.resume(); isPaused = false; updateNotification("Recording screen") }
                .onFailure { android.util.Log.e(TAG, "Resume failed", it) }
        }
    }

    private fun stopRecording() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        isPaused = false
        outputFile?.let { if (!it.exists() || it.length() <= 0L) it.delete() }
        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Zalith Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Zalith Recorder")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.kedamc.launcher.recorder.START"
        const val ACTION_PAUSE = "com.kedamc.launcher.recorder.PAUSE"
        const val ACTION_RESUME = "com.kedamc.launcher.recorder.RESUME"
        const val ACTION_STOP = "com.kedamc.launcher.recorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        private const val CHANNEL_ID = "zalith_recorder"
        private const val NOTIFICATION_ID = 7214
        private const val TAG = "ZalithRecorder"
    }
}
