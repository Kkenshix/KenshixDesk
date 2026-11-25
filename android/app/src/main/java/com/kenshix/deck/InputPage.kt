package com.kenshix.deck

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
internal class InputPage(context: Context, private val link: DeckLink, back: () -> Unit) : LinearLayout(context) {
    private val status = DeckUi.label(context, "Parmağını sürükle · Tıklamak için dokun", 13, DeckUi.muted)
    private var lastX = 0f
    private var lastY = 0f
    private var dx = 0f
    private var dy = 0f
    private var distance = 0f
    private var sending = false

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(9))
        addView(DeckUi.screenHeading(context, "Klavye ve touchpad", back))
        val entry = EditText(context).apply { DeckUi.field(this); hint = "Windows’a yazılacak metin"; isSingleLine = true }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(entry, LayoutParams(0, -2, 1f))
            addView(DeckUi.action(context, "Gönder") {
                val text = entry.text.toString()
                if (text.isNotEmpty() && text.length <= 256) {
                    send(JSONObject().put("action","text").put("value",text))
                    entry.text.clear()
                }
            })
        })
        addView(LinearLayout(context).apply {
            for ((name, key) in listOf("Enter" to "ENTER","Escape" to "ESC","Tab" to "TAB","Geri sil" to "BACKSPACE","Yukarı" to "UP","Aşağı" to "DOWN")) {
                addView(DeckUi.action(context, name) { send(JSONObject().put("action","key").put("value",key)) })
            }
        })
        addView(status)
        val pad = DeckUi.label(context, "Touchpad", 19, DeckUi.text, 600).apply {
            gravity = Gravity.CENTER
            DeckUi.touch(this, DeckUi.BLACK, 15, DeckUi.muted)
            setOnClickListener { send(JSONObject().put("action","click")) }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                        distance = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val x = event.x - lastX
                        val y = event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        distance += abs(x) + abs(y)
                        if (distance > dp(4)) {
                            dx += x
                            dy += y
                            flush()
                        }
                    }
                    MotionEvent.ACTION_UP -> if (distance <= dp(4)) view.performClick() else flush()
                    MotionEvent.ACTION_CANCEL -> { dx = 0f; dy = 0f }
                }
                true
            }
        }
        addView(pad, LayoutParams(-1, 0, 1f).apply { topMargin = dp(7) })
    }

    private fun flush() {
        if (sending) return
        val x = (dx * 1.15f).roundToInt().coerceIn(-240,240)
        val y = (dy * 1.15f).roundToInt().coerceIn(-240,240)
        if (x == 0 && y == 0) return
        dx = 0f
        dy = 0f
        sending = true
        send(JSONObject().put("action","move").put("x",x).put("y",y)) {
            sending = false
            postDelayed({ if (isAttachedToWindow) flush() }, 26)
        }
    }

    private fun send(payload: JSONObject, after: () -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        link.request(message("input").put("operation",id).put("input",payload)) { response ->
            post {
                if (!isAttachedToWindow) return@post
                if (response?.optString("operation") != id || response.optString("status") != "ok") {
                    status.text = "Windows girdiyi uygulamadı"
                    dx = 0f
                    dy = 0f
                }
                after()
            }
        }
    }

    private fun dp(value: Int) = DeckUi.dp(context,value)
}
