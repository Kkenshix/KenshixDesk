package com.kenshix.deck

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import org.json.JSONObject
import java.util.Locale

internal class PerformanceView(
    activity: Activity, private val link: DeckLink, back: () -> Unit
) : LinearLayout(activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private val processor = Gauge("CPU kullanımı")
    private val graphics = Gauge("GPU kullanımı")
    private val memory = Gauge("RAM")
    private val disk = Gauge("Sistem diski")
    private val status = DeckUi.label(activity, "", 14, DeckUi.muted)
    private val sample = object : Runnable {
        override fun run() {
            link.request(message("metrics")) { reply ->
                handler.post {
                    if (!attached) return@post
                    val report = reply?.optJSONObject("measurements")
                    val percent = report?.optInt("cpu_percent", -1)?.takeIf { it in 0..100 }
                    processor.display(percent?.let { "$it%" } ?: "—", percent)
                    val gpu = report?.optInt("gpu_percent", -1)?.takeIf { it in 0..100 }
                    graphics.display(gpu?.let { "$it%" } ?: "—", gpu)
                    memory.display(report?.optJSONObject("memory"))
                    disk.display(report?.optJSONObject("system_disk"))
                    status.visibility = if (report == null) View.VISIBLE else View.GONE
                    status.text = if (report == null) "Windows ölçümlerine erişilemiyor." else ""
                    handler.postDelayed(this, 1100)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        val pad = dp(18)
        setPadding(pad, dp(12), pad, pad)
        addView(DeckUi.screenHeading(activity, "PC Performansı", back))
        val metrics = LinearLayout(activity).apply { orientation = VERTICAL }
        listOf(processor, graphics, memory, disk).forEach { gauge ->
            metrics.addView(gauge, LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        metrics.addView(status)
        addView(ScrollView(activity).apply { addView(metrics) }, LayoutParams(-1, 0, 1f))
    }

    private inner class Gauge(name: String) : LinearLayout(this@PerformanceView.context) {
        private val readout = DeckUi.label(context, "—", 17, DeckUi.accent, 650)
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(DeckUi.accent)
            progressBackgroundTintList = ColorStateList.valueOf(DeckUi.border)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = VERTICAL
            background = DeckUi.shape(context, radius = 20)
            setPadding(dp(16), dp(17), dp(16), dp(15))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(DeckUi.label(context, name, 15, DeckUi.text, 550), LayoutParams(0, -2, 1f))
                addView(readout)
            })
            addView(bar, LayoutParams(-1, dp(8)).apply { topMargin = dp(14) })
        }

        fun display(text: String, percent: Int?) {
            readout.text = text
            bar.setProgress(percent ?: 0, android.animation.ValueAnimator.areAnimatorsEnabled())
        }

        fun display(value: JSONObject?) {
            val total = value?.optLong("total") ?: 0
            if (total <= 0) {
                display("—", null)
                return
            }
            val used = value?.optLong("used")?.coerceIn(0, total) ?: 0
            val text = String.format(Locale.getDefault(), "%.1f / %.1f GiB", used / 1073741824.0, total / 1073741824.0)
            display(text, (100.0 * used / total).toInt())
        }
    }

    private fun dp(size: Int) = DeckUi.dp(context, size)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        handler.post(sample)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
