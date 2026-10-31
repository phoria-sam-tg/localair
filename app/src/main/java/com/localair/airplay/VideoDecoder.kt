package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Async MediaCodec AVC decoder that lives in the Service, independent of
 * Activity lifecycle. Always set as the video sink so SPS+PPS are never
 * missed. The Activity provides/revokes a Surface — codec is only created
 * when both SPS+PPS AND a Surface are present, and torn down when the
 * Surface goes away.
 */
class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
    /** Displayed (cropped) video size, whenever the decoder reports a new one. */
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
) : VideoSink {

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile var hasFrames = false; private set
    @Volatile private var rendered = 0L
    @Volatile private var fed = 0L

    private val pending = ConcurrentLinkedQueue<Pair<ByteArray, Long>>()
    private val codecThread = HandlerThread("VideoDecoder").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    fun attachSurface(s: Surface) {
        surface = s
        Log.i(TAG, "surface attached")
        codecHandler.post { maybeStartCodec() }
    }

    /**
     * Stops the codec before returning: SurfaceHolder.surfaceDestroyed requires
     * that nothing uses the surface once it returns, and a codec still rendering
     * into it is how "The surface has been released" happened under the
     * screensaver's attach/detach churn. Bounded, so a wedged codec can't hang
     * the UI thread.
     */
    fun detachSurface() {
        surface = null
        Log.i(TAG, "surface detached — stopping codec")
        val done = CountDownLatch(1)
        if (!codecHandler.post { stopCodec(); done.countDown() }) done.countDown()
        if (!done.await(DETACH_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "codec stop still pending after ${DETACH_WAIT_MS}ms")
        }
        pending.clear()
        rendered = 0; fed = 0
        if (hasFrames) { hasFrames = false; onFramesChanged(false) }
    }

    fun release() {
        detachSurface()
        codecThread.quitSafely()
        sps = null; pps = null
    }

    override fun onSessionEnd() {
        Log.i(TAG, "session ended — resetting")
        codecHandler.post { stopCodec() }
        sps = null; pps = null
        pending.clear()
        rendered = 0; fed = 0
        if (hasFrames) { hasFrames = false; onFramesChanged(false) }
    }

    override fun onNalUnit(data: ByteArray, ptsUs: Long) {
        val oldSps = sps
        extractParams(data)
        // iOS sends a new SPS when the mirrored size or orientation changes
        // (rotating, leaving an app). Start a fresh codec on the new parameters
        // instead of relying on in-band adaptive playback, which this codec was
        // never configured for; frames queued under the old SPS are dropped.
        val newSps = sps
        if (oldSps != null && newSps != null && !oldSps.contentEquals(newSps) && codec != null) {
            Log.i(TAG, "SPS changed (${oldSps.size}B -> ${newSps.size}B): restarting codec")
            pending.clear()
            codecHandler.post { stopCodec(); maybeStartCodec() }
        }
        if (!hasFrames && sps != null && pps != null) {
            hasFrames = true
            onFramesChanged(true)
            Log.i(TAG, "first NAL with config: ${data.size}B, types=${dumpNalTypes(data)}")
        }
        // Always buffer — codec may not be running yet (waiting for Surface).
        // Cap at ~120 frames (~2s at 60fps) to avoid unbounded growth.
        pending.offer(data to ptsUs)
        while (pending.size > 120) pending.poll()

        if (codec == null && sps != null && pps != null && surface != null) {
            codecHandler.post { maybeStartCodec() }
        }
    }

    private fun extractParams(buf: ByteArray) {
        val starts = findStartCodes(buf)
        for (i in starts.indices) {
            val begin = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else buf.size
            if (begin + 4 >= end) continue
            when (buf[begin + 4].toInt() and 0x1F) {
                7 -> { sps = buf.copyOfRange(begin, end); Log.i(TAG, "SPS ${sps!!.size}B") }
                8 -> { pps = buf.copyOfRange(begin, end); Log.i(TAG, "PPS ${pps!!.size}B") }
            }
        }
    }

    private fun findStartCodes(buf: ByteArray): List<Int> {
        val out = ArrayList<Int>(4)
        var i = 0
        while (i + 3 < buf.size) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() &&
                buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()) {
                out.add(i); i += 4
            } else i++
        }
        return out
    }

    private fun dumpNalTypes(buf: ByteArray): String {
        val starts = findStartCodes(buf)
        return starts.joinToString(",") { (buf[it + 4].toInt() and 0x1F).toString() }
    }

    private fun maybeStartCodec() {
        val s = sps ?: return
        val p = pps ?: return
        if (codec != null) return
        val surf = surface ?: return
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(s))
                setByteBuffer("csd-1", ByteBuffer.wrap(p))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(callback)
            c.configure(fmt, surf, null, 0)
            c.start()
            // The surface can be detached while configure/start run; don't keep
            // a codec bound to a dead surface, or the next attach would see
            // codec != null and never start a fresh one.
            if (surface !== surf) {
                c.runCatching { stop(); release() }
                Log.i(TAG, "surface changed during codec start — discarded")
                return
            }
            codec = c
            Log.i(TAG, "codec started (async)")
        } catch (e: Exception) {
            Log.e(TAG, "codec init failed", e)
        }
    }

    /** Codec thread only, like maybeStartCodec, so a start and a stop never interleave. */
    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        c.runCatching { stop(); release() }
    }

    private val callback = object : MediaCodec.Callback() {
        // Every callback checks it still belongs to the live codec: detachSurface()
        // and onSessionEnd() release a codec while its callbacks (and the delayed
        // re-poll below) can still be in flight, and touching a released codec
        // throws IllegalStateException, which crashed the service.
        override fun onInputBufferAvailable(c: MediaCodec, idx: Int) {
            if (c !== codec) return
            val entry = pending.poll()
            if (entry == null) {
                codecHandler.postDelayed({
                    if (c !== codec) return@postDelayed
                    val late = pending.poll()
                    if (late != null) feed(c, idx, late.first, late.second)
                    else runCatching { c.queueInputBuffer(idx, 0, 0, 0, 0) }
                }, 5)
                return
            }
            feed(c, idx, entry.first, entry.second)
            if (fed == 1L || fed == 10L || fed % 300 == 0L) {
                Log.i(TAG, "fed=$fed rendered=$rendered queue=${pending.size}")
            }
        }

        private fun feed(c: MediaCodec, idx: Int, nal: ByteArray, pts: Long) {
            try {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear(); buf.put(nal)
                c.queueInputBuffer(idx, 0, nal.size, pts, 0)
                fed++
            } catch (e: IllegalStateException) {
                Log.w(TAG, "input after codec release, dropped")
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            try {
                c.releaseOutputBuffer(idx, true)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "output after codec release, dropped"); return
            }
            rendered++
            if (rendered == 1L || rendered % 60 == 0L) Log.i(TAG, "rendered $rendered frames")
        }

        override fun onOutputFormatChanged(c: MediaCodec, fmt: MediaFormat) {
            Log.i(TAG, "output format: $fmt")
            if (c !== codec) return
            val (w, h) = displaySize(fmt)
            if (w > 0 && h > 0) onVideoSize(w, h)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error: ${e.diagnosticInfo}", e)
        }
    }

    /** Width/height after the crop rect (the decoder's buffer is often larger than the picture). */
    private fun displaySize(fmt: MediaFormat): Pair<Int, Int> {
        fun int(key: String) = if (fmt.containsKey(key)) fmt.getInteger(key) else -1
        val l = int("crop-left"); val r = int("crop-right")
        val t = int("crop-top"); val b = int("crop-bottom")
        return if (l >= 0 && r >= l && t >= 0 && b >= t) (r - l + 1) to (b - t + 1)
        else int(MediaFormat.KEY_WIDTH) to int(MediaFormat.KEY_HEIGHT)
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val DETACH_WAIT_MS = 500L
    }
}
