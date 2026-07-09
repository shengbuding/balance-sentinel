package com.balancesentinel.app.data.repository

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class MidnightSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarm: ShadowAlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarm = Shadows.shadowOf(alarmManager)
    }

    @Test
    fun `schedule sets one alarm`() {
        MidnightScheduler.schedule(context)
        val scheduled = shadowAlarm.scheduledAlarms
        assertEquals(1, scheduled.size)
    }

    @Test
    fun `schedule sets alarm for tomorrow midnight`() {
        MidnightScheduler.schedule(context)
        val scheduled = shadowAlarm.scheduledAlarms
        assertEquals(1, scheduled.size)

        val alarm = scheduled[0]
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        assertEquals(calendar.timeInMillis, alarm.triggerAtMs)
    }

    @Test
    fun `cancel removes alarm`() {
        MidnightScheduler.schedule(context)
        assertEquals(1, shadowAlarm.scheduledAlarms.size)

        MidnightScheduler.cancel(context)
        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test
    fun `cancel is safe when no alarm scheduled`() {
        // should not throw
        MidnightScheduler.cancel(context)
        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test
    fun `schedule replaces previous alarm`() {
        MidnightScheduler.schedule(context)
        MidnightScheduler.schedule(context)
        // only one alarm, previous replaced via FLAG_UPDATE_CURRENT
        assertEquals(1, shadowAlarm.scheduledAlarms.size)
    }
}
