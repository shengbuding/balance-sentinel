package com.balancesentinel.app.data.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyAccountSourceTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "legacy_account_source_${System.nanoTime()}"
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `read returns Missing when legacy accounts are absent`() {
        assertSame(CredentialReadResult.Missing, source().read())
    }

    @Test
    fun `read returns Valid when legacy accounts are valid`() {
        val accounts = listOf(AccountInfo(id = "account-1", label = "Account", apiKey = "sk-key"))
        assertTrue(prefs.edit().putString("accounts", Json.encodeToString(accounts)).commit())

        val result = source().read()

        assertTrue(result is CredentialReadResult.Valid)
        result as CredentialReadResult.Valid
        assertEquals(accounts, result.payload.accounts)
        assertEquals(CredentialGeneration.LEGACY, result.generation)
    }

    @Test
    fun `read returns Corrupt for malformed legacy accounts`() {
        assertTrue(prefs.edit().putString("accounts", "{ invalid").commit())

        assertTrue(source().read() is CredentialReadResult.Corrupt)
    }

    private fun source() = LegacyAccountSource(prefs)
}
