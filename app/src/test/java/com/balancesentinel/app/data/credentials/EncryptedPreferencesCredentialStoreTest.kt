package com.balancesentinel.app.data.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedPreferencesCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "credential_store_${System.nanoTime()}"
        prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `read returns Missing when no credential payload exists`() {
        assertSame(CredentialReadResult.Missing, store().read())
    }

    @Test
    fun `read returns Valid for a valid credential payload`() {
        val payload = payload()
        store().write(payload)

        val result = store().read()

        assertTrue(result is CredentialReadResult.Valid)
        result as CredentialReadResult.Valid
        assertEquals(payload, result.payload)
        assertEquals(CredentialGeneration.ENCRYPTED_PREFERENCES, result.generation)
    }

    @Test
    fun `read returns Corrupt when authentication fails`() {
        val failure = SecurityException("authentication failed")

        val result = store(failingPrefs(failure)).read()

        assertCorruption(result, failure)
    }

    @Test
    fun `read returns Corrupt when decryption fails`() {
        val failure = DecryptionFailure("decryption failed")

        val result = store(failingPrefs(failure)).read()

        assertCorruption(result, failure)
    }

    @Test
    fun `read returns Corrupt for invalid JSON`() {
        assertTrue(prefs.edit().putString("credential_payload", "{ not json").commit())

        assertTrue(store().read() is CredentialReadResult.Corrupt)
    }

    @Test
    fun `read returns Corrupt for invalid credential fields`() {
        val invalid = CredentialPayload(listOf(AccountInfo(id = "", label = "Account", apiKey = "sk-key")))
        val raw = Json { encodeDefaults = true }.encodeToString(invalid)
        assertTrue(prefs.edit().putString("credential_payload", raw).commit())

        assertTrue(store().read() is CredentialReadResult.Corrupt)
    }

    @Test
    fun `write and clear refuse to overwrite a corrupt payload`() {
        val raw = "{ corrupt credential payload"
        assertTrue(prefs.edit().putString("credential_payload", raw).commit())
        val store = store()

        assertThrows(DataCorruptionException::class.java) { store.write(payload()) }
        assertEquals(raw, prefs.getString("credential_payload", null))
        assertThrows(DataCorruptionException::class.java) { store.clear() }
        assertEquals(raw, prefs.getString("credential_payload", null))
    }

    private fun store(sharedPreferences: SharedPreferences = prefs) =
        EncryptedPreferencesCredentialStore(context, sharedPreferences)

    private fun payload() = CredentialPayload(
        accounts = listOf(AccountInfo(id = "account-1", label = "Account", apiKey = "sk-key"))
    )

    private fun failingPrefs(failure: RuntimeException): SharedPreferences =
        object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? = throw failure
        }

    private fun assertCorruption(result: CredentialReadResult, failure: RuntimeException) {
        assertTrue(result is CredentialReadResult.Corrupt)
        assertSame(failure, (result as CredentialReadResult.Corrupt).exception.cause)
    }

    private class DecryptionFailure(message: String) : RuntimeException(message)
}
