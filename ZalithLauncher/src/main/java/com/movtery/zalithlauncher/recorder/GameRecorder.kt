/*
 * Zalith Launcher Lite - Game surface recorder, adapted from the open-source
 * Zalith Launcher 2+ recorder implementation.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.movtery.zalithlauncher.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the actual game render view (SurfaceView via PixelCopy or TextureView
 * via getBitmap), feeds frames into a MediaCodec surface encoder, and muxes H.264
 * into a public Movies/ZalithRecorder MP4. Audio is intentionally deferred.
 */
object GameRecorder {
    const val ACTION_PAUSE = "PAUSE"
    const val ACTION_RESUME = "RESUME"
    const val ACTION_STOP = "STOP"

    private const val TAG = "GameRecorder"
    private const val FRAME_RATE = 30
    private const val VIDEO_BIT_RATE = 6_000_000

    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var stopping = false
    @Volatile private var drainFailed = false

    private var context: Context? = null
    private var outputUri: Uri? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var outputFd: android.os.ParcelFileDescriptor? = null
    private var videoTrack = -1
    @Volatile private var muxerStarted = false
    private val muxerLock = Any()

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var drainThread: Thread? = null
    private var reusableBitmap: Bitmap? = null

    @Synchronized
    fun start(activityContext: Context): Boolean {
        if (recording || stopping) return false
        val view = GameSurfaceRegistry.getView()
        if (view == null || view.width < 2 || view.height < 2) {
            Log.e(TAG, "Game surface is not ready")
            return false
        }

        val width = (view.width / 2) * 2
        val height = (view.height / 2) * 2
        val appContext = activityContext.applicationContext

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Zalith_$timestamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ZalithRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create MediaStore video entry")
            outputUri = uri
            context = appContext
            outputFd = appContext.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open output video file descriptor")
            muxer = MediaMuxer(outputFd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE.coerceAtMost(width * height * FRAME_RATE * 2))
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = it.createInputSurface()
                it.start()
            }

