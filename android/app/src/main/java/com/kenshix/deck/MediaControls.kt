package com.kenshix.deck

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import org.json.JSONObject
import java.util.UUID

internal class MediaControls(
    context: Context,
    private val link: DeckLink,
    private val videoOnly: Boolean = false,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var items = emptyList<JSONObject>()
    private var selectedId: String? = null
    private var volumeTarget: String? = null
    private var dragging = false
    private var volumeInFlight = false
    private var pendingVolume: Pair<String, Int>? = null
    private var commandInFlight = false
    private var reading = false

    private val title = DeckUi.label(context, "", if (videoOnly) 26 else 16, DeckUi.text, 650).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val source = DeckUi.label(context, "", 12, DeckUi.muted, 500).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val feedback = DeckUi.label(context, "", 12, DeckUi.muted, 500).apply { visibility = GONE }
    private val audioLabel = DeckUi.label(context, "Ses", 12, DeckUi.muted, 550)
    private val previous = mediaButton("⏮", "Önceki") { command("previous") }
    private val playback = mediaButton("▶", "Oynat") { command(if (item()?.optBoolean("playing") == true) "pause" else "play") }
    private val next = mediaButton("⏭", "Sonraki") { command("next") }
    private val pick = DeckUi.action(context, "Video seç") {
        if (items.size > 1) {
            DeckUi.choices(DeckUi.dialog(context).setTitle("Video seç"), items.map {
                "${it.optString("title")} · ${it.optString("source")}"
            }) { index ->
                selectedId = items.getOrNull(index)?.optString("id")
                render()
            }.setNegativeButton("Kapat", null).showDeck()
        }
    }
    private val volume = SeekBar(context).apply {
        max = 100
        contentDescription = "Medya ses seviyesi"
        progressTintList = ColorStateList.valueOf(DeckUi.text)
        thumbTintList = ColorStateList.valueOf(DeckUi.text)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek: SeekBar) {
                seek.parent.requestDisallowInterceptTouchEvent(true)
                dragging = true
            }
            override fun onStopTrackingTouch(seek: SeekBar) {
                seek.parent.requestDisallowInterceptTouchEvent(false)
                dragging = false
                volumeTarget?.let { pendingVolume = it to seek.progress }
                flushVolume()
            }
            override fun onProgressChanged(seek: SeekBar, percent: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioLabel.text = "${volumeName()} · $percent%"
                    volumeTarget?.let { pendingVolume = it to percent }
                    flushVolume()
                }
            }
        })
    }
    private val poll = Runnable { refresh() }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(7))
        background = DeckUi.shape(context, DeckUi.BLACK, 14)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val description = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(title)
                addView(source, LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            addView(description, LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
            if (videoOnly) addView(pick, LayoutParams(-2, dp(48)))
            for (control in listOf(previous, playback, next)) {
                addView(control, LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) })
            }
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(audioLabel, LayoutParams(-2, -2))
            addView(volume, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(7) })
        })
        addView(feedback)
        render()
    }

    fun ownsTouch(rawX: Float, rawY: Float): Boolean {
        val bounds = Rect()
        return listOf(previous, playback, next, volume).any { control ->
            control.isEnabled && control.isShown && control.getGlobalVisibleRect(bounds) &&
                bounds.contains(rawX.toInt(), rawY.toInt())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        if (reading || !isAttachedToWindow) return
        reading = true
        link.request(message("media")) { result ->
            handler.post {
                reading = false
                if (!isAttachedToWindow) return@post
                val sessions = result?.optJSONArray("sessions")
                items = if (sessions == null) emptyList() else (0 until sessions.length()).mapNotNull {
                    sessions.optJSONObject(it)?.takeIf { entry -> !videoOnly || entry.optBoolean("video") }
                }
                render()
                handler.postDelayed(poll, if (sessions == null) 1100 else 500)
            }
        }
    }

    private fun render() {
        val item = item()
        title.text = item?.optString("title") ?: if (videoOnly) "Video oynatılmıyor" else "Medya oynatılmıyor"
        source.text = item?.let { listOf(it.optString("source"), it.optString("artist")).filter(String::isNotBlank).joinToString(" · ") }
            ?: if (videoOnly) "Windows’ta bir video aç" else "Spotify veya tarayıcıdan bir parça aç"
        if (videoOnly) pick.visibility = if (items.size > 1) VISIBLE else GONE
        val playing = item?.optBoolean("playing") == true
        playback.text = if (playing) "Ⅱ" else "▶"
        playback.contentDescription = if (playing) "Duraklat" else "Oynat"
        playback.isEnabled = !commandInFlight && item?.optBoolean(if (playing) "pause" else "play") == true
        previous.isEnabled = !commandInFlight && item?.optBoolean("previous") == true
        next.isEnabled = !commandInFlight && item?.optBoolean("next") == true
        if (!dragging && !volumeInFlight && pendingVolume == null) {
            val level = item?.optJSONObject("volume")
            volumeTarget = level?.optString("id")?.takeIf { it.isNotEmpty() }
            volume.isEnabled = volumeTarget != null
            volume.progress = level?.optInt("percent") ?: 0
            audioLabel.text = if (volumeTarget == null) "Ses" else "${volumeName()} · ${volume.progress}%"
        }
    }

    private fun item() = items.firstOrNull { it.optString("id") == selectedId } ?: items.firstOrNull()

    private fun command(name: String) {
        val id = item()?.optString("id") ?: return
        if (commandInFlight) return
        commandInFlight = true
        render()
        send(message("control_media").put("session", id).put("command", name)) { ok ->
            commandInFlight = false
            if (!ok) error("Oynatıcı komutu uygulamadı")
            render()
            handler.removeCallbacks(poll)
            refresh()
        }
    }

    private fun flushVolume() {
        val (target, level) = pendingVolume ?: return
        if (volumeInFlight) return
        pendingVolume = null
        volumeInFlight = true
        send(message("set_volume").put("target", target).put("percent", level)) { ok ->
            volumeInFlight = false
            if (!ok) error("Ses ayarlanamadı")
            if (pendingVolume != null) handler.postDelayed({ flushVolume() }, 80)
            else if (!dragging) {
                handler.removeCallbacks(poll)
                refresh()
            }
        }
    }

    private fun send(request: JSONObject, finished: (Boolean) -> Unit) {
        val operation = UUID.randomUUID().toString()
        link.request(request.put("operation", operation)) { result ->
            handler.post {
                if (isAttachedToWindow) finished(result?.optString("operation") == operation && result.optString("status") == "ok")
            }
        }
    }

    private fun error(message: String) {
        feedback.text = message
        feedback.visibility = VISIBLE
        handler.postDelayed({ if (feedback.text == message) feedback.visibility = GONE }, 2500)
    }

    private fun volumeName() = if (volumeTarget == "master") "Windows sesi" else "Uygulama sesi"
    private fun mediaButton(symbol: String, label: String, action: () -> Unit) =
        DeckUi.action(context, symbol) { action() }.apply {
            contentDescription = label
            textSize = 19f
            setPadding(0, 0, 0, 0)
        }
    private fun dp(value: Int) = DeckUi.dp(context, value)
}

internal class VideoPage(context: Context, link: DeckLink, close: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setPadding(DeckUi.dp(context, 18), DeckUi.dp(context, 9), DeckUi.dp(context, 18), 0)
        addView(DeckUi.screenHeading(context, "Video", close))
        addView(MediaControls(context, link, videoOnly = true), LayoutParams(-1, -2))
    }
}
