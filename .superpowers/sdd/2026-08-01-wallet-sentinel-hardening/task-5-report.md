# Task 5 Implementation Report

Status: DONE

Base: `ba4e688`

## Implemented Behavior

- Added canonical `WebOrigin` handling with IDNA/lowercase host normalization and registered ports.
- Added `ScriptNetworkPolicy.validate` and an internal resolved-destination path.
- Rejected non-HTTPS URLs, URL user information, IP literals, non-canonical hosts, unauthorized origins, unauthorized extra ports, empty DNS results, and any DNS result containing a non-global address.
- Covered private, loopback, link-local, carrier-grade NAT, documentation, benchmark, multicast, reserved IPv4, and non-global/reserved IPv6 ranges.
- Disabled OkHttp automatic redirects, followed at most five redirects manually, resolved relative `Location` values against the logical current URL, and re-applied policy before each request.
- Pinned each production OkHttp connection to the exact DNS answers already validated for that logical request. The loopback URL router used by HTTPS MockWebServer is internal to unit tests and is not exposed by the production API.
- Added `RhinoScriptRunner.run` with a dedicated daemon single-thread executor, a monotonic deadline in `ThreadLocal`, a 10,000-instruction observer threshold, `Future.get` wall-clock timeout, cancellation, `shutdownNow`, and bounded termination.
- Gave configuration evaluation and extractor evaluation separate complete script timeouts. HTTP uses its own call/connect/read timeout.
- Added typed `ScriptExecutionResult` and `ScriptInspection`.
- Made inspection evaluate only configuration with placeholder API key/access token/user ID values and no HTTP call.
- Reported canonical extra origins. Only a simple top-level literal `request.url` is classified static; dynamic, invalid, complex, and decoy-bearing sources are conservatively classified non-static.
- Made execution refuse either a disabled `UsageScript` or persisted `AccountInfo.usageScriptEnabled=false`.
- Required a present finite `remaining`/`balance` value and rejected non-finite optional totals.
- Replaced the old stringly `ScriptResult` path with typed failures.
- Removed all executor log/debug-store writes, script previews, source/error stack capture, and raw non-success response-body propagation.
- Replaced the literal NUL source byte with the textual Kotlin escape `"\u0000"`; a byte-level audit confirmed the rewritten file contains no NUL byte.
- Added `okhttp-tls` as a version-catalog test dependency for HTTPS MockWebServer coverage.

## Call-Site Audit

Codebase-memory project `wallet-sentinel-hardening-ba4e688` identified the direct production consumers as `OpenAiCompatibleProvider.executeCustomScript` and `AccountBalanceRefresher.fetch`/`mapProviderError`.

- `AccountInfo.toConfig()` remains the source of `usageScript`, `usageScriptEnabled`, and canonical comma-separated `authorizedScriptOrigins`.
- `OpenAiCompatibleProvider` reconstructs the executor account context from those settings and credentials, keeps a configured script terminal, and never falls through to a native contract after script failure.
- Provider logging is generic only: it records script presence/start/success, never source, URL, credentials, headers, response data, exception detail, or failure messages.
- Typed script failures cross the provider boundary only in an internal `ScriptExecutionException` carrying an existing bounded `RefreshFailure`.
- `AccountBalanceRefresher` unwraps only that carrier; all other provider invalid-response failures retain the existing stable schema mapping.
- The existing pre-provider disabled-script refusal remains in place.
- Executor and inspection code have no cache, history, alert, debug-store, clipboard, or other refresh side effects.

## Ordered Commits

1. `97de033` support - `refactor: add script security contract shells`
2. `121f50d` support - `refactor: expose inert script execution test seam`
3. `bdd23bf` support - `refactor: make extractor support sentinel observable`
4. `0dc3866` RED - `test: add RED for script sandbox boundaries`
5. `73211ff` GREEN/refactor - `fix: sandbox script network and execution`
6. `dae4b86` support - `refactor: add typed script failure carrier`
7. `4a2b2f6` RED - `test: add RED for typed script failure mapping`
8. `644318a` GREEN - `fix: preserve typed script failures through refresh`
9. `438d347` RED - `test: add RED for dynamic script inspection decoy`
10. `072aaa1` GREEN/refactor - `fix: classify dynamic inspection urls conservatively`

## RED Evidence

### Core policy, runner, and security

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Exit code: 1. Result: 14 tests, 14 failures.

