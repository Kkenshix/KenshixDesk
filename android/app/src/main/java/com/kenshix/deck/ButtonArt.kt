package com.kenshix.deck

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class ButtonArt(private val link: DeckLink) {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = object : android.util.LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val pending = mutableMapOf<String, MutableList<(ByteArray?) -> Unit>>()

    fun tile(context: Context, id: String, playing: () -> Boolean): MediaTile {
        val view = MediaTile(context, playing)
        if (!id.matches(Regex("[a-f0-9]{64}"))) return view
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                load(id) { data ->
                    if (!view.isAttachedToWindow) return@load
                    if (data == null) { view.showStatus("Medya alınamadı"); return@load }
                    if (isVideo(data)) {
                        Thread({
                            val file = runCatching {
                                val file = File(context.cacheDir, "deck-media-$id.mp4")
                                if (file.length() != data.size.toLong()) {
                                    val part = File.createTempFile("deck-media-", ".part", context.cacheDir)
                                    try {
                                        part.writeBytes(data)
                                        check(part.renameTo(file))
                                    } finally { part.delete() }
                                }
                                val videos = context.cacheDir.listFiles { name -> name.name.startsWith("deck-media-") && name.name.endsWith(".mp4") }
                                    .orEmpty().filter { it != file }.sortedByDescending(File::lastModified)
                                videos.drop(7).forEach(File::delete)
                                file
                            }.getOrNull()
                            handler.post { if (view.isAttachedToWindow && file != null) view.bindVideo(file) }
                        }, "deck-video-cache").start()
                    } else view.bindImage(data)
                }
            }
            override fun onViewDetachedFromWindow(v: View) = view.release()
        })
        return view
    }

    private fun isVideo(data: ByteArray) =
        data.size >= 12 && data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()

    private fun load(id: String, done: (ByteArray?) -> Unit) {
        cache.get(id)?.let { done(it); return }
        pending[id]?.let { it.add(done); return }
        pending[id] = mutableListOf(done)
        val bytes = ByteArrayOutputStream()
        fun finish(value: ByteArray?) {
            if (value != null) cache.put(id, value)
            pending.remove(id)?.forEach { it(value) }
        }
        fun request() {
            val offset = bytes.size()
            link.request(message("button_art").put("id", id).put("offset", offset)) { response ->
                handler.post {
                    val total = response?.optInt("total") ?: 0
                    val part = runCatching { Base64.decode(response?.optString("data"), Base64.DEFAULT) }.getOrNull()
                    if (response?.optString("id") != id || response.optInt("offset", -1) != offset ||
                        total !in 1..8_388_608 || part == null || part.size != minOf(32768, total - offset)) {
                        finish(null)
                        return@post
                    }
                    bytes.write(part)
                    if (bytes.size() < total) request()
                    else {
                        val data = bytes.toByteArray()
                        val actual = MessageDigest.getInstance("SHA-256").digest(data)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        finish(data.takeIf { actual == id })
                    }
                }
            }
        }
        request()
    }
}

internal class MediaTile(context: Context, private val playing: () -> Boolean) : FrameLayout(context) {
    private var animation: Animatable? = null
    private var video: VideoView? = null
    private var prepared = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = DeckUi.shape(context, Color.TRANSPARENT, 8, Color.TRANSPARENT)
        clipToOutline = true
        showStatus("Medya yükleniyor")
    }

    fun showStatus(message: String) {
        removeAllViews()
        addView(DeckUi.label(context, message, 11, DeckUi.muted).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -1))
    }

    fun bindImage(bytes: ByteArray) {
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val factor = minOf(1f, 384f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1),
                    (info.size.height * factor).toInt().coerceAtLeast(1))
            }
        }.getOrNull() ?: run { showStatus("Görsel açılamadı"); return }
        release()
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(drawable)
        }, LayoutParams(-1, -1))
        animation = drawable as? Animatable
        update()
    }

    fun bindVideo(file: File) {
        release()
        val view = object : VideoView(context) {
            override fun onTouchEvent(event: MotionEvent) = false
        }.apply {
            isFocusable = false
            setOnPreparedListener { player: MediaPlayer ->
                prepared = true
                player.isLooping = true
                player.setVolume(0f, 0f)
                update()
            }
            setOnErrorListener { _, _, _ ->
                prepared = false
                visibility = GONE
                showStatus("Video açılamadı")
                true
            }
        }
        video = view
        addView(view, LayoutParams(-1, -1, Gravity.CENTER))
        view.setVideoPath(file.absolutePath)
        update()
    }

    fun update() {
        val visible = playing() && isShown && getGlobalVisibleRect(Rect())
        animation?.let { if (visible && !it.isRunning) it.start() else if (!visible) it.stop() }
        if (prepared) video?.let {
            if (visible && !it.isPlaying) it.start()
            else if (!visible && it.isPlaying) it.pause()
        }
    }

    fun release() {
        animation?.stop()
        animation = null
        video?.stopPlayback()
        video = null
        prepared = false
        removeAllViews()
    }
}
