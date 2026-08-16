package com.balancesentinel.app.receiver

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class KeepAliveReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KeepAliveReceiver.cancel(context)
    }

    @After
    fun tearDown() {
        KeepAliveReceiverDependencies.reset()
        KeepAliveReceiver.cancel(context)
    }

    @Test
    fun `schedule keeps one pending alarm and cancel removes it`() {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        KeepAliveReceiver.schedule(context)
        assertEquals(1, Shadows.shadowOf(alarm).scheduledAlarms.size)

        KeepAliveReceiver.schedule(context)
        assertEquals(1, Shadows.shadowOf(alarm).scheduledAlarms.size)

        KeepAliveReceiver.cancel(context)
        assertEquals(0, Shadows.shadowOf(alarm).scheduledAlarms.size)
    }

    @Test
    fun `reconcile republishes only while monitoring remains desired`() = runBlocking {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var publishes = 0
        KeepAliveReceiverDependencies.desiredReader = { true }
        KeepAliveReceiverDependencies.notificationPublisher = { publishes += 1 }

        KeepAliveReceiver().reconcile(context)

        assertEquals(1, publishes)
        assertEquals(1, Shadows.shadowOf(alarm).scheduledAlarms.size)

        KeepAliveReceiverDependencies.desiredReader = { false }
        KeepAliveReceiver().reconcile(context)

        assertEquals(1, publishes)
        assertEquals(0, Shadows.shadowOf(alarm).scheduledAlarms.size)
    }
}
