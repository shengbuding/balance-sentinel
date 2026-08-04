# Wallet Sentinel v1.4.2 - Verification Report

**Verification date:** 2026-08-04

**Branch:** `wallet-sentinel-hardening`

**Review baseline:** `0e858065053f33b68a4f2173358ab97482f0c772`

**Verified code HEAD:** `fd46ba1`

## Outcome

The complete Debug and Release JVM suites, Android lint, Debug/Release packaging, and Kover verification passed from fresh task execution. A supplemental API 36 device run passed all 36 discovered tests. No API 35 device was available, so the target-API scenarios listed below remain explicitly unexecuted.

## Current-Head Gate Results

| Gate | Result | Duration |
|---|---|---:|
| Debug JVM | 1,033 tests; 0 failures; 0 errors; 3 skipped | 108.72 s |
| Release JVM | 1,033 tests; 0 failures; 0 errors; 3 skipped | 103.34 s |
| Android lint | 0 errors; Debug 152 warnings + 5 info; Release 153 warnings + 5 info | 168.87 s |
| Debug + Release APK | both assembled and audited | 133.16 s |
| Kover Debug | report generated; verification bound 1..100 passed | 110.54 s |
| API 36 device | 36 tests; 36 passed; 0 failed/skipped | 5m 38s |

The three JVM skips are AndroidKeyStore-dependent `WidgetProviderTest` rendering cases: no data, data exists, and all five providers.

## Commands

The final commands were run serially with no active Gradle client before or after each gate:

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat testReleaseUnitTest --rerun-tasks
.\gradlew.bat lintDebug lintRelease --rerun-tasks
.\gradlew.bat assembleDebug assembleRelease --rerun-tasks
.\gradlew.bat koverXmlReportDebug koverHtmlReportDebug koverVerifyDebug --rerun-tasks
```

The required focused integration command also passed:

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.balancesentinel.app.data.refresh.*" `
  --tests "com.balancesentinel.app.data.api.balance.*" `
  --tests "com.balancesentinel.app.data.repository.*" `
  --tests "com.balancesentinel.app.data.console.*" `
  --tests "com.balancesentinel.app.receiver.*" `
  --tests "com.balancesentinel.app.service.*" `
  --tests "com.balancesentinel.app.widget.*" `
  --rerun-tasks
```

Focused result: 46 suites, 593 tests, 0 failures/errors, 3 skipped.

Two independent mandatory complete Debug runs each produced 90 suites, 1,033 tests, 0 failures/errors, and 3 skips. This closes the historical CrashLogger repeated-install/reset blocker; `CrashLoggerTest` contributed 22 passing tests in each run.

## Coverage

| Counter | Covered | Total | Coverage |
|---|---:|---:|---:|
| Instructions | 38,931 | 140,998 | 27.6110% |
| Branches | 2,364 | 5,943 | 39.7779% |
| Lines | 5,624 | 14,657 | 38.3707% |
| Methods | 998 | 1,993 | 50.0753% |
| Classes | 301 | 873 | 34.4788% |

Kover verification uses a non-zero guard of 1% to 100%. Passing the guard is not treated as a claim of exhaustive coverage.

## Lint

- Debug: 157 findings total, consisting of 152 warnings and 5 informational findings.
- Release: 158 findings total, consisting of 153 warnings and 5 informational findings.
- Errors: 0 in both variants.
- `abortOnError=true` remained active.
- No `lint-baseline.xml` exists.
- Existing warnings remain visible and are classified as non-blocking release debt.

## APK And Manifest Evidence

| Artifact | Size | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 33,022,173 bytes | `B6C755905E050E8E360D9C58E66C5195DA459CEEE050FC0A1E2DC734D49D3CF5` |
| `app-release.apk` | 15,103,167 bytes | `93E782F874FBC49EDC9CCBDAFF3A4D33F8817F0264758AC48F77B0BBB669EAD5` |

The Console is a Compose destination, not an Android activity. The nonexistent Console activity is absent from source declarations, merged and packaged manifests, and both APK audits. Release inspection also found no active debug-capture installation path.

## Device Test Status

Instrumentation source contains 8 Kotlin classes and 39 `@Test` methods. The connected API 36 AVD discovered and executed 36 tests; all 36 passed. Three API-35-only `ConsoleWebViewSecurityTest` cases were omitted by `@SdkSuppress` as intended.

The valid device artifact is `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-12-evidence/device-api36-full-green/results.xml`. A failed intermediate run under `device-api36-focused-green` is retained only for diagnosis and is not counted as passing evidence.

Valid Insights boundary traces completed in 531 ms and 476 ms. The timeout was caused by semantics-tag placement, not ViewModel latency. The separately archived overlapping trace is invalid and is not used as evidence.

## API 35 Gaps

An API 35 device was not available. The following target-API checks remain unexecuted:

- Both backup preview/replace scenarios.
- All three Console WebView exact-origin, navigation, and logout scenarios.
- Boot restore.
- Foreground-service start restrictions and OEM behavior.
- Widget manual-refresh versus watchdog separation.
- Watchdog restart after failure or cancellation.
- Long refresh intervals.
- `BalanceRefreshServiceTest` does not establish API-35 foreground-service restriction behavior.

API 36 results and Robolectric tests are supplemental and are not presented as substitutes for these checks.

## Source Integrity

- 127 main Kotlin files.
- 88 unit-test Kotlin files across `test`, `testDebug`, and `testRelease`.
- 8 instrumented-test Kotlin files with 39 source tests.
- Chinese/English resources: 512/512 keys paired, no missing key.
- No NUL bytes in tracked source/text files (binary PNG/JAR assets are excluded).
- No deferred-work marker in the required source/plan scope.
- No lint baseline or staged generated build/test/APK output.

## Result

All executable Task 12 gates passed and no blocking/high defect remains open. Release approval remains conditional on consciously accepting the listed API 35 device gaps and the residual risks in `RELEASE_REVIEW_REPORT.md`.
