package com.qylent.manhwatakip

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import org.hamcrest.Matchers.startsWith

fun openLibraryFilters() {
    onView(withContentDescription(startsWith("Filtreler ve görünüm"))).perform(click())
}
