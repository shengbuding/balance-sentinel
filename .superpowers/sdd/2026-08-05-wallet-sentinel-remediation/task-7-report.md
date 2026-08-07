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
