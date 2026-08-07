# Task 7 Report

## Scope and Commits

- Baseline: `1d8589f686509e7bdce4c806ba9bbf6c286496fc`.
- Support seam: `97fa184` (`feat(settings): add Room settings repository support seam`).
- RED tests: `407bd49` (`test(settings): capture Room source-of-truth behavior`).
- GREEN implementation: `14060c0` (`feat(settings): publish configuration from Room snapshots`).
- Report commit: the commit adding this file.

Task 7 moves app/account alert configuration, notification selections, alert runtime state, and snoozes to a single immutable Room-backed snapshot. Legacy `WidgetPrefs` is read only by the startup migration and compatibility adapters; language, onboarding, update preferences, and per-widget layout remain device preferences.

## RED Evidence

Exact command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --rerun-tasks --no-parallel
```

Result: 30 tests executed, 6 failures, 0 compilation/fixture/environment failures. The six failures were the intended RED assertions: legacy interval migration was not wired (3 boundary cases), Room publication was not implemented, export did not distinguish foreground/background cadence, and mixed Room/Prefs state was still possible.

## GREEN Evidence

Exact command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 30 tests executed, 0 failures, 0 skipped.

Exact compile command:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 17 actionable tasks executed. Existing Room child-FK index warnings and unrelated Kotlin/deprecation warnings remain; no new schema index or migration identity change was introduced.

Exact whitespace check:

```powershell
git diff --check 1d8589f686509e7bdce4c806ba9bbf6c286496fc..HEAD
```

Result: passed with no whitespace errors.

## Static Search

Command:

```powershell
rg -n "WidgetPrefs|widgetPrefs|widget_prefs|refreshIntervalSeconds|alertEnabled|snoozeDurationMinutes|notification" app/src/main/java -g '*.kt'
```

The production configuration consumers now read `SettingsRepository.snapshot` or use suspend update/import APIs. Remaining `WidgetPrefs` matches are limited to:

- `DeepSeekApp` startup migration/cleanup and language preference restoration.
- `WidgetPrefsLegacySettingsSource` and legacy `ConfigManager`/`BackupImportPlanner` overloads retained for source compatibility.
- `DataManagementViewModel` full-app reset cleanup.
- The old `AlertChecker.check`/`checkChange` compatibility adapter, which is not used by the migrated refresh dispatcher; `checkPublished`/`checkChangePublished` are the Room-backed production entry points.
- The `WidgetPrefs` implementation itself and device-only preferences.

No configuration import/export, account lifecycle, service, receiver, widget, or settings UI path mirrors writes into `WidgetPrefs`.

## Self Review

- `RoomSettingsRepository.publishSnapshot` replaces all five settings tables inside one Room transaction, validates the 900-second background cadence boundary, dense-orders notification selections, and publishes one immutable state after commit.
- `updateSnapshot` serializes concurrent UI/runtime updates with a mutex. Observers receive `Loading` until a snapshot is available and then a consistent `Ready` value.
- Startup runs account migration before settings migration, resolves legacy IDs against canonical Room IDs, and only migrates when `app_settings` is absent.
- Consumers including Service, Receiver, Widget, notification derivation, alert checking, configuration export/import, account lifecycle, and ViewModels use the Room snapshot contract.

## Remaining Risks

- `BackupImportPlanner.applyAsync` still performs credential replacement before publishing settings, so credentials and settings are not one cross-repository atomic mutation. This is recorded as a follow-up risk rather than claimed as solved by Task 7.
- The compatibility `AlertChecker.check`/`checkChange` path still reads legacy preferences for older callers/tests. Production refresh code uses the published-snapshot methods; complete removal requires migrating those external callers and fixtures.
- Repository-wide legacy tests that assert `WidgetPrefs` mirror behavior remain stale under the intentional no-dual-write contract; the focused Task 7 suite is green.

## Fix Round 1 Evidence

Fix mapping:

- Import atomicity: `BackupImportPlanner.applyAsync` requires the Room repository before writes, publishes settings after account replacement, and restores the previous account list when publication fails. The synchronous compatibility entry point fails before mutating either source.
- Room-only alert evaluation: production `AlertChecker` no longer reads or writes `WidgetPrefs`; compatibility methods forward to the published Room snapshot APIs, including runtime-state updates.
- Room-backed compatibility export: `ConfigManager` export overloads obtain their settings from the Room snapshot rather than reconstructing settings from legacy preferences.
- Bounded import: `ConfigManager.importFromUri` streams UTF-8 input and rejects payloads above 1 MiB before decoding.
- Loading UI: `AlertSettingsScreen` shows a tagged loading card while the settings snapshot is not ready and keeps all settings controls inside the Ready branch. JVM assertions cover the loading/ready decision and the Compose behavior test is compiled in `androidTest`.

RED command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsFixRound1RedTest" --rerun-tasks --no-parallel
```

