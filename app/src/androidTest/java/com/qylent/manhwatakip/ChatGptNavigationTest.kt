package com.qylent.manhwatakip

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Test

class ChatGptNavigationTest {
    private val idle = ScreenIdle()
    private lateinit var chats: ChatFixture
    @org.junit.Before fun registerIdle() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.FINGERPRINT.contains("generic"))
        chats = ChatFixture()
        androidx.test.espresso.IdlingRegistry.getInstance().register(idle)
    }
    @org.junit.After fun unregisterIdle() {
        androidx.test.espresso.IdlingRegistry.getInstance().unregister(idle); idle.close()
        if (::chats.isInitialized) chats.close()
    }
    @Test fun aiDestinationOpensDedicatedExportsAndImportScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Gezinme: AI sohbeti")).perform(click())
            onView(withHint("Mesaj, seri adı veya bağlantısı")).perform(replaceText("Paylaşımdan dönen taslak"), closeSoftKeyboard())
            onView(withText("AI Paylaşımı")).perform(scrollTo(), click())
            onView(withText("Serileri dışa aktar")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Yeni öneri için dışa aktar")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Özet için dışa aktar")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Panodan içe aktar")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Dosya seç")).perform(scrollTo()).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            onView(withHint("Mesaj, seri adı veya bağlantısı")).check(matches(withText("Paylaşımdan dönen taslak")))
        }
    }
}
