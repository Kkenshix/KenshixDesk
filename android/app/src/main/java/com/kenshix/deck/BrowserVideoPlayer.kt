package com.kenshix.deck

import android.content.Context
import android.content.res.ColorStateList
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import okio.ByteString
import org.json.JSONObject
import java.util.UUID

internal data class BrowserOffer(
    val id: String, val site: String, val width: Int, val height: Int, val volume: Int,
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
            return BrowserOffer(id, site, width, height, volume)
        }
    }
}

internal class BrowserVideoPlayer(
    context: Context,
    val offer: BrowserOffer,
    close: () -> Unit,
    private val setVolume: (Int) -> Unit,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val service = when (offer.site) {
        "youtube" -> "YouTube"
        "kick" -> "Kick"
        else -> "Opera"
    }
    private val summary = DeckUi.label(context, service, 13, DeckUi.muted, 550)
    private var dragging = false
    private val decoder = VideoDecoder(offer.width, offer.height) {
        handler.post { summary.text = "Görüntü çözülemedi" }
    }

    private val sendVolume: Runnable = Runnable { setVolume(volume.progress) }
    private val volume: SeekBar = SeekBar(context).apply {
        contentDescription = "PC video sesi"
        max = 100
        progress = offer.volume
        progressTintList = ColorStateList.valueOf(DeckUi.text)
        thumbTintList = ColorStateList.valueOf(DeckUi.text)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    handler.removeCallbacks(sendVolume)
                    handler.postDelayed(sendVolume, 90)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                handler.removeCallbacks(sendVolume)
                sendVolume.run()
            }
        })
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(DeckUi.BLACK)
        addView(object : FrameLayout(context) {
            private val video = SurfaceView(context).also { child ->
                addView(child)
                child.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { decoder.surface(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) { decoder.surface(null) }
                })
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val height = MeasureSpec.getSize(heightMeasureSpec)
                val ratio = minOf(width.toDouble() / offer.width, height.toDouble() / offer.height)
                video.measure(MeasureSpec.makeMeasureSpec((offer.width * ratio).toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((offer.height * ratio).toInt(), MeasureSpec.EXACTLY))
                setMeasuredDimension(width, height)
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val x = (right - left - video.measuredWidth) / 2
                val y = (bottom - top - video.measuredHeight) / 2
                video.layout(x, y, x + video.measuredWidth, y + video.measuredHeight)
            }
        }, LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = DeckUi.shape(context, DeckUi.panel, 8)
            addView(DeckUi.action(context, "Kapat") { close() }, LayoutParams(-2, dp(48)))
            addView(summary, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(DeckUi.label(context, "PC sesi", 12, DeckUi.muted, 550))
            addView(volume, LayoutParams(dp(135), dp(48)))
        }, LayoutParams(-1, dp(50)))
    }

    fun accept(frame: ByteString) = decoder.enqueue(frame)

    fun updateVolume(percent: Int) {
        if (!dragging && percent in 0..100) volume.progress = percent
    }

    fun updatePaused(paused: Boolean) {
        summary.text = if (paused) "$service · PC’de duraklatıldı" else service
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        decoder.close()
    }

    private fun dp(value: Int) = DeckUi.dp(context, value)
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
                override fun onOutputFormatChanged(source: MediaCodec, format: MediaFormat) = Unit
            }, handler)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
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
