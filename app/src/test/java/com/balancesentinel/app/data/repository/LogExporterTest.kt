package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LogExporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RefreshLogStore.clear(context)
    }

    @After
    fun tearDown() {
        RefreshLogStore.clear(context)
    }

    // ═══════════════════════════════════════════════════════════
    // export
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `export returns a file path when stores are empty`() {
        val path = LogExporter.export(context)
        assertNotNull(path)
        val file = File(path!!)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun `export file contains debug report header`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("DeepSeek Balance"))
        assertTrue(content.contains("调试报告"))
        assertTrue(content.contains("导出时间"))
    }

    @Test
    fun `export file contains system status section`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("系统状态"))
        assertTrue(content.contains("前台服务"))
        assertTrue(content.contains("电池优化"))
    }

    @Test
    fun `export file contains device info section`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("设备信息"))
        assertTrue(content.contains("制造商"))
        assertTrue(content.contains("Android"))
    }

    @Test
    fun `export file contains refresh log section`() {
        val entry = RefreshLogEntry(
            id = 1L, type = RefreshLogType.MANUAL,
            totalBalance = "100.00", currency = "CNY",
            isAvailable = true, timestamp = System.currentTimeMillis(),
            message = "test refresh"
        )
        RefreshLogStore.addEntries(context, listOf(entry))

        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("刷新日志"))
        assertTrue(content.contains("100.00"))
    }

    @Test
    fun `export shows no-records message when refresh log is empty`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("(无记录)") || content.contains("0 条"))
    }

    @Test
    fun `export file contains crash log section`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("崩溃日志"))
    }

    @Test
    fun `export file contains report end marker`() {
        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("报告结束"))
    }

    @Test
    fun `export with refresh log entries shows them in output`() {
        RefreshLogStore.addEntries(context, listOf(
            RefreshLogEntry(id = 1L, type = RefreshLogType.AUTO,
                totalBalance = "50.00", currency = "USD", timestamp = 1000000L,
                message = "auto refresh"),
            RefreshLogEntry(id = 2L, type = RefreshLogType.MISSED,
                timestamp = 2000000L, message = "missed refresh",
                missReason = "系统资源紧张")
        ))

        val path = LogExporter.export(context)
        val content = File(path!!).readText()
        assertTrue(content.contains("auto refresh"))
        assertTrue(content.contains("系统资源紧张"))
    }

    @Test
    fun `export handles various refresh log types`() {
        val now = System.currentTimeMillis()
        RefreshLogStore.addEntries(context, listOf(
            RefreshLogEntry(id = 1L, type = RefreshLogType.MANUAL,
                totalBalance = "100", currency = "CNY", timestamp = now, message = "m1"),
            RefreshLogEntry(id = 2L, type = RefreshLogType.SCHEDULE,
                timestamp = now, message = "scheduled",
                intervalSeconds = 30, expectedTime = now + 30000)
        ))

        val path = LogExporter.export(context)
        val content = File(path!!).readText()

        // Check type labels
        assertTrue(content.contains("[手动") || content.contains("手动"))
        assertTrue(content.contains("[调度") || content.contains("调度"))
    }

    @Test
    fun `export cleans up temp files after test`() {
        val path = LogExporter.export(context)
        val file = File(path!!)
        assertTrue(file.exists())
        file.delete()
        assertFalse(file.exists())
    }
}
