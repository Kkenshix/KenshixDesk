package com.kenshix.deck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.util.UUID

internal class WindowPage(context: Context, private val link: DeckLink, close: () -> Unit) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val status = DeckUi.label(context, "", 13, DeckUi.muted)
    private var signature = ""
    private val poll = Runnable { update() }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(8))
        addView(DeckUi.screenHeading(context, "Windows pencereleri", close))
        addView(status)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(-1, 0, 1f))
    }

    private fun update() {
        link.request(message("windows")) { result ->
            handler.post {
                if (!isAttachedToWindow) return@post
                val data = result?.optJSONArray("windows")
                if (data?.toString().orEmpty() != signature) {
                    signature = data?.toString().orEmpty()
                    list.removeAllViews()
                    for (index in 0 until (data?.length() ?: 0)) {
                        data?.optJSONObject(index)?.let { row(it) }
                    }
                }
                status.text = when {
                    data == null -> "Windows bağlantısı bekleniyor"
                    data.length() == 0 -> "Açık pencere bulunamadı"
                    else -> "${data.length()} açık pencere"
                }
                handler.postDelayed(poll, 1150)
            }
        }
    }

    private fun row(window: JSONObject) {
        val id = window.optString("id")
        val title = window.optString("title")
        val command = if (window.optBoolean("minimized") || window.optBoolean("maximized")) "restore" else "maximize"
        list.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = DeckUi.shape(context, DeckUi.panel, 12)
            setPadding(dp(12), dp(6), dp(6), dp(6))
            val name = DeckUi.label(context, title, 15, DeckUi.text, 650).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name)
                addView(DeckUi.label(context, window.optString("app") + if (window.optBoolean("active")) " · Önde" else "", 12, DeckUi.muted))
            }, LayoutParams(0, -2, 1f))
            addView(DeckUi.action(context, "Öne al") { run(id, "focus") })
            addView(DeckUi.action(context, "İşlemler") {
                val commands = listOf("minimize", command, "next_monitor", "close")
                val labels = listOf("Küçült", if (command == "restore") "Boyutu geri yükle" else "Büyüt", "Diğer monitöre taşı", "Kapat")
                DeckUi.choices(DeckUi.dialog(context).setTitle(title), labels) { index ->
                    if (commands[index] == "close") {
                        DeckUi.dialog(context).setTitle("Pencere kapatılsın mı?").setMessage(title)
                            .setNegativeButton("Vazgeç", null)
                            .setPositiveButton("Kapat") { _, _ -> run(id, "close") }.showDeck()
                    } else run(id, commands[index])
                }.setNegativeButton("Kapat", null).showDeck()
            }, LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        }, LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun run(id: String, command: String) {
        val operation = UUID.randomUUID().toString()
        link.request(message("control_window").put("operation", operation).put("window", id).put("command", command)) { result ->
            handler.post {
                if (!isAttachedToWindow) return@post
                if (result?.optString("operation") != operation || result.optString("status") != "ok") {
                    DeckUi.dialog(context).setTitle("Pencere işlemi tamamlanmadı")
                        .setMessage("Pencere kapanmış olabilir veya Windows bu işlemi engellemiş olabilir.")
                        .setPositiveButton("Kapat", null).showDeck()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(poll)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = DeckUi.dp(context, value)
}
