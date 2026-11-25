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
import android.widget.FrameLayout
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

internal const val BROWSER_FRAME_LIMIT = 512 * 1024

internal data class BrowserOffer(
    val id: String, val width: Int, val height: Int, val mime: String,
) {
    companion object {
        fun parse(json: JSONObject): BrowserOffer? {
            val id = json.optString("id")
            val canonical = runCatching { UUID.fromString(id).toString() }.getOrNull()
            val site = json.optString("site")
            val width = json.optInt("width")
            val height = json.optInt("height")
            val mime = when (json.optString("codec", "vp8")) {
                "vp8" -> MediaFormat.MIMETYPE_VIDEO_VP8
                "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
                else -> return null
            }
            val volume = json.optInt("volume", -1)
            if (id != canonical || site !in setOf("youtube", "kick", "opera") ||
                minOf(width, height) !in 2..1080 || maxOf(width, height) > 1920 ||
                width % 2 != 0 || height % 2 != 0 || volume !in 0..100) return null
            return BrowserOffer(id, width, height, mime)
        }
    }
}

internal class BrowserVideoPlayer(
    context: Context,
    val offer: BrowserOffer,
    failed: () -> Unit,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = VideoDecoder(offer.width, offer.height, offer.mime) {
        handler.post { failed() }
    }

    private val picture = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { decoder.surface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { decoder.surface(null) }
        })
    }

    init {
        contentDescription = "Opera videosu"
        addView(picture)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val scale = minOf(width.toDouble() / offer.width, height.toDouble() / offer.height)
        val pictureWidth = (offer.width * scale).roundToInt()
        val pictureHeight = (offer.height * scale).roundToInt()
        picture.measure(MeasureSpec.makeMeasureSpec(pictureWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pictureHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - picture.measuredWidth) / 2
        val y = (height - picture.measuredHeight) / 2
        picture.layout(x, y, x + picture.measuredWidth, y + picture.measuredHeight)
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
    private val mime: String,
    private val failed: () -> Unit,
) {
    private val thread = HandlerThread("DeckVideo").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = ArrayDeque<ByteString>()
    private val inputs = ArrayDeque<Int>()
    private var needsKey = true
    private var drainPosted = false
    private var codec: MediaCodec? = null
    private var previousPresentationUs: Long? = null
    private var previousRenderNs = 0L
    @Volatile private var closed = false

    fun enqueue(frame: ByteString) {
        if (closed || frame.size !in 10..BROWSER_FRAME_LIMIT || frame[0].toInt() !in 0..1) return
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
            val next = MediaCodec.createDecoderByType(mime)
            codec = next
            next.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(source: MediaCodec, index: Int) {
                    if (codec === source) { inputs.addLast(index); drain() }
                }
                override fun onOutputBufferAvailable(source: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (codec === source) runCatching {
                        source.releaseOutputBuffer(index, renderTime(info.presentationTimeUs))
                    }
                }
                override fun onError(source: MediaCodec, error: MediaCodec.CodecException) {
                    if (codec === source) { clearCodec(); failed() }
                }
                override fun onOutputFormatChanged(source: MediaCodec, format: MediaFormat) = Unit
            }, handler)
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BROWSER_FRAME_LIMIT)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            next.configure(format, output, null, 0)
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

    private fun renderTime(presentationUs: Long): Long {
        val now = System.nanoTime()
        val gapUs = previousPresentationUs?.let { presentationUs - it }
        val planned = gapUs?.takeIf { it in 1..250_000 }?.let { previousRenderNs + it * 1_000 }
        val renderAt = if (planned == null || planned !in now - 8_000_000..now + 42_000_000) {
            now + 22_000_000
        } else {
            maxOf(now, planned)
        }
        previousPresentationUs = presentationUs
        previousRenderNs = renderAt
        return renderAt
    }

    private fun clearCodec() {
        val old = codec
        codec = null
        previousPresentationUs = null
        previousRenderNs = 0L
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