Relevant failures:

- Policy denial assertions failed because the inert policy allowed HTTP, IP literals, wrong ports, unauthorized origins, private/reserved DNS results, and uncanonicalized origins.
- Rhino deadline assertions failed because the inert runner did not provide deadline/dedicated-worker behavior.
- Security assertions received the inert typed failure instead of same-origin/authorized success, policy denial, redirect denial/limit, and extractor timeout behavior.

The test sources compiled and executed. Failures were at executable assertions against the real contract shells, so they prove missing production behavior rather than a broken fixture or missing type.

### Executor inspection and extraction

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Initial exit code: 1. Result: 5 tests, 4 failures. The inert extractor failure accidentally satisfied one schema assertion, so this was not counted as evidence for that branch. Support commit `bdd23bf` changed the inert sentinel to an empty success.

Rerun exit code: 1. Result: 5 tests, 5 failures. Placeholder inspection, extra-origin reporting, dynamic classification, configuration timeout, finite-balance rejection, and finite-balance success all failed through their intended assertions.

### Typed provider/refresher mapping

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.providers.OpenAiCompatibleProviderTest" --tests "com.balancesentinel.app.data.refresh.AccountBalanceRefresherTest" --rerun-tasks
```

Exit code: 1. Result: 8 tests, 2 failures.

Only these new assertions failed:

- `custom script policy failure remains typed for refresh mapping`
- `typed script failure survives the provider boundary`

The six existing call-site regressions passed, proving the failures were the missing typed provider/refresher mapping rather than a harness or compatibility failure.

### Dynamic inspection decoy

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Exit code: 1. Result: 6 tests, 1 failure.

Only `unrelated literal url does not hide a dynamic request url` failed at the `staticallyDeterminable` assertion. This proved that an unrelated literal `url` property could fool the original classifier.

## Implementation Diagnostics Not Counted As RED

- One `compileDebugUnitTestKotlin` run exited 1 while adapting Rhino `setClassShutter` and OkHttp `Dns` interop. This was a compile diagnostic, not RED evidence.
- An intermediate 19-test GREEN attempt had four HTTPS connector failures. A single-test diagnostic exposed `SSLPeerUnverifiedException` for `127.0.0.1`; adding that test-only SAN fixed the fixture. These failures were not counted as behavior RED.
- No unfiltered `testDebugUnitTest` or connected test command was run.

## Fresh GREEN Verification

Core security gate:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Exit code: 0. Result: 20 tests passed, 0 failed; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

Direct call-site gate:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.AccountBalanceRefresherTest" --tests "com.balancesentinel.app.data.api.providers.OpenAiCompatibleProviderTest" --rerun-tasks
```

Exit code: 0. Result: 8 tests passed, 0 failed; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

Production compile:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks
```

Exit code: 0. Gradle `BUILD SUCCESSFUL`, 15/15 tasks executed.

Android compile/min/target SDK remain 35 and JVM target remains 17.

## Files Changed

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicy.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/RhinoScriptRunner.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/ScriptInspection.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScript.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProvider.kt`
- `app/src/main/java/com/balancesentinel/app/data/refresh/AccountBalanceRefresher.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicyTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/RhinoScriptRunnerTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutorTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptSecurityTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/providers/OpenAiCompatibleProviderTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/refresh/AccountBalanceRefresherTest.kt`
- `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-5-report.md`

## Self-Review

- Every destination and redirect is policy-checked before request construction/connection.
- Production connection DNS is the already validated address list; no second resolver is used.
- All denied MockWebServer paths preserve the expected request count at the denial point.
- Every resolved address must pass the global-unicast check; mixed public/private answers are denied.
- Rhino timeouts are monotonic and independent per evaluation phase.
- Response bodies, request headers/bodies, credentials, source, exception messages, and stacks are absent from stable errors and normal logs.
- Script failure remains a terminal provider path and never reaches a guessed/native balance endpoint.
- Inspection/execution contain no refresh side effects.
- Test-only routing is internal; the public executor API cannot override production DNS/connection routing.
- Static inspection classification is intentionally conservative for complex scripts rather than risking a false static result.
- `git diff --check` is clean apart from local CRLF conversion notices.

## Remaining Concerns

No scoped security or connection-semantics concern remains. Existing unrelated Kotlin/deprecation warnings remain visible. Per dispatch, the unrelated full unit suite and connected tests were intentionally not run.

