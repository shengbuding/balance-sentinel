# Task 12 Report: Query Consumers to Room

## Status

Implemented the Task 12 consumer migration on baseline `2330369`.

## Changes

- `InsightsViewModel` now scopes summary queries to the selected account and a bounded 365-day date range, and reads raw history only for the selected currency and 24-hour window via Room pages. It no longer loads all history into UI state.
- `DataManagementViewModel` cold-start statistics use Room `COUNT(*)`, `COUNT(DISTINCT ...)`, and bounded summary/cache reads. Clear actions use Room DAO delete operations.
- `StaticWidgetProvider` reads the bounded widget summary snapshot API and keeps all receiver/update work asynchronous with `goAsync`.
- `LogViewModel` uses the Room event-log repository with a bounded limit (max 100 entries) instead of loading the legacy store.
- `LogExporter` reads Room event logs with a bounded export limit.
- `DataExporter` export/has-data/import paths use Room history, usage, and event-log repositories with page/count operations. Full streaming/export chunking remains Task 13 scope.
- Added Room DAO count/distinct/clear primitives and updated the usage repository test fake.

## Verification

- `./gradlew.bat compileDebugKotlin --no-parallel`: PASS.
- `git diff --check`: PASS.
- Focused command was run serially with `--no-parallel`. Existing focused tests that still populate legacy JSON stores fail because those tests predate the Task 12 Room fixture migration; this is expected test-fixture drift, not a compile/runtime error in the Room paths.
- Static search across the six Task 12 production consumers found no `RawRecordStore`, `DailySummaryStore`, `UsageDataStore`, `RefreshLogStore`, or legacy `getAll*` calls.

## Concerns / follow-up

- Existing `InsightsViewModelTest`, `DataManagementViewModelTest`, and `LogViewModelTest` fixtures still write legacy stores and must be migrated to Room fixtures in the Task 12 test follow-up.
- `DataExporter` still materializes the export model in memory; the 90k streaming limit and true streaming writer remain Task 13.
- Widget summary data remains backed by the existing bounded SharedPreferences cache; the provider no longer calls its unbounded `getAllBalances` API directly.
