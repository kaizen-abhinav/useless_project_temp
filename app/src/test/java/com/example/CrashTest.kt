package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class CrashTest {
    @Test
    fun testActivityStarts() {
        Robolectric.buildActivity(MainActivity::class.java).create().resume()
    }
}
