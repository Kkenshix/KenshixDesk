package com.kenshix.deck

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class ButtonArt(private val link: DeckLink) {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = object : android.util.LruCache<String, ByteArray>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val pending = mutableMapOf<String, MutableList<(ByteArray?) -> Unit>>()

    fun show(view: ImageView, id: String, active: () -> Boolean) {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return
        fun bind(bytes: ByteArray?) {
            if (bytes == null || !view.isAttachedToWindow) return
            val drawable: Drawable = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val factor = minOf(1f, 240f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1),
                        (info.size.height * factor).toInt().coerceAtLeast(1))
                }
            }.getOrNull() ?: return
            view.setImageDrawable(drawable)
            update(view, active)
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = load(id, ::bind)
            override fun onViewDetachedFromWindow(v: android.view.View) {
                (view.drawable as? Animatable)?.stop()
            }
        })
        if (view.isAttachedToWindow) load(id, ::bind)
    }

    fun update(view: ImageView, active: () -> Boolean) {
        val animation = view.drawable as? Animatable ?: return
        val rectangle = android.graphics.Rect()
        if (active() && view.isShown && view.getGlobalVisibleRect(rectangle)) animation.start()
        else animation.stop()
    }

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
                        total !in 1..524288 || part == null || part.size != minOf(32768, total - offset)) {
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
