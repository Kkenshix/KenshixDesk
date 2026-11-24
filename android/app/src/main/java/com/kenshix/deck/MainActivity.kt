package com.kenshix.deck

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView

import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : Activity() {
    private val background = Color.BLACK
    private val surface = Color.rgb(20, 23, 29)
    private val line = Color.rgb(48, 53, 64)
    private val text = Color.rgb(243, 245, 250)
    private val muted = Color.rgb(170, 177, 193)
    private val accent = Color.rgb(170, 199, 255)
    private val handler = Handler(Looper.getMainLooper())

    private val actions = linkedMapOf(
        "mixer" to "Ses Mikseri", "play_pause" to "Oynat / duraklat",
        "next_track" to "Sonraki parça", "previous_track" to "Önceki parça",
        "task_view" to "Görev görünümü", "task_manager" to "Görev Yöneticisi",
        "obs_status" to "OBS durumu", "obs_start_record" to "OBS kaydı başlat",
        "obs_stop_record" to "OBS kaydı durdur", "obs_toggle_record" to "OBS kaydını aç / kapat",
        "obs_pause_record" to "OBS kaydını duraklat", "obs_resume_record" to "OBS kaydını sürdür",
        "obs_start_stream" to "OBS yayını başlat", "obs_stop_stream" to "OBS yayını durdur",
        "obs_start_replay" to "Replay Buffer başlat", "obs_stop_replay" to "Replay Buffer durdur",
        "obs_save_replay" to "Replay Buffer kaydet"
    )

    private lateinit var link: DeckLink
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var body: FrameLayout
    private lateinit var battery: BroadcastReceiver
    private var batteryText = ""
    private var status = "Eşleştirme gerekli"
    private var deck = JSONObject().put("revision", 0).put("buttons", JSONArray())
    private var editing = false
    private var mixer = false
    private var obs = false
    private var dimmed = false
    private var idleSeconds = 30
    private var activeBrightness = -1f
    private var idleBrightness = 0.08f
    private var eatingGesture = false
    private var inactive = false
    private var saving = false
    private val preferences by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private val dim = Runnable {
        if (!inactive) {
            dimmed = true
            brightness(idleBrightness)
        }
    }
    private val clock = object : Runnable {
        override fun run() {
            if (::clockView.isInitialized) {
                clockView.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + batteryText
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activeBrightness = preferences.getFloat("active_brightness", -1f)
        idleBrightness = preferences.getFloat("idle_brightness", 0.08f)
        idleSeconds = preferences.getInt("idle_seconds", 30).coerceIn(5, 300)
        deck = runCatching { JSONObject(preferences.getString("cached_deck", "").orEmpty()) }.getOrDefault(deck)
        status = if (preferences.contains("identity")) "Bağlantı bekleniyor" else "Eşleştirme gerekli"
        body = FrameLayout(this)
        render()
        link = DeckLink(Credentials(preferences), { connection, snapshot ->
            handler.post {
                if (isDestroyed) return@post
                val changed = status != connection
                status = connection
                if (snapshot != null) applyDeck(snapshot)
                if (changed && !connected()) {
                    mixer = false
                    obs = false
                    editing = false
                }
                statusView.text = status
                if (changed || (snapshot != null && !mixer && !obs)) renderBody()
            }
        }, { message -> handler.post { if (!isDestroyed) notify(message) } })
        battery = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                batteryText = if (level >= 0 && scale > 0) "  ·  ${if (charging) "⚡" else ""}${level * 100 / scale}%" else ""
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(battery, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(battery, filter)
        }
        handler.post(clock)
        wake()
    }

    override fun onStart() {
        super.onStart()
        inactive = false
        link.pause(false)
        wake()
    }

    override fun onStop() {
        inactive = true
        link.pause(true)
        handler.removeCallbacks(dim)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(battery)
        link.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        renderBody()
    }

    @Deprecated("Android Activity back routing")
    override fun onBackPressed() {
        if (mixer || obs || editing) {
            mixer = false
            obs = false
            editing = false
            renderBody()
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            eatingGesture = dimmed
            wake()
        }
        if (eatingGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                eatingGesture = false
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun wake() {
        dimmed = false
        brightness(activeBrightness)
        handler.removeCallbacks(dim)
        handler.postDelayed(dim, idleSeconds * 1000L)
    }

    private fun brightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level }
    }

    private fun render() {
        root = column().apply { setBackgroundColor(this@MainActivity.background) }
        val top = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(10), dp(4))
            minimumHeight = dp(64)
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.dragon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(image, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        val brand = column().apply {
            addView(label("KenshixDeck", 15, text, true))
            statusView = label(status, 12, accent)
            addView(statusView)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clockView = label("", 12, muted).apply { gravity = Gravity.END }
        top.addView(clockView, LinearLayout.LayoutParams(-2, -2))
        val menu = button("⋮") { openMenu(it) }.apply { contentDescription = "Ayarlar" }
        top.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        renderBody()
    }

    private fun connected() = status == "USB" || status == "Wi-Fi"

    private fun renderBody() {


        body.removeAllViews()
        if (obs && connected()) {
            body.addView(ObsView(this, link) { obs = false; renderBody() }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (mixer && connected()) {
            renderMixer()
            return
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val create = button("+") {
            if (connected()) editButton(null)
            else if (status == "Eşleştirme gerekli") showPairing()
            else notify("Buton eklemek için PC bağlantısı gerekli.")
        }.apply { contentDescription = "Yeni buton" }

        if (connected()) {
            val buttons = deck.optJSONArray("buttons") ?: JSONArray()
            val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
            var gridRow: LinearLayout? = null
            for (i in 0..buttons.length()) {
                if (i % columns == 0) {
                    gridRow = row().apply { weightSum = columns.toFloat() }
                    content.addView(gridRow)
                }
                val tile = if (i == buttons.length()) create else {
                    val record = buttons.getJSONObject(i)
                    button(record.getString("label")) {
                        if (editing) editButton(record) else execute(it, record)
                    }
                }
                gridRow?.addView(tile, LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                    marginEnd = dp(6)
                    bottomMargin = dp(8)
                })
            }
        } else {
            content.addView(create, LinearLayout.LayoutParams(dp(68), dp(68)))
            if (status == "Bağlantı bekleniyor") {
                content.addView(label(status, 16, muted).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        scroll.addView(content)
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            if (connected()) menu.add(if (editing) "Düzenlemeyi bitir" else "Butonları düzenle").setOnMenuItemClickListener {
                editing = !editing
                mixer = false
                obs = false
                renderBody()
                true
            }
            menu.add("PC ile eşleştir").setOnMenuItemClickListener { showPairing(); true }
            if (preferences.contains("identity")) menu.add("Telefon anahtarını kaldır").setOnMenuItemClickListener {
                link.forget()
                true
            }
            menu.add("Ekran").setOnMenuItemClickListener { showScreenSettings(); true }
            menu.add("Sürüm").setOnMenuItemClickListener {
                val version = packageManager.getPackageInfo(packageName, 0)
                AlertDialog.Builder(this@MainActivity).setTitle("KenshixDeck")
                    .setMessage("Sürüm ${version.versionName} / ${version.longVersionCode}\n${BuildConfig.GIT_SHA}")
                    .setPositiveButton("Kapat", null).show()
                true
            }
        }.show()
    }

    private fun showScreenSettings() {
        val panel = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        fun number(caption: String, value: Int): EditText {
            panel.addView(label(caption, 14, muted))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                panel.addView(this)
            }
        }
        val normal = number("Aktif parlaklık % (0: Android ayarı)", if (activeBrightness < 0) 0 else (activeBrightness * 100).toInt())
        val idle = number("Boşta parlaklık %", (idleBrightness * 100).toInt())
        val delay = number("Boşta bekleme süresi (saniye)", idleSeconds)
        AlertDialog.Builder(this).setTitle("Ekran").setView(panel).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet") { _, _ ->
                val normalValue = normal.text.toString().toIntOrNull()
                val idleValue = idle.text.toString().toIntOrNull()
                val delayValue = delay.text.toString().toIntOrNull()
                if (normalValue == null || normalValue !in 0..100 ||
                    idleValue == null || idleValue !in 5..35 ||
                    delayValue == null || delayValue !in 5..300) {
                    notify("Parlaklık ve süre değerlerini kontrol et.")
                    return@setPositiveButton
                }
                activeBrightness = if (normalValue == 0) -1f else normalValue / 100f
                idleBrightness = idleValue / 100f
                idleSeconds = delayValue
                preferences.edit().putFloat("active_brightness", activeBrightness)
                    .putFloat("idle_brightness", idleBrightness).putInt("idle_seconds", idleSeconds).apply()
                wake()
            }.show()
    }

    private fun showPairing() {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val code = EditText(this).apply {
            hint = "PC’deki eşleştirme kodu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(100))
        }
        val lan = EditText(this).apply {
            hint = "Wi-Fi için yerel IPv4 (isteğe bağlı)"
            isSingleLine = true
            setText(preferences.getString("lan", ""))
        }
        fields.addView(code)
        fields.addView(lan)
        AlertDialog.Builder(this).setTitle("PC ile eşleştir").setView(fields)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Eşleştir") { _, _ -> link.pair(code.text.toString(), lan.text.toString().trim()) }
            .show()
    }

    private fun editButton(current: JSONObject?) {
        if (saving) return
        val originalRevision = deck.getLong("revision")
        val panel = column().apply { setPadding(dp(24), dp(12), dp(24), dp(8)) }
        val title = EditText(this).apply {
            hint = "Buton adı"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(60))
            setText(current?.optString("label"))
        }
        val action = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actions.values.toList())
            setSelection(actions.keys.indexOf(current?.optString("action")).coerceAtLeast(0))
        }
        panel.addView(title)
        panel.addView(action)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current == null) "Yeni buton" else "Butonu düzenle")
            .setView(panel).setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", null)
        if (current != null) dialog.setNeutralButton("Sil") { _, _ ->
            AlertDialog.Builder(this).setMessage("Buton silinsin mi?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil") { _, _ -> saveEdit(originalRevision, current.getString("id"), null) }.show()
        }
        dialog.show().also { displayed ->
            displayed.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = title.text.toString().trim()
                if (input.isEmpty()) { title.error = "Buton adı gerekli"; return@setOnClickListener }
                val id = current?.getString("id") ?: UUID.randomUUID().toString()
                val replacement = JSONObject().put("id", id).put("label", input)
                    .put("action", actions.keys.elementAt(action.selectedItemPosition))
                saveEdit(originalRevision, id, replacement)
                displayed.dismiss()
            }
        }
    }

    private fun applyDeck(snapshot: JSONObject) {
        deck = snapshot
        preferences.edit().putString("cached_deck", snapshot.toString()).apply()
    }

    private fun saveEdit(expected: Long, id: String, replacement: JSONObject?) {
        if (saving) return
        if (deck.getLong("revision") != expected) {
            notify("Panel değişti. Butonu yeniden aç.")
            return
        }
        val previous = deck.getJSONArray("buttons")
        val next = JSONArray()
        var found = false
        for (i in 0 until previous.length()) {
            val button = previous.getJSONObject(i)
            if (button.getString("id") == id) {
                found = true
                if (replacement != null) next.put(replacement)
            } else next.put(button)
        }
        if (!found && replacement != null) next.put(replacement)
        val payload = JSONObject().put("revision", expected).put("buttons", next)
        saving = true
        link.request(message("save").put("deck", payload)) { result ->
            handler.post {
                if (isDestroyed) return@post
                saving = false
                when (result?.optString("type")) {
                    "saved", "conflict" -> {
                        applyDeck(result.getJSONObject("deck"))
                        renderBody()
                        if (result.optString("type") == "conflict") notify("Panel başka bir bağlantıdan değişti. Yeniden dene.")
                    }
                    else -> notify("Değişikliğin kaydedildiği doğrulanamadı.")
                }
            }
        }
    }

    private fun execute(view: View, record: JSONObject) {
        if (record.getString("action") == "obs_status") {
            obs = true
            renderBody()
            return
        }
        if (record.getString("action") == "mixer") {
            mixer = true
            renderBody()
            return
        }
        val operation = UUID.randomUUID().toString()
        view.isEnabled = false
        link.request(message("execute").put("operation", operation).put("button", record.getString("id"))) { result ->
            handler.post {
                if (isDestroyed) return@post
                view.isEnabled = true
                val state = if (result?.optString("operation") == operation) result.optString("status") else "unknown"
                view.background = tile(if (state == "ok") Color.rgb(28, 68, 52) else Color.rgb(88, 37, 43))
                handler.postDelayed({ view.background = tile(surface) }, 450)
                if (state != "ok") notify(if (state == "failed") "Windows işlemi tamamlayamadı." else "İşlem sonucu doğrulanamadı.")
            }
        }
    }

    private fun renderMixer() {
        val view = MixerView(this, link, { mixer = false; renderBody() }, ::notify)
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun notify(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun tile(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), line)
    }

    private fun button(value: String, tapped: (View) -> Unit) = Button(this).apply {
        text = value
        textSize = if (value.length == 1) 22f else 13f
        isAllCaps = false
        setTextColor(this@MainActivity.text)
        setPadding(dp(5), dp(4), dp(5), dp(4))
        minWidth = 0
        minHeight = dp(44)
        background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33AAC7FF), tile(surface), null)
        setOnClickListener(tapped)
    }
}
