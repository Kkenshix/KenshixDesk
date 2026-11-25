package com.kenshix.deck

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

/**
 * Temporarily brings the kiosk clock into view when the managed panel is put to sleep.
 */
class ClockWakeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        window.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setTurnScreenOn(false)
            finish()
        }
    }
}