Result: 6 tests executed, 6 expected production-behavior failures, 0 compilation/fixture/environment failures. The RED assertions covered rollback, sync-import bypass, Room-only alert state, compatibility export, and the bounded-import contract. The Compose loading behavior test was added under `androidTest` and compiled separately.

GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.data.repository.SettingsFixRound1RedTest" --rerun-tasks --no-parallel
```

Result: 37 tests executed, 0 failures, 0 errors, 0 skipped. XML suites independently report `SettingsRepositoryTest` 4, `LegacySettingsMigrationTest` 3, `AlertCheckerTest` 20, `BalanceRefreshServiceTest` 3, and `SettingsFixRound1RedTest` 7; no first failure exists.

Android test compilation:

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; the Compose loading behavior test compiled successfully.

Required production compile:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 17 actionable tasks executed. Existing Room foreign-key index warnings and unrelated Kotlin/deprecation warnings remain.

Static checks:

- No `WidgetPrefs` references remain in production `AlertChecker`.
- `ConfigManager` retains `WidgetPrefs` only in source-compatible parameter signatures; production reads use Room snapshots.
- Account writes in `BackupImportPlanner` are confined to the async try/rollback path.
- No production `runBlocking`, `allowMainThreadQueries`, or `body.string` were introduced.
- `git diff --check d573a5f7739f41d5d100595a7f91be8299f501a6..HEAD` passed with no whitespace errors after the GREEN commit `bd37662`.

Fix Round 1 self-review found no additional regressions in the focused scope. Remaining concerns are the unrelated unbounded `readBytes()` calls in `ConsoleScreen.kt:1102` and `DataExporter.kt:107`, plus repository-wide legacy tests that still assert the intentionally removed `WidgetPrefs` mirror behavior.

## Fix Round 2 Evidence

Fix mapping:

- Executable loading behavior: `AlertSettingsLoadingRobolectricTest` runs the real `AlertSettingsScreen` and `HomeViewModel` against a repository whose `StateFlow` remains `Loading`, then asserts that the `settings_loading` semantics node is displayed. The JVM test source set now includes Compose UI test dependencies, so this assertion executes without a device or AVD.
- Real import atomicity: the former no-op `ConfigManager.applySettings` test was replaced. The new test writes a complete credential-bearing backup, reads it through `ConfigManager.importFromUri`, plans it through `BackupImportPlanner`, and injects a failure after the real Room repository has committed imported settings. It asserts exact account and Room settings preimages after failure.
- Compensating rollback: `BackupImportPlanner.applyAsync` captures both preimages before any write and restores both stores on failure, preserving the original failure and attaching rollback failures as suppressed exceptions.

RED commit: `b7bf96b` (`test(settings): add fix round two red coverage`).

Targeted RED/GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.screen.AlertSettingsLoadingRobolectricTest" --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest.failed URI import restores account and Room settings preimages" --rerun-tasks --no-parallel
```

Fixture stabilization used the same command before the valid RED run: the first attempt found a test-only missing `withTransaction` import and unavailable `assertDoesNotExist` helper; the second found unrealistic imported account IDs blocked by planner validation; the third found cleanup running before the account assertion. No production code was changed during those corrections.

Valid RED result: 2 tests executed, 1 passed and 1 expected behavior failure, 0 errors, 0 skipped. The Robolectric Compose test passed. The import test failed because Room contained `alertEnabled=true`, `alertThreshold=50.0`, and an empty account-alert list after the injected post-publication failure instead of its original snapshot.

GREEN result for the same command: `BUILD SUCCESSFUL`; 2 tests executed, 0 failures, 0 errors, 0 skipped.

Focused GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.data.repository.SettingsFixRound1RedTest" --tests "com.balancesentinel.app.ui.screen.AlertSettingsLoadingRobolectricTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 38 tests executed, 0 failures, 0 errors, 0 skipped. XML suites report `SettingsRepositoryTest` 4, `LegacySettingsMigrationTest` 3, `AlertCheckerTest` 20, `BalanceRefreshServiceTest` 3, `SettingsFixRound1RedTest` 7, and `AlertSettingsLoadingRobolectricTest` 1.

