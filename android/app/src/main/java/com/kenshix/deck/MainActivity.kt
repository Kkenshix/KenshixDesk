package com.kenshix.deck

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
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
import android.widget.ScrollView

import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : Activity() {
    private val background = DeckUi.BLACK
    private val surface = DeckUi.panel
    private val line = DeckUi.border
    private val text = DeckUi.text
    private val muted = DeckUi.muted
    private val accent = DeckUi.accent
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
    private val kiosk by lazy { Kiosk(this) }
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var body: FrameLayout
    private lateinit var battery: BroadcastReceiver
    private var batteryText = ""
    private var status = "Eşleştirme gerekli"
    private var deck = JSONObject().put("revision", 0).put("buttons", JSONArray())
    private var currentPanel: String? = null
    private var editing = false
    private var mixer = false
    private var obs = false
    private var performance = false
    private var dimmed = false
    private var idleSeconds = 30
    private var activeBrightness = -1f
    private var idleBrightness = 0.08f
    private var eatingGesture = false
    private var inactive = false
    private var saving = false
    private var lastDestination: String? = null
    private var noticeView: TextView? = null
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
        currentPanel = layout().item(preferences.getString("current_panel", null))?.id
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
                    performance = false
                    mixer = false
                    obs = false
                    editing = false
                }
                updateConnection()
                if (changed || (snapshot != null && !mixer && !obs && !performance)) renderBody()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) runCatching { kiosk.onFocus() }
            .onFailure { notify("Kiosk ekranı başlatılamadı.") }
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
        if (mixer || obs || performance || editing) {
            mixer = false
            obs = false
            performance = false
            editing = false
            renderBody()
        } else {
            if (!kiosk.fullScreen()) super.onBackPressed()
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
            setPadding(dp(18), dp(8), dp(16), dp(8))
            minimumHeight = dp(76)
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.dragon)
            background = DeckUi.shape(this@MainActivity, DeckUi.accentPanel, 14)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val brand = column().apply {
            addView(label("KenshixDeck", 18, text, true).apply { letterSpacing = -.03f })
            statusView = label(status, 11, accent).apply { setPadding(0, dp(5), 0, 0) }
            updateConnection()
            addView(statusView)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clockView = label("", 12, muted).apply { gravity = Gravity.END }
        top.addView(clockView, LinearLayout.LayoutParams(-2, -2))
        val menu = button("⋮") { openMenu() }.apply {
            contentDescription = "Ayarlar"
            DeckUi.touch(this, surface, 16)
        }
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
    private fun layout() = PanelLayout(deck.optJSONObject("layout"))

    private fun renderBody() {


        val destination = when {
            performance && connected() -> "performance"
            obs && connected() -> "obs"
            mixer && connected() -> "mixer"
            else -> currentPanel ?: "deck"
        }
        val animate = lastDestination != destination
        lastDestination = destination
        fun display(view: View) {
            body.removeAllViews()
            body.addView(view, FrameLayout.LayoutParams(-1, -1))
            if (animate) DeckUi.appear(view)
        }
        if (performance && connected()) {
            display(PerformanceView(this, link) { performance = false; renderBody() })
            return
        }
        if (obs && connected()) {
            display(ObsView(this, link) { obs = false; renderBody() })
            return
        }
        if (mixer && connected()) {
            display(MixerView(this, link, { mixer = false; renderBody() }, ::notify))
            return
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(18), dp(18), dp(10), dp(18)) }
        val create = button("+") {
            if (connected()) editButton(null)
            else if (status == "Eşleştirme gerekli") showPairing()
            else notify("Buton eklemek için PC bağlantısı gerekli.")
        }.apply {
            contentDescription = "Yeni buton"
            textSize = 28f
            setTextColor(accent)
            DeckUi.touch(this, DeckUi.accentPanel, 22, Color.rgb(74, 60, 110))
        }

        if (connected()) {
            val layout = layout()
            val selection = layout.item(currentPanel)
            if (selection != null) {
                val path = row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, dp(8), dp(10))
                    addView(button("‹") { selectPanel(selection.parent) }.apply {
                        contentDescription = "Üst panele dön"
                    }, LinearLayout.LayoutParams(dp(40), dp(44)))
                    addView(label(selection.name, 17, text, true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(10), 0, dp(6), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button("Paneller") { choosePanel() })
                }
                content.addView(path)
            }
            val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
            var gridRow = row()
            var used = columns
            fun place(tile: View, span: Int) {
                if (used + span > columns) {
                    gridRow = row().apply { weightSum = columns.toFloat() }
                    content.addView(gridRow)
                    used = 0
                }
                gridRow.addView(tile, LinearLayout.LayoutParams(0, dp(112), span.toFloat()).apply {
                    marginEnd = dp(8)
                    bottomMargin = dp(8)
                })
                used += span
            }

            layout.items().filter { it.parent == currentPanel && it.kind != "profile" }.forEach { node ->
                place(panelTile(node.name, if (node.kind == "page") "Sayfa" else "Klasör", null) {
                    selectPanel(node.id)
                }, 1)
            }
            val buttons = deck.optJSONArray("buttons") ?: JSONArray()
            for (index in 0 until buttons.length()) {
                val record = buttons.getJSONObject(index)
                val id = record.getString("id")
                if (layout.panelFor(id) != currentPanel) continue
                val tile = panelTile(record.getString("label"), actions[record.getString("action")].orEmpty(),
                    layout.colorFor(id)) {
                    if (editing) editButton(record) else execute(it, record)
                }
                place(tile, layout.spanFor(id))
            }
            place(create, 1)
        } else {
            content.addView(create, LinearLayout.LayoutParams(dp(68), dp(68)))
            if (status == "Bağlantı bekleniyor") {
                content.addView(label(status, 16, muted).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        scroll.addView(content)
        display(scroll)
    }

    private fun updateConnection() {
        statusView.text = status
        statusView.setTextColor(if (connected()) DeckUi.success else muted)
    }

    private fun openMenu() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (connected()) {
            options += (if (editing) "Düzenlemeyi bitir" else "Butonları düzenle") to {
                editing = !editing
                mixer = false
                obs = false
                performance = false
                renderBody()
            }
            options += "Profiller ve paneller" to ::choosePanel
            options += "Panel düzeni" to ::managePanel
            options += "PC Performansı" to {
                mixer = false
                obs = false
                performance = true
                renderBody()
            }
        }
        options += "PC ile eşleştir" to ::showPairing
        if (preferences.contains("identity")) options += "Telefon anahtarını kaldır" to {
            DeckUi.dialog(this).setTitle("Telefon anahtarını kaldır")
                .setMessage("Windows ile bağlantı kurmak için tekrar eşleştirme gerekecek.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Kaldır") { _, _ -> link.forget() }.showDeck()
        }
        options += (if (kiosk.fullScreen()) "Bakım moduna geç" else "Kiosk") to ::showKiosk
        options += "Ekran" to ::showScreenSettings
        options += "Sürüm" to ::showVersion
        DeckUi.choices(DeckUi.dialog(this).setTitle("Ayarlar"), options.map { it.first }) { index ->
            options[index].second()
        }.setNegativeButton("Kapat", null).showDeck()
    }

    private fun showVersion() {
        val version = packageManager.getPackageInfo(packageName, 0)
        DeckUi.dialog(this).setTitle("KenshixDeck")
            .setMessage("Sürüm ${version.versionName} / ${version.longVersionCode}\n${BuildConfig.GIT_SHA}")
            .setPositiveButton("Kapat", null).showDeck()
    }
    private fun selectPanel(id: String?) {
        currentPanel = layout().item(id)?.id
        preferences.edit().putString("current_panel", currentPanel).apply()
        mixer = false
        obs = false
        performance = false
        renderBody()
    }

    private fun choosePanel() {
        val panels = layout().items()
        val captions = listOf("Ana panel") + panels.map { panel ->
            val prefix = when (panel.kind) { "profile" -> "Profil"; "page" -> "Sayfa"; else -> "Klasör" }
            val parent = panels.firstOrNull { it.id == panel.parent }?.name
            if (parent == null) "$prefix · ${panel.name}" else "$prefix · $parent / ${panel.name}"
        }
        DeckUi.choices(DeckUi.dialog(this).setTitle("Profiller ve paneller"), captions) { index ->
            selectPanel(panels.getOrNull(index - 1)?.id)
        }
            .setNeutralButton("Düzenle") { _, _ -> managePanel() }.setNegativeButton("Kapat", null).showDeck()
    }

    private fun managePanel() {
        if (saving) return
        val model = layout()
        val revision = deck.getLong("revision")
        val item = model.item(currentPanel)
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries += "Yeni profil" to {
            panelName("Yeni profil", null) { label ->
                val id = model.add(label, null, "profile")
                savePanel(revision, model) { selectPanel(id) }
            }
        }
        if (item != null) {
            if (item.kind == "profile") entries += "Sayfa ekle" to {
                panelName("Yeni sayfa", null) { label ->
                    val id = model.add(label, item.id, "page")
                    savePanel(revision, model) { selectPanel(id) }
                }
            }
            entries += "Klasör ekle" to {
                panelName("Yeni klasör", null) { label ->
                    val id = model.add(label, item.id, "folder")
                    savePanel(revision, model) { selectPanel(id) }
                }
            }
            entries += "Adını değiştir" to {
                panelName("Panel adı", item.name) { label ->
                    model.rename(item.id, label)
                    savePanel(revision, model)
                }
            }
            if (model.empty(item.id)) entries += "Boş paneli sil" to {
                DeckUi.dialog(this).setTitle("Boş paneli sil").setMessage(item.name)
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Sil") { _, _ ->
                        model.remove(item.id)
                        savePanel(revision, model) { selectPanel(item.parent) }
                    }.showDeck()
            }
        }
        DeckUi.choices(DeckUi.dialog(this).setTitle(item?.name ?: "Panel düzeni"), entries.map { it.first }) { index ->
            entries[index].second.invoke()
        }
            .setNegativeButton("Kapat", null).showDeck()
    }

    private fun panelName(title: String, old: String?, change: (String) -> Unit) {
        val field = EditText(this).apply {
            DeckUi.field(this)
            hint = "Ad"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(60))
            setText(old)
        }
        val wrapper = DeckUi.editor(this).apply { addView(field) }
        val dialog = DeckUi.dialog(this).setTitle(title).setView(DeckUi.scrollForm(wrapper))
            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", null).showDeck()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim()
            if (value.isEmpty()) { field.error = "Ad gerekli"; return@setOnClickListener }
            dialog.dismiss()
            change(value)
        }
    }

    private fun savePanel(expected: Long, model: PanelLayout, after: () -> Unit = {}) {
        val next = JSONObject(deck.toString()).put("layout", model.snapshot())
        sendDeck(expected, next, after)
    }

    private fun showKiosk() {
        val dialog = DeckUi.dialog(this).setNegativeButton("Vazgeç", null)
        when {
            kiosk.fullScreen() -> dialog.setTitle("Bakım moduna geç")
                .setMessage("Android ana ekranına ve ayarlarına tekrar erişilebilecek.")
                .setPositiveButton("Kiosk çıkışı") { _, _ ->
                    runCatching { kiosk.disable() }
                        .onSuccess { notify("Bakım modu açık.") }
                        .onFailure { notify("Kiosk tamamen kapatılamadı.") }
                }
            kiosk.isOwner() -> dialog.setTitle("Kiosk modunu aç")
                .setMessage("KenshixDeck ana ekran olarak açılır. Çıkmak için Ayarlar menüsünden bakım moduna geç.")
                .setPositiveButton("Kiosku aç") { _, _ ->
                    runCatching {
                        kiosk.enable { active -> notify(if (active) "Kiosk etkin." else "Android kiosk kilidini başlatamadı.") }
                    }
                        .onFailure { notify("Android kiosk kilidini başlatamadı.") }
                }
                .setNeutralButton("Android ayarları") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            else -> dialog.setTitle("Kiosk")
                .setMessage("Tam kiosk için Android cihaz yönetimi kurulumu gerekli. Tam ekran arayüzü burada deneyebilirsin.")
                .setPositiveButton("Tam ekranı dene") { _, _ -> kiosk.preview(true) }
        }
        dialog.showDeck()
    }

    private fun showScreenSettings() {
        val panel = DeckUi.editor(this)
        fun number(caption: String, value: Int): EditText {
            panel.addView(label(caption, 13, muted).apply { setPadding(0, dp(8), 0, dp(6)) })
            return EditText(this).apply {
                DeckUi.field(this)
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                panel.addView(this)
            }
        }
        val normal = number("Aktif parlaklık % (0: Android ayarı)", if (activeBrightness < 0) 0 else (activeBrightness * 100).toInt())
        val idle = number("Boşta parlaklık %", (idleBrightness * 100).toInt())
        val delay = number("Boşta bekleme süresi (saniye)", idleSeconds)
        DeckUi.dialog(this).setTitle("Ekran").setView(DeckUi.scrollForm(panel)).setNegativeButton("Vazgeç", null)
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
            }.showDeck()
    }

    private fun showPairing() {
        val fields = DeckUi.editor(this)
        val code = EditText(this).apply {
            DeckUi.field(this)
            hint = "PC’deki eşleştirme kodu"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(100))
        }
        val lan = EditText(this).apply {
            DeckUi.field(this)
            hint = "Wi-Fi için yerel IPv4 (isteğe bağlı)"
            isSingleLine = true
            setText(preferences.getString("lan", ""))
        }
        fields.addView(code)
        fields.addView(lan, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        DeckUi.dialog(this).setTitle("PC ile eşleştir").setView(DeckUi.scrollForm(fields))
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Eşleştir") { _, _ -> link.pair(code.text.toString(), lan.text.toString().trim()) }
            .showDeck()
    }

    private fun editButton(current: JSONObject?) {
        if (saving) return
        val originalRevision = deck.getLong("revision")
        val model = layout()
        val id = current?.getString("id") ?: UUID.randomUUID().toString()
        val panelId = currentPanel
        val panel = DeckUi.editor(this)
        val title = EditText(this).apply {
            DeckUi.field(this)
            hint = "Buton adı"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(60))
            setText(current?.optString("label"))
        }
        val action = DeckUi.choose(this, "Eylem", actions.values.toList(),
            actions.keys.indexOf(current?.optString("action")).coerceAtLeast(0))
        panel.addView(title)
        panel.addView(action)
        val span = DeckUi.choose(this, "Genişlik", listOf("Standart genişlik", "Çift genişlik"), model.spanFor(id) - 1)
        val tints = linkedMapOf("Varsayılan renk" to null, "Petrol" to "#143731",
            "Gece mavisi" to "#182B49", "Mürdüm" to "#3B2649", "Kiremit" to "#442C28")
        val tint = DeckUi.choose(this, "Renk", tints.keys.toList(),
            tints.values.indexOf(model.colorFor(id)).coerceAtLeast(0))
        panel.addView(span)
        panel.addView(tint)
        val dialog = DeckUi.dialog(this)
            .setTitle(if (current == null) "Yeni buton" else "Butonu düzenle")
            .setView(DeckUi.scrollForm(panel)).setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", null)
        if (current != null) dialog.setNeutralButton("Sil") { _, _ ->
            DeckUi.dialog(this).setMessage("Buton silinsin mi?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil") { _, _ -> saveEdit(originalRevision, id, null, model) }.showDeck()
        }
        dialog.showDeck().also { displayed ->
            displayed.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = title.text.toString().trim()
                if (input.isEmpty()) { title.error = "Buton adı gerekli"; return@setOnClickListener }
                val replacement = JSONObject().put("id", id).put("label", input)
                    .put("action", actions.keys.elementAt(action.selectedItemPosition))
                model.place(id, panelId, span.selectedItemPosition + 1, tints.values.elementAt(tint.selectedItemPosition))
                saveEdit(originalRevision, id, replacement, model)
                displayed.dismiss()
            }
        }
    }

    private fun applyDeck(snapshot: JSONObject) {
        deck = snapshot
        if (layout().item(currentPanel) == null) currentPanel = null
        preferences.edit().putString("cached_deck", snapshot.toString()).putString("current_panel", currentPanel).apply()
    }

    private fun saveEdit(expected: Long, id: String, replacement: JSONObject?, model: PanelLayout) {
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
        if (replacement == null) model.unplace(id)
        val payload = JSONObject().put("revision", expected).put("buttons", next).put("layout", model.snapshot())
        sendDeck(expected, payload)
    }

    private fun sendDeck(expected: Long, payload: JSONObject, after: () -> Unit = {}) {
        if (saving) return
        if (deck.getLong("revision") != expected) {
            notify("Panel değişti. Yeniden dene.")
            return
        }
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
                        else after()
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
        val restingBackground = view.background
        view.isEnabled = false
        link.request(message("execute").put("operation", operation).put("button", record.getString("id"))) { result ->
            handler.post {
                if (isDestroyed) return@post
                view.isEnabled = true
                val state = if (result?.optString("operation") == operation) result.optString("status") else "unknown"
                view.background = DeckUi.shape(this, surface, 22, if (state == "ok") DeckUi.success else DeckUi.danger)
                handler.postDelayed({ view.background = restingBackground }, 520)
                if (state != "ok") notify(if (state == "failed") "Windows işlemi tamamlayamadı." else "İşlem sonucu doğrulanamadı.")
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun notify(message: String) {
        if (!::body.isInitialized || isDestroyed) return
        noticeView?.let(body::removeView)
        val view = label(message, 14, text, true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = DeckUi.shape(context, DeckUi.raised, 16)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        noticeView = view
        body.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(18), 0, dp(18), dp(14))
        })
        DeckUi.appear(view, 7)
        handler.postDelayed({
            body.removeView(view)
            if (noticeView === view) noticeView = null
        }, 2800)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        DeckUi.label(this, value, size, color, if (bold) 650 else 400)
    private fun panelTile(title: String, action: String, tint: String?, tapped: (View) -> Unit) =
        column().apply {
            gravity = Gravity.BOTTOM
            isClickable = true
            isFocusable = true
            setPadding(dp(13), dp(12), dp(11), dp(12))
            DeckUi.touch(this, tint?.let(Color::parseColor) ?: surface, 22)
            addView(label(when (action) {
                "Sayfa" -> "▤"
                "Klasör" -> "▱"
                "Ses Mikseri" -> "≋"
                else -> "↗"
            }, 19, accent, true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, 0, 0, dp(9))
            })
            addView(label(title, 16, text, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (action.isNotEmpty() && action != title) {
                addView(label(action, 11, muted).apply {
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            setOnClickListener(tapped)
        }

    private fun button(value: String, tapped: (View) -> Unit) = DeckUi.action(this, value, run = tapped).apply {
        textSize = if (value.length == 1) 23f else 13f
        minWidth = 0
    }
}