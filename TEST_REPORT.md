# Wallet Sentinel v1.5.1 - Verification Report

**Verification date:** 2026-08-20

**Branch:** `release/v1.5.1`

**Release tag:** `v1.5.1`

## v1.5.1 release gate

The v1.5.1 release candidate was verified locally before tagging. The GitHub
tag workflow repeats the JVM, lint, Kover, signed packaging, APK metadata, and
certificate checks before creating the Release asset.

| Gate | Result |
|---|---|
| Debug JVM | 1,531 tests; 0 failures; 0 errors; 3 skipped |
| Release JVM | 1,531 tests; 0 failures; 0 errors; 3 skipped |
| Debug/Release lint | 0 errors |
| Kover | verification passed |
| Debug/Release assembly | both APKs built successfully |
| Release artifact | `com.balancesentinel.app`, expected tag version `v1.5.1` |

The three JVM skips per variant are the existing AndroidKeyStore-dependent
widget rendering cases. Full API 35 connected-test discovery remains an
environment limitation and is not counted as a pass.

## v1.5.0 formal release gate (historical)

The v1.5.0 release is built from the local `master` release commit and is
published by the `v1.5.0` tag workflow. The workflow runs the Debug/Release JVM
suites, Debug/Release lint, Kover verification, signed Release packaging,
APK package/version verification, certificate allowlist verification, and
attaches the APK to the GitHub Release.

| Gate | Result |
|---|---|
| Debug JVM | 1,486 tests; 0 failures; 0 errors; 3 skipped |
| Release JVM | 1,486 tests; 0 failures; 0 errors; 3 skipped |
| Debug/Release lint | 0 errors |
| Kover | verification passed; line 58.96%; branch 48.79% |
| Targeted instrumentation | `MainActivityTest` 4/4 passed |
| Full API 35 instrumentation discovery | Emulator startup failure before assertions; not counted as a pass |
| Release artifact | `com.balancesentinel.app`, `versionName=v1.5.0` |

The three JVM skips are AndroidKeyStore-dependent widget rendering cases. The
full API 35 connected-test discovery limitation is recorded as a residual
release risk; it does not invalidate the passing JVM, lint, Kover, or targeted
smoke gates.

## Release artifact

The final local artifact path and SHA-256 are recorded after the signed build:

`app/build/outputs/apk/release/app-release.apk`

See the attached GitHub Release asset for the distributable APK. Do not place
keystore contents, passwords, API keys, cookies, or session values in this
report.

---

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

Kover task names were bound to this checkout before the coverage gate with:

```powershell
.\gradlew.bat tasks --all
```

Discovery exited 0 in 2.84 seconds and identified `app:koverHtmlReportDebug`, `app:koverVerifyDebug`, and `app:koverXmlReportDebug`. The exact command, complete task output, filtered task list, and timing are preserved at `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-12-evidence/task-discovery/{command.txt,gradle.log,kover-debug-tasks.txt,metadata.txt}`.

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

The preserved device inventory and gate invocation were:

```powershell
adb devices -l
.\gradlew.bat connectedDebugAndroidTest --rerun-tasks
```

`device-api36/adb-devices.txt` records one connected target, `emulator-5554`, with product/model `sdk_gphone64_x86_64`. `device-api36/device-properties.txt` records SDK 36, Android 16, x86_64, boot completed, and AVD name `medium_phone`; `adb-path.txt` records the exact Platform Tools binary. The gate command is preserved in `device-api36/command.txt`. The valid passing output is `device-api36-full-green/{gradle.log,results.xml,metadata.txt,connected-results.tar.gz,connected-report.zip}`: exit 0, 36/36 passed, and `BUILD SUCCESSFUL` in 5m 38s.

Instrumentation source contains 8 Kotlin classes and 39 `@Test` methods. No instrumentation class ran on API 35.

| Instrumentation class | Source tests | API 36 | API 35 |
|---|---:|---|---|
| `MainActivityTest` | 3 | 3 passed | 0 run; no API 35 device |
| `BalanceRefreshServiceTest` | 4 | 4 passed | 0 run; no API 35 device |
| `BackupRestoreScreenTest` | 2 | 2 passed | 0 run; no API 35 device |
| `HomeScreenTest` | 9 | 9 passed | 0 run; no API 35 device |
| `InsightsScreenTest` | 2 | 2 passed | 0 run; no API 35 device |
| `OnboardingScreenTest` | 8 | 8 passed | 0 run; no API 35 device |
| `SettingsScreenTest` | 8 | 8 passed | 0 run; no API 35 device |
| `ConsoleWebViewSecurityTest` | 3 | 0 run; API-35-only via `@SdkSuppress` | 0 run; no API 35 device |
| **Total** | **39** | **36 passed** | **0 run** |

