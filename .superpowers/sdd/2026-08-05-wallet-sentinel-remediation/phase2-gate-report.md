# Phase 2 JVM Gate Report

Date: 2026-08-09

## Scope

- Branch: `wallet-sentinel-hardening`
- Repair base: `4ebd803947a94c024fb14c4f21ec001a09fce009`
- RED: `dc77b04dbf1741836c2734093ef48cafc3f85e9d`
- GREEN: `6c48f01567b1060fc67d9b9422d6d882cce45778`
- Changed test source: `app/src/test/java/com/balancesentinel/app/receiver/KeepAliveReceiverTest.kt`
- `progress.md`, Task 13 history/pagination behavior, Task 14, and production source were not modified.

## Root Cause

`KeepAliveReceiverTest` exercises watchdog restart paths that call the real
`appendRoomEvent` boundary. Those paths open and write the global
`WalletDatabaseProvider`, but the fixture did not clear the provider after
each Robolectric test. Under full-suite load, the stale provider and queued
Room invalidation refresh survived beyond their owning test. The eventual
`Illegal connection pointer` was reported as an uncaught exception before an
unrelated `BalanceRefreshServiceTest` assertion.

The full failure was reduced to this serial prefix before the fix:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.DeepSeekAppTest --tests 'com.balancesentinel.app.data.*' --tests 'com.balancesentinel.app.receiver.*' --tests 'com.balancesentinel.app.service.*' --rerun-tasks --no-parallel --console=plain
```

Result: `BUILD FAILED` in 5m45s; 932 tests, 1 failure. The failure was the
same `BalanceRefreshServiceTest.service batch reports repository account count
for deadline sizing` canary with `Room Invalidation Tracker Refresh` and an
illegal SQLite connection pointer.

## RED

Commit `dc77b04` adds an ordered, behavior-level regression using the real
provider and DAO. It verifies that watchdog diagnostics do not leave a stale
or populated database fixture for the following test.

```powershell
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.receiver.KeepAliveReceiverTest --rerun-tasks --no-parallel --console=plain
```

Result: `BUILD FAILED` in 52s; 12 tests, 1 failure. The new test failed with
`AssertionError: database fixture should be usable`, exposing the stale Room
provider without a compile, fixture-construction, or environment error.

## GREEN

Commit `6c48f01` adds the minimal fixture cleanup: `@After` calls
`WalletDatabaseProvider.clearForTests()`.

Focused verification:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.receiver.KeepAliveReceiverTest --tests com.balancesentinel.app.service.BalanceRefreshServiceTest --rerun-tasks --no-parallel --console=plain
```

Result: `BUILD SUCCESSFUL` in 52s; 33 Gradle tasks executed.

Previously failing prefix after GREEN:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.balancesentinel.app.DeepSeekAppTest --tests 'com.balancesentinel.app.data.*' --tests 'com.balancesentinel.app.receiver.*' --tests 'com.balancesentinel.app.service.*' --rerun-tasks --no-parallel --console=plain
```

Result: `BUILD SUCCESSFUL` in 5m38s; 933 tests, 0 failures, 0 errors,
0 skipped.

## Exact Full Gate

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-parallel --console=plain
```

Result: `BUILD SUCCESSFUL` in 6m20s; 33 actionable tasks executed.

JUnit XML aggregate from 116 suite files:

- Tests: 1,223
- Failures: 0
- Errors: 0
- Skipped: 3
- Aggregate test time: 330.642 seconds

The rerun rebuilt debug production and unit-test Kotlin. It emitted existing
kapt, Room foreign-key index, and deprecation/static-analysis warnings; no new
warning points to `KeepAliveReceiverTest`. Because the committed repair is
test-only and the exact gate rebuilt the relevant compilation tasks, separate
production compile and lint reruns were not required.

## Integrity Checks

```powershell
git diff 4ebd803947a94c024fb14c4f21ec001a09fce009..HEAD --check
git status --short
```

Both commands produced no output before this report was added. The repair
range contains separate RED and GREEN commits and changes only the receiver
test fixture.

## Review

Independent task review found the lifecycle repair precise, real-behavior
based, and scoped to the owning fixture. Its blocking items were completion of
the exact full JVM gate and this report; both are now supplied. It recorded one
non-blocking diagnostic-quality minor: the RED converts the underlying stale
Room exception into an explicit `database fixture should be usable` assertion.

## Remaining Risk

The original fault was load-sensitive, but both the deterministic receiver RED
and the previously failing prefix now pass with 933 tests after adding the
regression, followed by the exact full 1,223-test gate. No unresolved
functional gate failure remains.
