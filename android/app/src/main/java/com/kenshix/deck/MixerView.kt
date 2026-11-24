package com.kenshix.deck

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Button
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
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(activity)
    private val rows = LinearLayout(activity)
    private val levels = linkedMapOf<String, Level>()
    private var active = false
    private var reading = false

    private inner class Level(val id: String, label: String) {
        private val value = TextView(activity)
        private var muted = false
        private var dragging = false
        private var sending = false
        private var pending: Int? = null
        private val slider = SeekBar(activity).apply {
            max = 100
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
        private val mute = Button(activity).apply {
            isAllCaps = false
            setOnClickListener {
                if (!sending) send(message("set_mute").put("muted", !muted))
            }
        }

        init {
            rows.addView(LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                addView(TextView(activity).apply {
                    text = label
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(value)
                addView(slider)
                addView(mute)
            })
        }

        fun render(record: JSONObject) {
            if (busy()) return
            slider.progress = record.getInt("percent")
            value.text = if (record.optBoolean("mixed")) "Oturum seviyeleri farklı" else slider.progress.toString() + "%"
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
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(LinearLayout(activity).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(Button(activity).apply {
                text = "‹"
                contentDescription = "Geri"
                setOnClickListener { exit() }
            }, LayoutParams(dp(56), dp(48)))
            addView(TextView(activity).apply {
                text = "Ses Mikseri"
                textSize = 22f
                setPadding(dp(12), 0, 0, 0)
            })
        })
        status.text = "Ses aygıtı okunuyor"
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
                    status.text = "Ses aygıtı okunamadı"
                    return@post
                }
                if (levels.values.any { it.busy() }) return@post
                status.text = ""
                val items = (0 until records.length()).map(records::getJSONObject)
                if (levels.keys.toList() != items.map { it.getString("id") }) {
                    levels.clear()
                    rows.removeAllViews()
                    items.forEach { levels[it.getString("id")] = Level(it.getString("id"), it.getString("label")) }
                }
                items.forEach { levels[it.getString("id")]?.render(it) }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
