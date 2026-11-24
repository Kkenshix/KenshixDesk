package com.kenshix.deck

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal class ObsView(activity: Activity, private val link: DeckLink, exit: () -> Unit) : LinearLayout(activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(activity)
    private var active = false
    private val poll = object : Runnable {
        override fun run() {
            link.request(message("obs_status")) { response ->
                handler.post {
                    if (!active) return@post
                    status.text = if (response?.optString("type") == "obs_status") {
                        val recording = when {
                            response.optBoolean("paused") -> "Duraklatıldı"
                            response.optBoolean("recording") -> "Açık"
                            else -> "Kapalı"
                        }
                        listOf(
                            "Kayıt  " + recording,
                            "Kayıt süresi  " + response.optLong("duration_ms") / 1000 + " sn",
                            "Yayın  " + if (response.optBoolean("streaming")) "Açık" else "Kapalı",
                            "Replay Buffer  " + if (response.optBoolean("replay")) "Açık" else "Kapalı",
                            "Sahne  " + response.optString("scene", "—"),
                            "OBS görüntüleme FPS  " + response.optString("render_fps", "—"),
                        ).joinToString("\n")
                    } else "OBS’ye erişilemiyor. PC’de OBS WebSocket ayarını kontrol et."
                    handler.postDelayed(this, 2000)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(Button(activity).apply {
            text = "Geri"
            isAllCaps = false
            setOnClickListener { exit() }
        })
        addView(TextView(activity).apply {
            text = "OBS Studio"
            textSize = 22f
        })
        status.text = "OBS durumu okunuyor"
        status.textSize = 16f
        addView(status)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = true
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        active = false
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
