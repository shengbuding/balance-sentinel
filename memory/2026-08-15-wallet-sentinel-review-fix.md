# Wallet Sentinel review fix handoff

Date: 2026-08-15
Status: COMPLETE

## Symptom

The previous Codex task failed at the provider/runtime layer and stopped before
the review fixes were complete. In the app, Insights showed every configured
account for every currency. An account with no data for the selected currency
still entered the multi-account merge path, which produced misleading
`MULTI_ACCOUNT_*` depletion methods and left an invalid account selection after
switching currencies.

## Root cause

`InsightsViewModel.loadData()` used the full Room account list for the account
chips and for both merge functions. The engines filtered records by currency,
but the orchestration layer still counted accounts that produced no output.
The selected account was validated only against the full account list, not
against accounts eligible for the selected currency.

## Fix

- Added `InsightsUiState.eligibleAccounts` and derive it from account IDs found
  in 365-day summaries or 24-hour raw records for the selected currency.
- Use the eligible list for account chips, raw/daily/intraday computation, and
  multi-account merging.
- Normalize an incompatible selected account to `null` and persist that value
  in `SavedStateHandle` when the currency changes.
- Added regression coverage for currency-specific account filtering, avoiding
  false multi-account estimates, and clearing stale selections.

The same review handoff also completed the total-balance notification ordering
schema/config/migration path, canonical widget Insights navigation, Room-backed
widget account validation, Console localization, and Chinese loading/language
labels.

## Evidence

- `:app:testDebugUnitTest --tests com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest`
  passed.
- `testDebugUnitTest` passed in 7m 17s.
- `lintDebug lintRelease` passed.
- `assembleDebug` passed.
- `connectedDebugAndroidTest` passed: 71/71 tests, 0 skipped, 0 failed.
- `git diff --check` passed; only Git's LF/CRLF conversion warnings were emitted.

## Regression tests

- `InsightsViewModelTest.currency eligibility excludes accounts without matching data and keeps single account estimate`
- `InsightsViewModelTest.switching currency clears an account selection that is no longer eligible`

## Status

DONE. No commit was created. Existing uncommitted review changes remain in the
working tree for the user to inspect.

---

# Persistent notification follow-up

## Symptom

The pinned balance notification disappeared after the bounded foreground
service stopped, after a process restart, or after Android 15 exhausted the
`dataSync` foreground-service budget. A combined Robolectric run also reported
`UncaughtExceptionsBeforeTest` with `Illegal connection pointer` in
`RoomSettingsRepository.observerJob`.

## Root cause

- Controlled service shutdown used `STOP_FOREGROUND_REMOVE`, so the shared
  notification ID was removed with the foreground service.
- Application startup unconditionally cancelled notification ID `1001`.
- Background refresh and health workers did not rebuild a detached notification.
- The older GitHub release appeared more persistent because it restarted the
  service from exact alarms, `onTaskRemoved`, and `BOOT_COMPLETED`. Those paths
  conflict with Android 15 `dataSync` foreground-service limits and must not be
  restored.
- The test-only SQLite failure came from a real Room settings observer surviving
  a Robolectric test boundary while the database connection was being closed.

## Fix

- Detach the notification on bounded/platform shutdown, preserve it across app
  startup, reuse it when promoting a new service session, and republish committed
  cache data from refresh and 15-minute health workers.
- Add a two-second hard service-stop deadline around timeout-state persistence,
  so Android 15 compliance does not depend indefinitely on Room completion.
- Route explicit user shutdown through a service action that records
  `USER_STOPPED`, uses `STOP_FOREGROUND_REMOVE`, and cancels the detached ID.
- Gate worker publication on the persisted monitoring intent so a queued refresh
  cannot resurrect the notification after opt-out.
- Treat notification delivery as best effort after a committed service refresh.
- Isolate service/worker tests from application startup and close the settings
  repository before the test database.

## Evidence

- `:app:compileDebugKotlin` passed.
- The seven notification/service-focused JVM test classes passed together.
- `:app:testDebugUnitTest` passed: 1,466 tests, 0 failures, 0 errors, 3 skipped.
- API 35 `BalanceRefreshServiceTest` instrumentation passed: 4/4 tests.
- `git diff --check` reported no whitespace errors; only configured LF/CRLF
  conversion warnings were emitted.

## Regression tests

- `BalanceRefreshServiceTest.explicit user stop removes the persistent notification`
- `BalanceRefreshServiceTest.notification failure does not fail committed service refresh work`
- `RefreshWorkerTest.disabled monitoring never republishes after a background refresh`
- `MonitoringHealthWorkerTest.disabled monitoring never republishes notification`
- `ContinuousMonitoringControllerTest.platform timeout preserves desired intent and marks the projection limited`
- `ContinuousMonitoringControllerTest.user stop clears desired intent and records the user stop reason`
- `DeepSeekAppTest` startup notification-preservation coverage
- `PersistentBalanceNotificationPublisherTest`

