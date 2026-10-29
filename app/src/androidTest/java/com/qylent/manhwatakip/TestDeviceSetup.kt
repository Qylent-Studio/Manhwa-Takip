package com.qylent.manhwatakip

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preparation for the disposable emulator; never dismisses dialogs on physical phones. */
object TestDeviceSetup {
    fun prepare() {
        if (Build.HARDWARE != "ranchu" && !Build.FINGERPRINT.contains("generic")) return
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        val handwriting = By.text("Try out your stylus")
        if (device.hasObject(handwriting)) {
            device.pressBack()
            assertTrue("Test emülatöründeki Gboard öğreticisi kapanmalı", device.wait(Until.gone(handwriting), 10_000))
        }
        val systemUiAnr = By.res("android", "alertTitle").text("System UI isn't responding")
        if (device.hasObject(systemUiAnr)) {
            device.findObject(By.res("android", "aerr_close")).click()
            assertTrue("Test cihazındaki SystemUI ANR penceresi kapanmalı", device.wait(Until.gone(systemUiAnr), 10_000))
        }
    }
}

class TestDeviceSetupTest {
    @Test fun prepareDisposableEmulator() { TestDeviceSetup.prepare() }
}
