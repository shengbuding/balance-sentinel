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