## Fix Round 1

Status: DONE

Baseline gate:

- `git rev-parse HEAD` returned the required `ced00217f07f7485df0d21b335ee8ecbe8863255`.
- `git status --short --untracked-files=all` was empty.
- Process inspection found only one idle Gradle 8.11.1 daemon and no active Gradle client/build.

### Root-Cause Verification

- `isCanonicalDomainName("localhost")` passed every existing canonical-label check. A same-origin `https://localhost` request therefore reached `HostResolver`, and a fake public answer was accepted.
- `isGlobalIpv6` admitted nearly all of `2000::/3`, excluding only Teredo and documentation space. It accepted benchmarking `2001:2::/48`, ORCHID `2001:10::/28`, ORCHIDv2 `2001:20::/28`, DETs, 6to4, AS112, and the new `3fff::/20` documentation block.
- The IANA IPv6 Special-Purpose Address Registry was audited on 2026-08-02. The local classifier now denies all registry families: entries outside `2000::/3` are rejected by the global-unicast gate, and special prefixes inside it are explicitly represented by `2001::/23`, `2001:db8::/32`, `2002::/16`, `2620:4f:8000::/48`, and `3fff::/20`.
- `hasLiteralRequestUrl` delegated only to anchored `STATIC_URL_PATTERN`. The pattern matched the first literal `request.url` despite a later duplicate, a direct or computed mutation performed by `request.toJSON`, or an origin-affecting credential placeholder.

### Corrected RED Evidence

The first candidate fixture put `{{apiKey}}` directly in a hostname. The inspection sentinel contains underscores, so that fixture failed at `assertNotNull` before reaching the static verdict. That run and the resulting intermediate 15/16 GREEN attempt were diagnostics, not RED/GREEN evidence. The uncommitted production changes were removed, the fixture was corrected to the valid authority form `https://{{apiKey}}@api.example.com/...`, and RED was observed again against unchanged production.

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Exit code: 1. Result: 16 tests executed, 6 failures, 0 errors, 0 skipped. Both production and test Kotlin compilation completed, and all failures reached behavior assertions:

- `localhost is denied before dns even when same origin resolves publicly`: expected resolver lookups `0`, was `1`.
- `special purpose ipv6 is denied while ordinary global ipv6 is allowed`: accepted `2001:2::1`.
- `duplicate request url cannot make the overridden value look static`: failed the false static-verdict assertion after evaluation selected `https://api.example.com/2`.
- `later request url assignment cannot look static`: failed the false static-verdict assertion after direct assignment selected `https://api.example.com/2`.
- `computed request url mutation cannot look static`: failed the false static-verdict assertion after computed assignment selected `https://api.example.com/2`.
- `credential placeholder in url authority cannot look static`: reached and failed the false static-verdict assertion.

RED commits:

1. `48cd24a test: add RED for script policy review findings`
2. `5961a09 test: correct authority placeholder RED fixture`

### GREEN Implementation

- `localhost` is rejected by canonical hostname validation before origin authorization and DNS.
- IPv6 remains allowlisted to `2000::/3`, with deterministic byte-prefix rejection for every current IANA special-purpose family inside that range. Ordinary global `2606:4700:4700::1111` remains allowed.
- Static URL inspection now parses the source with Rhino `Parser` and inspects the AST. It requires one direct configuration object, one ordinary `request` object, one ordinary string-literal `url`, and one ordinary extractor function.
- Computed/unknown object keys, duplicate `request`/`url` properties, request accessors or `toJSON`, direct/computed URL assignments, increment/decrement/delete mutations, parse uncertainty, and placeholders in the scheme/authority region all return conservative `false`.
- A credential placeholder used only in headers retains the existing positive static verdict.

Focused GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Exit code: 0. Result: 16 tests passed, 0 failed; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

Four-class Task 5 security gate:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Exit code: 0. Result: 26 tests passed, 0 failed, 0 errors, 0 skipped; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

GREEN commit:

- `3d0adad fix: harden script destination inspection`

### Files Changed

- `app/src/main/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicy.kt`
- `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/ScriptNetworkPolicyTest.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutorTest.kt`
- `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-5-report.md`

### Self-Review And Concerns

