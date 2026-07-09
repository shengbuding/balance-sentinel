package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class DataExporterImportTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `parse sample daily summary`() {
        val sample = """
        {
            "exportedAt": "2026-07-08T15:56:31",
            "appVersion": "v1.1.1-21-g6bb3997-dirty",
            "dailySummaries": [
                {
                    "accountId": "30c5333e",
                    "date": "2026-07-05",
                    "currency": "CNY",
                    "open": 8.69,
                    "close": 8.69,
                    "consumed": 0.0,
                    "toppedUp": 0.0,
                    "avgBalance": 8.69,
                    "sampleCount": 1,
                    "toppedUpBalanceClose": 8.69
                }
            ],
            "rawRecords": [
                {
                    "accountId": "30c5333e",
                    "timestamp": 1751723700000,
                    "currency": "CNY",
                    "totalBalance": 8.69,
                    "grantedBalance": 0.0,
                    "toppedUpBalance": 8.69
                }
            ],
            "refreshLogs": [
                {
                    "id": 1,
                    "type": "MANUAL",
                    "totalBalance": "8.69",
                    "currency": "CNY",
                    "isAvailable": true,
                    "grantedBalance": "",
                    "toppedUpBalance": "",
                    "timestamp": 1751723700000,
                    "message": "",
                    "intervalSeconds": 0,
                    "expectedTime": 0
                }
            ]
        }
        """.trimIndent()

        val result = json.decodeFromString<DataExport>(sample)
        assertEquals(1, result.dailySummaries.size)
        assertEquals(1, result.rawRecords.size)
        assertEquals(1, result.refreshLogs.size)
        assertEquals("30c5333e", result.dailySummaries[0].accountId)
        assertEquals("2026-07-05", result.dailySummaries[0].date)
        assertEquals(8.69f, result.dailySummaries[0].open)
    }

    @Test
    fun `parse without version and usageSnapshots fields`() {
        // This is the exact structure from the user's export
        val sample = """
        {
            "exportedAt": "2026-07-08T15:56:31",
            "appVersion": "v1.1.1-21-g6bb3997-dirty",
            "dailySummaries": [
                {
                    "accountId": "a48fc413",
                    "date": "2026-07-05",
                    "currency": "CNY",
                    "open": 6.66,
                    "close": 8.48,
                    "consumed": 9.18,
                    "toppedUp": 11.0,
                    "avgBalance": 7.2887025,
                    "sampleCount": 447,
                    "toppedUpBalanceClose": 8.48
                }
            ],
            "rawRecords": [],
            "refreshLogs": []
        }
        """.trimIndent()

        val result = json.decodeFromString<DataExport>(sample)
        assertEquals(1, result.dailySummaries.size)
        assertEquals("a48fc413", result.dailySummaries[0].accountId)
        assertEquals(7.2887025f, result.dailySummaries[0].avgBalance)
        assertEquals(447, result.dailySummaries[0].sampleCount)
        assertEquals(0, result.usageSnapshots.size)  // default
        assertEquals(1, result.version)              // default
    }
}
