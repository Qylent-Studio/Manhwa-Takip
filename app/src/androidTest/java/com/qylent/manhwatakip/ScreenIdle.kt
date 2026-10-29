package com.qylent.manhwatakip

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.test.espresso.IdlingResource
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Wait for library I/O without fixed sleeps. */
class ScreenIdle : IdlingResource {
    private var callback: IdlingResource.ResourceCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (isIdleNow) callback?.onTransitionToIdle() else handler.postDelayed(this, 30)
        }
    }
    override fun getName() = "Visible library operation"
    override fun isIdleNow(): Boolean {
        val loading = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { activity ->
            activity.findViewById<View>(R.id.loading)?.visibility == View.VISIBLE
        }
        if (loading) { handler.removeCallbacks(poll); handler.postDelayed(poll, 30) }
        return !loading
    }
    override fun registerIdleTransitionCallback(resourceCallback: IdlingResource.ResourceCallback) { callback = resourceCallback }
    fun close() { handler.removeCallbacks(poll); callback = null }
}