The valid device artifact is `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/task-12-evidence/device-api36-full-green/results.xml`. A failed intermediate run under `device-api36-focused-green` is retained only for diagnosis and is not counted as passing evidence.

Valid Insights boundary traces completed in 531 ms and 476 ms. The timeout was caused by semantics-tag placement, not ViewModel latency. The separately archived overlapping trace is invalid and is not used as evidence.

## API 35 Gaps

An API 35 device was not available, so all eight instrumentation classes have zero API 35 executions. Required scenarios map as follows:

| Required device scenario | Instrumentation mapping | API 35 status | Non-device evidence only |
|---|---|---|---|
| Backup preview/no-write | `BackupRestoreScreenTest` | Not run; its API 36 case passed | `BackupImportPlannerTest` and `DataManagementViewModelTest` are JVM-only |
| Destructive replace, complete credentials, and separate confirmations | `BackupRestoreScreenTest` | Not run; its API 36 case passed | `BackupImportPlannerTest` and `DataManagementViewModelTest` are JVM-only |
| Console exact-origin WebView injection | `ConsoleWebViewSecurityTest` | Not run; API-35-only test was suppressed on API 36 | `ConsoleOriginPolicyTest` and Console security regression tests are JVM-only |
| Console cross-origin navigation and logout cleanup | `ConsoleWebViewSecurityTest` | Not run; API-35-only tests were suppressed on API 36 | `ConsoleOriginPolicyTest` and `ConsoleSessionCleanerTest` are JVM-only |
| Boot restore | No instrumentation class exists | Unexecuted | `BootReceiverTest` is JVM-only and is not device boot proof |
| API-35 foreground-service start restrictions and OEM behavior | No qualifying instrumentation scenario exists; `BalanceRefreshServiceTest` is only a basic service smoke class | Unexecuted; its four API 36 tests do not prove API-35 restrictions | `ForegroundServiceStarterTest` and the JVM `BalanceRefreshServiceTest` are non-device evidence only |
| Widget manual refresh versus watchdog separation | No instrumentation class exists | Unexecuted | `StaticWidgetSchedulingTest` and widget runner tests are JVM-only |
| Watchdog restart after refresh failure or cancellation | No instrumentation class exists | Unexecuted | `StaticWidgetSchedulingTest` covers these branches only on the JVM |
| Long refresh intervals | No instrumentation class exists | Unexecuted | `RefreshSchedulerTest` is JVM-only scheduler-state coverage, not elapsed device behavior |

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

## Final-Fix Appendix (Verified Source HEAD `7f15344`)

**Verification date:** 2026-08-04

**Required base:** `f13d8e0d009972bae62cc81f6a19f0ac1740682b`

**Verified source HEAD:** `7f15344baa3ebfda9a2d991734751b545080a3fa`

**Final-fix source range:** `f13d8e0d009972bae62cc81f6a19f0ac1740682b..7f15344baa3ebfda9a2d991734751b545080a3fa`

**Whole-review audit range:** `0e858065053f33b68a4f2173358ab97482f0c772..7f15344baa3ebfda9a2d991734751b545080a3fa`

The original Task 12 sections above remain the historical `fd46ba1` snapshot. This appendix is the authoritative verification record for the final-fix source HEAD. The later report-only commit does not change compiled source, tests, resources, or APK inputs.

### Final-Fix Gate Results

All Gradle invocations below were serialized and recorded zero Java `GradleWrapperMain` clients before and after execution.

| Gate | Fresh result |
|---|---|
| Combined affected Debug JVM suites | 56 suites, 700 tests, 0 failures, 0 errors, 3 skipped |
| `compileDebugKotlin --rerun-tasks` | exit 0; `BUILD SUCCESSFUL` |
| `compileDebugAndroidTestKotlin --rerun-tasks` | exit 0; `BUILD SUCCESSFUL` |
| Complete Debug JVM run 1 | 91 suites, 1,057 tests, 0 failures, 0 errors, 3 skipped |
| Complete Debug JVM run 2 | 91 suites, 1,057 tests, 0 failures, 0 errors, 3 skipped |
| Complete Release JVM run | 91 suites, 1,057 tests, 0 failures, 0 errors, 3 skipped |
| Debug + Release lint | 0 errors in both variants; no baseline |
| Debug + Release assembly | both APKs built and packaged-manifest audits passed |
| Kover Debug XML + HTML + verify | exit 0; configured 1%..100% verification bound passed |
| API 36 instrumentation | 38 tests, 0 failures, 0 errors, 0 skipped |

