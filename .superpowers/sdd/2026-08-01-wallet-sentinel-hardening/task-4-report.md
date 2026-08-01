# Task 4 Report

Status: DONE_WITH_CONCERNS

## Recovery Audit

The base is `29ea7e6381121bea28f21d077af46361e6362cfe`; HEAD is the required RED
commit `0f6254385283368b97b1dc76862225203ba21e31`. Its production changes are
only the injectable HomeViewModel gateway seam and WidgetRefreshRunner stub.
The prior implementer installed the Application-scoped runtime, gateway routing
in all four entry points, typed failure cache preservation, and lifecycle
invalidation — but left legacy provider/network/cache/history/log/usage/alert
code reachable in HomeViewModel, BalanceRefreshService, and StaticWidgetProvider.

This implementer completed the GREEN phase by removing all legacy duplicate code
paths and dead code, replacing the service Thread with CoroutineScope, and
creating the planned service runner test.

## Implemented Changes

### Production code

- **HomeViewModel.kt**: Removed the legacy `else` branch in `refreshSingleAccount`
  (~80 lines of direct provider/network/Widget-cache code) and the legacy
  `if (gateway == null)` block in `refreshBalance` (~115 lines of direct
  provider/network/cache/history/log/usage/alert/debug code). Both methods now
  exclusively route through the injected/shared `RefreshGateway`. Removed 11
  unused imports (`IOException`, `ProviderFactory`, `ProviderResult`,
  `UnifiedBalance`, `BalanceEntry`, `ProviderError`, `RawRecord`, `AlertChecker`,
  `RawRecordStore`, `UsageSnapshot`, `UsageDataStore`).

- **BalanceRefreshService.kt**: Replaced `Thread { runBlocking { ... } }.start()`
  with `refreshScope.launch { ... }` in `doRefresh()`. The gateway call
  `refreshGateway.refreshAll(RefreshTrigger.SERVICE)` is now a direct suspend
  call. Removed ~160 lines of dead legacy code (ProviderCache loop,
  ProviderFactory instantiation, RawRecordStore writes, RefreshLogStore AUTO
  entries, AlertChecker calls, FormatUtils notification formatting). Removed
  unused `apiService` field and 9 unused imports.

- **StaticWidgetProvider.kt**: Removed ~38 lines of dead legacy code after
  `return@Thread` (DeepSeekApiService instantiation, direct API calls,
  BalanceWidgetDataStore writes, RefreshLogStore entries). Removed unused
  `DeepSeekApiService` import.

- **AccountLifecycleManager.kt**: No changes needed — already correctly calls
  `gateway?.invalidate(oldId)` in both `save` (before edit/replacement
  persistence) and `delete` (before cleanup persistence).

### Test code

- **HomeViewModelTest.kt**: Removed 3 legacy `RecordingProvider` tests that
  verified direct provider config passing (behavior now owned by the
  gateway/committer). Converted 4 tests from `mockRepository` to injected
  `RecordingRefreshGateway`: `refreshBalance sets isLoading while fetching`,
  `refreshBalance failure updates error message`, `setRefreshInterval with
  accounts triggers refresh`, `removeAccount clears balance from state map`.
  Cleaned up 6 unused test imports.

- **service/BalanceRefreshRunnerTest.kt** (NEW): Created at the brief's planned
  `com.balancesentinel.app.service` package path. Tests service refresh routing
  through the shared gateway with `RefreshTrigger.SERVICE`, notification data
  derivation from committed Widget storage, and empty-account no-op behavior.

- **widget/BalanceRefreshRunnerTest.kt**: Unchanged — already tests Widget
  refresh routing through the gateway.

## Verification

### Command 1: Focused JVM GREEN
```
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.widget.WidgetProviderTest --tests com.balancesentinel.app.ui.viewmodel.HomeViewModelTest --tests com.balancesentinel.app.service.BalanceRefreshRunnerTest --rerun-tasks
```
Exit code: 0. All tests pass.

### Command 2: Lifecycle/runtime
```
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.data.repository.AccountLifecycleManagerTest --tests com.balancesentinel.app.data.refresh.RefreshCoordinatorTest --rerun-tasks
```
Exit code: 0. All tests pass.

### Command 3: Compile check
```
.\gradlew.bat compileDebugKotlin --rerun-tasks
```
Exit code: 0. Clean compilation (only pre-existing deprecation warnings).

### Connected test
Not attempted — prior implementer already recorded `DeviceException: No connected devices!`.

## Call-Site Audit

All four entry points verified clean of duplicate provider/network/committer side effects:

1. **HomeViewModel.refreshSingleAccount**: Gateway-only routing. No `ProviderFactory`,
   `ProviderResult`, `DeepSeekApiService`, `RawRecordStore`, `AlertChecker`,
   `UsageDataStore`, or `RefreshLogStore` writes. Legacy `else` branch replaced
   with `Logger.w`.

2. **HomeViewModel.refreshBalance**: Gateway-only routing via `gw.refreshAll(MANUAL_ALL)`.
   No legacy per-account provider loop, no manual usage fetch, no manual
   `ApiDebugStore.addEntry`. Legacy block replaced with `Logger.w`.

3. **BalanceRefreshService.doRefresh**: Uses `refreshScope.launch` (not Thread).
   Calls `refreshGateway.refreshAll(SERVICE)` as direct suspend. Derives
   notification totals from `BalanceWidgetDataStore.getAllBalances()` (committed
   Widget storage). No `ProviderCache`, `ProviderFactory`, `RawRecordStore`,
   `AlertChecker`, or manual `RefreshLogStore AUTO` writes. Scope cancelled in
   `onDestroy`.

4. **StaticWidgetProvider.handleRefresh**: Uses `goAsync()` + `WidgetRefreshRunner`
   with `RefreshRuntime.from(context)`. No `DeepSeekApiService` instantiation,
   no direct API calls, no manual `BalanceWidgetDataStore` writes.

5. **AccountLifecycleManager**: `gateway?.invalidate(oldId)` called before
   edit/replacement persistence (line 23) and before delete cleanup (line 37).

## Concerns

1. **HomeViewModel `repository` constructor parameter**: `BalanceRepository` is
   still accepted as a constructor parameter with a default value but is no
   longer referenced by any code path. It should be deprecated or removed in a
   future cleanup pass. Not blocking for Task 4.

2. **Connected test gap**: `BalanceRefreshServiceTest` (androidTest) was not
   verified due to `DeviceException: No connected devices!`. This gap was
   already recorded by the prior implementer.

3. **RED verification**: The RED commit `0f62543` was not re-verified in this
   worktree by temporarily reverting production code. The prior implementer
   noted Git rejected a binary reverse-apply. The RED tests were accepted as
   part of the handoff commit.

## Commit

All Task 4 source/test changes committed as an auditable GREEN commit.

