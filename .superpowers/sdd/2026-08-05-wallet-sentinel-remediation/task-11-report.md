# Task 11 report

## Status

Partial GREEN implementation committed as `c82ae65`.

## Changes

- Added `RoomRefreshPersistence`, a single `WalletDatabase.withTransaction` boundary for refresh raw records, usage snapshots/records, and event logs.
- Refresh production path now uses Room persistence by default and no longer snapshots/restores legacy stores. A nullable legacy writer remains only as an explicit test/migration compatibility seam.
- Added exact raw-record ID deletion DAO used by Room cleanup implementations.
- Synchronous account lifecycle save/delete now delegates to the Room account mutation coordinator; account-owned Room rows are removed by FK cascade. Widget/debug external cleanup remains outside the Room transaction.

## Verification

Command:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-parallel
```

Result: `BUILD SUCCESSFUL`.

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL` (three migrated Room behavior test classes pass).

## Concerns

- Cleanup default overload now reads Room history and performs summary upsert plus exact ID deletion in one Room transaction (`e757320`). The explicit `LegacyHistoryRepository` overload remains available for migration compatibility.
- Legacy stores are no longer exercised by the focused behavior tests; they remain migration-reader compatibility seams.
- The Room refresh transaction currently publishes provider/widget caches after the durable Room commit; those external caches are intentionally not part of the records/usage/logs transaction.

## Fix Round 1

- Added Room continuity generation through yesterday in the default cleanup path.
- Chunked exact-ID deletes at 500 rows and added a 1,200-row regression test to avoid SQLite bind limits.
- Added late-arrival exact-ID preservation and default committer FK failure tests.
- Switched foreground service, watchdog receiver, Insights, and Log UI production paths from legacy stores to Room repositories/DAOs; account deletion again clears the provider cache after Room cascade.

Verification:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --rerun-tasks --no-parallel
.\gradlew.bat :app:compileDebugKotlin --no-parallel
git diff --check
```

All focused tests and compilation passed; `git diff --check` passed. Commit: `97bbb9d`.

Residual audit: DataExporter, DataManagementViewModel, StaticWidgetProvider and some legacy repository adapters still require a broader Room read/write migration; their call sites remain listed by the full `rg` audit and are not silently classified as complete.

## Fix Round 1 finalization

Removed the `RefreshResultCommitter` legacy writer constructor/branch entirely; its production path now has only the Room atomic persistence boundary. Restored provider-cache clearing after account deletion and chunked cleanup deletion. Task 12 consumer paths (DataExporter, DataManagementViewModel and related UI/export consumers) remain explicitly deferred.

Final focused command passed with `BUILD SUCCESSFUL`; `:app:compileDebugKotlin --rerun-tasks --no-parallel` passed; `git diff --check` passed.
