package com.kenshix.deck

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

internal class PerformanceView(
    activity: Activity, private val link: DeckLink, back: () -> Unit
) : LinearLayout(activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private val processor = value()
    private val memory = value()
    private val disk = value()
    private val status = value()
    private val sample = object : Runnable {
        override fun run() {
            link.request(message("metrics")) { reply ->
                handler.post {
                    if (!attached) return@post
                    val report = reply?.optJSONObject("measurements")
                    processor.text = if (report == null || report.isNull("cpu_percent")) "—"
                        else report.getInt("cpu_percent").toString() + "%"
                    memory.text = usage(report?.optJSONObject("memory"))
                    disk.text = usage(report?.optJSONObject("system_disk"))
                    status.text = if (report == null) "Windows ölçümlerine erişilemiyor." else ""
                    handler.postDelayed(this, 1100)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(16))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(android.widget.Button(context).apply {
            text = "‹"
            contentDescription = "Geri"
            setOnClickListener { back() }
        })
        header.addView(value().apply { text = "PC Performansı"; textSize = 22f; setPadding(dp(12), 0, 0, 0) })
        addView(header)
        row("CPU kullanımı", processor)
        row("RAM", memory)
        row("Sistem diski", disk)
        addView(status)
    }

    private fun row(title: String, readout: TextView) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(16, 19, 25))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(value().apply { text = title }, LayoutParams(0, -2, 1f))
            addView(readout)
        }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun value() = TextView(context).apply {
        setTextColor(Color.rgb(230, 239, 241))
        textSize = 16f
        text = "—"
    }

    private fun usage(value: JSONObject?): String {
        val used = value?.optLong("used") ?: return "—"
        val total = value.optLong("total")
        if (total <= 0) return "—"
        return String.format(java.util.Locale.getDefault(), "%.1f / %.1f GiB", used / 1073741824.0, total / 1073741824.0)
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density).toInt()
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
