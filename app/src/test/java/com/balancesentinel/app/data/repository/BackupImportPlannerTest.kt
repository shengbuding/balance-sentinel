package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.balance.RequestConfig
import com.balancesentinel.app.data.api.balance.ScriptInspection
import com.balancesentinel.app.data.api.balance.UsageScript
import com.balancesentinel.app.data.api.balance.WebOrigin
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupImportPlannerTest {

    private lateinit var context: Context
    private lateinit var accountPrefs: SharedPreferences
    private lateinit var manager: ApiKeyManager
    private lateinit var widgetPrefs: WidgetPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        accountPrefs = context.getSharedPreferences("backup-plan-${System.nanoTime()}", Context.MODE_PRIVATE)
        manager = ApiKeyManager(context, accountPrefs)
        widgetPrefs = WidgetPrefs(context)
        widgetPrefs.resetAll()
    }

    @After
    fun tearDown() {
        accountPrefs.edit().clear().commit()
        widgetPrefs.resetAll()
    }

    @Test
    fun `sanitized merge preserves every local account credential script and grant`() = runTest {
        // Mutation caught: copying sanitized credential/script fields over the matching local account.
        val local = account(
            id = LOCAL_ID,
            apiKey = LOCAL_KEY,
            label = "Old",
            providerType = ProviderType.DEEPSEEK,
            extraCredentials = mapOf("secretKey" to "local-secondary"),
            extraSettings = mapOf("baseUrl" to "https://old.example.com", "timeout" to "30"),
            usageScript = SCRIPT,
            usageScriptEnabled = true,
            authorizedScriptOrigins = setOf("https://usage.example.com"),
            revision = 7
        )
        val untouched = account(id = OLD_ID, apiKey = OLD_KEY, label = "Untouched")
        val incoming = local.copy(
            label = "New",
            apiKey = "",
            providerType = ProviderType.CUSTOM,
            extraCredentials = mapOf("secretKey" to "", "futureToken" to ""),
            extraSettings = mapOf(
                "baseUrl" to "https://new.example.com",
                "secretKey" to "must-not-enter-settings"
            ),
            usageScript = null,
            usageScriptEnabled = false,
            authorizedScriptOrigins = emptySet()
        )

        val plan = planner().plan(config(false, listOf(incoming)), listOf(local, untouched), ImportMode.MERGE)

        val expected = local.copy(
            label = "New",
            providerType = ProviderType.CUSTOM,
            extraSettings = mapOf("baseUrl" to "https://new.example.com", "timeout" to "30")
        )
        assertEquals(listOf(expected, untouched), plan.finalAccounts)
        assertEquals(1, plan.matchedUpdatedCount)
        assertEquals(1, plan.retainedCredentialCount)
        assertEquals(0, plan.deletedCount)
        assertEquals(0, plan.conflictCount)
        assertTrue(plan.canApply)
    }

    @Test
    fun `sanitized unmatched accounts are skipped without deleting locals`() = runTest {
        // Mutation caught: treating an unmatched sanitized account as creatable or replacing local storage.
        val local = account(id = LOCAL_ID, apiKey = LOCAL_KEY)
        val unmatched = account(id = NEW_ID, apiKey = "", label = "Sanitized newcomer")

        val plan = planner().plan(config(false, listOf(unmatched)), listOf(local), ImportMode.MERGE)

        assertEquals(listOf(local), plan.finalAccounts)
        assertEquals(1, plan.skippedCount)
        assertEquals(0, plan.createdCount)
        assertEquals(0, plan.deletedCount)
        assertTrue(plan.canApply)
    }

    @Test
    fun `sanitized provider change without required local credential is a conflict`() = runTest {
        // Mutation caught: changing to a provider whose required secretKey is absent locally.
        val local = account(id = LOCAL_ID, apiKey = LOCAL_KEY, providerType = ProviderType.DEEPSEEK)
        val incoming = local.copy(
            label = "Unsafe update",
            apiKey = "",
            providerType = ProviderType.ZHIPU,
            extraCredentials = mapOf("secretKey" to "")
        )

        val plan = planner().plan(config(false, listOf(incoming)), listOf(local), ImportMode.MERGE)

        assertEquals(listOf(local), plan.finalAccounts)
        assertEquals(1, plan.conflictCount)
        assertEquals(0, plan.matchedUpdatedCount)
    }

    @Test
    fun `duplicate incoming IDs reject every duplicate as a conflict`() = runTest {
        // Mutation caught: last-write-wins handling for duplicate backup account IDs.
        val first = account(id = NEW_ID, apiKey = NEW_KEY, label = "First")
        val second = first.copy(label = "Second")

        val plan = planner().plan(config(true, listOf(first, second)), emptyList(), ImportMode.MERGE)

        assertEquals(emptyList<AccountInfo>(), plan.finalAccounts)
        assertEquals(2, plan.conflictCount)
        assertEquals(0, plan.createdCount)
    }

    @Test
    fun `duplicate source IDs remain conflicts when one credential is forged`() = runTest {
        // Mutation caught: normalization hiding a duplicate whose API key does not match the shared source ID.
        val valid = account(id = NEW_ID, apiKey = NEW_KEY, label = "Valid")
        val forged = account(id = NEW_ID, apiKey = OLD_KEY, label = "Forged")

        val plan = planner().plan(config(true, listOf(valid, forged)), emptyList(), ImportMode.MERGE)

        assertEquals(emptyList<AccountInfo>(), plan.finalAccounts)
        assertEquals(2, plan.conflictCount)
        assertEquals(0, plan.createdCount)
    }

    @Test
    fun `full imports reject mismatched IDs and incomplete provider credentials`() = runTest {
        // Mutation caught: accepting a forged ID or a ZHIPU account without its required secretKey.
        val mismatched = account(id = OLD_ID, apiKey = NEW_KEY, label = "Forged")
        val incomplete = account(
            id = ZHIPU_ID,
            apiKey = ZHIPU_KEY,
            label = "Incomplete",
            providerType = ProviderType.ZHIPU
        )

        val plan = planner().plan(config(true, listOf(mismatched, incomplete)), emptyList(), ImportMode.MERGE)

        assertEquals(emptyList<AccountInfo>(), plan.finalAccounts)
        assertEquals(2, plan.conflictCount)
        assertEquals(0, plan.createdCount)
    }

    @Test
    fun `schema v1 infers complete credentials and normalizes legacy IDs`() = runTest {
        // Mutation caught: treating every v1 backup as sanitized or retaining its 8-character legacy ID.
        val legacy = account(id = NEW_LEGACY_ID, apiKey = NEW_KEY, label = "Legacy")
        val v1 = config(credentialsIncluded = false, accounts = listOf(legacy), version = 1)

        val plan = planner().plan(v1, emptyList(), ImportMode.REPLACE_ALL)

        assertEquals(1, plan.finalAccounts.size)
        assertEquals(NEW_ID, plan.finalAccounts[0].id)
        assertEquals(1, plan.createdCount)
        assertTrue(plan.canApply)
    }

    @Test
    fun `schema v2 full backup preserves a Room UUID identity`() = runTest {
        val uuid = "4fdf6c7e-8b6d-4f3b-9cf5-8a8c57ef4521"
        val incoming = account(id = uuid, apiKey = NEW_KEY, label = "Room account")

        val plan = planner().plan(config(true, listOf(incoming)), emptyList(), ImportMode.REPLACE_ALL)

        assertTrue(plan.canApply)
        assertEquals(uuid, plan.finalAccounts.single().id)
        assertEquals(1, plan.createdCount)
    }

    @Test
    fun `full schema v1 merge replaces a matching legacy local ID with its normalized ID`() = runTest {
        // Mutation caught: exact-ID-only matching that appends a normalized v1 account beside its legacy local account.
        val localLegacy = account(id = NEW_LEGACY_ID, apiKey = NEW_KEY, label = "Local legacy")
        val incoming = account(id = NEW_LEGACY_ID, apiKey = NEW_KEY, label = "Imported")

        val plan = planner().plan(
            config(credentialsIncluded = false, accounts = listOf(incoming), version = 1),
            listOf(localLegacy),
            ImportMode.MERGE
        )

        assertEquals(1, plan.finalAccounts.size)
        assertEquals(
            incoming.copy(id = NEW_ID, usageScriptEnabled = false, authorizedScriptOrigins = emptySet()),
            plan.finalAccounts.single()
        )
        assertEquals(1, plan.matchedUpdatedCount)
        assertEquals(0, plan.createdCount)
    }

    @Test
    fun `full imports disable scripts clear grants and expose static canonical origins`() = runTest {
        // Mutation caught: preserving imported script enablement/grants or omitting static-origin preview data.
        val origin = WebOrigin.https("Usage.Example.com")
        val incoming = account(
            id = SCRIPT_ID,
            apiKey = SCRIPT_KEY,
            usageScript = SCRIPT,
            usageScriptEnabled = true,
            authorizedScriptOrigins = setOf("https://malicious.example")
        )
        val plan = planner(
            inspector = { _, _ ->
                ScriptInspection(
                    request = null,
                    requiredExtraOrigins = setOf(origin),
                    staticallyDeterminable = true
                )
            }
        ).plan(config(true, listOf(incoming)), emptyList(), ImportMode.MERGE)

        assertEquals(1, plan.finalAccounts.size)
        assertFalse(plan.finalAccounts[0].usageScriptEnabled)
        assertEquals(emptySet<String>(), plan.finalAccounts[0].authorizedScriptOrigins)
        assertEquals(
            listOf(ScriptAuthorization(SCRIPT_ID, setOf(WebOrigin.https("usage.example.com")), true)),
            plan.scriptAuthorizations
        )
    }

    @Test
    fun `script authorization enables only static scripts with every required origin checked`() = runTest {
        // Mutation caught: enabling imported code before all canonical origins receive explicit authorization.
        val firstOrigin = WebOrigin.https("usage.example.com")
        val secondOrigin = WebOrigin.https("audit.example.com")
        val incoming = account(id = SCRIPT_ID, apiKey = SCRIPT_KEY, usageScript = SCRIPT)
        val p = planner(
            inspector = { _, _ ->
                ScriptInspection(null, setOf(firstOrigin, secondOrigin), staticallyDeterminable = true)
            }
        )
        val basePlan = p.plan(config(true, listOf(incoming)), emptyList(), ImportMode.MERGE)
        assertEquals(1, basePlan.finalAccounts.size)

        val partial = p.withScriptAuthorizations(
            basePlan,
            enabledAccountIds = setOf(SCRIPT_ID),
            authorizedOrigins = mapOf(SCRIPT_ID to setOf(firstOrigin))
        )
        assertFalse(partial.finalAccounts[0].usageScriptEnabled)

        val complete = p.withScriptAuthorizations(
            basePlan,
            enabledAccountIds = setOf(SCRIPT_ID),
            authorizedOrigins = mapOf(SCRIPT_ID to setOf(firstOrigin, secondOrigin))
        )
        assertTrue(complete.finalAccounts[0].usageScriptEnabled)
        assertEquals(
            setOf("https://audit.example.com", "https://usage.example.com"),
            complete.finalAccounts[0].authorizedScriptOrigins
        )
    }

    @Test
    fun `HTTP inspected base requests remain disabled after authorization`() = runTest {
        // Mutation caught: allowing an authorization attempt to enable a script whose inspected base request is HTTP.
        val incoming = account(id = SCRIPT_ID, apiKey = SCRIPT_KEY, usageScript = SCRIPT)
        val p = planner(
            inspector = {
                    _, _ ->
                ScriptInspection(
                    request = RequestConfig("http://usage.example.com/balance"),
                    requiredExtraOrigins = emptySet(),
                    staticallyDeterminable = true
                )
            }
        )
        val basePlan = p.plan(config(true, listOf(incoming)), emptyList(), ImportMode.MERGE)

        val authorized = p.withScriptAuthorizations(
            basePlan,
            enabledAccountIds = setOf(SCRIPT_ID),
            authorizedOrigins = emptyMap()
        )

        assertFalse(authorized.finalAccounts.single().usageScriptEnabled)
        assertEquals(emptySet<String>(), authorized.finalAccounts.single().authorizedScriptOrigins)
    }

    @Test
    fun `HTTP required extra origins remain disabled after authorization`() = runTest {
        // Mutation caught: persisting an HTTP script grant once a user checks the inspected extra origin.
        val httpOrigin = WebOrigin("http", "audit.example.com", 80)
        val incoming = account(id = SCRIPT_ID, apiKey = SCRIPT_KEY, usageScript = SCRIPT)
        val p = planner(
            inspector = {
                    _, _ ->
                ScriptInspection(
                    request = RequestConfig("https://usage.example.com/balance"),
                    requiredExtraOrigins = setOf(httpOrigin),
                    staticallyDeterminable = true
                )
            }
        )
        val basePlan = p.plan(config(true, listOf(incoming)), emptyList(), ImportMode.MERGE)

        val authorized = p.withScriptAuthorizations(
            basePlan,
            enabledAccountIds = setOf(SCRIPT_ID),
            authorizedOrigins = mapOf(SCRIPT_ID to setOf(httpOrigin))
        )

        assertFalse(authorized.finalAccounts.single().usageScriptEnabled)
        assertEquals(emptySet<String>(), authorized.finalAccounts.single().authorizedScriptOrigins)
    }

    @Test
    fun `non static imported scripts remain disabled even when enablement is requested`() = runTest {
        // Mutation caught: allowing checkbox state to bypass a non-static inspection result.
        val incoming = account(id = SCRIPT_ID, apiKey = SCRIPT_KEY, usageScript = SCRIPT)
        val p = planner(
            inspector = { _, _ -> ScriptInspection(null, emptySet(), staticallyDeterminable = false) }
        )
        val basePlan = p.plan(config(true, listOf(incoming)), emptyList(), ImportMode.REPLACE_ALL)
        assertEquals(1, basePlan.finalAccounts.size)

        val authorized = p.withScriptAuthorizations(
            basePlan,
            enabledAccountIds = setOf(SCRIPT_ID),
            authorizedOrigins = emptyMap()
        )

        assertFalse(authorized.finalAccounts[0].usageScriptEnabled)
        assertTrue(authorized.canApply)
    }

    @Test
    fun `replace requires a complete credential backup and explicit confirmation`() = runTest {
        // Mutation caught: allowing sanitized replacement or applying replacement after only the preview action.
        val local = account(id = OLD_ID, apiKey = OLD_KEY)
        val p = planner()
        val sanitized = p.plan(config(false, emptyList()), listOf(local), ImportMode.REPLACE_ALL)

        assertFalse(sanitized.canApply)
        assertTrue(sanitized.blockingReasons.isNotEmpty())
        assertThrows(IllegalStateException::class.java) {
            p.apply(sanitized, confirmedFullReplace = true)
        }

        val complete = p.plan(
            config(true, listOf(account(id = NEW_ID, apiKey = NEW_KEY))),
            listOf(local),
            ImportMode.REPLACE_ALL
        )
        assertEquals(1, complete.deletedCount)
        assertTrue(complete.canApply)
        assertThrows(IllegalStateException::class.java) {
            p.apply(complete, confirmedFullReplace = false)
        }
    }

    @Test
    fun `apply persists accounts once before applying settings`() {
        // Mutation caught: clear-plus-add persistence or applying settings before the account commit succeeds.
        val base = context.getSharedPreferences("backup-commit-${System.nanoTime()}", Context.MODE_PRIVATE)
        val tracking = TrackingPreferences(base, commitResult = true)
        val realManager = ApiKeyManager(context, tracking)
        val p = BackupImportPlanner(realManager, widgetPrefs)
        val imported = account(id = NEW_ID, apiKey = NEW_KEY)
        val plan = readyPlan(
            mode = ImportMode.MERGE,
            accounts = listOf(imported),
            settings = settings(refreshIntervalSeconds = 77)
        )

        p.apply(plan, confirmedFullReplace = false)

        assertEquals(1, tracking.commitCount)
        assertEquals(listOf(imported), realManager.getAccounts())
        assertEquals(77, widgetPrefs.refreshIntervalSeconds)
        base.edit().clear().commit()
    }

    @Test
    fun `account persistence failure leaves settings unchanged`() {
        // Mutation caught: writing settings before replaceAll returns successfully.
        widgetPrefs.refreshIntervalSeconds = 222
        val base = context.getSharedPreferences("backup-fail-${System.nanoTime()}", Context.MODE_PRIVATE)
        val failing = TrackingPreferences(base, commitResult = false)
        val p = BackupImportPlanner(ApiKeyManager(context, failing), widgetPrefs)
        val plan = readyPlan(
            mode = ImportMode.MERGE,
            accounts = listOf(account(id = NEW_ID, apiKey = NEW_KEY)),
            settings = settings(refreshIntervalSeconds = 77)
        )

        assertThrows(IllegalStateException::class.java) {
            p.apply(plan, confirmedFullReplace = false)
        }
        assertEquals(222, widgetPrefs.refreshIntervalSeconds)
        assertEquals(1, failing.commitCount)
    }

    private fun planner(
        inspector: suspend (UsageScript, AccountInfo) -> ScriptInspection = { _, _ ->
            ScriptInspection(null, emptySet(), staticallyDeterminable = true)
        }
    ) = BackupImportPlanner(manager, widgetPrefs, inspector)

    private fun account(
        id: String = LOCAL_ID,
        apiKey: String = LOCAL_KEY,
        label: String = "Account",
        providerType: ProviderType = ProviderType.DEEPSEEK,
        extraCredentials: Map<String, String> = emptyMap(),
        extraSettings: Map<String, String> = emptyMap(),
        usageScript: String? = null,
        usageScriptEnabled: Boolean = true,
        authorizedScriptOrigins: Set<String> = emptySet(),
        revision: Long = 0
    ) = AccountInfo(
        id = id,
        label = label,
        apiKey = apiKey,
        providerType = providerType,
        extraCredentials = extraCredentials,
        extraSettings = extraSettings,
        usageScript = usageScript,
        usageScriptEnabled = usageScriptEnabled,
        authorizedScriptOrigins = authorizedScriptOrigins,
        revision = revision
    )

    private fun config(
        credentialsIncluded: Boolean,
        accounts: List<AccountInfo>,
        version: Int = 2,
        settings: ConfigSettings = settings()
    ) = AppConfig(
        version = version,
        credentialsIncluded = credentialsIncluded,
        exportedAt = "2026-08-01T00:00:00",
        appVersion = "2.0",
        accounts = accounts,
        settings = settings
    )

    private fun settings(refreshIntervalSeconds: Int = 30) = ConfigSettings(
        refreshIntervalSeconds = refreshIntervalSeconds,
        alertEnabled = false,
        alertThreshold = 0f,
        changeAlertEnabled = false,
        changeAlertThreshold = 0f,
        changeAlertPeriodMinutes = 60,
        logMaxEntries = 100
    )

    private fun readyPlan(
        mode: ImportMode,
        accounts: List<AccountInfo>,
        settings: ConfigSettings
    ) = BackupImportPlan(
        mode = mode,
        finalAccounts = accounts,
        matchedUpdatedCount = 0,
        retainedCredentialCount = 0,
        createdCount = accounts.size,
        skippedCount = 0,
        conflictCount = 0,
        deletedCount = 0,
        scriptAuthorizations = emptyList(),
        canApply = true,
        blockingReasons = emptyList(),
        settings = settings
    )

    private class TrackingPreferences(
        private val delegate: SharedPreferences,
        private val commitResult: Boolean
    ) : SharedPreferences by delegate {
        var commitCount: Int = 0
            private set

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    commitCount++
                    return commitResult && editor.commit()
                }
            }
        }
    }

    private companion object {
        const val LOCAL_KEY = "sk-local-secret"
        const val LOCAL_ID = "96ed403d28356eeb"
        const val NEW_KEY = "sk-new-complete"
        const val NEW_ID = "7c6888f7ec01a4e6"
        const val NEW_LEGACY_ID = "7c6888f7"
        const val OLD_KEY = "sk-old-complete"
        const val OLD_ID = "41afefea72a24e69"
        const val ZHIPU_KEY = "sk-zhipu-primary"
        const val ZHIPU_ID = "e51030526f2b2f4a"
        const val SCRIPT_KEY = "sk-script-account"
        const val SCRIPT_ID = "6bbdfb3957422e13"
        const val SCRIPT = "({ request: { url: 'https://usage.example.com/balance' } })"
    }
}
