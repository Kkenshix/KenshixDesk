package com.kenshix.deck

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import kotlin.math.roundToInt
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
        "macro" to "Tuş, tıklama veya makro", "mixer" to "Ses Mikseri", "spotify" to "Spotify", "play_pause" to "Oynat / duraklat",
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
    private val buttonArt by lazy { ButtonArt(link) }
    private val kiosk by lazy { Kiosk(this) }
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var profilesView: LinearLayout
    private var profileHeaderKey = ""
    private var transitionSide = 0
    private var pageBadge: View? = null
    private lateinit var body: FrameLayout
    private lateinit var battery: BroadcastReceiver
    private lateinit var screenPower: BroadcastReceiver
    private var openClockOnWake = false
    private var batteryText = ""
    private var status = "Eşleştirme gerekli"
    private var deck = JSONObject().put("revision", 0).put("buttons", JSONArray())
    private var currentPanel: String? = null
    private var editing = false
    private var mixer = false
    private var showingSpotify = false
    private var mixerSwipe = false
    private var touchInProgress = false
    private var openDialogs = 0
    private var obs = false
    private var performance = false
    private var showingVideo = false
    private var browserPlayer: BrowserVideoPlayer? = null
    private var queuedVideo: BrowserOffer? = null
    private var lastBrowserVideo: String? = null
    private var showingWindows = false
    private var showingInput = false
    private var mediaGesture = false
    private var dimmed = false
    private var standby = false
    private lateinit var header: View
    private lateinit var headerDivider: View
    private var downX = 0f
    private var downY = 0f
    private var idleSeconds = 15
    private var standbySeconds = 45
    private var activeBrightness = -1f
    private var idleBrightness = 0.08f
    private var eatingGesture = false
    private var inactive = false
    private var saving = false
    private var lastDestination: String? = null
    private var noticeView: TextView? = null
    private var activeRoutine: Pair<String, String>? = null
    private val animatedTiles = mutableListOf<MediaTile>()
    private val preferences by lazy { getSharedPreferences("deck", MODE_PRIVATE) }
    private val dim = Runnable {
        if (!inactive && !touchInProgress && openDialogs == 0) {
            dimmed = true
            brightness(effectiveIdleBrightness())
            updateAnimations()
        }
    }
    private val showStandby = Runnable { enterStandby() }
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
        idleSeconds = preferences.getInt("dim_seconds", 15).coerceIn(5, 299)
        standbySeconds = preferences.getInt("standby_seconds", 45).coerceIn(idleSeconds + 1, 300)
        deck = runCatching { JSONObject(preferences.getString("cached_deck", "").orEmpty()) }.getOrDefault(deck)
        currentPanel = layout().item(preferences.getString("current_panel", null))?.id
        status = if (preferences.contains("identity")) "Bağlantı bekleniyor" else "Eşleştirme gerekli"
        body = FrameLayout(this)
        link = DeckLink(Credentials(preferences), { connection, snapshot ->
            handler.post {
                if (isDestroyed) return@post
                val changed = status != connection
                status = connection
                if (snapshot != null) applyDeck(snapshot)
                if (changed && !connected()) {
                    performance = false
                    obs = false
                    editing = false
                }
                updateConnection()
                if (changed || (snapshot != null && !mixer && !obs && !performance)) renderBody()
            }
        }, { message -> handler.post { if (!isDestroyed) notify(message) } }, { video ->
            handler.post {
                if (!isDestroyed) {
                    queuedVideo = video?.let(BrowserOffer::parse)
                    openQueuedVideo()
                }
            }
        })
        render()
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
        screenPower = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    openClockOnWake = true
                    if (!standby && kiosk.enabled() && kiosk.locked()) {
                        handler.postDelayed({
                            val power = getSystemService(android.os.PowerManager::class.java)
                            if (openClockOnWake && !isDestroyed && !power.isInteractive && kiosk.locked()) {
                                runCatching {
                                    startActivity(Intent(this@MainActivity, ClockWakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                                }
                            }
                        }, 170)
                    }
                }
                if (intent.action == Intent.ACTION_SCREEN_ON && !inactive && openClockOnWake) {
                    openClockOnWake = false
                    enterStandby()
                }
            }
        }
        val powerEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenPower, powerEvents, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenPower, powerEvents)
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
        if (openClockOnWake) {
            openClockOnWake = false
            enterStandby()
        } else wake()
        renderBody()
    }

    override fun onStop() {
        inactive = true
        browserPlayer?.release()
        browserPlayer = null
        touchInProgress = false
        link.pause(true)
        handler.removeCallbacks(dim)
        handler.removeCallbacks(showStandby)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(battery)
        unregisterReceiver(screenPower)
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
        if (browserPlayer != null) { closeBrowserVideo(); return }
        if (kiosk.locked()) return
        if (mixer || obs || performance || editing || showingSpotify) {
            mixer = false
            showingSpotify = false
            obs = false
            performance = false
            editing = false
            renderBody()
        } else {
            if (!kiosk.fullScreen()) super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (browserPlayer != null) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchInProgress = true
            downX = event.x
            downY = event.y
            val clock = body.getChildAt(0) as? StandbyView
            mixerSwipe = (body.getChildAt(0) as? MixerView)?.ownsSliderTouch(event.rawX, event.rawY) == false
            mediaGesture = standby && clock?.ownsMediaTouch(event.rawX, event.rawY) == true
            eatingGesture = !mediaGesture && (dimmed || standby)
            if (!mediaGesture) wake()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchInProgress = false
            if (!mediaGesture) restartIdleTimer()
        }
        if (mediaGesture) {
            val delivered = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) mediaGesture = false
            return delivered
        }
        if (eatingGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                eatingGesture = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && (!mixer || mixerSwipe) && !obs &&
            !performance && !showingSpotify && !showingVideo && !showingWindows && !showingInput && !editing && downY >= header.bottom) {
            val horizontal = event.x - downX
            if (kotlin.math.abs(horizontal) > dp(88) && kotlin.math.abs(event.y - downY) < dp(52)) {
                val panels = layout().items()
                val item = panels.firstOrNull { it.id == currentPanel }
                val direction = if (horizontal > 0) 1 else -1
                val profiles = listOf<String?>(null) + panels.filter { it.kind == "profile" }.map { it.id }
                val pages = panels.filter { it.kind == "page" && it.parent == item?.parent }.map { it.id }
                val pageIndex = if (item?.kind == "page") pages.indexOf(item.id) + direction else -1
                val activeProfile = layout().profileFor(currentPanel)?.id
                val profileIndex = profiles.indexOf(activeProfile) + direction
                if (mixer || (currentPanel == null && direction > 0) ||
                    (activeProfile != null && profileIndex == 0 && pageIndex !in pages.indices)) {
                    val profile = if (mixer && direction > 0) profiles.getOrNull(1) else null
                    if (!mixer || direction < 0 || profile != null) {
                        cancelTouch(event)
                        transitionSide = direction
                        if (mixer) openProfile(profile, badge = true) else openMixer()
                        return true
                    }
                }
                val movingPage = pageIndex in pages.indices
                val target = if (movingPage) pages[pageIndex] else profiles.getOrNull(profileIndex)
                if (movingPage || profileIndex in profiles.indices) {
                    cancelTouch(event)
                    transitionSide = direction
                    if (movingPage) selectPanel(target, badge = true) else openProfile(target, badge = true)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun openMixer() {
        mixer = true
        renderBody()
        showPageName("Ses Mikseri")
    }

    private fun enterStandby() {
        if (inactive || browserPlayer != null || touchInProgress || openDialogs > 0 || !::link.isInitialized) return
        handler.removeCallbacks(dim)
        handler.removeCallbacks(showStandby)
        dimmed = true
        standby = true
        brightness(effectiveIdleBrightness())
        renderBody()
    }

    private fun wake() {
        if (standby) {
            standby = false
            renderBody()
        }
        dimmed = false
        updateAnimations()
        brightness(activeBrightness)
        restartIdleTimer()
    }

    private fun restartIdleTimer() {
        handler.removeCallbacks(dim)
        handler.removeCallbacks(showStandby)
        if (inactive || standby || browserPlayer != null || touchInProgress || openDialogs > 0) return
        handler.postDelayed(dim, idleSeconds * 1000L)
        handler.postDelayed(showStandby, standbySeconds * 1000L)
    }

    internal fun watchDialog(decor: View) {
        openDialogs++
        wake()
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                openDialogs--
                restartIdleTimer()
                handler.post { openQueuedVideo() }
            }
        })
    }

    private fun openQueuedVideo() {
        val offer = queuedVideo ?: return
        if (inactive || openDialogs > 0 || saving || lastBrowserVideo == offer.id ||
            preferences.getString("closed_browser_video", null) == offer.id) return
        lastBrowserVideo = offer.id
        val next = runCatching {
            BrowserVideoPlayer(this, offer, ::closeBrowserVideo) {
                recordBrowserVideo(offer.id, "playing")
            }
        }.getOrElse {
            notify("Telefonun video oynatıcısı açılamadı.")
            return
        }
        browserPlayer?.release()
        browserPlayer = next
        val wasStandby = standby
        wake()
        if (!wasStandby) renderBody()
    }

    private fun closeBrowserVideo() {
        val player = browserPlayer ?: return
        preferences.edit().putString("closed_browser_video", player.offer.id).apply()
        recordBrowserVideo(player.offer.id, "closed")
        player.release()
        browserPlayer = null
        renderBody()
        wake()
    }

    private fun recordBrowserVideo(id: String, state: String) {
        link.request(message("browser_video_event").put("id", id).put("event", state)) {}
    }

    private fun updateAnimations() {
        animatedTiles.forEach(MediaTile::update)
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
        val image = DeckUi.dragon(this).apply {
            background = DeckUi.shape(context, DeckUi.BLACK, 13)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        top.addView(image, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(13) })
        val brand = column().apply {
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("KenshixDeck", 21, text, true).apply { letterSpacing = -.03f })
                profilesView = row().apply { gravity = Gravity.CENTER_VERTICAL }
                addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(profilesView)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            })
            statusView = label(status, 11, accent).apply { setPadding(0, dp(5), 0, 0) }
            updateConnection()
            addView(statusView)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clockView = label("", 12, muted).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "Saat ve performans ekranı"
            setOnClickListener { enterStandby() }
        }
        top.addView(clockView, LinearLayout.LayoutParams(-2, -2))
        top.addView(button("Video") {
            showingVideo = !showingVideo
            showingWindows = false
            showingInput = false
            renderBody()
        }.apply { contentDescription = "Video sayfası" }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6) })
        val create = button("+") {
            if (connected()) editButton(null)
            else if (status == "Eşleştirme gerekli") showPairing()
            else notify("Buton eklemek için PC bağlantısı gerekli.")
        }.apply {
            contentDescription = "Yeni buton"
            setPadding(0, 0, 0, 0)
        }
        top.addView(create, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val menu = button("Ayarlar") { openMenu() }.apply {
            contentDescription = "Ayarlar"
            DeckUi.touch(this, surface, 16)
        }
        top.addView(menu, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        header = top
        root.addView(top)
        headerDivider = View(this).apply { setBackgroundColor(line) }
        root.addView(headerDivider, LinearLayout.LayoutParams(-1, dp(1)))
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


        animatedTiles.clear()
        val destination = when {
            browserPlayer != null -> "browser_video"
            standby -> "standby"
            showingSpotify -> "spotify"
            showingVideo -> "video"
            showingWindows -> "windows"
            showingInput -> "input"
            performance && connected() -> "performance"
            obs && connected() -> "obs"
            mixer -> "mixer"
            else -> currentPanel ?: "deck"
        }
        val animate = lastDestination != destination
        lastDestination = destination
        renderProfileStrip()
        fun display(view: View) {
            val previous = body.getChildAt(0) as? ScrollView
            val keepScroll = !animate && previous != null && view is ScrollView
            val offset = if (keepScroll) previous?.scrollY ?: 0 else 0
            body.removeAllViews()
            body.addView(view, FrameLayout.LayoutParams(-1, -1))
            if (keepScroll) view.post { view.scrollTo(0, offset) }
            if (animate && transitionSide != 0 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                view.translationX = dp(32).toFloat() * transitionSide
                view.alpha = 0.6f
                view.animate().translationX(0f).alpha(1f).setDuration(370)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.45f)).start()
            } else if (animate) DeckUi.appear(view)
            transitionSide = 0
        }
        header.visibility = if (standby || browserPlayer != null) View.GONE else View.VISIBLE
        headerDivider.visibility = header.visibility
        browserPlayer?.let { player ->
            if (body.getChildAt(0) !== player) display(player)
            return
        }
        if (standby) {
            if (body.getChildAt(0) !is StandbyView) display(StandbyView(this, link))
            return
        }
        if (showingSpotify) {
            if (body.getChildAt(0) !is SpotifyPage) display(SpotifyPage(this, link) { showingSpotify = false; renderBody() })
            return
        }
        if (showingVideo) {
            if (body.getChildAt(0) !is VideoPage) display(VideoPage(this, link) { showingVideo = false; renderBody() })
            return
        }
        if (showingWindows) {
            if (body.getChildAt(0) !is WindowPage) display(WindowPage(this, link) { showingWindows = false; renderBody() })
            return
        }
        if (showingInput) {
            if (body.getChildAt(0) !is InputPage) display(InputPage(this, link) { showingInput = false; renderBody() })
            return
        }
        if (performance && connected()) {
            display(PerformanceView(this, link) { performance = false; renderBody() })
            return
        }
        if (obs && connected()) {
            display(ObsView(this, link) { obs = false; renderBody() })
            return
        }
        if (mixer) {
            if (body.getChildAt(0) !is MixerView) display(MixerView(this, link, { mixer = false; renderBody() }, ::notify))
            return
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(18), dp(18), dp(10), dp(18)) }
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateAnimations()
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            updateAnimations()
        }
        if (connected() || rows().isNotEmpty()) {
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
                    addView(button(if (selection.kind == "profile") "Sayfalar" else "Panel seç") { choosePanel() })
                    if (selection.kind == "profile") addView(button("+ Sayfa") { addPage() })
                }
                content.addView(path)
            }
            val columns = 5
            val gap = dp(8)
            val screenWidth = windowManager.currentWindowMetrics.bounds.width()
            val width = (screenWidth - root.paddingLeft - root.paddingRight - dp(28)).coerceAtLeast(180)
            val scale = screenWidth / 1600f
            val cell = (width - columns * gap) / columns
            var gridRow = row()
            var used = width
            fun place(tile: View, span: Int, widthPx: Int? = null, heightPx: Int? = null) {
                val tileWidth = (widthPx?.let { (it * scale).roundToInt() } ?: (cell * span + gap * (span - 1)))
                    .coerceAtMost(width - gap)
                val tileHeight = heightPx?.let { (it * scale).roundToInt() } ?: dp(112)
                if (used + tileWidth + gap > width) {
                    gridRow = row()
                    content.addView(gridRow)
                    used = 0
                }
                gridRow.addView(tile, LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                    marginEnd = gap
                    bottomMargin = gap
                })
                used += tileWidth + gap
            }

            layout.items().filter { it.parent == currentPanel && it.kind != "profile" }.forEach { node ->
                place(panelTile(node.name, if (node.kind == "page") "Sayfa" else "Klasör", null) {
                    transitionSide = 1
                    selectPanel(node.id, badge = true)
                }, 1)
            }
            val buttons = deck.optJSONArray("buttons") ?: JSONArray()
            var spotifyFooter: View? = null
            var spotifyHeight = dp(66)
            for (index in 0 until buttons.length()) {
                val record = buttons.getJSONObject(index)
                val id = record.getString("id")
                if (layout.panelFor(id) != currentPanel) continue
                val action = record.getString("action")
                val onClick: (View) -> Unit = {
                    if (editing) editButton(record) else execute(it, record)
                }
                if (currentPanel == null && action == "spotify" && spotifyFooter == null) {
                    spotifyHeight = layout.heightFor(id)?.let { (it * scale).roundToInt() } ?: dp(66)
                    spotifyFooter = spotifyTile(record, layout.colorFor(id), onClick)
                    continue
                }
                val tile = panelTile(record.getString("label"), actions[action].orEmpty(), layout.colorFor(id), onClick)
                if (!editing) {
                    attachGestures(tile, record)
                    if (record.getString("action") == "obs_toggle_record") watchRecording(tile)
                }
                record.optString("art").takeIf { it.isNotEmpty() }?.let { image ->
                    val picture = buttonArt.tile(this, image) { !dimmed && !inactive }
                    tile.removeViewAt(0)
                    tile.addView(picture, 0, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(5) })
                    animatedTiles.add(picture)
                }
                place(tile, layout.spanFor(id), layout.widthFor(id), layout.heightFor(id))
            }
            spotifyFooter?.let { footer ->
                content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
                content.addView(footer, LinearLayout.LayoutParams(-1, spotifyHeight).apply {
                    topMargin = dp(6)
                    marginEnd = dp(8)
                })
            }
        } else if (status == "Bağlantı bekleniyor") {
            content.addView(label(status, 16, muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        scroll.addView(content)
        display(scroll)
    }

    private fun spotifyTile(record: JSONObject, tint: String?, onClick: (View) -> Unit) = row().apply {
        val name = record.getString("label")
        contentDescription = name
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), 0, dp(17), 0)
        DeckUi.touch(this, DeckUi.tileShade(tint), 15, DeckUi.border)
        setOnClickListener(onClick)
        if (!editing) attachGestures(this, record)
        val image = record.optString("art")
        val icon = if (image.isEmpty()) label("♫", 26, accent, true).apply {
            gravity = Gravity.CENTER
        } else buttonArt.tile(this@MainActivity, image) { !dimmed && !inactive }.also(animatedTiles::add)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(13) })
        addView(column().apply {
            addView(label(name, 17, text, true))
            addView(label("Oynatma ve Spotify sesi", 12, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("›", 27, accent, true))
    }

    private fun watchRecording(tile: LinearLayout) {
        val title = tile.getChildAt(1) as TextView
        val poll = object : Runnable {
            override fun run() {
                link.request(message("obs_status")) { result ->
                    handler.post {
                        if (!tile.isAttachedToWindow) return@post
                        title.text = when {
                            result?.optString("type") != "obs_status" -> "OBS Kaydı"
                            result.optBoolean("recording") -> "Kaydı Durdur"
                            else -> "Kaydı Başlat"
                        }
                        handler.postDelayed(this, 1800)
                    }
                }
            }
        }
        tile.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { handler.post(poll) }
            override fun onViewDetachedFromWindow(view: View) { handler.removeCallbacks(poll) }
        })
    }

    private fun updateConnection() {
        statusView.text = status
        statusView.setTextColor(if (connected()) text else muted)
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
            options += "Profil / sayfa seç" to ::choosePanel
            options += "Buton ara / sabitlenenler" to ::searchButtons
            options += "Yeni profil" to ::addProfile
            if (layout().profileFor(currentPanel) != null) options += "Yeni sayfa" to ::addPage
            options += "Ad değiştir / klasör / sil" to ::managePanel
            options += "Video" to { showingVideo = true; showingWindows = false; showingInput = false; renderBody() }
            options += "Windows pencereleri" to { showingWindows = true; showingVideo = false; showingInput = false; renderBody() }
            options += "Klavye ve touchpad" to { showingInput = true; showingWindows = false; showingVideo = false; renderBody() }
            options += "PC Performansı" to {
                mixer = false
                obs = false
                performance = true
                showingVideo = false
                showingWindows = false
                renderBody()
            }
        }
        options += "Bağlantı tanılama" to {
            DeckUi.dialog(this).setTitle("Bağlantı tanılama").setMessage(link.diagnostics())
                .setPositiveButton("Kapat", null).showDeck()
        }
        options += "Saat ekranı kartları" to ::clockCards
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
        fun show(state: JSONObject?) {
            val checked = state?.optLong("checked_unix")?.takeIf { it > 0 }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) } ?: "—"
            val host = state?.optString("version")?.takeIf { it.isNotEmpty() }?.let {
                "Windows $it / ${state.optInt("build")}\n${state.optString("commit")}"
            } ?: "Windows bağlantısı bekleniyor"
            DeckUi.dialog(this).setTitle("KenshixDeck")
                .setMessage("Android ${version.versionName} / ${version.longVersionCode}\n${BuildConfig.GIT_SHA}\n\n$host\n\n${state?.optString("message").orEmpty()}\nSon kontrol $checked")
                .setPositiveButton("Kapat", null).showDeck()
        }
        if (::link.isInitialized && connected()) {
            link.request(message("update_status")) { result ->
                handler.post { if (!isDestroyed) show(result?.optJSONObject("state")) }
            }
        } else show(null)
    }

    private fun renderProfileStrip() {
        if (!::profilesView.isInitialized) return
        val model = layout()
        val profiles = listOf(Panel("", "Ana panel", null, "profile")) + model.items().filter { it.kind == "profile" }
        val active = model.profileFor(currentPanel)?.id.orEmpty()
        val key = profiles.joinToString("|") { it.id + it.name } + ":$active"
        if (key == profileHeaderKey) return
        val animate = profileHeaderKey.isNotEmpty()
        profileHeaderKey = key
        profilesView.removeAllViews()
        var selected: View? = null
        for (profile in profiles.takeIf { it.size > 1 }.orEmpty()) {
            val highlighted = profile.id == active
            val tab = DeckUi.label(this, profile.name, 13, if (highlighted) accent else muted, if (highlighted) 700 else 500).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(220)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (highlighted) {
                    background = DeckUi.shape(context, DeckUi.accentPanel, 11, DeckUi.muted)
                    if (animate) {
                        alpha = .52f
                        this.animate().alpha(1f).setDuration(430).start()
                    }
                }
                setOnClickListener { openProfile(profile.id.ifEmpty { null }, badge = true) }
            }
            profilesView.addView(tab)
            if (highlighted) selected = tab
        }
        selected?.let { tab ->
            profilesView.post {
                (profilesView.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo((tab.left - dp(20)).coerceAtLeast(0), 0)
            }
        }
    }

    private fun openProfile(id: String?, badge: Boolean) {
        val model = layout()
        if (id == null) { selectPanel(null, badge); return }
        val remembered = preferences.getString("profile_page_$id", null)
        val next = remembered?.takeIf { model.profileFor(it)?.id == id } ?: id
        selectPanel(next, badge)
    }

    private fun selectPanel(id: String?, badge: Boolean = false) {
        val model = layout()
        val panel = model.item(id)
        currentPanel = panel?.id
        showingSpotify = false
        showingVideo = false
        showingWindows = false
        showingInput = false
        preferences.edit().putString("current_panel", currentPanel).apply {
            val profile = model.profileFor(currentPanel)
            if (profile != null) putString("profile_page_${profile.id}", currentPanel)
        }.apply()
        mixer = false
        obs = false
        performance = false
        renderBody()
        if (badge) showPageName(panel?.name ?: "Ana panel")
    }

    private fun showPageName(name: String) {
        pageBadge?.let(body::removeView)
        val badge = label(name, 16, text, true).apply {
            setPadding(dp(19), dp(12), dp(19), dp(12))
            background = DeckUi.shape(context, DeckUi.accentPanel, 18, Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
        }
        pageBadge = badge
        body.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        badge.animate().alpha(1f).setDuration(170).start()
        handler.postDelayed({
            if (pageBadge === badge) {
                badge.animate().alpha(0f).setDuration(240).withEndAction {
                    body.removeView(badge)
                    if (pageBadge === badge) pageBadge = null
                }.start()
            }
        }, 760)
    }

    private fun addProfile() {
        val model = layout()
        val revision = deck.getLong("revision")
        panelName("Yeni profil", null) { name ->
            val id = model.add(name, null, "profile")
            savePanel(revision, model) { selectPanel(id, true) }
        }
    }

    private fun addPage() {
        val model = layout()
        val profile = model.profileFor(currentPanel) ?: return
        val revision = deck.getLong("revision")
        panelName("${profile.name} · Yeni sayfa", null) { name ->
            val id = model.add(name, profile.id, "page")
            savePanel(revision, model) { selectPanel(id, true) }
        }
    }

    private fun rows(): List<JSONObject> {
        val data = deck.optJSONArray("buttons") ?: return emptyList()
        return (0 until data.length()).mapNotNull(data::optJSONObject)
    }

    private fun searchButtons() {
        val field = EditText(this).apply { DeckUi.field(this); hint = "Buton adı ara"; isSingleLine = true }
        val links = DeckUi.editor(this)
        val form = DeckUi.editor(this).apply { addView(field); addView(links) }
        var dialog: AlertDialog? = null
        fun refresh(query: String) {
            links.removeAllViews()
            val matches = rows().filter {
                it.optString("label").contains(query, ignoreCase = true)
            }.sortedByDescending { it.optJSONObject("behavior")?.optBoolean("pinned") == true }
            for (row in matches.take(50)) {
                val pinned = row.optJSONObject("behavior")?.optBoolean("pinned") == true
                links.addView(DeckUi.action(this, (if (pinned) "★  " else "") + row.optString("label")) {
                    dialog?.dismiss()
                    selectPanel(layout().panelFor(row.optString("id")))
                    if (pinned) execute(it, row)
                })
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = refresh(value?.toString().orEmpty())
            override fun afterTextChanged(value: android.text.Editable?) = Unit
        })
        refresh("")
        dialog = DeckUi.dialog(this).setTitle("Butonlar").setView(DeckUi.scrollForm(form))
            .setNegativeButton("Kapat", null).showDeck()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(tile: View, record: JSONObject) {
        val behavior = record.optJSONObject("behavior")
        fun target(key: String) = behavior?.optString(key)?.takeIf(String::isNotEmpty)?.let { id ->
            rows().firstOrNull { it.optString("id") == id }
        }
        val double = target("double_tap")
        val hold = target("long_press")
        tile.setOnLongClickListener {
            if (hold == null) openButtonOptions(record) else execute(it, hold)
            true
        }
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (double == null) tile.performClick()
                return true
            }
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (double != null) tile.performClick()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (double != null) execute(tile, double)
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                tile.performLongClick()
            }
        })
        tile.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
    }

    private fun openButtonOptions(record: JSONObject) {
        if (!connected()) { notify("Butonu düzenlemek için PC bağlantısı gerekli."); return }
        val operation = activeRoutine?.takeIf { it.first == record.getString("id") }?.second
        if (operation == null) {
            editButton(record)
        } else {
            DeckUi.dialog(this).setTitle(record.getString("label"))
                .setNegativeButton("Kapat", null)
                .setNeutralButton("Düzenle") { _, _ -> editButton(record) }
                .setPositiveButton("Makroyu iptal et") { _, _ ->
                    link.request(message("cancel_operation").put("operation", operation)) {}
                }.showDeck()
        }
    }

    private fun choosePanel() {
        val panels = layout().items()
        val captions = listOf("Ana panel") + panels.map { panel ->
            val prefix = when (panel.kind) { "profile" -> "Profil"; "page" -> "Sayfa"; else -> "Klasör" }
            val parent = panels.firstOrNull { it.id == panel.parent }?.name
            if (parent == null) "$prefix · ${panel.name}" else "$prefix · $parent / ${panel.name}"
        }
        DeckUi.choices(DeckUi.dialog(this).setTitle("Profiller ve paneller"), captions) { index ->
            val panel = panels.getOrNull(index - 1)
            if (panel?.kind == "profile") openProfile(panel.id, badge = true) else selectPanel(panel?.id, badge = true)
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

    private fun clockCards() {
        val order = preferences.getString("standby_cards","CPU,GPU,RAM").orEmpty().split(",").filter(String::isNotBlank).toMutableList()
        val default = listOf("CPU","GPU","RAM","Disk")
        val form = DeckUi.editor(this)
        fun rebuild() {
            form.removeAllViews()
            (order + default.filterNot(order::contains)).forEach { name ->
                form.addView(row().apply {
                    addView(DeckUi.action(this@MainActivity, if (name in order) "✓ $name" else "+ $name") {
                        if (!order.remove(name)) order.add(name)
                        rebuild()
                    }, LinearLayout.LayoutParams(0,dp(48),1f))
                    if (order.indexOf(name) > 0) addView(DeckUi.action(this@MainActivity, "↑") {
                        val position = order.indexOf(name)
                        if (position > 0) java.util.Collections.swap(order, position, position - 1)
                        rebuild()
                    }.apply { contentDescription = "$name kartını öne taşı" })
                })
            }
        }
        rebuild()
        DeckUi.dialog(this).setTitle("Saat ekranı kartları").setView(DeckUi.scrollForm(form))
            .setNegativeButton("Vazgeç",null).setPositiveButton("Kaydet") { _, _ ->
                preferences.edit().putString("standby_cards",order.joinToString(",")).apply()
            }.showDeck()
    }

    private fun effectiveIdleBrightness(): Float {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = preferences.getFloat("night_brightness", 0f)
        return if (night > 0f && (hour >= 22 || hour < 7)) night else idleBrightness
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
        val delay = number("Kararma süresi (saniye)", idleSeconds)
        val night = number("Gece parlaklığı % (22–07, 0 = kapalı)", (preferences.getFloat("night_brightness",0f)*100).toInt())
        val clockDelay = number("Saat ekranına geçiş (saniye)", standbySeconds)
        DeckUi.dialog(this).setTitle("Ekran").setView(DeckUi.scrollForm(panel)).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet") { _, _ ->
                val normalValue = normal.text.toString().toIntOrNull()
                val idleValue = idle.text.toString().toIntOrNull()
                val delayValue = delay.text.toString().toIntOrNull()
                val clockValue = clockDelay.text.toString().toIntOrNull()
                val nightValue = night.text.toString().toIntOrNull()
                if (normalValue == null || normalValue !in 0..100 ||
                    idleValue == null || idleValue !in 5..35 ||
                    nightValue == null || nightValue !in 0..35 || delayValue == null || delayValue !in 5..299 || clockValue == null || clockValue !in (delayValue + 1)..300) {
                    notify("Parlaklık ve süre değerlerini kontrol et.")
                    return@setPositiveButton
                }
                activeBrightness = if (normalValue == 0) -1f else normalValue / 100f
                idleBrightness = idleValue / 100f
                idleSeconds = delayValue
                standbySeconds = clockValue
                preferences.edit().putFloat("active_brightness", activeBrightness)
                    .putFloat("idle_brightness", idleBrightness).putInt("dim_seconds", idleSeconds)
                    .putInt("standby_seconds", standbySeconds).putFloat("night_brightness", nightValue / 100f).apply()
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
        val routine = RoutineEditor(this, current?.optJSONObject("routine"), actions)
        val behavior = current?.optJSONObject("behavior")
        val alternatives = rows().filter { it.optString("id") != id }
        val macros = alternatives.filter { it.optJSONObject("routine") != null }
        fun target(title: String, key: String, options: List<JSONObject>): DeckChoice {
            val saved = behavior?.optString(key)
            val index = options.indexOfFirst { it.optString("id") == saved } + 1
            return DeckUi.choose(this, title, listOf("Yok") + options.map { it.optString("label") }, index)
        }
        val shared = target("Paylaşılan makro", "shared_macro", macros)
        val double = target("Çift dokunma", "double_tap", alternatives)
        val hold = target("Basılı tutma", "long_press", alternatives)
        val confirm = CheckBox(this).apply { text = "Çalıştırmadan önce onay iste"; isChecked = behavior?.optBoolean("confirm") == true }
        val pinned = CheckBox(this).apply { text = "Sık kullanılanlara sabitle"; isChecked = behavior?.optBoolean("pinned") == true }
        panel.addView(shared)
        panel.addView(routine)
        panel.addView(double)
        panel.addView(hold)
        panel.addView(confirm)
        panel.addView(pinned)
        fun updateRoutine(index: Int) {
            val macro = actions.keys.elementAt(index) == "macro"
            shared.visibility = if (macro && macros.isNotEmpty()) View.VISIBLE else View.GONE
            routine.visibility = if (macro && shared.selectedItemPosition == 0) View.VISIBLE else View.GONE
        }
        updateRoutine(action.selectedItemPosition)
        action.onSelection = ::updateRoutine
        shared.onSelection = { updateRoutine(action.selectedItemPosition) }
        val span = DeckUi.choose(this, "Genişlik", listOf("Standart genişlik", "Çift genişlik"), model.spanFor(id) - 1)
        val tints = linkedMapOf("Varsayılan" to null, "Siyah" to "#000000",
            "Kömür" to "#161616", "Grafit" to "#282828", "Füme" to "#383838")
        model.colorFor(id)?.takeIf { it !in tints.values }?.let { tints["Kayıtlı özel ton"] = it }
        val tint = DeckUi.choose(this, "Renk", tints.keys.toList(),
            tints.values.indexOf(model.colorFor(id)).coerceAtLeast(0))
        panel.addView(span)
        fun sizeField(caption: String, value: Int?): EditText = EditText(this).apply {
            DeckUi.field(this)
            hint = caption
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value?.toString().orEmpty())
            panel.addView(this)
        }
        val width = sizeField("Genişlik (px, boşsa otomatik)", model.widthFor(id))
        val height = sizeField("Yükseklik (px, boşsa otomatik)", model.heightFor(id))
        panel.addView(tint)
        val clone = if (current != null) android.widget.CheckBox(this).apply {
            text = "Yeni kopya olarak kaydet"
            setTextColor(DeckUi.text)
            panel.addView(this)
        } else null
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
                val target = if (clone?.isChecked == true) UUID.randomUUID().toString() else id
                val replacement = JSONObject().put("id", target).put("label", input)
                    .put("action", actions.keys.elementAt(action.selectedItemPosition))
                val extra = JSONObject()
                alternatives.getOrNull(double.selectedItemPosition - 1)?.let { extra.put("double_tap", it.optString("id")) }
                alternatives.getOrNull(hold.selectedItemPosition - 1)?.let { extra.put("long_press", it.optString("id")) }
                if (confirm.isChecked) extra.put("confirm", true)
                if (pinned.isChecked) extra.put("pinned", true)
                val macro = macros.getOrNull(shared.selectedItemPosition - 1)
                if (replacement.getString("action") == "macro" && macro != null) {
                    extra.put("shared_macro", macro.optString("id"))
                } else if (replacement.getString("action") == "macro") {
                    val definition = routine.snapshot()
                    if (definition == null) {
                        displayed.setTitle("Makro adımlarını ve otomasyon alanlarını kontrol et")
                        return@setOnClickListener
                    }
                    replacement.put("routine", definition)
                }
                if (extra.length() > 0) replacement.put("behavior", extra)
                current?.optString("art")?.takeIf { it.isNotEmpty() }?.let { replacement.put("art", it) }
                val pixelWidth = width.text.toString().toIntOrNull()
                val pixelHeight = height.text.toString().toIntOrNull()
                if (width.text.isNotEmpty() && (pixelWidth == null || pixelWidth !in 80..1500)) {
                    width.error = "80–1500 px gir"; return@setOnClickListener
                }
                if (height.text.isNotEmpty() && (pixelHeight == null || pixelHeight !in 70..660)) {
                    height.error = "70–660 px gir"; return@setOnClickListener
                }
                model.place(target, panelId, span.selectedItemPosition + 1, tints.values.elementAt(tint.selectedItemPosition), pixelWidth, pixelHeight)
                saveEdit(originalRevision, target, replacement, model)
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

    private fun execute(view: View, record: JSONObject, confirmed: Boolean = false) {
        if (!connected()) { notify("Bu buton için PC bağlantısı gerekiyor"); return }
        if (!confirmed && record.optJSONObject("behavior")?.optBoolean("confirm") == true) {
            DeckUi.dialog(this).setTitle(record.optString("label"))
                .setMessage("Bu işlem çalıştırılsın mı?")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Çalıştır") { _, _ -> execute(view, record, true) }.showDeck()
            return
        }
        if (record.getString("action") == "obs_status") {
            obs = true
            renderBody()
            return
        }
        if (record.getString("action") == "mixer") {
            openMixer()
            return
        }
        if (record.getString("action") == "spotify") {
            showingSpotify = true
            renderBody()
            return
        }
        if (record.getString("action") == "macro" && activeRoutine != null) return
        val buttonId = record.getString("id")
        val operation = UUID.randomUUID().toString()
        val restingBackground = view.background
        view.isEnabled = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 66_000
        fun process(result: JSONObject?) {
            handler.post {
                if (isDestroyed) return@post
                view.isEnabled = true
                val state = if (result?.optString("operation") == operation) result.optString("status") else "unknown"
                if (state == "running" && android.os.SystemClock.elapsedRealtime() < deadline) {
                    activeRoutine = buttonId to operation
                    handler.postDelayed({
                        if (!isDestroyed) link.request(message("operation_status").put("operation", operation), ::process)
                    }, 450)
                    return@post
                }
                if (activeRoutine?.second == operation) activeRoutine = null
                if (record.optString("action") == "macro") {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    if (state == "ok") notify("Makro tamamlandı")
                }
                val tile = view as? LinearLayout
                val readout = tile?.getChildAt(tile.childCount - 1) as? TextView
                val caption = readout?.text
                view.background = DeckUi.shape(this, surface, 15, if (state == "ok") DeckUi.success else DeckUi.danger)
                if (tile != null && tile.childCount >= 3) readout?.text = if (state == "ok") "✓ Tamamlandı" else "! İşlem durumu"
                handler.postDelayed({
                    view.background = restingBackground
                    if (caption != null) readout?.text = caption
                }, 660)
                if (state != "ok") notify(when (state) {
                    "failed" -> "Windows işlemi tamamlayamadı."
                    "busy" -> "Başka bir makro çalışıyor."
                    "canceled" -> "Makro iptal edildi."
                    "timeout" -> "Makro zaman aşımına uğradı."
                    else -> "İşlem sonucu doğrulanamadı."
                })
            }
        }
        link.request(message("execute").put("operation", operation).put("button", buttonId), ::process)
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
            val fill = DeckUi.tileShade(tint)
            DeckUi.touch(this, fill, 15, DeckUi.border)
            clipToOutline = true
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
