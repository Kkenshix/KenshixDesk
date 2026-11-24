package com.kenshix.deck

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.view.WindowInsets
import android.view.WindowInsetsController

class DeckAdminReceiver : DeviceAdminReceiver()

internal class Kiosk(private val activity: Activity) {
    private val policy = activity.getSystemService(DevicePolicyManager::class.java)
    private val tasks = activity.getSystemService(ActivityManager::class.java)
    private val preferences = activity.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    private val admin = ComponentName(activity, DeckAdminReceiver::class.java)
    private val home = ComponentName(activity.packageName, activity.packageName + ".DeckHome")
    private var preview = false

    fun isOwner(): Boolean = policy.isDeviceOwnerApp(activity.packageName)
    fun enabled(): Boolean = isOwner() && preferences.getBoolean("enabled", false)
    fun fullScreen(): Boolean = enabled() || preview
    fun locked(): Boolean = tasks.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

    fun preview(active: Boolean) {
        preview = active
        updateBars()
    }

    fun enable(report: (Boolean) -> Unit) {
        check(isOwner())
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            policy.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            policy.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            policy.setLockTaskPackages(admin, arrayOf(activity.packageName))
            setHome(true)
            policy.addPersistentPreferredActivity(admin, filter, home)
            check(preferences.edit().putBoolean("enabled", true).commit())
            preview = false
            onFocus()
            activity.window.decorView.postDelayed({
                if (enabled()) {
                    val active = locked()
                    if (!active) runCatching { disable() }
                    report(active)
                }
            }, 600)
        } catch (failure: RuntimeException) {
            runCatching { disable() }
            throw failure
        }
    }

    fun disable() {
        check(preferences.edit().putBoolean("enabled", false).commit())
        preview = false
        var failure: RuntimeException? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: RuntimeException) {
                if (failure == null) failure = error
            }
        }
        if (locked()) attempt { activity.stopLockTask() }
        if (isOwner()) {
            attempt { policy.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS) }
            attempt { policy.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
            attempt { setHome(false) }
            attempt { policy.setLockTaskPackages(admin, emptyArray()) }
        }
        updateBars()
        failure?.let { throw it }
    }

    fun onFocus() {
        if (enabled() && !locked() && policy.isLockTaskPermitted(activity.packageName)) {
            activity.startLockTask()
        }
        updateBars()
    }

    private fun setHome(enabled: Boolean) {
        activity.packageManager.setComponentEnabledSetting(
            home,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun updateBars() {
        val controller = activity.window.insetsController ?: return
        if (fullScreen()) {
            controller.systemBarsBehavior = if (locked()) WindowInsetsController.BEHAVIOR_DEFAULT
                else WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
    }
}
