# Task 6 Report

## Scope

- Baseline: `198d1f5033ae01dc99d78d7acee2f487ed7daf90`.
- Task 6 implementation range before this report correction: `f3aacdb`, `36cb4e9`, `c2f017a`, and `c534e03`.
- This correction only updates this report; no production or test source changed.

## RED

- Commit `f3aacdb`: added the async typed account snapshot support seam and an in-flight revision-change stale behavior test.
- Command: `./gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshCoordinatorTest.revision*" --rerun-tasks --no-parallel --console=plain`.
- Result: compilation succeeded; 1 test ran and failed at the intended stale-result assertion.
- Commit `36cb4e9`: added Service and Widget legacy `ApiKeyManager` reader RED checks.
- Command: `./gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest.service does not*" --tests "com.balancesentinel.app.widget.WidgetProviderTest.widget provider does not*" --no-parallel --console=plain`.
- Result: compilation succeeded; 2 tests ran, with the intended Service legacy-reader assertion failing.

## GREEN

- Commit `c2f017a`: Room-backed refresh account store, typed corruption handling, repository revision re-read gate, Runtime Room wiring, and Service/Widget/WidgetConfig call-site migration.
- Exact command: `./gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshCoordinatorTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --rerun-tasks --no-parallel --console=plain`.
- Result: `BUILD SUCCESSFUL`; 33 actionable Gradle tasks executed; 13 tests completed and 3 existing tests skipped.
- Exact command: `./gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel --console=plain`.
- Result: `BUILD SUCCESSFUL`; 17 actionable Gradle tasks executed.
- Exact command: `git diff --check 198d1f5033ae01dc99d78d7acee2f487ed7daf90..HEAD`.
- Result: passed with no whitespace errors.

## Self Review

- Stable Room UUIDs and revisions are consumed through typed snapshots. Corrupt credential/metadata state fails closed before an account refresh starts.
- The existing generation/invalidate protocol remains active, and the current repository revision is read again before commit so an edited or deleted account cannot accept an old result.
- Service does not retain or read `ApiKeyManager` on the main thread. Widget rendering does not use that legacy reader, and the existing receiver path retains `goAsync` with dispatcher `finally` completion.
- `WidgetConfigActivity` loads the Room-backed typed account state asynchronously and persists the selected stable account UUID.

## Remaining Concerns

- `RefreshResultCommitter` still uses the synchronous compatibility `RefreshAccountStore` API. `RoomRefreshAccountStore` refreshes its immutable cache before commit, but a future fully-suspending committer would make this contract explicit.
- Widget aggregate rendering filters persisted balances by nonblank account ID when Room metadata is unavailable to synchronous `RemoteViews` rendering. It is fail-closed for refresh corruption, but does not independently reconcile a stale persisted balance against Room at render time.

## Fix Round 1

### Scope and Commits

- Review base: `136e0a9e27411086b673920ee4395e60f3224992`.
- RED: `ccf8acc` (`test(task6): add fix round one behavior red`).
- GREEN: `052f6bb` (`fix(task6): close mutation widget and service races`) and `1612029` (`fix(task6): reuse service account snapshot consistently`).

### Findings and Fixes

- Critical, Room mutation/cache stale-result race: RED ran an in-flight refresh through the real `RefreshResultCommitter` and asserted that Widget balances, raw records, and refresh logs remained untouched during an account mutation. The old path committed and failed the test. GREEN joins Room save/delete and refresh persistence with a fair process-wide semaphore, invalidates the account generation while holding the mutation permit, and gates both Coordinator and direct committer calls. Stable UUID/revision checks and existing generation semantics remain intact.
- Important 1, stale Widget persisted-balance rendering: RED covered a deleted account, typed `Corrupt`, and a normal `Ready` account. GREEN reads typed Room account state asynchronously before `RemoteViews` rendering and filters every configured/aggregate branch by verified stable account IDs; Loading/Corrupt states render no balance.
- Important 2, Service deadline/empty-batch regression: RED covered two-account count propagation and an empty repository with stale committed Widget data. GREEN exposes a typed gateway snapshot, sizes wake lock/deadline from one captured snapshot, and does not read or notify from old committed balances when the account count is zero.
- Important 3, production Widget receiver `runBlocking`: RED used a controlled suspended action to prove dispatch returns before completion and that finish runs after success, failure, and cancellation. GREEN launches the receiver work in an IO coroutine scope, removes production `runBlocking`, and completes `goAsync()` plus wake-lock cleanup in `finally`.
- Important 4, RED quality: removed reflection/field-shape checks. Replacement tests assert persisted side effects, typed Widget-visible data, Service batch output, and asynchronous receiver lifecycle behavior.

### RED Evidence

- Command: `./gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest.account mutation barrier*" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --rerun-tasks --no-parallel --console=plain`.
- Result: compilation and fixtures succeeded; 15 tests completed, 3 existing tests skipped, and 6 behavior tests failed as expected: stale persistence commit, Service account count, Service stale empty-batch data, deleted Widget balance, Corrupt Widget balance, and suspended-dispatch timing.

### GREEN Evidence

- Command: `./gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.refresh.RefreshCoordinatorTest" --tests "com.balancesentinel.app.data.refresh.RefreshResultCommitterTest" --tests "com.balancesentinel.app.service.BalanceRefreshServiceTest" --tests "com.balancesentinel.app.widget.WidgetProviderTest" --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --tests "com.balancesentinel.app.data.repository.AccountMutationRecoveryTest" --rerun-tasks --no-parallel --console=plain`.
- Result: `BUILD SUCCESSFUL`; 33 Gradle tasks executed; 53 tests completed, 3 existing Widget rendering tests skipped, 0 failures. Per-class counts: RefreshCoordinator 4, RefreshResultCommitter 12, BalanceRefreshService 3, WidgetProvider 12 (3 skipped), AccountLifecycleManager 11, AccountMutationRecovery/Room mutation coordinator 11.
- Command: `./gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel --console=plain`.
- Result: `BUILD SUCCESSFUL`; 17 Gradle tasks executed.
- Command: `git diff --check 136e0a9e27411086b673920ee4395e60f3224992..HEAD`.
- Result: passed with no whitespace errors.

### Self Review

- The mutation permit is acquired before token invalidation and durable mutation work. Refresh commit acquires the same permit before its account lock and persistence transaction, avoiding lock inversion and preventing an in-flight old token from writing after mutation begins.
- The permit is a fair `Semaphore`, not a thread-owned lock, so Room mutation coroutines may suspend/resume on another IO thread without illegal unlock. Nested synchronous committer gates are reentrant only within the same commit thread.
- Widget account reads and rendering are asynchronous; receiver and provider pending results finish in all completion paths. No production `runBlocking` was introduced or retained in the changed call surface.
- Service derives count, deadline, wake-lock timeout, and empty-batch filtering from the same typed snapshot, preserving existing refresh result semantics.

### Remaining Risks

- The mutation barrier is process-local and assumes the app's current single-process architecture. A future secondary Android process that mutates Room would need a database-level compare-and-commit token rather than this in-process permit.
- Three pre-existing Robolectric Widget `RemoteViews` rendering tests remain skipped because AndroidKeyStore is unavailable. Typed visibility and pending-result behavior are covered in unit tests, but final device-level rendering remains an instrumentation concern.
