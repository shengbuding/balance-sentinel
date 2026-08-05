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
            """({request:{url:"https://cdn.example.com/balance",method:"POST",headers:{Authorization:"Bearer {{apiKey}}"},body:"{}"},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://cdn.example.com/balance", inspection.request?.url)
        assertEquals("POST", inspection.request?.method)
        assertEquals("{}", inspection.request?.body)
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

    // Mutation caught: accepting a side-effecting request value that changes inherited serialization.
    @Test
    fun `inherited serializer mutation cannot make a literal request url look static`() = runBlocking {
        val script = UsageScript(
            """({request:{url:"https://api.example.com/static",method:(Object.prototype.toJSON=function(key){return key===""?{request:{url:"https://other.example.com/effective"},hasExtractor:true}:this;},"GET")},extractor:function(r){return r;}})"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://other.example.com/effective", inspection.request?.url)
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

    // Mutation caught: rejecting optional/nullish syntax during configuration evaluation.
    @Test
    fun `inspection evaluates optional and nullish configuration expressions`() = runBlocking {
        val script = UsageScript(
            """(function(){var endpoint=null;return {request:{url:endpoint?.url ?? "https://cdn.example.com/balance"},extractor:function(r){return r;}};})()"""
        )

        val inspection = UsageScriptExecutor.inspect(script, account())

        assertEquals("https://cdn.example.com/balance", inspection.request?.url)
        assertEquals(setOf(WebOrigin.https("cdn.example.com")), inspection.requiredExtraOrigins)
        assertFalse(inspection.staticallyDeterminable)
        assertEquals(null, inspection.failure)
    }

    // Mutation caught: parsing the original unsupported extractor when classifying a literal request.
    @Test
    fun `optional extractor preserves literal request static inspection`() = runBlocking {
        val inspection = UsageScriptExecutor.inspect(optionalNullishScript(), account())

        assertEquals("https://api.example.com/x", inspection.request?.url)
        assertTrue(inspection.staticallyDeterminable)
        assertEquals(null, inspection.failure)
    }

    // Mutation caught: mapping nullish to truthiness or rewriting operators in lexical literals.
    @Test
    fun `optional and nullish extraction preserves falsy values and absent fallbacks`() = runBlocking {
        val script = optionalNullishScript()
        val present = UsageScriptExecutor.extractForTest(
            script,
            account(),
            """{"payload":{"remaining":0,"flag":false,"text":""}}"""
        )
        val absent = UsageScriptExecutor.extractForTest(script, account(), "{}")

        assertTrue(present is ScriptExecutionResult.Success)
        assertTrue(absent is ScriptExecutionResult.Success)
        val presentBalance = (present as ScriptExecutionResult.Success).balances.single()
        val absentBalance = (absent as ScriptExecutionResult.Success).balances.single()
        assertEquals(0.0, presentBalance.remaining!!, 0.0)
        assertEquals(0.0, presentBalance.total!!, 0.0)
        assertEquals(0.0, presentBalance.used!!, 0.0)
        assertEquals("literal ?. ??", presentBalance.planName)
        assertEquals(true, presentBalance.isValid)
        assertEquals(7.0, absentBalance.remaining!!, 0.0)
        assertEquals(1.0, absentBalance.total!!, 0.0)
        assertEquals(1.0, absentBalance.used!!, 0.0)
    }

    // Mutation caught: guarding only the marked property and dereferencing the remaining chain.
    @Test
    fun `continuous optional property chain short circuits after a null root`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var live={child:{value:42}};
                        var nullRoot=null;
                        var undefinedRoot;
                        var liveValue=live?.child.value;
                        var nullValue=nullRoot?.child.value;
                        var undefinedValue=undefinedRoot?.child.value;
                        return {
                            remaining:liveValue,
                            total:nullValue===undefined?1:0,
                            used:undefinedValue===undefined?1:0,
                            unit:'USD'
                        };
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue(result is ScriptExecutionResult.Success)
        val balance = (result as ScriptExecutionResult.Success).balances.single()
        assertEquals(42.0, balance.remaining!!, 0.0)
        assertEquals(1.0, balance.total!!, 0.0)
        assertEquals(1.0, balance.used!!, 0.0)
    }

    // Mutation caught: extracting an optional method before calling it and losing its receiver.
    @Test
    fun `optional method call preserves its receiver`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var root={value:41,method:function(){return this===root?this.value+1:-1;}};
                        return {remaining:root?.method(),unit:'USD'};
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue(result is ScriptExecutionResult.Success)
        assertEquals(
            42.0,
            (result as ScriptExecutionResult.Success).balances.single().remaining!!,
            0.0
        )
    }

    // Mutation caught: leaving the call outside the optional property's null guard.
    @Test
    fun `optional method call skips null and undefined roots`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var calls=0;
                        var live={method:function(){calls++;return 1;}};
                        var nullRoot=null;
                        var undefinedRoot;
                        var liveValue=live?.method();
                        var nullValue=nullRoot?.method();
                        var undefinedValue=undefinedRoot?.method();
                        return {
                            remaining:calls,
                            total:nullValue===undefined?liveValue:99,
                            used:undefinedValue===undefined?0:99,
                            unit:'USD'
                        };
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue(result is ScriptExecutionResult.Success)
        val balance = (result as ScriptExecutionResult.Success).balances.single()
        assertEquals(1.0, balance.remaining!!, 0.0)
        assertEquals(1.0, balance.total!!, 0.0)
        assertEquals(0.0, balance.used!!, 0.0)
    }

    // Mutation caught: materializing the grouped property as a bare function value before calling it.
    @Test
    fun `grouped optional method call preserves its receiver`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var argumentCalls=0;
                        var root={value:41,method:function(offset){
                            return this===root?this.value+offset+1:-1;
                        }};
                        var remaining=(root?.method)(argumentCalls++);
                        return {remaining:remaining,total:argumentCalls,unit:'USD'};
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue(result is ScriptExecutionResult.Success)
        assertEquals(
            42.0,
            (result as ScriptExecutionResult.Success).balances.single().remaining!!,
            0.0
        )
        assertEquals(
            1.0,
            (result as ScriptExecutionResult.Success).balances.single().total!!,
            0.0
        )
    }

    // Control: grouping ends optional short-circuiting, so the null result is still called and throws.
    @Test
    fun `grouped optional method call throws for a null root`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var root=null;
                        var threw=false;
                        try{(root?.method)();}catch(error){threw=true;}
                        return {remaining:threw?1:-1,unit:'USD'};
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue(result is ScriptExecutionResult.Success)
        assertEquals(
            1.0,
            (result as ScriptExecutionResult.Success).balances.single().remaining!!,
            0.0
        )
    }

    // Mutation caught: absorbing NewExpression as a FunctionCall and silently dropping `new`.
    @Test
    fun `optional chain directly targeted by new fails closed`() = runBlocking {
        val result = UsageScriptExecutor.extractForTest(
            UsageScript(
                scriptWithExtractor(
                    """
                        var normalCalls=0;
                        var root={Ctor:function(){normalCalls++;return 7;}};
                        var value=new root?.Ctor();
                        return {
                            remaining:normalCalls===1&&value===7?99:-1,
                            unit:'USD'
                        };
                    """.trimIndent()
                )
            ),
            account(),
            "{}"
        )

        assertTrue("unexpected result: $result", result is ScriptExecutionResult.Failure)
        assertTrue(
            (result as ScriptExecutionResult.Failure).failure is
                RefreshFailure.ResponseSchemaFailure
        )
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

    private fun optionalNullishScript() = UsageScript(
        """
            ({
                request:{url:"https://api.example.com/x"},
                extractor:function(response){
                    /* Operators inside comments stay literal: ?. ?? */
                    var marker="literal ?. ??";
                    var pattern=/\?\?/;
                    var fallback=7;
                    var remaining=response.payload?.remaining ?? fallback;
                    var flag=response.payload?.flag ?? true;
                    var text=response.payload?.text ?? "fallback";
                    return {
                        remaining:remaining,
                        total:text===""?0:1,
                        used:flag===false?0:1,
                        plan_name:marker,
                        isValid:pattern.test(marker),
                        unit:"USD"
                    };
                }
            })
        """.trimIndent()
    )

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
