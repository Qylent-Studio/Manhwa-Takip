package com.qylent.manhwatakip

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue

/** Test-only condition waits; never changes product behavior or bypasses assertions. */
object TestWindowReadiness {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun capture(label: String) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val windows = instrumentation.uiAutomation.windows.joinToString("\n") { window ->
            val root = window.root
            "window id=${window.id} type=${window.type} focused=${window.isFocused} active=${window.isActive} title=${window.title} root=${root?.className} package=${root?.packageName}"
        }
        val focused = UiDevice.getInstance(instrumentation).executeShellCommand("dumpsys window")
            .lineSequence().filter { it.contains("mCurrentFocus=") || it.contains("mFocusedApp=") }.joinToString("\n")
        val evidence = "elapsed=${SystemClock.elapsedRealtime()} $label\n$windows\n$focused\n\n"
        File(app.getExternalFilesDir(null), "task-1-window-evidence.txt").appendText(evidence)
        android.util.Log.i("Task1WindowEvidence", evidence)
    }

    fun awaitFocusedDialog(title: String) {
        capture("before waiting for dialog: $title")
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            ready = instrumentation.uiAutomation.windows.any { window ->
                window.isFocused && window.root?.findAccessibilityNodeInfosByText(title)
                    ?.any { it.text?.toString() == title && it.isVisibleToUser } == true
            }
            if (!ready) SystemClock.sleep(50)
        }
        capture("dialog readiness=$ready: $title")
        assertTrue("Intended consent dialog must own focus: $title (see task-1-window-evidence.txt)", ready)
    }

    fun <A : Activity> awaitConfiguration(scenario: ActivityScenario<A>, fontScale: Float? = null, orientation: Int? = null) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var ready = false
        var actual = "not observed"
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val config = activity.resources.configuration
                actual = "fontScale=${config.fontScale}, orientation=${config.orientation}"
                ready = (fontScale == null || kotlin.math.abs(config.fontScale - fontScale) < 0.01f) &&
                    (orientation == null || config.orientation == orientation)
            }
            if (!ready) SystemClock.sleep(50)
        }
        if (!ready) capture("configuration timeout: $actual")
        assertTrue("Actual configuration must apply before UI assertions: $actual; wanted fontScale=$fontScale orientation=$orientation", ready)
    }
}