## Related

Android can still remove notifications after force-stop, an explicit system
"Stop" action, notification permission revocation, or OEM task killing. The
implementation maximizes compliant recovery but does not claim to override
those platform/user actions. A real six-hour system quota timeout was not waited
out during instrumentation; the timeout callback and state transition are
covered at the service/controller level.

## Status

DONE_WITH_CONCERNS. The compliant persistence path is implemented and verified;
the remaining concern is the platform-controlled force-stop/quota behavior that
cannot be made absolute by an application.

---

# Insights current-currency follow-up

## Symptom

A custom account currently returned only `USD`, and Home rendered one currency,
but Insights still exposed both `CNY` and `USD`.

## Root cause

Insights built `availableCurrencies` from every historical raw record and daily
summary. A currency produced by an older script response therefore remained a
tab even after the account's current refresh snapshot had replaced it.

## Fix

- Prefer the latest cached balance batch for each current account, matching the
  snapshot already used by Home.
- For accounts without a current cache entry, query only those visible account
  IDs and only the 365-day Insights window.
- Restrict each account's eligibility to its own current currencies, so an old
  `CNY` series cannot re-enter a cross-account `CNY` aggregation.
- Replace an invalid saved currency with the current valid currency and prevent
  cancelled loads from overwriting a newer selection.

## Evidence

- The user scenario regression failed before the fix and passed afterward.
- `InsightsViewModelTest` and `HistoryRepositoryTest` passed together.
- `:app:testDebugUnitTest` passed: 1,469 tests, 0 failures, 0 errors, 3 skipped.
- `:app:lintDebug` passed.
- `git diff --check` reported no whitespace errors; only LF/CRLF conversion
  warnings were emitted.

## Regression tests

- `InsightsViewModelTest.current balance snapshot hides historical currencies for the same account`
- `InsightsViewModelTest.history currency fallback is scoped to visible accounts`
- `InsightsViewModelTest.current snapshot limits each account to its current currencies`

## Status

DONE. No commit was created; the existing uncommitted review changes remain in
the working tree.

---

# Historical v1.5.0 pre-release and Claude Code handoff

## Outcome

- The implementation and review fixes were consolidated in
  `d8a3f01c21901694e11e8ac68571b06cfe6be17b` (`feat: complete wallet
  sentinel review fixes`).
- Release documentation was prepared in
  `0cdcb69ca139cd9d2e63ef2b1970fc97a013761f` (`docs: prepare v1.5.0
  local release`). This commit is the current `master` HEAD.
- At the pre-release snapshot, the local annotated tag `v1.5.0` pointed to
  `0cdcb69`; its message was
  `v1.5.0 local pre-release`. The tag is local and unsigned.
- Local `master` is 501 commits ahead of the existing local
  `origin/master` reference. No push, pull request, GitHub Release, workflow
  dispatch, or artifact upload was performed.
- The tree was clean at the tagged release commit. This final memory update,
  the Claude Code handoff document, and the README links are intentionally
  left as local, uncommitted documentation changes for the reviewer.

## Release artifact

- Path: `app/build/outputs/apk/release/app-release.apk`
- Package: `com.balancesentinel.app`
- Version: `versionName=v1.5.0`, `versionCode=691`
- Size: `16,800,134` bytes
- SHA-256:
  `3F8DC76B4D0263C18ABB8C777056BDBA04EBEDBA6A82D6870ED8F12879802F24`
- APK Signature Scheme v2 verification passed.
- Signing certificate SHA-256:
  `319aa8dae339e8c95e5538331605550d7abc94992cbeb5ce54b74b276ccbad3f`

No keystore, password, API key, cookie, or session value was added to Git or
written into the handoff documents.

## Final verification baseline

- Debug JVM tests: 1,486 total, 0 failures, 0 errors, 3 skipped.
- Release JVM tests: 1,486 total, 0 failures, 0 errors, 3 skipped.
- The tagged tree's Release JVM tests were rerun and passed.
- Debug and Release lint completed with 0 errors.
- Kover verification passed. Reported line coverage was 58.96% and branch
  coverage was 48.79%; a change-focused audit estimated roughly 69% coverage
  of the core paths and identified nine boundary-test gaps.
- Targeted `MainActivityTest` instrumentation passed 4/4. Full
  `connectedDebugAndroidTest` discovery crashed twice at 0 discovered tests on
  the API 35 emulator before assertions ran.
- The pre-release security review found no confirmed blocking finding at
  confidence 7/10 or higher. Signing material remained untracked.

## Independent review boundary

Claude Code should review the tagged implementation and the current doc-only
working-tree diff, with findings first and precise `file:line` evidence. The
highest-value boundaries are:

1. Exact-alarm permission/fallback paths, OEM cadence, retained notification
   recovery, and user opt-out races.
2. Foreground-service platform timeout, persistence deadline, and duplicate
   stop completion.
3. Usage-field configuration encoding, Room/import-export persistence, NewAPI
   credential fallback, and custom-script validation.
4. Insights cancellation/generation races, selected-account query cost, and
   regression of complete-first-point aggregate chart semantics.
5. Background-refresh toggle disable/change/re-enable semantics.
6. Desktop-sync metadata/payload policy. The sync code is a reserved local
   contract only; no network transport or UI exists.
7. Startup `runBlocking`, synchronous Console preference commits, permission
   resolution on real devices, and narrow-screen alert layout/accessibility.

The 90/120-second exact-alarm watchdog is an explicit product requirement for
aggressive notification survival. It remains a battery, Doze-quota, and OEM
reliability risk and must be reported as such rather than described as a
guaranteed cadence.

## Handoff

The executable review brief, commands, output contract, artifact facts, and
source map are in `docs/claude-code-review-handoff.md`. The reviewer must not
push, create a pull request or GitHub Release, dispatch a remote workflow, or
upload the APK.

## Status

READY_FOR_INDEPENDENT_REVIEW. Local pre-release is complete; only the current
documentation handoff changes are intentionally uncommitted.

---

# Formal v1.5.0 GitHub release

## Outcome

- Formal release version: `v1.5.0`.
- The release is published from the final local `master` commit and the
  `v1.5.0` tag. The tag-triggered GitHub Actions workflow builds the signed APK,
  verifies the certificate allowlist, creates the GitHub Release, and uploads
  the APK asset.
- Release URL:
  `https://github.com/shengbuding/balance-sentinel/releases/tag/v1.5.0`
- GitHub CLI was not authenticated locally; publication therefore used the
  repository's SSH remote and tag-triggered workflow. No token was written to
  the repository.

## Documentation synchronized

- `README.md` now describes v1.5.0 as the current formal release and links the
  local changelog and GitHub Release.
- `CHANGELOG.md` records the user-visible v1.5.0 additions, changes, fixes and
  verification gates.
- `PROJECT_INDEX.md`, `PRODUCTION_AUDIT.md`, `TEST_REPORT.md`, and
  `RELEASE_REVIEW_REPORT.md` now include the v1.5.0 release state while keeping
  v1.4.2 evidence as historical context.
- `docs/claude-code-review-handoff.md` now indexes the formal release and its
  accepted residual risks.
- `.github/workflows/release.yml` now uses `v1.5.0` as the manual dispatch
  example/default, validates the requested tag and checkout, extracts the
  matching CHANGELOG section, runs the full JVM/lint/Kover gates, and verifies
  the uploaded APK asset.

## Final release evidence

- Debug/Release JVM: each 1,486 tests, 0 failures, 0 errors, 3 skipped.
- Debug/Release lint: 0 errors; Kover verification passed.
- Targeted `MainActivityTest`: 4/4 passed.
- Full API 35 instrumentation discovery remains limited by emulator startup
  failure before assertions.
- Release APK hash and signer certificate are recorded in `TEST_REPORT.md` and
  the Claude Code handoff after the signed build.

## Status

RELEASED. Do not push a second tag or create a duplicate Release; use the
GitHub Release URL above to verify the public asset and workflow result.

---

# Exact alarm, recharge, and chart follow-up

## Symptoms

- The retained notification could disappear after OEM/background cleanup.
- Recharge detection only worked when a provider supplied DeepSeek-style
  cumulative metadata; custom usage scripts exposed only the current balance.
- The all-account intraday chart could render a zero line followed by a final
  vertical jump because account series were merged in insertion order.

## Root causes and fixes

- `KeepAliveReceiver` now schedules a 90/120-second exact alarm when permitted,
  re-arms itself before publishing cached data, and only republishes the
  retained notification. It never launches a background `dataSync` service.
  The receiver and `SCHEDULE_EXACT_ALARM` permission are registered, and the
  capability UI opens the system exact-alarm settings when needed. Explicit
  user shutdown cancels both the alarm and notification.
- `RecordAggregator`, `IntradayEngine`, `DailyEngine`, and the Room semantic
  aggregate share provider-neutral recharge inference: when all recharge/grant
  metadata is zero, a positive balance delta above one cent is treated as a
  recharge; explicit cumulative metadata remains authoritative.
- `InsightsViewModel.mergeIntradayOutputs()` now consumes timestamps through a
  sorted map and carries each account's last known balance forward. Daily
  merged points are sorted by date as well.

## Evidence

- `:app:testDebugUnitTest` passed: 1,477 tests, 0 failures, 0 errors, 3
  skipped.