The affected JVM gate covered Debug redaction, Console policy/interception, refresh and committer behavior, repository/import/cleanup paths, `HomeViewModel`, receivers, services/notification derivation, widgets, and saved-script/balance execution. Its exact log, boundary record, and 56 XML suites are under `.superpowers/sdd/2026-08-01-wallet-sentinel-hardening/final-fix-evidence/final-gates/01-affected-jvm/`.

The two complete Debug runs and the complete Release run are independent process-bounded executions. Their XML and HTML reports are separately archived under final-gates directories `04-test-debug-unit-run-1`, `05-test-debug-unit-run-2`, and `06-test-release-unit`.

The three JVM skips remain the AndroidKeyStore-dependent `WidgetProviderTest` rendering cases.

### Final-Fix Coverage

| Counter | Covered | Total | Coverage |
|---|---:|---:|---:|
| Instructions | 40,690 | 142,883 | 28.4778% |
| Branches | 2,514 | 6,114 | 41.1187% |
| Lines | 5,904 | 14,974 | 39.4283% |
| Methods | 1,060 | 2,056 | 51.5564% |
| Classes | 312 | 887 | 35.1747% |

Kover verification exited 0 with the configured non-zero 1%..100% guard. The guard remains a regression check, not a claim of exhaustive coverage.

### Final-Fix Lint

- Debug: 156 findings, consisting of 151 warnings and 5 informational findings.
- Release: 156 findings, consisting of 151 warnings and 5 informational findings.
- Errors: 0 in both variants.
- No tracked lint baseline exists, and lint was not weakened or suppressed for this fix wave.

### Final-Fix APK And Manifest Evidence

| Artifact | Size | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 33,090,609 bytes | `127631A86E961A751043D818EF34B97C4C1B70437C7375F0CF6056A4A4132339` |
| `app-release.apk` | 15,138,835 bytes | `7289B66DA801BD34E68BFA7DC73EED81E5432ACBDB0A21C5FFC6C7F0F8F7F362` |

Both fresh APK manifests contain all five exported static widget providers with only the framework `APPWIDGET_UPDATE` action. Both contain exactly one non-exported `WidgetRefreshReceiver`; neither contains a manifest filter for `WIDGET_REFRESH_NOW` or `WIDGET_WATCHDOG`. `ConsoleActivity` is absent from both packaged manifests. The APKs, `aapt` dumps, hashes, and parsed audit are preserved under final-gates directory `08-assemble-apks`.

### Final-Fix Device Evidence

`adb devices -l` found one healthy target: `emulator-5554`, Android 16/API 36, x86_64, AVD `medium_phone`, with boot completion set to 1. The complete unfiltered `connectedDebugAndroidTest --rerun-tasks` invocation discovered and passed 38/38 tests. The source now contains 8 instrumentation files and 41 `@Test` methods; the three `ConsoleWebViewSecurityTest` methods remain API-35-only and were suppressed on API 36.

No API 35 device was available. No API 35 execution is claimed, and the target-API device gaps documented in the historical report remain release risks.

### Final-Fix Source Integrity

- Default and English string resources contain 554 unique keys each, with zero duplicates and zero paired-key differences.
- The final-fix range contains no staged APK, class, DEX, archive, build, or generated output.
- Privacy-policy decision: no hardening-range update is required because this wave adds no permission or third-party data destination and instead narrows credential, origin, export, and redaction behavior.
- The full working-tree range from `0e858065053f33b68a4f2173358ab97482f0c772` through the final-fix source and these appendices passed `git diff --check` with exit 0. The tracked-text scan covered 297 files and found no NUL bytes; the range contained no tracked generated/build artifact; zero Gradle wrapper clients remained. Clean status and the exact committed range are repeated in the final ignored handoff after the report commit.

### Final-Fix Result

Every executable final-fix gate passed at source HEAD `7f15344`. Release status remains conditional on explicit acceptance of the unavailable API 35 device coverage; API 36 and Robolectric evidence are supplemental and are not presented as substitutes.
