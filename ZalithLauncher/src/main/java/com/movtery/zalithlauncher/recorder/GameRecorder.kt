/*
 * Zalith Launcher Lite - Game surface recorder, adapted from the open-source
 * Zalith Launcher 2+ recorder implementation.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.movtery.zalithlauncher.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
 *
 * Notes on the capture path:
 * - The game framebuffer has an alpha channel and Minecraft clears it with alpha 0,
 *   so the copied bitmap is forced opaque and drawn with SRC blending; otherwise the
 *   sky accumulates into white in the encoded video.
 * - Frames are copied straight into a bitmap that already has the output size, so
 *   the copy itself downscales and the per-frame upload stays small.
 * - Frames are scheduled against a fixed deadline instead of "delay after capture".
 */
object GameRecorder {
    const val ACTION_PAUSE = "PAUSE"
    const val ACTION_RESUME = "RESUME"
    const val ACTION_STOP = "STOP"

    private const val TAG = "GameRecorder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

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

    // Output geometry and pacing, fixed for the lifetime of one recording.
    private var frameRate = 30
    private var frameIntervalMs = 33L
    private val destRect = Rect()
    private val capturePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        isFilterBitmap = true
    }
    private var alphaLogged = false

    /** Incremented on start/pause/resume/stop so a stale capture chain can never reschedule itself. */
    @Volatile private var captureGeneration = 0
    @Volatile private var nextFrameAtMs = 0L

    // Presentation timestamp normalisation (start at zero, cut out paused time).
    private val ptsLock = Any()
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var pausedAtUs = 0L
    private var ptsShiftUs = 0L
    private val resumePoints = ArrayList<LongArray>()

    @Synchronized
    fun start(activityContext: Context, config: RecorderConfig = RecorderConfig()): Boolean {
        if (recording || stopping) return false
        val view = GameSurfaceRegistry.getView()
        if (view == null || view.width < 2 || view.height < 2) {
            Log.e(TAG, "Game surface is not ready")
            return false
        }

        val appContext = activityContext.applicationContext

        try {
            val codec = MediaCodec.createEncoderByType(MIME).also { encoder = it }
            val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull()

            val (width, height) = fitSize(view.width, view.height, config.resolution, caps)
            if (caps != null && !caps.isSizeSupported(width, height)) {
                throw IOException("Encoder does not support ${width}x${height}")
            }
            var fps = config.frameRate.fps
            if (caps != null) {
                runCatching { caps.getSupportedFrameRatesFor(width, height).upper.toInt() }
                    .getOrNull()
                    ?.let { maxFps -> fps = fps.coerceAtMost(maxFps.coerceAtLeast(1)) }
            }
            frameRate = fps
            frameIntervalMs = (1000L / fps).coerceAtLeast(1L)
            destRect.set(0, 0, width, height)

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

            val bitRate = (width.toLong() * height * fps * 0.15).toInt().coerceIn(2_000_000, 16_000_000)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Hint the encoder to run in real time so it does not fall behind the game.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()

            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            alphaLogged = false
            synchronized(ptsLock) {
                firstPtsUs = -1L
                lastPtsUs = -1L
                pausedAtUs = 0L
                ptsShiftUs = 0L
                resumePoints.clear()
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

            captureGeneration++
            nextFrameAtMs = SystemClock.uptimeMillis()
            scheduleNextFrame(captureGeneration)
            Log.i(TAG, "Recording started: ${width}x${height} at $fps FPS, ${bitRate / 1000} kbps, uri=$uri")
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
        captureGeneration++
        captureHandler?.removeCallbacksAndMessages(null)
        pausedAtUs = System.nanoTime() / 1_000L
        Log.i(TAG, "Recording paused")
    }

    @Synchronized
    fun resume() {
        if (!recording || !paused || stopping) return
        val nowUs = System.nanoTime() / 1_000L
        synchronized(ptsLock) {
            // Frames drawn from now on are shifted back by the time spent paused,
            // so the video does not freeze for the length of the pause.
            ptsShiftUs += (nowUs - pausedAtUs).coerceAtLeast(0L)
            resumePoints.add(longArrayOf(nowUs, ptsShiftUs))
        }
        paused = false
        captureGeneration++
        nextFrameAtMs = SystemClock.uptimeMillis()
        scheduleNextFrame(captureGeneration)
        Log.i(TAG, "Recording resumed")
    }

    @Synchronized
    fun stop(activityContext: Context) {
        if (!recording || stopping) return
        recording = false
        paused = false
        stopping = true
        captureGeneration++
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
                    if (drainFailed || videoTrack < 0) {
                        ctx.contentResolver.delete(uri, null, null)
                        Log.e(TAG, "Deleted incomplete recording; drainFailed=$drainFailed, videoTrack=$videoTrack")
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

    /**
     * Picks the output size: fit the game view into the requested box (swapped for
     * portrait views), never upscale, and align to what the encoder accepts. If the
     * encoder rejects the size, fall back to a 720p-sized box.
     */
    private fun fitSize(
        viewWidth: Int,
        viewHeight: Int,
        resolution: RecorderResolution,
        caps: MediaCodecInfo.VideoCapabilities?
    ): Pair<Int, Int> {
        val landscape = viewWidth >= viewHeight
        val widthAlign = (caps?.widthAlignment ?: 2).coerceAtLeast(2)
        val heightAlign = (caps?.heightAlignment ?: 2).coerceAtLeast(2)

        fun fit(boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
            val scale = minOf(boxWidth.toFloat() / viewWidth, boxHeight.toFloat() / viewHeight, 1f)
            val w = ((viewWidth * scale).toInt() / widthAlign * widthAlign).coerceAtLeast(widthAlign)
            val h = ((viewHeight * scale).toInt() / heightAlign * heightAlign).coerceAtLeast(heightAlign)
            return w to h
        }

        var size = if (landscape) fit(resolution.width, resolution.height) else fit(resolution.height, resolution.width)
        if (caps != null && !caps.isSizeSupported(size.first, size.second)) {
            size = if (landscape) fit(1280, 720) else fit(720, 1280)
        }
        return size
    }

    /** Schedules the next capture against a fixed deadline; never bursts to catch up. */
    private fun scheduleNextFrame(generation: Int) {
        if (generation != captureGeneration || !recording || paused || stopping) return
        val handler = captureHandler ?: return
        val now = SystemClock.uptimeMillis()
        var at = nextFrameAtMs + frameIntervalMs
        if (at < now) at = now
        nextFrameAtMs = at
        handler.postAtTime({ captureFrame(generation) }, at)
    }

    private fun captureFrame(generation: Int) {
        if (generation != captureGeneration || !recording || paused || stopping) return
        val view = GameSurfaceRegistry.getView()
        val surface = encoderSurface
        val bitmap = reusableBitmap
        if (view == null || surface == null || bitmap == null || bitmap.isRecycled) {
            scheduleNextFrame(generation)
            return
        }

        when (view) {
            is SurfaceView -> {
                try {
                    // The destination bitmap already has the output size, so PixelCopy scales.
                    PixelCopy.request(view, null, bitmap, { result ->
                        onFrameCopied(generation, result == PixelCopy.SUCCESS, bitmap, surface)
                        if (result != PixelCopy.SUCCESS) Log.w(TAG, "PixelCopy failed: result=$result")
                    }, captureHandler!!)
                } catch (error: Exception) {
                    Log.e(TAG, "PixelCopy request failed", error)
                    scheduleNextFrame(generation)
                }
            }
            is TextureView -> {
                val copied = runCatching { view.getBitmap(bitmap) }
                    .onFailure { Log.w(TAG, "TextureView bitmap capture failed", it) }
                    .getOrNull() != null
                onFrameCopied(generation, copied, bitmap, surface)
            }
            else -> scheduleNextFrame(generation)
        }
    }

    private fun onFrameCopied(generation: Int, copied: Boolean, bitmap: Bitmap, surface: Surface) {
        if (copied && generation == captureGeneration && recording && !paused && !bitmap.isRecycled) {
            if (!alphaLogged) {
                alphaLogged = true
                // One-time diagnostic: alpha of a sky pixel before the opaque fix is applied.
                runCatching {
                    Log.i(
                        TAG,
                        "Captured frame alpha sample: top=${Color.alpha(bitmap.getPixel(bitmap.width / 2, 2))}, " +
                            "center=${Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))}"
                    )
                }
            }
            // The game framebuffer is cleared with alpha 0; ignore alpha completely.
            bitmap.setHasAlpha(false)
            drawBitmapToEncoder(bitmap, surface)
        }
        scheduleNextFrame(generation)
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
            try {
                // SRC overwrites whatever the reused buffer held instead of blending onto it.
                canvas.drawBitmap(bitmap, null, destRect, capturePaint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure { Log.w(TAG, "Drawing captured frame failed", it) }
    }

    /** Zero-based presentation time with paused time removed; always strictly increasing. */
    private fun adjustPts(rawUs: Long): Long = synchronized(ptsLock) {
        if (firstPtsUs < 0L) firstPtsUs = rawUs
        var shift = 0L
        for (point in resumePoints) {
            if (rawUs >= point[0]) shift = point[1]
        }
        var adjusted = (rawUs - firstPtsUs - shift).coerceAtLeast(0L)
        if (lastPtsUs >= 0L && adjusted <= lastPtsUs) adjusted = lastPtsUs + 1L
        lastPtsUs = adjusted
        adjusted
    }

    private fun drainEncoder() {
        val info = MediaCodec.BufferInfo()
        try {
            var endOfStream = false
            while (!endOfStream) {
                val codec = encoder ?: break
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
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
                                    info.presentationTimeUs = adjustPts(info.presentationTimeUs)
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
