# Task 5 Report

## Baseline and commits

- Baseline: `ba8662c3e32e91d553c5aecec16ab607821e6941`
- Support: `4da06cc` (`refactor: add UI account repository seam`)
- Support: `c67bc62` (`refactor: expose typed UI account load state`)
- RED: `65ec1c5` (`test: expose UI account repository migration gaps`)
- GREEN: `d7737a5` (`feat: migrate UI account flows to Room`)

## RED evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest" --rerun-tasks --no-parallel --console=plain
```

Compilation and fixture setup succeeded. The command ran 123 tests and produced
12 expected behavior failures, with no compile, fixture, or environment errors.
The failures demonstrated that the pre-GREEN UI still:

- read legacy account snapshots instead of asynchronously collecting the typed
  account repository Flow;
- downgraded or failed to expose corruption instead of blocking account writes;
- bypassed the Task 4 mutation coordinator for create, edit, and delete; and
- retained stale account instances or failed to establish fresh subscriptions
  after recreation instead of resolving the latest account by stable ID.

## GREEN evidence

Required focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.ui.viewmodel.HomeViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --tests "com.balancesentinel.app.ui.viewmodel.InsightsViewModelTest" --rerun-tasks --no-parallel --console=plain
```

Results: `HomeViewModelTest` 46/46, `DataManagementViewModelTest` 29/29,
and `InsightsViewModelTest` 48/48 passed. Total: 123/123, with 0 failures,
errors, or skips.

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel --console=plain
git diff --check
```

Debug Kotlin compilation succeeded. Output contained only existing Room index,
kapt, and deprecation warnings; no warning was introduced by Task 5. The diff
check was clean. No Room schema, Entity, DAO, persistent identity, or Task 4
coordinator/recovery protocol file changed.

## Implementation

- `RoomAccountUiRepository` combines stable Room metadata with the encrypted
  credential payload. Missing or inconsistent mappings produce a typed Corrupt
  state rather than a partial or empty account list.
- Home, DataManagement, and Insights subscribe to the typed account Flow.
  Corruption remains explicit in each UI state and disables account/configuration
  writes that depend on a trustworthy account snapshot.
- Home create, edit, and delete each delegate once to the Task 4 coordinator.
  Edit state retains only the stable account ID and resolves the latest Flow
  snapshot before constructing a mutation draft.
- Home loads cached balances after the first Ready emission and can deliberately
  resubscribe after an external import. Page recreation establishes new Flow
  subscriptions rather than retaining an activity-era account snapshot.
- Alert settings consume Home's typed account state instead of directly reading
  `ApiKeyManager`. Backup configuration export consumes DataManagement's typed
  snapshot. Insights gives corruption precedence over the ordinary empty state.
- Flow error handlers rethrow `CancellationException`. Insights state updates
  use atomic `update` operations so concurrent account and range changes do not
  lose `rangeDays`.
- Home hides the add action and disables edit/delete/long-press deletion while
  account data is corrupt. Chinese and English loading/corruption strings were
  added without changing the existing visual design.

## Test lifecycle incident

One intermediate GREEN run exposed a Robolectric cross-test deadlock rather
than a production behavior failure. A thread dump traced it to the old
compatibility mutation path remaining active past the owning test lifecycle.
The test integration was changed to use lifecycle-consistent synchronous test
adapters and explicit dispatcher/scheduler completion. The final Home suite and
the complete 123-test focused command both terminated normally.

## Remaining risks

- Home refresh still uses the legacy `ApiKeyManager` path in
  `refreshSingleAccount()` / `refreshBalance()`; refresh, Service, and Widget
  migration belongs to Task 6.
- `HomeViewModel.getConfigJson()` remains for test/compatibility callers, while
  production BackupRestore now uses the DataManagement typed snapshot.
- Task 4 manifest credential material and the payload-level CredentialStore
  remain SECURITY-ONLY risks. Task 5 does not broaden credential persistence.
- Fully atomic configuration import remains Task 8 scope; this task only moves
  preview/export account reads to the typed repository state.

## Self-review

The committed change is limited to the Task 5 UI repository seam, three
ViewModels, their screens/components, localized strings, and focused tests. It
does not alter Room v1 identity or persistence contracts. Account corruption is
visible and fail-closed, UI account writes use the coordinator, and stable IDs
rather than stale `AccountInfo` instances cross page and edit lifecycles.
