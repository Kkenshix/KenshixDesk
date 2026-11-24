package com.kenshix.deck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal class StandbyView(context: Context, private val link: DeckLink) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = DeckUi.label(context, "", 62, DeckUi.text, 600)
    private val date = DeckUi.label(context, "", 19, DeckUi.muted, 500)
    private val readings = DeckUi.label(context, "", 17, DeckUi.accent, 550)
    private val clockUpdate = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
            date.text = DateFormat.getDateInstance(DateFormat.FULL).format(now)
            handler.postDelayed(this, 1000)
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            link.request(message("metrics")) { response ->
                handler.post {
                    if (!isAttachedToWindow) return@post
                    showMetrics(response?.optJSONObject("measurements"))
                    handler.postDelayed(this, 5000)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(DeckUi.BLACK)
        addView(clock)
        addView(date, LayoutParams(-2, -2).apply { topMargin = DeckUi.dp(context, 8) })
        addView(readings, LayoutParams(-2, -2).apply { topMargin = DeckUi.dp(context, 24) })
        showMetrics(null)
    }

    private fun showMetrics(report: JSONObject?) {
        fun format(value: Int?) = value?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—"
        val ram = report?.optJSONObject("memory")?.let {
            val total = it.optLong("total")
            if (total > 0) (100.0 * it.optLong("used") / total).toInt() else null
        }
        readings.text = "CPU ${format(report?.optInt("cpu_percent", -1))}   " +
            "GPU ${format(report?.optInt("gpu_percent", -1))}   RAM ${format(ram)}"
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
}
