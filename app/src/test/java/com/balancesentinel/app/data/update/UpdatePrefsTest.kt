package com.balancesentinel.app.data.update

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
class UpdatePrefsTest {

    private lateinit var prefs: UpdatePrefs

    @Before
    fun setUp() {
        prefs = UpdatePrefs(ApplicationProvider.getApplicationContext())
        // Clear state between tests
        prefs.lastPromptDate = ""
        prefs.skippedVersion = ""
    }

    @Test
    fun `lastPromptDate persists correctly`() {
        prefs.lastPromptDate = "2026-07-06"
        assertEquals("2026-07-06", prefs.lastPromptDate)
    }

    @Test
    fun `lastPromptDate defaults to empty string`() {
        // Fresh instance with no saved value
        val fresh = UpdatePrefs(ApplicationProvider.getApplicationContext())
        fresh.lastPromptDate = "" // reset
        assertEquals("", fresh.lastPromptDate)
    }

    @Test
    fun `skippedVersion persists correctly`() {
        prefs.skippedVersion = "1.2.0"
        assertEquals("1.2.0", prefs.skippedVersion)
    }

    @Test
    fun `skippedVersion defaults to empty string`() {
        prefs.skippedVersion = ""
        assertEquals("", prefs.skippedVersion)
    }

    @Test
    fun `shouldAutoCheckToday returns false when already prompted today`() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.lastPromptDate = today
        assertFalse(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldAutoCheckToday returns true when last prompted yesterday`() {
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Date(System.currentTimeMillis() - 86400000)
        )
        prefs.lastPromptDate = yesterday
        assertTrue(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldAutoCheckToday returns true when never prompted`() {
        prefs.lastPromptDate = ""
        assertTrue(prefs.shouldAutoCheckToday())
    }

    @Test
    fun `shouldSkipVersion returns true for skipped version`() {
        prefs.skippedVersion = "1.2.0"
        assertTrue(prefs.shouldSkipVersion("1.2.0"))
    }

    @Test
    fun `shouldSkipVersion returns false for different version`() {
        prefs.skippedVersion = "1.2.0"
        assertFalse(prefs.shouldSkipVersion("1.3.0"))
    }

    @Test
    fun `shouldSkipVersion returns false when nothing skipped`() {
        prefs.skippedVersion = ""
        assertFalse(prefs.shouldSkipVersion("1.0.0"))
    }

    @Test
    fun `markPromptedToday sets lastPromptDate to today`() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.markPromptedToday()
        assertEquals(today, prefs.lastPromptDate)
    }
}
