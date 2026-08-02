package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshFailure
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageScriptExecutorTest {

    // Mutation caught: using real credentials during inspection or losing a required extra origin.
    @Test
    fun `inspection uses placeholders and reports a canonical extra origin`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://cdn.example.com/balance",headers:{Authorization:"Bearer {{apiKey}}"}},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://cdn.example.com/balance", inspection.request?.url)
        assertFalse(inspection.request!!.headers.getValue("Authorization").contains(API_KEY))
        assertEquals(setOf(WebOrigin.https("cdn.example.com")), inspection.requiredExtraOrigins)
        assertTrue(inspection.staticallyDeterminable)
        assertEquals(null, inspection.failure)
    }

    // Mutation caught: presenting an evaluated dynamic request URL as statically auditable.
    @Test
    fun `dynamic request url is not statically determinable`() = runBlocking {
        val script = UsageScript(
            """(function(){var p="/balance";return {request:{url:"https://api.example.com"+p},extractor:function(r){return r;}};})()"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertNotNull(inspection.request)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: treating an unrelated literal url property as the request URL declaration.
    @Test
    fun `unrelated literal url does not hide a dynamic request url`() = runBlocking {
        val script = UsageScript(
            """(function(){var metadata={url:"https://decoy.example.com"};var p="/balance";return {request:{url:"https://api.example.com"+p},extractor:function(r){return r;}};})()"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertNotNull(inspection.request)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: accepting the first request.url when a duplicate property overrides it.
    @Test
    fun `duplicate request url cannot make the overridden value look static`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://api.example.com/static",url:"https://api.example.com/"+(1+1)},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://api.example.com/2", inspection.request?.url)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: ignoring a later direct assignment that changes request.url during evaluation.
    @Test
    fun `later request url assignment cannot look static`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://api.example.com/static",toJSON:function(){this.url="https://api.example.com/"+(1+1);return this;}},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://api.example.com/2", inspection.request?.url)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: ignoring a computed-property mutation of request.url during evaluation.
    @Test
    fun `computed request url mutation cannot look static`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://api.example.com/static",toJSON:function(){this["url"]="https://api.example.com/"+(1+1);return this;}},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://api.example.com/2", inspection.request?.url)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: treating a credential placeholder in the URL authority as static.
    @Test
    fun `credential placeholder in url authority cannot look static`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://{{apiKey}}@api.example.com/balance"},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertNotNull(inspection.request)
        assertFalse(inspection.staticallyDeterminable)
    }

    // Mutation caught: allowing configuration evaluation to outlive its complete phase timeout.
    @Test(timeout = 3_000)
    fun `inspection maps configuration deadline to script timeout`() = runBlocking {
        val inspection = UsageScriptExecutor.inspect(
            UsageScript("while (true) {}", timeout = 1),
            account()
        )

        assertTrue(inspection.failure is RefreshFailure.ScriptTimeout)
    }

    // Mutation caught: accepting a missing or non-finite remaining balance from the extractor.
    @Test
    fun `extractor requires a finite remaining balance`() = runBlocking {
        val missing = UsageScriptExecutor.extractForTest(
            UsageScript(scriptWithExtractor("return {unit:'USD'};")),
            account(),
            "{}"
        )
        val infinite = UsageScriptExecutor.extractForTest(
            UsageScript(scriptWithExtractor("return {remaining:1/0,unit:'USD'};")),
            account(),
            "{}"
        )

        assertSchemaFailure(missing)
        assertSchemaFailure(infinite)
    }

    // Mutation caught: rejecting a valid finite extractor result or changing its balance values.
    @Test
    fun `extractor returns finite balance data`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(scriptWithExtractor("return {remaining:12.5,total:20,used:7.5,unit:'USD'};")),
            account(),
            "{}"
        ) as ScriptExecutionResult.Success

        assertEquals(12.5, result.balances.single().remaining!!, 0.0)
        assertEquals("USD", result.balances.single().unit)
    }

    private fun assertSchemaFailure(result: ScriptExecutionResult) {
        assertTrue(result is ScriptExecutionResult.Failure)
        assertTrue((result as ScriptExecutionResult.Failure).failure is RefreshFailure.ResponseSchemaFailure)
    }

    private fun scriptWithExtractor(body: String) =
        """({request:{url:"https://api.example.com/x"},extractor:function(r){$body}})"""

    private fun account() = AccountInfo(
        id = "account-id",
        label = "Primary",
        apiKey = API_KEY,
        providerType = ProviderType.CUSTOM,
        extraSettings = mapOf("baseUrl" to "https://api.example.com/v1"),
        usageScriptEnabled = true
    )

    private companion object {
        const val API_KEY = "real-secret-api-key"
    }
}
