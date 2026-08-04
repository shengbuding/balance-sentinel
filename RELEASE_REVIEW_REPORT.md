# Wallet Sentinel v1.4.2 - Release Review

**Review date:** 2026-08-04

**Review range:** `0e858065053f33b68a4f2173358ab97482f0c772...fd46ba1`

**Branch:** `wallet-sentinel-hardening`

## Decision

**Conditionally releasable.** The full JVM, lint, build, packaging, coverage, hygiene, and source-review gates passed with no open blocking or high-severity defect. Release owners must explicitly accept the missing API 35 device scenarios and the non-blocking residuals below.

## Verified Gates

| Area | Evidence | Result |
|---|---|---|
| Debug JVM | two mandatory independent runs plus a final current-head run | each 1,033 tests, 0 failures/errors, 3 skipped |
| Release JVM | fresh current-head run | 1,033 tests, 0 failures/errors, 3 skipped |
| Focused integration | refresh/API/repository/Console/receiver/service/widget suites | 593 tests, 0 failures/errors, 3 skipped |
| Android lint | Debug and Release with `abortOnError=true` | 0 errors; warnings remain visible |
| Packaging | fresh Debug and Release assembly | both APKs built and audited |
| Kover | XML, HTML, verification | passed; line coverage 38.3707% |
| Device | API 36 AVD | 36/36 discovered tests passed |
| Hygiene | diff/resource/NUL/marker/staging audits | passed |

## Full-Range Security And Persistence Review

The entire hardening range was reviewed across refresh, account lifecycle, provider scripts/contracts, backup/restore, Console WebView boundaries, services/receivers/widgets, alerts/history, and debug capture.

Confirmed release properties:

- Shared refresh coordination prevents stale or failed work from committing cache, history, or alert mutations.
- Unsupported providers issue zero guessed requests; six native contracts use provider-specific endpoints and fixtures, including SiliconFlow COM.
- Backup apply is impossible before preview; replace requires complete credentials and two confirmations.
- URL, origin, DNS, and redirect validation rejects substring, user-info, alternate-port, and private-address bypasses within the allowed-origin model.
- Account lifecycle changes migrate or remove owned data through the lifecycle manager.
- Daily summary deletion occurs only after synchronous write and exact readback.
- Credential, cookie, raw-response, and script-secret paths were traced through stable errors, logs, backups, and clipboard behavior.
- Release does not install active debug capture, and the nonexistent Console activity is absent from manifests/APKs.

The repository's codebase-memory graph tools were unavailable during the final review. The fallback was full-range Git history/diff inspection, `rg`, and direct source/test tracing; this tooling limitation does not change the recorded verification results.

## Artifact Identity

| APK | Bytes | SHA-256 |
|---|---:|---|
| Debug | 33,022,173 | `B6C755905E050E8E360D9C58E66C5195DA459CEEE050FC0A1E2DC734D49D3CF5` |
| Release | 15,103,167 | `93E782F874FBC49EDC9CCBDAFF3A4D33F8817F0264758AC48F77B0BBB669EAD5` |

## API 35 Release Gap

Only an API 36 AVD was available. Its 36/36 pass is supplemental, not target-API proof. Before broad rollout, run these on API 35 where possible:

- Backup preview and destructive replace.
- Console exact-origin injection, cross-origin navigation, and logout cleanup.
- Boot restore.
- Foreground-service start restriction and representative OEM behavior.
- Widget manual/watchdog separation.
- Watchdog restart after failure/cancellation.
- Long refresh intervals.

`BalanceRefreshServiceTest` is a JVM test and does not prove API-35 foreground-service restrictions.

## Accepted Non-Blocking Residuals

1. URL validation contains duplicated hard-coded copy; SELECT fields currently use text input, but no provider declares SELECT.
2. Some provider compatibility parameters remain intentionally ignored, and debug clients may be allocated per request.
3. Cross-file SharedPreferences lifecycle mutations are compensating operations rather than a transaction; secondary diagnostics may be swallowed.
4. A thrown widget action finishes its pending result but can escape without structured logging.
5. Existing Kotlin, deprecation, and lint warnings remain. Lint reports 0 errors, 152/153 warnings, and 5 informational findings per variant.
6. Additional authorized HTTPS origins are limited to port 443.
7. Thirty-day TTL arithmetic is duplicated.
8. Scheduling retains intentionally bounded lateness.
9. There is no explicit multi-entry relative-order test or explicit `getAccountIds()` access-order mutation test.

The historical CrashLogger blocker is closed by two independent complete Debug passes and is not an accepted residual.

## Privacy Decision

`PRIVACY_POLICY.md` is intentionally unchanged. The full-range review found no proven mismatch between current behavior and its promises. No policy edit was made merely to create documentation churn.

## Recommendation

The branch is suitable for a controlled release after the release owner accepts the API 35 gaps above. For broad rollout, prioritize an API 35 device pass for WebView origin enforcement, foreground-service restrictions, boot restoration, and watchdog behavior.
