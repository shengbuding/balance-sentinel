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

Result: `37 tests completed, 15 failed`. Failures are expected against the pre-Task-11 tests because they seed/read legacy stores and assert SharedPreferences rollback/order. The failures identify tests that must be migrated to the Room fixtures and transaction assertions required by the brief.

## Concerns

- Cleanup default overload now reads Room history and performs summary upsert plus exact ID deletion in one Room transaction (`e757320`). The explicit `LegacyHistoryRepository` overload remains available for migration compatibility.
- Existing focused tests are legacy-path tests and must be replaced with Room behavior RED/GREEN tests.
- The Room refresh transaction currently publishes provider/widget caches after the durable Room commit; those external caches are intentionally not part of the records/usage/logs transaction.
