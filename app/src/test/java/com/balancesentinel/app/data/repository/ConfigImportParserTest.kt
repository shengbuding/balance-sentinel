package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.io.BoundedInput
import com.balancesentinel.app.data.model.AccountInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ConfigImportParserTest {
    private val parser = ConfigImportParser()

    @Test fun `bounded UTF-8 reader preserves a code point split across read chunks`() {
        val expected = "x".repeat(DEFAULT_BUFFER_SIZE - 1) + "\u20ac"

        val actual = BoundedInput.readUtf8(
            ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)),
            expected.toByteArray(Charsets.UTF_8).size
        )

        assertEquals(expected, actual)
    }

    @Test fun `exact limits are accepted`() {
        assertEquals(256, parser.parse(ByteArrayInputStream(validJson(accounts = 256))).accounts.size)
        assertEquals(16 * 1024, parser.parse(ByteArrayInputStream(validJson(accounts = 1, label = "x".repeat(16 * 1024)))).accounts.single().label.length)
        assertEquals(256 * 1024, parser.parse(ByteArrayInputStream(validJson(accounts = 1, script = "s".repeat(256 * 1024)))).accounts.single().usageScript!!.length)
    }

    @Test fun `each limit plus one is rejected`() {
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 257))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 1, label = "x".repeat(16 * 1024 + 1)))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 1, script = "s".repeat(256 * 1024 + 1)))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream("[${"[".repeat(33)}0${"]".repeat(33)}]".toByteArray())) }
    }

    private fun validJson(accounts: Int = 0, label: String = "account", script: String? = null): ByteArray {
        val rows = (0 until accounts).joinToString(",") { i ->
            val key = "key-$i"
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            "{\"id\":\"$id\",\"label\":\"$label\",\"apiKey\":\"$key\"${script?.let { ",\"usageScript\":\"$it\"" } ?: ""}}"
        }
        return "{\"version\":2,\"credentialsIncluded\":true,\"exportedAt\":\"now\",\"appVersion\":\"1\",\"accounts\":[$rows],\"settings\":{\"refreshIntervalSeconds\":30,\"alertEnabled\":false,\"alertThreshold\":0,\"changeAlertEnabled\":false,\"changeAlertThreshold\":0,\"changeAlertPeriodMinutes\":0,\"logMaxEntries\":10}}".toByteArray()
    }
}
