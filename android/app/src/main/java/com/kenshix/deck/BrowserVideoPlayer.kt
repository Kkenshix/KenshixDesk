package com.kenshix.deck

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.SeekBar
import org.json.JSONObject

internal data class BrowserOffer(val id: String, val site: String, val key: String, val seconds: Int) {
    companion object {
        private val idPattern = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        private val youtube = Regex("[A-Za-z0-9_-]{11}")
        private val kick = Regex("[a-z0-9_-]{2,50}")

        fun parse(json: JSONObject): BrowserOffer? {
            val id = json.optString("id")
            val site = json.optString("site")
            val key = json.optString("key")
            val seconds = json.optInt("seconds", -1)
            if (!idPattern.matches(id) || seconds !in 0..86_400) return null
            val valid = when (site) {
                "youtube" -> youtube.matches(key)
                "kick" -> kick.matches(key)
                else -> false
            }
            return if (valid) BrowserOffer(id, site, key, seconds) else null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class BrowserVideoPlayer(
    context: Context,
    val offer: BrowserOffer,
    close: () -> Unit,
    private val playing: () -> Unit,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var loaded = false
    private val youtube = offer.site == "youtube"
    private val origin = "https://" + context.packageName
    private val base = "$origin/"
    private val audio = context.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC
    private val summary = DeckUi.label(context, if (youtube) "YouTube" else "Kick · Canlı yayın", 13, DeckUi.muted, 550)
    private val web = WebView(context).apply {
        setBackgroundColor(DeckUi.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return request.isForMainFrame || uri.scheme != "https" || uri.host !in
                    setOf("www.youtube.com", "player.kick.com")
            }
        }
    }

    private val status = object : Runnable {
        override fun run() {
            if (released || !isAttachedToWindow) return
            web.evaluateJavascript("window.deckPlayerState || ''") { value ->
                if (!released && isAttachedToWindow) {
                    when (value) {
                        "\"playing\"" -> playing()
                        "\"blocked\"" -> summary.text = "YouTube · Oynat düğmesine dokun"
                        "\"error\"" -> summary.text = "YouTube · Bu video gömülü oynatılamıyor"
                    }
                    if (value != "\"playing\"" && value != "\"error\"") handler.postDelayed(this, 520)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(DeckUi.BLACK)
        addView(web, LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = DeckUi.shape(context, DeckUi.panel, 8)
            addView(DeckUi.action(context, "Kapat") { close() }, LayoutParams(-2, dp(48)))
            addView(summary, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(DeckUi.label(context, "Ses", 12, DeckUi.muted, 550))
            addView(SeekBar(context).apply {
                contentDescription = "Telefon video sesi"
                max = audio.getStreamMaxVolume(stream)
                progress = audio.getStreamVolume(stream)
                progressTintList = ColorStateList.valueOf(DeckUi.text)
                thumbTintList = ColorStateList.valueOf(DeckUi.text)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) audio.setStreamVolume(stream, value, 0)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }, LayoutParams(dp(135), dp(48)))
        }, LayoutParams(-1, dp(50)))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            if (!released && isAttachedToWindow && !loaded) {
                loaded = true
                web.loadDataWithBaseURL(base, html(), "text/html", "UTF-8", base)
                if (youtube) handler.postDelayed(status, 520)
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(status)
        super.onDetachedFromWindow()
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        removeView(web)
        web.destroy()
    }

    private fun html(): String {
        val player = if (youtube) """
            <div id="video"></div>
            <script>
            window.onYouTubeIframeAPIReady = function () {
                new YT.Player('video', {
                    videoId: '${offer.key}',
                    playerVars: {autoplay: 1, start: ${offer.seconds}, playsinline: 1, origin: '$origin'},
                    events: {
                        onStateChange: function (event) { if (event.data === 1) window.deckPlayerState = 'playing'; },
                        onAutoplayBlocked: function () { window.deckPlayerState = 'blocked'; },
                        onError: function () { window.deckPlayerState = 'error'; }
                    }
                });
            };
            </script>
            <script src="https://www.youtube.com/iframe_api"></script>
        """ else """
            <iframe title="Kick" src="https://player.kick.com/${offer.key}?autoplay=true&amp;allowfullscreen=false"
                allow="autoplay; encrypted-media" scrolling="no"></iframe>
        """
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body,iframe,#video{width:100%;height:100%;margin:0;border:0;background:#000;overflow:hidden}</style>
            </head><body>${player}</body></html>
        """
    }

    private fun dp(value: Int) = DeckUi.dp(context, value)
}