            videoTrack = -1
            muxerStarted = false
            drainFailed = false
            paused = false
            stopping = false
            recording = true
            captureThread = HandlerThread("ZalithGameCapture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            drainThread = Thread({ drainEncoder() }, "ZalithGameEncoderDrain").also { it.start() }
            scheduleNextFrame()
            Log.i(TAG, "Recording started: ${width}x${height} at ${FRAME_RATE} FPS, uri=$uri")
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start game recording", error)
            recording = false
            cleanupFailedStart()
            return false
        }
    }

    @Synchronized
    fun pause() {
        if (!recording || paused || stopping) return
        paused = true
        captureHandler?.removeCallbacksAndMessages(null)
        Log.i(TAG, "Recording paused")
    }

    @Synchronized
    fun resume() {
        if (!recording || !paused || stopping) return
        paused = false
        scheduleNextFrame()
        Log.i(TAG, "Recording resumed")
    }

    @Synchronized
    fun stop(activityContext: Context) {
        if (!recording || stopping) return
        recording = false
        paused = false
        stopping = true
        captureHandler?.removeCallbacksAndMessages(null)

        // Finalization runs off the UI thread; the encoder drain thread writes the
        // remaining samples through EOS before the muxer is closed.
        Thread({
            try {
                runCatching { encoder?.signalEndOfInputStream() }
                drainThread?.join(8_000L)
                if (drainThread?.isAlive == true) {
                    Log.e(TAG, "Encoder drain timed out while stopping")
                    drainThread?.interrupt()
                    drainFailed = true
                }

                synchronized(muxerLock) {
                    if (muxerStarted) {
                        runCatching { muxer?.stop() }
                            .onFailure { Log.e(TAG, "MediaMuxer.stop failed", it); drainFailed = true }
                        muxerStarted = false
                    }
                    runCatching { muxer?.release() }
                        .onFailure { Log.e(TAG, "MediaMuxer.release failed", it); drainFailed = true }
                    muxer = null
                }

                runCatching { encoder?.stop() }.onFailure { Log.w(TAG, "Encoder stop failed", it) }
                runCatching { encoder?.release() }.onFailure { Log.w(TAG, "Encoder release failed", it) }
                encoder = null
                runCatching { encoderSurface?.release() }
                encoderSurface = null
                runCatching { outputFd?.close() }
                outputFd = null

                val uri = outputUri
                val ctx = context ?: activityContext.applicationContext
                if (uri != null) {
                    if (drainFailed || !muxerStarted && videoTrack < 0) {
                        ctx.contentResolver.delete(uri, null, null)
                        Log.e(TAG, "Deleted incomplete recording; muxerStarted=$muxerStarted, videoTrack=$videoTrack")
                    } else {
                        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        val updated = ctx.contentResolver.update(uri, values, null, null)
                        if (updated <= 0) {
                            ctx.contentResolver.delete(uri, null, null)
                            Log.e(TAG, "Could not publish finalized recording")
                        } else {
                            Log.i(TAG, "Recording saved: $uri")
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error finalizing recording", error)
                outputUri?.let { runCatching { (context ?: activityContext).contentResolver.delete(it, null, null) } }
            } finally {
                cleanupCaptureThread()
                synchronized(this) {
                    outputUri = null
                    context = null
                    videoTrack = -1
                    muxerStarted = false
                    recording = false
                    paused = false
                    stopping = false
                    drainFailed = false
                }
            }
        }, "ZalithRecordingFinalize").start()
    }

    private fun scheduleNextFrame() {
        if (!recording || paused || stopping) return
        captureHandler?.postDelayed({ captureFrame() }, 1000L / FRAME_RATE)
    }

    private fun captureFrame() {
        if (!recording || paused || stopping) return
        val view = GameSurfaceRegistry.getView()
        val surface = encoderSurface
        if (view == null || surface == null) {
            scheduleNextFrame()
            return
        }

        when (view) {
            is SurfaceView -> {
                val width = view.width.coerceAtLeast(1)
                val height = view.height.coerceAtLeast(1)
                val bitmap = reusableBitmap?.takeIf {
                    !it.isRecycled && it.width == width && it.height == height
                } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    reusableBitmap?.let { old -> if (!old.isRecycled) old.recycle() }
                    reusableBitmap = it
                }
                try {
                    PixelCopy.request(view, bitmap, { result ->
                        if (result == PixelCopy.SUCCESS && recording && !paused) drawBitmapToEncoder(bitmap, surface)
                        else if (result != PixelCopy.SUCCESS) Log.w(TAG, "PixelCopy failed: result=$result")
                        scheduleNextFrame()
                    }, captureHandler!!)
                } catch (error: Exception) {
                    Log.e(TAG, "PixelCopy request failed", error)
                    scheduleNextFrame()
                }
            }
            is TextureView -> {
                val bitmap = runCatching {
                    view.getBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                }.onFailure { Log.w(TAG, "TextureView bitmap capture failed", it) }.getOrNull()
                if (bitmap != null) {
                    drawBitmapToEncoder(bitmap, surface)
                    bitmap.recycle()
                }
                scheduleNextFrame()
            }
            else -> scheduleNextFrame()
        }
    }

    @Suppress("DEPRECATION")
    private fun drawBitmapToEncoder(bitmap: Bitmap, surface: Surface) {
        runCatching {
            val canvas = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) surface.lockHardwareCanvas()
                else surface.lockCanvas(null)
            } catch (_: Exception) {
                surface.lockCanvas(null)
            }
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            surface.unlockCanvasAndPost(canvas)
        }.onFailure { Log.w(TAG, "Drawing captured frame failed", it) }
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        try {
            var endOfStream = false
            while (!endOfStream) {
                val codec = encoder ?: break
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopping && !recording) continue
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxerLock) {
                        if (videoTrack < 0) {
                            videoTrack = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                            Log.i(TAG, "Muxer started with video track $videoTrack")
                        }
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(videoTrack, buffer, info)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (isEos) endOfStream = true
                    }
                }
            }
        } catch (error: Exception) {
            drainFailed = true
            Log.e(TAG, "Encoder drain failed", error)
        }
    }

    private fun cleanupFailedStart() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { encoderSurface?.release() }
        encoderSurface = null
        synchronized(muxerLock) {
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        runCatching { outputFd?.close() }
        outputFd = null
        outputUri?.let { uri -> runCatching { context?.contentResolver?.delete(uri, null, null) } }
        outputUri = null
        context = null
        cleanupCaptureThread()
        stopping = false
    }

    private fun cleanupCaptureThread() {
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
        reusableBitmap = null
    }
}
