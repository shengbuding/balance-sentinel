package com.balancesentinel.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OnboardingHelper.reset(context)
    }

    @After
    fun tearDown() {
        OnboardingHelper.reset(context)
    }

    @Test
    fun `shouldShow returns true initially`() {
        assertTrue(OnboardingHelper.shouldShow(context))
    }

    @Test
    fun `shouldShow returns false after markCompleted`() {
        OnboardingHelper.markCompleted(context)
        assertFalse(OnboardingHelper.shouldShow(context))
    }

    @Test
    fun `shouldShow returns true after reset even if completed`() {
        OnboardingHelper.markCompleted(context)
        OnboardingHelper.reset(context)
        assertTrue(OnboardingHelper.shouldShow(context))
    }

    @Test
    fun `markCompleted and reset are idempotent`() {
        OnboardingHelper.markCompleted(context)
        OnboardingHelper.markCompleted(context)
        assertFalse(OnboardingHelper.shouldShow(context))

        OnboardingHelper.reset(context)
        OnboardingHelper.reset(context)
        assertTrue(OnboardingHelper.shouldShow(context))
    }
}
