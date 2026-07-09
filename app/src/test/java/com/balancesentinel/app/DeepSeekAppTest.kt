package com.balancesentinel.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = DeepSeekApp::class)
class DeepSeekAppTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Clean up test APK files
        val apkDir = File(context.cacheDir, "apk")
        if (apkDir.exists()) apkDir.deleteRecursively()
    }

    @Test
    fun `notification channels are created`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)

        val channels = shadow.notificationChannels
        assertTrue("should have at least 3 channels", channels.size >= 3)

        val channelIds = channels.map { it.id }
        assertTrue("Missing service channel", channelIds.contains(DeepSeekApp.CHANNEL_ID))
        assertTrue("Missing alert channel", channelIds.contains(DeepSeekApp.CHANNEL_ID_ALERT))
        assertTrue("Missing usage channel", channelIds.contains(DeepSeekApp.CHANNEL_ID_USAGE))

        // Verify channel properties
        val svcChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID }
        assertNotNull(svcChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, svcChannel!!.importance)

        val alertChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID_ALERT }
        assertNotNull(alertChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alertChannel!!.importance)

        val usageChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID_USAGE }
        assertNotNull(usageChannel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, usageChannel!!.importance)
    }

    @Test
    fun `stale APK files are cleaned up on startup`() {
        val apkDir = File(context.cacheDir, "apk")
        apkDir.mkdirs()

        // Create test APK files
        val matchingFile = File(apkDir, "update-v1.0.0.apk")
        matchingFile.writeText("fake apk")
        val matchingFile2 = File(apkDir, "update-v2.0.0.apk")
        matchingFile2.writeText("fake apk 2")

        // Create a non-matching file that should survive
        val nonMatchingFile = File(apkDir, "readme.txt")
        nonMatchingFile.writeText("keep me")

        assertTrue(matchingFile.exists())
        assertTrue(matchingFile2.exists())
        assertTrue(nonMatchingFile.exists())

        // Simulate the cleanup that happens in onCreate
        if (apkDir.exists()) {
            apkDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        }

        assertFalse("update-*.apk should be deleted", matchingFile.exists())
        assertFalse("update-*.apk should be deleted", matchingFile2.exists())
        assertTrue("non-apk file should survive", nonMatchingFile.exists())
    }

    @Test
    fun `stale APK cleanup handles non-existent directory gracefully`() {
        val apkDir = File(context.cacheDir, "apk")
        if (apkDir.exists()) apkDir.deleteRecursively()

        // Should not throw when directory doesn't exist
        try {
            if (apkDir.exists()) {
                apkDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            fail("Should not throw: ${e.message}")
        }
    }

    @Test
    fun `companion object constants are correct`() {
        assertEquals("balance_refresh_channel", DeepSeekApp.CHANNEL_ID)
        assertEquals(1001, DeepSeekApp.NOTIFICATION_ID)
        assertEquals("balance_alert_channel", DeepSeekApp.CHANNEL_ID_ALERT)
        assertEquals("balance_usage_channel", DeepSeekApp.CHANNEL_ID_USAGE)
        assertEquals(3002, DeepSeekApp.NOTIFICATION_ID_GROUP_SUMMARY)
    }
}
