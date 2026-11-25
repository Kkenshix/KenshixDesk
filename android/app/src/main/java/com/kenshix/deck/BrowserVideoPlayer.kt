package com.kenshix.deck

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import okio.ByteString
import org.json.JSONObject
import java.util.UUID

internal data class BrowserOffer(
    val id: String, val width: Int, val height: Int,
) {
    companion object {
        fun parse(json: JSONObject): BrowserOffer? {
            val id = json.optString("id")
            val canonical = runCatching { UUID.fromString(id).toString() }.getOrNull()
            val site = json.optString("site")
            val width = json.optInt("width")
            val height = json.optInt("height")
            val volume = json.optInt("volume", -1)
            if (id != canonical || site !in setOf("youtube", "kick", "opera") || width !in 2..960 ||
                height !in 2..540 || width % 2 != 0 || height % 2 != 0 || volume !in 0..100) return null
            return BrowserOffer(id, width, height)
        }
    }
}

internal class BrowserVideoPlayer(
    context: Context,
    val offer: BrowserOffer,
    failed: () -> Unit,
) : SurfaceView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = VideoDecoder(offer.width, offer.height) {
        handler.post { failed() }
    }

    init {
        contentDescription = "Opera videosu"
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { decoder.surface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { decoder.surface(null) }
        })
    }

    fun accept(frame: ByteString) = decoder.enqueue(frame)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        decoder.close()
    }

}

private class VideoDecoder(
    private val width: Int,
    private val height: Int,
    private val failed: () -> Unit,
) {
    private val thread = HandlerThread("DeckVideo").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = ArrayDeque<ByteString>()
    private val inputs = ArrayDeque<Int>()
    private var needsKey = true
    private var drainPosted = false
    private var codec: MediaCodec? = null
    @Volatile private var closed = false

    fun enqueue(frame: ByteString) {
        if (closed || frame.size !in 10..(256 * 1024) || frame[0].toInt() !in 0..1) return
        synchronized(frames) {
            if (frames.size >= 12) {
                frames.clear()
                needsKey = true
            }
            if (needsKey && frame[0].toInt() != 1) return
            needsKey = false
            frames.addLast(frame)
            if (drainPosted) return
            drainPosted = true
        }
        handler.post {
            synchronized(frames) { drainPosted = false }
            drain()
        }
    }

    fun surface(output: Surface?) = handler.post {
        if (closed) return@post
        val previous = codec != null
        clearCodec()
        if (previous) synchronized(frames) { frames.clear(); needsKey = true }
        if (output?.isValid != true) return@post
        runCatching {
            val next = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP8)
            codec = next
            next.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(source: MediaCodec, index: Int) {
                    if (codec === source) { inputs.addLast(index); drain() }
                }
                override fun onOutputBufferAvailable(source: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (codec === source) runCatching { source.releaseOutputBuffer(index, System.nanoTime()) }
                }
                override fun onError(source: MediaCodec, error: MediaCodec.CodecException) {
                    if (codec === source) { clearCodec(); failed() }
                }
                override fun onOutputFormatChanged(source: MediaCodec, format: MediaFormat) {
                    if (codec === source) source.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                }
            }, handler)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            next.configure(format, output, null, 0)
            next.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            next.start()
        }.onFailure { clearCodec(); failed() }
    }

    private fun drain() {
        val active = codec ?: return
        if (closed) return
        while (inputs.isNotEmpty()) {
            val frame = synchronized(frames) { frames.removeFirstOrNull() } ?: return
            val index = inputs.removeFirst()
            runCatching {
                val bytes = frame.toByteArray()
                var time = 0L
                for (i in 1..8) time = (time shl 8) or (bytes[i].toLong() and 255)
                val buffer = checkNotNull(active.getInputBuffer(index))
                buffer.clear()
                buffer.put(bytes, 9, bytes.size - 9)
                active.queueInputBuffer(index, 0, bytes.size - 9, time,
                    if (bytes[0].toInt() == 1) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }.onFailure {
                clearCodec()
                failed()
                return
            }
        }
    }

    private fun clearCodec() {
        val old = codec
        codec = null
        inputs.clear()
        if (old != null) runCatching { old.release() }
    }

    fun close() {
        if (closed) return
        closed = true
        handler.post {
            clearCodec()
            synchronized(frames) { frames.clear() }
            thread.quitSafely()
        }
    }
}
