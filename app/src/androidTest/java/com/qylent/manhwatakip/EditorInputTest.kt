package com.qylent.manhwatakip

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorInputTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun keyboardCommitPreservesCommaChapter() {
        ActivityScenario.launch(EditActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val chapter = views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint == "Son okunan bölüm" }
                chapter.requestFocus()
                assertTrue(chapter.onCreateInputConnection(EditorInfo()).commitText("48,5", 1))
                assertEquals("48,5", chapter.text.toString())
            }
        }
    }

    @Test fun pendingSaveFreezesFormAndFinishesAfterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LibraryApplication
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val title = "Kayıt testi ${UUID.randomUUID()}"
        ActivityScenario.launch(EditActivity::class.java).use { scenario ->
            app.executor.execute { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                scenario.onActivity { activity ->
                    val controls = views(activity.window.decorView)
                    controls.filterIsInstance<EditText>().single { it.hint == "Seri adı" }.setText(title)
                    controls.filterIsInstance<Button>().single { it.text == "Kaydet" }.performClick()
                    assertTrue("Kaydet sırasında form kilitlenmeli", controls.filter { it is EditText || it is Spinner }.all { !it.isEnabled })
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(views(activity.window.decorView).filter { it is EditText || it is Spinner }.all { !it.isEnabled })
                }
            } finally { release.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                Thread.sleep(30)
            }
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            assertEquals(1, app.store.all().count { it.title == title })
        }
    }
}
