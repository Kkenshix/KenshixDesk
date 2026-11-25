package com.kenshix.deck

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import kotlin.math.roundToInt
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
    private val settings = activity.getSharedPreferences("mixer", android.content.Context.MODE_PRIVATE)
    private val pinned = settings.getStringSet("pinned", emptySet()).orEmpty().toMutableSet()
    private var current = emptyList<JSONObject>()

    private inner class Level(val id: String, label: String) {
        private val value = TextView(activity).apply { setTextColor(accent); textSize = 16f }
        private var muted = false
        private var dragging = false
        private var sending = false
        private var pending: Int? = null
        private val slider = object : SeekBar(activity) {
            private var pointer = MotionEvent.INVALID_POINTER_ID

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isEnabled) return false
                        parent.requestDisallowInterceptTouchEvent(true)
                        pointer = event.getPointerId(0)
                        dragging = true
                        isPressed = true
                        setTouchPosition(event.x)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(pointer)
                        if (index >= 0) setTouchPosition(event.getX(index))
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == pointer) {
                            setTouchPosition(event.getX(event.actionIndex))
                            endTouch()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pointer != MotionEvent.INVALID_POINTER_ID) {
                            setTouchPosition(event.x)
                            performClick()
                            endTouch()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> endTouch()
                }
                return true
            }

            private fun setTouchPosition(x: Float) {
                val track = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                val fraction = ((x - paddingLeft) / track).coerceIn(0f, 1f)
                val percent = (fraction * 100).roundToInt()
                if (progress != percent || value.text.isEmpty()) {
                    progress = percent
                    change(percent)
                }
            }

            private fun endTouch() {
                pointer = MotionEvent.INVALID_POINTER_ID
                isPressed = false
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                flush()
            }

            override fun performClick(): Boolean = super.performClick()
        }.apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, percent: Int, fromUser: Boolean) {
                    if (fromUser) change(percent)
                }
            })
        }

        private fun change(percent: Int) {
            value.text = "$percent%"
            pending = percent
            flush()
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
                        text = if (pinned.contains(this@Level.id)) "★  $label" else label
                        setOnLongClickListener {
                            if (!pinned.remove(this@Level.id)) pinned.add(this@Level.id)
                            settings.edit().putStringSet("pinned", pinned.toSet()).apply()
                            levels.clear()
                            read()
                            true
                        }
                        textSize = 16f
                        setTextColor(ink)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }, LayoutParams(0, -2, 1f))
                    addView(value)
                })
                addView(slider, LayoutParams(-1, dp(48)))
                addView(mute, LayoutParams(-2, dp(44)).apply { gravity = Gravity.END })
            }, LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }

        fun render(record: JSONObject) {
            if (busy()) return
            val percent = record.getInt("percent")
            if (slider.progress != percent) slider.progress = percent
            value.text = if (record.optBoolean("mixed")) "Oturum seviyeleri farklı" else "$percent%"
            muted = record.getBoolean("muted")
            mute.text = if (muted) "Sesi aç" else "Sessize al"
        }

        fun busy() = dragging || sending || pending != null

        fun ownsTouch(x: Int, y: Int): Boolean {
            val bounds = Rect()
            return slider.isShown && slider.getGlobalVisibleRect(bounds) && bounds.contains(x, y)
        }

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
        addView(LinearLayout(activity).apply {
            addView(DeckUi.action(activity, "Ses çıkışı") { pickOutput() })
            addView(DeckUi.action(activity, "Ses profilleri") { profiles() },
                LayoutParams(-2,dp(48)).apply { marginStart = dp(6) })
        }, LayoutParams(-1,-2).apply { bottomMargin = dp(7) })
        rows.orientation = VERTICAL
        addView(ScrollView(activity).apply { addView(rows) }, LayoutParams(-1, 0, 1f))
    }

    fun ownsSliderTouch(x: Float, y: Float) =
        levels.values.any { it.ownsTouch(x.toInt(), y.toInt()) }

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
                current = (0 until records.length()).map(records::getJSONObject)
                val items = current.sortedBy { level ->
                    val id = level.getString("id")
                    if (id == "master") 0 else if (id == "microphone") 1 else if (id in pinned) 2 else 3
                }
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

    private fun pickOutput() {
        link.request(message("audio_outputs")) { response ->
            handler.post {
                if (!active) return@post
                val values = response?.optJSONArray("outputs")
                if (values == null || values.length() == 0) { notice("Ses çıkışları bulunamadı"); return@post }
                val outputs = (0 until values.length()).map(values::getJSONObject)
                DeckUi.choices(DeckUi.dialog(activity).setTitle("Windows ses çıkışı"), outputs.map {
                    it.optString("name") + if (it.optBoolean("active")) " · Kullanılıyor" else ""
                }) { index ->
                    if (!outputs[index].optBoolean("active")) runSetting(message("select_audio").put("output", outputs[index].getString("id")))
                }.setNegativeButton("Kapat", null).showDeck()
            }
        }
    }

    private fun profiles() {
        val saved = runCatching { JSONObject(settings.getString("profiles","{}").orEmpty()) }.getOrDefault(JSONObject())
        val names = saved.keys().asSequence().toList().sorted()
        DeckUi.choices(DeckUi.dialog(activity).setTitle("Ses profilleri"), listOf("Mevcut seviyeleri kaydet") + names) { index ->
            if (index == 0) {
                val values = current.filter { it.optString("id").startsWith("app:") }.take(40)
                if (values.isEmpty()) { notice("Kaydetmek için bir uygulamada ses çalmalı"); return@choices }
                val input = android.widget.EditText(activity).apply { DeckUi.field(this); hint = "Profil adı"; isSingleLine = true }
                DeckUi.dialog(activity).setTitle("Ses profili kaydet")
                    .setView(DeckUi.editor(activity).apply { addView(input) }).setNegativeButton("Vazgeç",null)
                    .setPositiveButton("Kaydet") { _, _ ->
                        val name = input.text.toString().trim().take(50)
                        if (name.isNotEmpty() && (name in names || names.size < 24)) {
                            val levels = org.json.JSONArray()
                            values.forEach { levels.put(JSONObject().put("id",it.getString("id")).put("percent",it.getInt("percent"))) }
                            saved.put(name, levels)
                            settings.edit().putString("profiles",saved.toString()).apply()
                        }
                    }.showDeck()
            } else {
                val name = names[index - 1]
                DeckUi.dialog(activity).setTitle(name).setNegativeButton("Kapat",null)
                    .setNeutralButton("Sil") { _, _ -> saved.remove(name); settings.edit().putString("profiles",saved.toString()).apply() }
                    .setPositiveButton("Uygula") { _, _ -> runSetting(message("apply_audio_preset").put("levels",saved.getJSONArray(name))) }
                    .showDeck()
            }
        }.setNegativeButton("Kapat",null).showDeck()
    }

    private fun runSetting(request: JSONObject) {
        val id = UUID.randomUUID().toString()
        link.request(request.put("operation",id)) { reply ->
            handler.post {
                if (!active) return@post
                if (reply?.optString("operation") == id && reply.optString("status") == "ok") {
                    notice("Ses ayarı uygulandı")
                    read()
                } else notice("Windows ses ayarını uygulayamadı")
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
