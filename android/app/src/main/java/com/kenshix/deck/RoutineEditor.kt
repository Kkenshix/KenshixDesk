package com.kenshix.deck

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

internal class RoutineEditor(
    private val context: Context,
    initial: JSONObject?,
    actions: Map<String, String>
) : LinearLayout(context) {
    private val steps = mutableListOf<JSONObject>()
    private val allowedActions = actions.filterKeys { it !in listOf("macro", "mixer", "obs_status") }
    private val types = linkedMapOf(
        "keys" to "Tuş veya kısayol", "text" to "Metin yaz", "click" to "Koordinata tıkla",
        "launch" to "Windows uygulamasını aç", "wait" to "Bekle", "action" to "Hazır eylem", "wait_obs" to "OBS hazır olana kadar bekle",
        "if_obs" to "OBS hazır değilse adım atla"
    )

    private val triggerNames = linkedMapOf(
        "" to "Elle çalıştır", "host_start" to "Host açıldığında", "daily" to "Her gün belirli saatte",
        "app_started" to "Uygulama açıldığında", "app_stopped" to "Uygulama kapandığında",
        "obs_recording_started" to "OBS kaydı başlayınca", "obs_recording_stopped" to "OBS kaydı durunca",
        "disk_usage" to "Disk doluluk uyarısı"
    )
    private val initialTrigger = initial?.optJSONObject("trigger")
    private val trigger = DeckUi.choose(context, "Otomasyon", triggerNames.values.toList(),
        triggerNames.keys.indexOf(initialTrigger?.optString("type")).coerceAtLeast(0))
    private val triggerValue = EditText(context).apply {
        DeckUi.field(this)
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(64))
    }
    private val stepViews = DeckUi.editor(context)
    private val keepGoing = CheckBox(context).apply {
        text = "Bir adım hata verirse devam et"
        setTextColor(DeckUi.text)
        isChecked = initial?.optBoolean("continue_on_error") == true
    }

    init {
        orientation = VERTICAL
        initial?.optJSONArray("steps")?.let { items ->
            repeat(items.length()) { items.optJSONObject(it)?.let(steps::add) }
        }
        addView(stepViews)
        addView(DeckUi.action(context, "+ Makro adımı", primary = true) { addStep() })
        addView(keepGoing)
        addView(trigger)
        addView(triggerValue)
        trigger.onSelection = { showTrigger(it) }
        showTrigger(trigger.selectedItemPosition, initialTrigger)
        refresh()
    }

    fun snapshot(): JSONObject? {
        if (steps.isEmpty() || steps.withIndex().any { (index, step) ->
            step.optString("type") == "if_obs" && index + step.optInt("skip") >= steps.size
        }) return null
        val result = JSONObject().put("steps", JSONArray(steps)).put("continue_on_error", keepGoing.isChecked)
        val type = triggerNames.keys.elementAt(trigger.selectedItemPosition)
        if (type.isEmpty()) return result
        val value = triggerValue.text.toString().trim()
        val spec = JSONObject().put("type", type)
        when (type) {
            "daily" -> {
                val time = value.split(":")
                val hour = time.getOrNull(0)?.toIntOrNull()
                val minute = time.getOrNull(1)?.toIntOrNull()
                if (time.size != 2 || hour == null || hour !in 0..23 || minute == null || minute !in 0..59) return null
                spec.put("hour", hour).put("minute", minute)
            }
            "app_started", "app_stopped" -> {
                if (!value.matches(Regex("[0-9a-zA-Z_. -]+\\.[eE][xX][eE]"))) return null
                spec.put("executable", value)
            }
            "disk_usage" -> {
                val percent = value.toIntOrNull() ?: return null
                if (percent !in 10..100) return null
                spec.put("percent", percent)
            }
        }
        return result.put("trigger", spec)
    }

    private fun showTrigger(index: Int, initial: JSONObject? = null) {
        val name = triggerNames.keys.elementAt(index)
        triggerValue.visibility = if (name in listOf("daily", "app_started", "app_stopped", "disk_usage")) VISIBLE else GONE
        triggerValue.hint = when (name) {
            "daily" -> "Saat, ör. 18:30"
            "disk_usage" -> "Doluluk yüzdesi (10–100)"
            else -> "Uygulama adı, ör. notepad.exe"
        }
        triggerValue.setText(when (name) {
            "daily" -> initial?.let { "${it.optInt("hour")}:${it.optInt("minute").toString().padStart(2, '0')}" }.orEmpty()
            "disk_usage" -> initial?.optInt("percent", 90)?.toString() ?: "90"
            else -> initial?.optString("executable").orEmpty()
        })
    }

    private fun refresh() {
        stepViews.removeAllViews()
        steps.forEachIndexed { index, step ->
            val detail = when (step.optString("type")) {
                "keys" -> step.getJSONArray("keys").let { keys ->
                    (0 until keys.length()).joinToString("+") { keys.getString(it) }
                }
                "text" -> step.optString("text").take(28)
                "click" -> "${step.optInt("x")}, ${step.optInt("y")}"
                "launch" -> step.optString("executable").substringAfterLast('\\')
                "wait", "wait_obs" -> "${step.optInt("ms")} ms"
                "action" -> allowedActions[step.optString("action")].orEmpty()
                else -> "${step.optInt("skip")} adım"
            }
            stepViews.addView(DeckUi.action(context, "${index + 1}. ${types[step.optString("type")]} · $detail") {
                val choices = mutableListOf<Pair<String, () -> Unit>>()
                choices.add("Düzenle" to { editStep(step.getString("type"), index) })
                if (index > 0) choices.add("Yukarı taşı" to { move(index, index - 1) })
                if (index < steps.lastIndex) choices.add("Aşağı taşı" to { move(index, index + 1) })
                choices.add("Sil" to { steps.removeAt(index); refresh() })
                DeckUi.choices(DeckUi.dialog(context).setTitle("Makro adımı"), choices.map { it.first }) {
                    choices[it].second()
                }.setNegativeButton("Kapat", null).showDeck()
            })
        }
    }

    private fun move(from: Int, to: Int) {
        steps.add(to, steps.removeAt(from))
        refresh()
    }

    private fun addStep() {
        if (steps.size >= 24) return
        DeckUi.choices(DeckUi.dialog(context).setTitle("Eylem türü"), types.values.toList()) {
            editStep(types.keys.elementAt(it), null)
        }.setNegativeButton("Vazgeç", null).showDeck()
    }

    private fun editStep(type: String, index: Int?) {
        val original = index?.let(steps::get)
        val form = DeckUi.editor(context)
        fun field(hint: String, value: String, limit: Int = 12) = EditText(context).apply {
            DeckUi.field(this)
            this.hint = hint
            filters = arrayOf(InputFilter.LengthFilter(limit))
            isSingleLine = true
            setText(value)
            form.addView(this)
        }
        val result = JSONObject().put("type", type)
        val collect: () -> Boolean = when (type) {
            "keys" -> {
                val keys = original?.optJSONArray("keys")
                val first = field("Tuş veya kısayol, ör. Ctrl+Shift+M", keys?.let {
                    (0 until it.length()).joinToString("+") { index -> keys.getString(index) }
                }.orEmpty(), 70)
                val hold = field("Basılı tutma (ms, 0–5000)", original?.optInt("hold_ms")?.toString() ?: "0")
                val read: () -> Boolean = {
                    val names = first.text.toString().split("+").map { it.trim().uppercase() }
                    val time = hold.text.toString().trim().toIntOrNull()
                    val valid = names.size in 1..5 && names.distinct().size == names.size && names.all(::knownKey) && time != null && time in 0..5000
                    if (valid) result.put("keys", JSONArray(names)).put("hold_ms", time)
                    valid
                }
                read
            }
            "text" -> {
                val entry = field("Yazılacak metin", original?.optString("text").orEmpty(), 512)
                val read: () -> Boolean = {
                    val value = entry.text.toString()
                    val valid = value.isNotEmpty() && value.none(Char::isISOControl)
                    if (valid) result.put("text", value)
                    valid
                }
                read
            }
            "launch" -> {
                val application = field("Windows .exe tam yolu", original?.optString("executable").orEmpty(), 400)
                val read: () -> Boolean = {
                    val value = application.text.toString().trim()
                    val valid = value.length > 6 && value[0].isLetter() && value[1] == ':' &&
                        (value[2] == '\\' || value[2] == '/') && value.endsWith(".exe", true)
                    if (valid) result.put("executable", value)
                    valid
                }
                read
            }
            "click" -> {
                val x = field("Windows X koordinatı", original?.optInt("x")?.toString().orEmpty())
                val y = field("Windows Y koordinatı", original?.optInt("y")?.toString().orEmpty())
                val read: () -> Boolean = {
                    val left = x.text.toString().trim().toIntOrNull()
                    val top = y.text.toString().trim().toIntOrNull()
                    val valid = left != null && top != null && left in -65536..65536 && top in -65536..65536
                    if (valid) result.put("x", left).put("y", top)
                    valid
                }
                read
            }
            "action" -> {
                val selector = DeckUi.choose(context, "Eylem", allowedActions.values.toList(),
                    allowedActions.keys.indexOf(original?.optString("action")).coerceAtLeast(0))
                form.addView(selector)
                val read: () -> Boolean = {
                    result.put("action", allowedActions.keys.elementAt(selector.selectedItemPosition))
                    true
                }
                read
            }
            else -> {
                val skipping = type == "if_obs"
                val key = if (skipping) "skip" else "ms"
                val maximum = if (skipping) 23 else 15000
                val value = field(if (skipping) "Atlanacak adım sayısı" else "Süre (ms, 1–15000)",
                    original?.optInt(key)?.toString() ?: if (skipping) "1" else "500")
                val read: () -> Boolean = {
                    val number = value.text.toString().trim().toIntOrNull()
                    val valid = number != null && number in 1..maximum
                    if (valid) result.put(key, number)
                    valid
                }
                read
            }
        }
        val dialog = DeckUi.dialog(context).setTitle(types[type]).setView(DeckUi.scrollForm(form))
            .setNegativeButton("Vazgeç", null).setPositiveButton("Uygula", null).showDeck()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (collect()) {
                if (index == null) steps.add(result) else steps[index] = result
                refresh()
                dialog.dismiss()
            } else dialog.setTitle("Geçerli bir değer gir")
        }
    }

    private fun knownKey(value: String): Boolean =
        value.matches(Regex("[A-Z0-9]|F([1-9]|1[0-9]|2[0-4])")) || value in supportedKeys

    companion object {
        private val supportedKeys = setOf("CTRL", "SHIFT", "ALT", "WIN", "ENTER", "ESC", "SPACE", "TAB",
            "BACKSPACE", "DELETE", "INSERT", "PAGEUP", "PAGEDOWN", "END", "HOME", "LEFT", "RIGHT", "UP", "DOWN")
    }
}
