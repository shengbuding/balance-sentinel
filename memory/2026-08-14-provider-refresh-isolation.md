# Provider refresh isolation and diagnostics follow-up

Date: 2026-08-14
Status: COMPLETE

## Confirmed root cause

The DeepSeek refresh failure was caused by the phone routing traffic through a
Japan VPN path. Disabling or changing that VPN route restored normal DeepSeek
refreshes. The provider implementation and public DeepSeek endpoint were not
the root cause.

The separate home-screen behavior was still a real application defect: a slow
or failed provider delayed projection of providers that had already completed,
and a first-refresh failure had no durable state when no balance row existed.

## Provider-isolation fixes retained

- `BalanceWidgetDataStore` persists balance and failure snapshots and exposes a
  `SharedPreferences`-backed observation flow.
- First-refresh failures are persisted even when no prior balance exists, and a
  later successful refresh clears the matching failure.
- Failure records remain consistent across account deletion, cache clearing,
  and account-ID migration.
- `RefreshRuntime` records failures for every failed account instead of exiting
  early on an empty cache.
- `HomeViewModel` projects per-account cache changes while a multi-provider
  batch is still running, so completed providers become visible immediately.
- Per-request, per-account baselines prevent unrelated accounts from leaving
  their loading state early.
- Persisted first-refresh errors survive ViewModel recreation without replaying
  an old snackbar.

## DeepSeek-specific handling

The temporary DeepSeek-only TLS peer probe and failure interception were
removed after the VPN root cause was confirmed. No trust bypass or provider
fallback was added.

The normal transport security policy remains unchanged:

- cleartext traffic is disabled;
- only the Android system certificate store is trusted;
- `api.deepseek.com` retains its two independently packaged SPKI pins through
  2027-07-01.

## Diagnostics and export improvements

- Added generic network diagnostics for active network state, transports,
  validation, captive portal, metering, roaming, VPN presence, Private DNS
  state, DNS server count, and proxy presence.
- Network diagnostics intentionally do not collect SSID, IP addresses, DNS
  addresses, device identifiers, or credentials.
- Expanded the diagnostic report with application/device metadata, network
  state, refresh configuration, scheduler/service health, refresh statistics,
  account metadata, recent refresh-run ledger entries, event logs, API debug
  captures, widget errors, crashes, and breadcrumbs.
- The report is redacted and bounded to 2 MiB, with an explicit truncation
  marker when necessary.
- URL userinfo is stripped during redaction, and widget errors are redacted
  before they are persisted.
- The Data Management diagnostic-report action now uses Android's Storage
  Access Framework `CreateDocument`, matching the other exports and allowing
  the user to choose the destination directory.
- The account debug dialog can trigger a refresh and reloads captured requests
  after that refresh completes, avoiding the prior capture/read race.

## Shared refresh interval

- Foreground monitoring and background scheduling now use one user-configured
  refresh interval.
- Android WorkManager still applies its platform minimum of 900 seconds to the
  actual background job while foreground monitoring uses the configured value.
- `DeepSeekApp` is the single continuous owner of background WorkManager
  reconciliation; duplicate ViewModel reconciliation was removed.
- Foreground service rescheduling happens only after the setting has been
  persisted successfully.
- Widget scheduling metadata records the actual clamped background interval.
- Disabling background refresh clears stale expected-next-refresh state.
- Configuration export/import preserves the explicit background-enabled state.
  The legacy `backgroundRefreshInterval` compatibility field remains at least
  900 seconds so older app versions can still import exported configuration.

## Verification

- `:app:compileDebugKotlin`: passed.
- `:app:compileDebugUnitTestKotlin`: passed.
- Full `:app:testDebugUnitTest --rerun-tasks`: 1419 tests, 0 failures,
  0 errors, 3 skipped.
- `:app:lintDebug`: passed.
- `:app:compileReleaseKotlin`: passed.
- `:app:assembleDebug`: passed.
- `git diff --check`: passed; only existing LF-to-CRLF working-copy warnings
  were emitted.

One pre-existing concurrent Data Management test timed out once during the
first full run. The individual test, its complete test class, and the final
full rerun all passed without code changes, confirming a transient scheduling
delay rather than a product regression.

## APK evidence

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

- Size: 38,317,297 bytes
- Built: 2026-08-14 15:20:53 +08:00
- Version: versionCode 686, `v1.4.2-497-g780667b-dirty`
- SHA-256: `74A94DCA43436D1B5769ADB1BCF7C8F0846D5D44625337288B765AE95E58B7DA`
- APK signature verification: valid Android Debug signer, APK Signature
  Scheme v2, 0 errors, 0 warnings.

Packaged-manifest inspection confirmed `ACCESS_NETWORK_STATE` is present and
the configured network-security resource is referenced. Packaged
`network_security_config.xml` confirms cleartext is disabled, only system
anchors are trusted, and both expected DeepSeek pins remain present.
