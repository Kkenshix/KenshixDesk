package com.kenshix.deck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal class StandbyView(context: Context, private val link: DeckLink) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = DeckUi.label(context, "", 64, DeckUi.text, 620).apply { gravity = Gravity.CENTER }
    private val date = DeckUi.label(context, "", 17, DeckUi.muted, 500).apply { gravity = Gravity.CENTER }
    private val status = DeckUi.label(context, "PC verileri alınıyor", 12, DeckUi.muted, 500)
    private val cpu = Gauge(context, "CPU")
    private val gpu = Gauge(context, "GPU")
    private val ram = Gauge(context, "RAM")
    private val instruments = LinearLayout(context)
    private val content = LinearLayout(context)
    private val local = Locale.forLanguageTag("tr-TR")
    private val moment = DateFormat.getTimeInstance(DateFormat.SHORT, local)
    private val day = DateFormat.getDateInstance(DateFormat.FULL, local)
    private var lastSample = 0L
    private var minute = -1L

    private val clockUpdate = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = moment.format(now)
            date.text = day.format(now)
            val currentMinute = now.time / 60_000
            if (minute != currentMinute) {
                minute = currentMinute
                val offsets = listOf(0, 7, -6, 4, -4)
                content.translationX = DeckUi.dp(context, offsets[(minute % offsets.size).toInt()]).toFloat()
                content.translationY = DeckUi.dp(context, offsets[((minute + 2) % offsets.size).toInt()]).toFloat()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            link.request(message("metrics")) { response ->
                handler.post {
                    if (!isAttachedToWindow) return@post
                    val report = response?.optJSONObject("measurements")
                    val now = SystemClock.elapsedRealtime()
                    if (report != null) {
                        lastSample = now
                        showMetrics(report)
                        status.text = "PC · Canlı"
                        status.setTextColor(DeckUi.success)
                    } else {
                        status.setTextColor(DeckUi.muted)
                        status.text = if (lastSample > 0 && now - lastSample < 8000) "Son ölçüm gösteriliyor" else "PC bağlantısı bekleniyor"
                        if (lastSample == 0L || now - lastSample >= 8000) showMetrics(null)
                    }
                    handler.postDelayed(this, if (report == null) 900 else 2300)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(DeckUi.BLACK)
        content.orientation = VERTICAL
        content.gravity = Gravity.CENTER
        val brand = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(DeckUi.dragon(context), LayoutParams(DeckUi.dp(context, 62), DeckUi.dp(context, 62)))
            addView(DeckUi.label(context, "KenshixDeck", 17, DeckUi.text, 700).apply {
                gravity = Gravity.CENTER
            }, LayoutParams(-2, -2).apply { topMargin = DeckUi.dp(context, 7) })
        }
        val separator = android.view.View(context).apply { setBackgroundColor(DeckUi.border) }
        val time = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(clock, LayoutParams(-1, -2))
            addView(date, LayoutParams(-1, -2).apply { topMargin = DeckUi.dp(context, 7) })
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(brand, LayoutParams(DeckUi.dp(context, 123), -2))
            addView(separator, LayoutParams(DeckUi.dp(context, 1), DeckUi.dp(context, 66)).apply {
                marginStart = DeckUi.dp(context, 12)
                marginEnd = DeckUi.dp(context, 19)
            })
            addView(time, LayoutParams(DeckUi.dp(context, 238), -2))
        }, LayoutParams(-2, -2))
        instruments.gravity = Gravity.CENTER
        for (meter in listOf(cpu, gpu, ram)) {
            instruments.addView(meter.view, LayoutParams(DeckUi.dp(context, 120), -2).apply {
                marginStart = DeckUi.dp(context, 5)
                marginEnd = DeckUi.dp(context, 5)
            })
        }
        content.addView(instruments, LayoutParams(-2, -2).apply { topMargin = DeckUi.dp(context, 22) })
        content.addView(status, LayoutParams(-2, -2).apply { topMargin = DeckUi.dp(context, 14) })
        addView(content, LayoutParams(-2, -2))
        showMetrics(null)
    }

    private fun showMetrics(report: JSONObject?) {
        cpu.render(report?.optInt("cpu_percent", -1))
        gpu.render(report?.optInt("gpu_percent", -1))
        val memory = report?.optJSONObject("memory")?.let {
            val total = it.optLong("total")
            if (total > 0) (100.0 * it.optLong("used") / total).toInt() else null
        }
        ram.render(memory)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(clockUpdate)
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private class Gauge(context: Context, private val title: String) {
        val view = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.START
            setPadding(DeckUi.dp(context, 13), DeckUi.dp(context, 8), DeckUi.dp(context, 8), DeckUi.dp(context, 8))
            background = DeckUi.shape(context, DeckUi.BLACK, 12)
            addView(DeckUi.label(context, title, 12, DeckUi.muted, 600))
        }
        private val value: TextView = DeckUi.label(context, "—", 24, DeckUi.accent, 650)
        init { view.addView(value) }
        fun render(reading: Int?) {
            val valid = reading?.takeIf { it in 0..100 }
            val text = valid?.let { "$it%" } ?: "—"
            if (value.text == text && value.contentDescription != null) return
            value.text = text
            value.contentDescription = if (valid == null) "$title verisi yok" else "$title yüzde $valid"
        }
    }
}
