package com.balancesentinel.app.data.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun `read returns Valid for a valid credential payload`() = runTest {
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
    fun `read returns Corrupt for a blank legacy API key`() {
        val invalid = CredentialPayload(
            accounts = listOf(AccountInfo(id = "account-1", label = "Account", apiKey = "sk-key")),
            legacyApiKey = "   "
        )
        val raw = Json { encodeDefaults = true }.encodeToString(invalid)
        assertTrue(prefs.edit().putString("credential_payload", raw).commit())

        assertTrue(store().read() is CredentialReadResult.Corrupt)
    }

    @Test
    fun `write and clear refuse to overwrite a corrupt payload`() = runTest {
        val raw = "{ corrupt credential payload"
        assertTrue(prefs.edit().putString("credential_payload", raw).commit())
        val store = store()

        assertDataCorruption { store.write(payload()) }
        assertEquals(raw, prefs.getString("credential_payload", null))
        assertDataCorruption { store.clear() }
        assertEquals(raw, prefs.getString("credential_payload", null))
    }

    @Test
    fun `write persists on an IO dispatcher`() = runTest {
        val trackingPrefs = CommitThreadTrackingPreferences(prefs)
        val callerThread = Thread.currentThread().name

        store(trackingPrefs).write(payload())

        assertNotEquals(callerThread, trackingPrefs.commitThreadName)
    }

    @Test
    fun `write reports an explicit commit failure`() = runTest {
        var failure: IllegalStateException? = null
        try {
            store(CommitFailingPreferences(prefs)).write(payload())
            fail("Expected commit failure")
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertEquals("Credential payload write commit failed", failure?.message)
    }

    @Test
    fun `second store cannot corrupt after first store inspection before persistence`() = runTest {
        val firstCommitEntered = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val corruptionInjected = CountDownLatch(1)
        val sharedPrefs = InterleavingPreferences(
            prefs,
            firstCommitEntered,
            allowFirstCommit,
            corruptionInjected
        )
        val firstStore = store(sharedPrefs)
        val secondStore = store(sharedPrefs)

        val firstWrite = async(Dispatchers.Default) { firstStore.write(payload()) }
        assertTrue(firstCommitEntered.await(5, TimeUnit.SECONDS))
        val secondClear = async(Dispatchers.Default) {
            try {
                secondStore.clear()
                null
            } catch (error: Exception) {
                error
            }
        }

        assertFalse(
            "A second store must not reach its corrupting persistence while the first inspection is open",
            corruptionInjected.await(250, TimeUnit.MILLISECONDS)
        )
        allowFirstCommit.countDown()
        firstWrite.await()
        secondClear.await()

        assertEquals("{ injected corrupt payload", prefs.getString("credential_payload", null))
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

    private suspend fun assertDataCorruption(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected DataCorruptionException")
        } catch (_: DataCorruptionException) {
        }
    }

    private class CommitThreadTrackingPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        var commitThreadName: String? = null

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    commitThreadName = Thread.currentThread().name
                    return editor.commit()
                }
            }
        }
    }

    private class CommitFailingPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun commit(): Boolean = false
            }
        }
    }

    private class InterleavingPreferences(
        private val delegate: SharedPreferences,
        private val firstCommitEntered: CountDownLatch,
        private val allowFirstCommit: CountDownLatch,
        private val corruptionInjected: CountDownLatch
    ) : SharedPreferences by delegate {
        private val commitCount = AtomicInteger()

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun remove(key: String?): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean = when (commitCount.incrementAndGet()) {
                    1 -> {
                        firstCommitEntered.countDown()
                        check(allowFirstCommit.await(5, TimeUnit.SECONDS))
                        editor.commit()
                    }
                    2 -> {
                        delegate.edit().putString("credential_payload", "{ injected corrupt payload").commit()
                        corruptionInjected.countDown()
                        false
                    }
                    else -> editor.commit()
                }
            }
        }
    }
}