Extended import-planner command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.data.repository.SettingsFixRound1RedTest" --tests "com.balancesentinel.app.ui.screen.AlertSettingsLoadingRobolectricTest" --tests "com.balancesentinel.app.data.repository.BackupImportPlannerTest" --rerun-tasks --no-parallel
```

Result: 54 tests executed, 2 failures. Both are pre-existing stale synchronous-entrypoint assertions: `BackupImportPlannerTest.account persistence failure leaves settings unchanged` and `BackupImportPlannerTest.apply persists accounts once before applying settings`. They expect the deprecated `apply` method to write, while Round 1 intentionally changed it to reject before writes; all 38 Task 7 focused tests passed when those stale tests were excluded.

Production compile:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 17 actionable tasks executed. Existing Room foreign-key index and unrelated Kotlin/deprecation warnings remain.

Whitespace check:

```powershell
git diff --check 21e7183..HEAD
```

Result: passed with no whitespace errors.

GREEN commit: `07232bc` (`fix(settings): restore Room snapshot after import failure`).

Fix Round 2 self-review confirms that the loading assertion is now executable on the JVM and that the import test crosses URI decoding, planning, account persistence, real Room publication, injected failure, and both rollback assertions. Cross-store atomicity remains compensating rather than a single storage transaction: if the underlying account or Room store also fails during rollback, the original operation can still leave partial state; such rollback failures are retained as suppressed exceptions. The unrelated unbounded reads and stale legacy synchronous-import tests remain outside this fix scope.

## Fix Round 3 Evidence

Finding-to-fix mapping:

- Stale Room preimage: `BackupImportPlanner.applyAsync` no longer reads a settings snapshot before starting the import and no longer republishes that stale snapshot during failure handling.
- Concurrent Room updates: `SettingsRepository.applyConfigImport` is an atomic repository seam. `RoomSettingsRepository` captures the current Room preimage under `writeMutex`, publishes imported configuration, runs account persistence while the mutex is held, and restores Room before releasing the mutex if the import fails. `publishSnapshot` and `updateSnapshot` use the same mutex, so runtime/UI writes queue until the import rollback is complete.
- Account compensation: the planner still restores the account preimage independently when the import fails, retaining rollback failures as suppressed exceptions.

RED command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest.failed import rollback preserves concurrent Room runtime update" --rerun-tasks --no-parallel
```

Result: 1 test executed, 1 expected behavior failure, 0 errors. The concurrent runtime row was requested during the paused import, but the stale rollback restored an empty runtime-state list.

RED commit: `3e7a32c` (`test(settings): cover concurrent import rollback`).

Targeted GREEN command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest.failed import rollback preserves concurrent Room runtime update" --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest.failed URI import restores account and Room settings preimages" --rerun-tasks --no-parallel
```

Result: 2 tests executed, 0 failures, 0 errors, 0 skipped.

GREEN commit: `9c7fd99` (`fix(settings): serialize import rollback with Room updates`).

Focused regression command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.SettingsRepositoryTest" --tests "com.balancesentinel.app.data.migration.LegacySettingsMigrationTest" --tests "com.balancesentinel.app.data.repository.AlertCheckerTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.data.repository.SettingsFixRound1RedTest" --tests "com.balancesentinel.app.ui.screen.AlertSettingsLoadingRobolectricTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 39 tests executed, 0 failures, 0 errors, 0 skipped. XML suites report `SettingsRepositoryTest` 5, `LegacySettingsMigrationTest` 3, `AlertCheckerTest` 20, `BalanceRefreshServiceTest` 3, `SettingsFixRound1RedTest` 7, and `AlertSettingsLoadingRobolectricTest` 1.

Production compile:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 17 actionable tasks executed. Existing Room foreign-key index and unrelated Kotlin/deprecation warnings remain.

Whitespace check:

```powershell
git diff --check a692faa..HEAD
```

The atomic repository seam is intentionally scoped to the Room-backed implementation: custom repositories must opt in explicitly rather than silently receiving a non-atomic fallback. Cross-store behavior remains compensating for catastrophic account rollback failure, but concurrent Room writes no longer share the stale rollback window. The unrelated stale synchronous `BackupImportPlannerTest` cases documented in Round 2 remain outside this fix scope.