- `:app:lintDebug` passed (the pre-existing warning set remains).
- `:app:assembleDebug` passed.
- Debug APK SHA-256:
  `CBAB6D2FBCDEBEECCABC71D773C9E025A6CE791316606A07A8660CACDEB595D0`.

## Status

DONE_WITH_CONCERNS. Exact alarms remain subject to user permission and OEM or
system force-stop behavior; no Android app can override those controls.

---

# Settings, permissions, custom fields, and NewAPI follow-up

## Additional symptoms

- Custom usage scripts could fail when their returned object contained nested
  metadata, even when the selected balance was valid.
- A configured nested balance/display path was not consistently projected into
  the home card, and the generic preset could replace a real zero with a later
  non-zero fallback because it used JavaScript `||`.
- The edit dialog placed display-field editors and the script switch in one
  horizontal row, making the controls unusable on normal widths.

## Root causes and fixes

- `UsageScriptExecutor` now resolves configured balance/display paths through
  JSON objects and arrays, safely ignores unrelated nested metadata, and only
  projects configured fields (while preserving top-level compatibility when no
  display mapping is configured).
- The generic preset uses null checks instead of truthiness for balance fallback.
- The NewAPI preset follows CC-Switch's `/api/user/self` contract: optional
  `accessToken`/`userId` fields, `New-Api-User` header, and quota conversion by
  `500000` into USD. The account API key remains the access-token fallback.
- The edit dialog now stacks script, display-field, and balance-field controls
  vertically, matching the add flow.
- The broader review work also includes exact-alarm capability recovery,
  provider-neutral recharge detection, current-currency Insights filtering,
  complete console session cleanup, permission onboarding/status surfaces,
  settings switches and regrouped settings navigation, alert-page reordering,
  and transport-neutral desktop sync contracts documented without opening a
  network service.

## Evidence

- `:app:testDebugUnitTest --tests com.balancesentinel.app.data.api.balance.UsageScriptExecutorTest` passed.
- `:app:testDebugUnitTest` passed: 1,485 tests, 0 failures, 0 errors, 3 skipped.
- `:app:lintDebug` passed.
- `:app:assembleDebug` passed.
- `:app:connectedDebugAndroidTest` passed: 71/71 tests, 0 skipped, 0 failed.
- `git diff --check` passed; only Git's LF/CRLF conversion warnings were emitted.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (39,272,717 bytes).
- Debug APK SHA-256: `7EA9C1ADFD3D2E9D124C637B35AC0912E7A306EF31359CA2EA804FA40E64B88D`.

## Regression tests

- `UsageScriptExecutorTest.configured nested balance and display paths are resolved`
- `UsageScriptExecutorTest.nested response metadata does not invalidate an otherwise valid result`
- `UsageScriptExecutorTest.generic preset preserves a real zero balance`
- `UsageScriptExecutorTest.new api preset uses cc switch headers and quota units`

## Status

DONE_WITH_CONCERNS. The debug artifact is verified. Exact alarms and persistent
notifications remain subject to Android permission, OEM policy, force-stop, and
system quota behavior.

---

# Insights chart and alert layout follow-up

## Symptoms

- The all-account Insights chart could begin with only the account that refreshed
  first, making the left edge look like a rise from zero or a partial balance.
- Alert account rows used independent `SpaceBetween` layouts for the header and
  data, and selected notification rows inserted inline reorder buttons. This
  shifted the balance/change switches and broke alignment on narrow screens.

## Fix

- Start the aggregate timeline at the latest first-sample timestamp/date across
  visible accounts. This keeps the first aggregate point complete without
  backfilling a future account balance into an earlier timestamp.
- Carry each account's latest known close forward only after that complete
  timeline begins; sparse daily openings use the previous close rather than an
  old opening value.
- Give alert rows and headers shared fixed notification/switch column widths;
  keep notification sorting controls in a separate trailing row so selection
  cannot resize the main table columns. Currency labels are ellipsized and
  header labels are centered within their cells.

## Evidence

- `:app:testDebugUnitTest` and `:app:testReleaseUnitTest` passed: 1,486 tests
  each, 0 failures, 0 errors, 3 skipped.
- `:app:lintDebug`, `:app:lintRelease`, and Kover XML/HTML/verify passed.
- `:app:assembleRelease` remains the final artifact gate after the local tag is
  created.
- `MainActivityTest` instrumentation passed: 4/4 tests, 0 skipped, 0 failed.
- Full `connectedDebugAndroidTest` discovery crashed twice at `0 tests` in the
  API 35 emulator before any assertion ran; this is the known instrumentation
  startup failure, while the targeted smoke test passed.
- Debug APK SHA-256:
  `B9753A765E4EC712EE390DA8602662975274BC97DDE94FBE0DE1A9FB3DE56115`.

## Status

DONE. No commit was created; the existing uncommitted review changes remain in
the working tree.
