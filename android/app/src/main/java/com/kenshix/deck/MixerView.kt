package com.kenshix.deck

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

internal class MixerView(
    private val activity: Activity,
    private val link: DeckLink,
    exit: () -> Unit,
    private val notice: (String) -> Unit,
) : LinearLayout(activity) {
    private val accent = DeckUi.accent
    private val ink = DeckUi.text
    private val secondary = DeckUi.muted
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(activity)
    private val rows = LinearLayout(activity)
    private val levels = linkedMapOf<String, Level>()
    private var active = false
    private var reading = false

    private inner class Level(val id: String, label: String) {
        private val value = TextView(activity).apply { setTextColor(accent); textSize = 16f }
        private var muted = false
        private var dragging = false
        private var sending = false
        private var pending: Int? = null
        private val slider = SeekBar(activity).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
                override fun onProgressChanged(bar: SeekBar, percent: Int, fromUser: Boolean) {
                    if (fromUser) {
                        value.text = percent.toString() + "%"
                        pending = percent
                        flush()
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    pending = bar.progress
                    flush()
                }
            })
        }
        private val mute = DeckUi.action(activity, "Sessize al") {
            if (!sending) send(message("set_mute").put("muted", !muted))
        }

        init {
            rows.addView(LinearLayout(activity).apply {
                orientation = VERTICAL
                background = DeckUi.shape(activity, radius = 20)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(activity).apply {
                        text = label
                        textSize = 16f
                        setTextColor(ink)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }, LayoutParams(0, -2, 1f))
                    addView(value)
                })
                addView(slider)
                addView(mute, LayoutParams(-2, dp(44)).apply { gravity = Gravity.END })
            }, LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }

        fun render(record: JSONObject) {
            if (busy()) return
            val percent = record.getInt("percent")
            if (slider.progress != percent) slider.setProgress(percent, android.animation.ValueAnimator.areAnimatorsEnabled())
            value.text = if (record.optBoolean("mixed")) "Oturum seviyeleri farklı" else "$percent%"
            muted = record.getBoolean("muted")
            mute.text = if (muted) "Sesi aç" else "Sessize al"
        }

        fun busy() = dragging || sending || pending != null

        private fun flush() {
            val percent = pending ?: return
            if (sending || !active) return
            pending = null
            send(message("set_volume").put("percent", percent))
        }

        private fun send(request: JSONObject) {
            if (!active) return
            sending = true
            val operation = UUID.randomUUID().toString()
            link.request(request.put("target", id).put("operation", operation)) { response ->
                handler.post {
                    if (!active) return@post
                    sending = false
                    if (response?.optString("operation") != operation || response.optString("status") != "ok") {
                        pending = null
                        notice("Ses değişikliği doğrulanamadı.")
                        read()
                    } else if (pending != null) {
                        handler.postDelayed({ flush() }, 45)
                    } else if (!dragging) {
                        read()
                    }
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            read()
            handler.postDelayed(this, 1300)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(8))
        addView(DeckUi.screenHeading(activity, "Ses Mikseri", exit))
        status.text = "Ses aygıtı okunuyor"
        status.setPadding(dp(12), dp(9), dp(12), dp(9))
        status.setTextColor(secondary)
        addView(status)
        rows.orientation = VERTICAL
        addView(ScrollView(activity).apply { addView(rows) }, LayoutParams(-1, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = true
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        active = false
        reading = false
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun read() {
        if (!active || reading || levels.values.any { it.busy() }) return
        reading = true
        link.request(message("mixer")) { response ->
            handler.post {
                if (!active) return@post
                reading = false
                val records = response?.optJSONArray("levels")
                if (records == null) {
                    status.visibility = android.view.View.VISIBLE
                    status.text = "Ses aygıtı okunamadı"
                    return@post
                }
                if (levels.values.any { it.busy() }) return@post
                status.visibility = android.view.View.GONE
                val items = (0 until records.length()).map(records::getJSONObject)
                if (levels.keys.toList() != items.map { it.getString("id") }) {
                    levels.clear()
                    rows.removeAllViews()
                    items.forEach { levels[it.getString("id")] = Level(it.getString("id"), it.getString("label")) }
                    DeckUi.appear(rows, 4)
                }
                items.forEach { levels[it.getString("id")]?.render(it) }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
