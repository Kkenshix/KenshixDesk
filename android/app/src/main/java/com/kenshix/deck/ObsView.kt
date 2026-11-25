package com.kenshix.deck

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.util.UUID

internal class ObsView(activity: Activity, private val link: DeckLink, exit: () -> Unit) : LinearLayout(activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val titles = listOf("Kayıt", "Kayıt süresi", "Yayın", "Replay Buffer", "Sahne", "Görüntüleme FPS")
    private val values = titles.map { DeckUi.label(activity, "—", 15, DeckUi.accent, 600) }
    private val status = DeckUi.label(activity, "OBS durumu okunuyor", 14, DeckUi.muted)
    private val detail = LinearLayout(activity).apply { orientation = VERTICAL; visibility = View.GONE }
    private var active = false
    private val poll = object : Runnable {
        override fun run() {
            link.request(message("obs_status")) { response ->
                handler.post {
                    if (!active) return@post
                    render(response?.takeIf { it.optString("type") == "obs_status" })
                    handler.postDelayed(this, 2000)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        val pad = DeckUi.dp(activity, 18)
        setPadding(pad, DeckUi.dp(activity, 12), pad, pad)
        addView(DeckUi.screenHeading(activity, "OBS Studio", exit))
        addView(status.apply {
            setPadding(pad, pad, pad, pad)
            background = DeckUi.shape(activity)
        })
        detail.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(DeckUi.action(activity, "Sahne değiştir") { chooseControls("scenes") })
            addView(DeckUi.action(activity, "Mikrofonlar") { chooseControls("microphones") })
            addView(DeckUi.action(activity, "Görüntü kaynakları") { chooseControls("sources") })
        }, LayoutParams(-1, -2).apply { bottomMargin = DeckUi.dp(activity, 10) })
        titles.forEachIndexed { index, title ->
            detail.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = DeckUi.shape(activity, radius = 16)
                setPadding(pad, pad, pad, pad)
                addView(DeckUi.label(activity, title, 14, DeckUi.muted), LayoutParams(0, -2, 1f))
                addView(values[index], LayoutParams(0, -2, 1f).also {
                    values[index].gravity = Gravity.END
                })
            }, LayoutParams(-1, -2).apply { bottomMargin = DeckUi.dp(activity, 8) })
        }
        addView(ScrollView(activity).apply { addView(detail) }, LayoutParams(-1, 0, 1f))
    }

    private fun chooseControls(kind: String) {
        link.request(message("obs_controls")) { result ->
            handler.post {
                if (!active) return@post
                val values = result?.optJSONArray(kind)
                if (values == null || values.length() == 0) {
                    showResult("OBS’de bu denetim için öğe bulunamadı")
                    return@post
                }
                val choices = (0 until values.length()).map { i ->
                    if (kind == "scenes") {
                        val name = values.optString(i)
                        ("$name${if (name == result.optString("current")) " · Yayında" else ""}") to
                            JSONObject().put("action","scene").put("name",name)
                    } else {
                        val value = values.getJSONObject(i)
                        val name = value.optString("name")
                        if (kind == "microphones") {
                            val muted = value.optBoolean("muted")
                            ("$name · ${if (muted) "Sesi aç" else "Sessize al"}") to
                                JSONObject().put("action","microphone").put("name",name).put("muted",!muted)
                        } else {
                            val visible = value.optBoolean("visible")
                            ("$name · ${if (visible) "Gizle" else "Göster"}") to
                                JSONObject().put("action","source").put("scene",result.optString("current"))
                                    .put("item",value.optLong("id")).put("visible",!visible)
                        }
                    }
                }
                val title = when (kind) { "scenes" -> "Sahne"; "microphones" -> "Mikrofon"; else -> "Görüntü kaynağı" }
                DeckUi.choices(DeckUi.dialog(context).setTitle(title), choices.map { it.first }) {
                    val id = UUID.randomUUID().toString()
                    link.request(message("change_obs").put("operation",id).put("target",choices[it].second)) { reply ->
                        handler.post {
                            if (active && (reply?.optString("operation") != id || reply.optString("status") != "ok")) {
                                showResult("OBS işlemi tamamlanmadı")
                            }
                        }
                    }
                }.setNegativeButton("Kapat",null).showDeck()
            }
        }
    }

    private fun showResult(message: String) {
        DeckUi.dialog(context).setMessage(message).setPositiveButton("Kapat",null).showDeck()
    }

    private fun render(response: JSONObject?) {
        if (response == null) {
            detail.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = "OBS’ye erişilemiyor. PC’de OBS WebSocket ayarını kontrol et."
            return
        }

        status.visibility = View.GONE
        if (detail.visibility != View.VISIBLE) {
            detail.visibility = View.VISIBLE
            DeckUi.appear(detail)
        }
        val recording = when {
            response.optBoolean("paused") -> "Duraklatıldı"
            response.optBoolean("recording") -> "Açık"
            else -> "Kapalı"
        }
        fun state(key: String) = if (response.optBoolean(key)) "Açık" else "Kapalı"
        val readings = listOf(recording, "${response.optLong("duration_ms") / 1000} sn",
            state("streaming"), state("replay"), response.optString("scene", "—"), response.optString("render_fps", "—"))
        readings.forEachIndexed { index, value -> values[index].text = value }
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