- The network change rejects only `localhost` and IPv6 special-purpose destinations; ordinary authorized public IPv4 and IPv6 behavior is preserved.
- The AST classifier intentionally favors false negatives for complex scripts. Inspection still evaluates and returns their request configuration, but does not claim the URL is statically proven.
- Static inspection reads no source via regex and logs no source, URL, credential, response, or exception detail.
- The scoped diff passed `git diff --check` apart from existing LF-to-CRLF conversion notices.
- No unfiltered unit suite or connected/device test was run, per the fix prompt. Existing unrelated Kotlin/deprecation warnings remain. No scoped security concern remains.

## Fix Round 2

Status: DONE

Baseline gate:

- `git rev-parse HEAD` returned the required `0ad1d59c24123335ad4b0c70db892b03acc0da12`.
- `git status --short --untracked-files=all` was empty.
- Process inspection found only idle Gradle 8.11.1 and Kotlin compiler daemons, with no Gradle wrapper client or active build.

### Root Cause

- Configuration evaluation executes every top-level and request object-property value before `evaluateConfiguration` serializes its wrapper with `JSON.stringify`.
- The Round 1 classifier proved the request URL literal and rejected mutations only when an assignment or unary operation targeted a direct or computed `url` property. It still accepted arbitrary expressions in ordinary request/config property values.
- A request `method` expression could therefore assign an inherited `Object.prototype.toJSON`. The wrapper inherited that hook, so serialization exposed `https://other.example.com/effective` even though the source declared the direct literal request URL `https://api.example.com/static`; the classifier nevertheless returned `true`.

### RED Evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Exit code: 1. Result: 11 tests executed, 1 failure, 0 errors, 0 skipped; Gradle `BUILD FAILED`, 29/29 tasks executed.

- `inherited serializer mutation cannot make a literal request url look static` compiled and executed against the real evaluator.
- The assertion that the evaluated request URL equals `https://other.example.com/effective` passed, proving inherited serialization changed the effective request.
- The following `assertFalse(inspection.staticallyDeterminable)` failed at `UsageScriptExecutorTest.kt:107` because the classifier returned `true`.

RED commit:

- `b4074f1cfdc85770705d102140bdb5d450d8b1bb test: add RED for inherited serializer mutation`

### GREEN Implementation

- Replaced the URL-target mutation blacklist with a conservative supported-shape proof for every configuration/request expression evaluated before serialization.
- The top-level object must contain exactly one ordinary `request` property and one ordinary `extractor` function property. The request may contain only distinct ordinary `url`, `method`, `headers`, and `body` properties.
- The URL must remain a string literal without an origin-affecting placeholder. Method/body and header values must be serialization-safe primitive literals; headers must be an ordinary literal object.
- Computed or duplicate properties, accessors/method syntax, prototype/meta-object keys, assignments, calls, updates, and all unsupported or uncertain values return conservative `false`.
- The positive inspection regression now explicitly preserves a literal `POST` method, literal body, and a credential placeholder used only in a literal header value.

Focused GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --rerun-tasks
```

Exit code: 0. Result: 11 tests passed, 0 failed, 0 errors, 0 skipped; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

Four-class Task 5 security gate:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.api.balance.ScriptNetworkPolicyTest" --tests "com.balancesentinel.app.data.api.balance.RhinoScriptRunnerTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest" --tests "com.balancesentinel.app.data.api.balance.UsageScriptSecurityTest" --rerun-tasks
```

Exit code: 0. Result: 27 tests passed, 0 failed, 0 errors, 0 skipped; Gradle `BUILD SUCCESSFUL`, 29/29 tasks executed.

GREEN commit:

- `fa8af78e9efd2a6de7d2b06284b072e59549e496 fix: require inert static script configuration`

### Files Changed

- `app/src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt`
- `app/src/test/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutorTest.kt`
- `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-5-report.md`

### Self-Review And Concerns

- The classifier no longer relies on target-name mutation detection; accepted expressions cannot execute user code before serialization.
- Literal URL, method, body, and header values retain the supported positive verdict, including inspection credential placeholders confined to header strings.
- Scripts with additional or complex configuration/request properties intentionally receive a conservative false static verdict; inspection still evaluates and returns their request when valid.
- No source, URL, credential, response body, or exception detail logging was added, and network-policy behavior was not changed.
- The scoped diff passed `git diff --check` apart from existing LF-to-CRLF conversion notices.
- No unfiltered unit suite or connected/device test was run, per the fix prompt. Existing unrelated Kotlin/deprecation warnings remain; no scoped security concern remains.
