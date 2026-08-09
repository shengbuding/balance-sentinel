# Task 17 report: resumable midnight maintenance and recovery reconciliation

## Status

GREEN. Midnight maintenance now runs through one unique WorkManager one-shot
chain. Delayed dates are processed in local-date order, each successful date is
checkpointed idempotently, a failed date remains the retry point, and every
successful completion re-enqueues either the next overdue date or the next local
midnight. Boot, package replacement, and time-zone broadcasts reconcile the same
unique work without requiring the Home screen to be opened.

Baseline: `e6c9df6b51304c0b4aad28f70a30aed0972bd478`

Support: `6947324` (`feat: add midnight maintenance support seams`)

RED: `155950d` (`test: define midnight maintenance red`)

GREEN: `8cc54cb` (`feat: complete resumable midnight maintenance`)

## Implementation

- Added `MaintenanceCheckpointStore` with a Room singleton adapter and an
  atomic “advance only when newer” DAO update. Duplicate and older completion
  writes are ignored, while the checkpoint retains the local `ZoneId` and
  success timestamp.
- Added `MidnightMaintenanceWorker` with injectable cleanup/checkpoint seams.
  It selects the first unfinished date through yesterday, runs one date-scoped
  cleanup, returns WorkManager retry on failure, advances the checkpoint only
  after a failure-free report, and re-enqueues the next overdue date or the
  next local midnight after success.
- Added `MidnightWorkScheduler` and `DefaultMidnightWorkRuntime`. Reconcile
  computes the next midnight with `LocalDate.plusDays(1).atStartOfDay(zone)`,
  so DST and timezone offset changes are handled as local calendar transitions,
  not fixed 24-hour durations. WorkManager uses one stable unique name and
  `ExistingWorkPolicy.REPLACE` so recovery reconciliation cannot create a
  parallel chain.
- Added `WorkReconcileReceiver` for `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED`, and `TIMEZONE_CHANGED`. The receiver only reconciles
  work; it does not invoke cleanup or start a service. `BootReceiver` retains
  its existing foreground-service/keepalive behavior and delegates the
  midnight reconcile. `DeepSeekApp` and `HomeViewModel` both route startup/home
  scheduling through the new scheduler, so opening Home is not required.
- Added `CleanupScheduler.runCleanupForDate(...)` so retries target exactly one
  date and do not reprocess later dates or rebuild full continuity on every
  overdue step.
- Removed the old `MidnightScheduler`, `MidnightReceiver`, their manifest Alarm
  declaration, and their obsolete alarm-based tests. No production reference to
  `MIDNIGHT_AGGREGATE` remains.

## RED evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.work.Midnight*" --tests "com.balancesentinel.app.receiver.WorkReconcileReceiverTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --rerun-tasks --no-parallel
```

Before the GREEN implementation, the command compiled successfully and ran 10
tests with 3 assertion failures (0 compile failures, 0 errors, 0 environment
failures):

- three-day overdue execution did not advance dates in order;
- a second-day failure did not preserve the first-day checkpoint/retry point;
- next-midnight calculation did not account for the New York spring DST
  transition.

## GREEN evidence

Required focused command from the plan:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.work.Midnight*" --tests "com.balancesentinel.app.receiver.WorkReconcileReceiverTest" --tests "com.balancesentinel.app.data.repository.CleanupSchedulerTest" --rerun-tasks --no-parallel
```

Post-GREEN/post-commit result: `BUILD SUCCESSFUL`; 12 tests completed, 0
failures, 0 errors, 0 skipped.

Additional compile gate:

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`.

Static gate:

```powershell
git diff --check HEAD^..HEAD
```

Result: clean. The manifest/action scan confirms the old midnight receiver and
`MIDNIGHT_AGGREGATE` declaration are absent; the new receiver owns the three
recovery actions.

## Scope notes

- Existing keepalive and foreground-service AlarmManager paths remain outside
  Task 17; only the old midnight Alarm chain was retired.
- The focused JVM tests use injectable runtime/checkpoint seams to avoid opening
  a second WorkManager database under Robolectric. Production scheduling uses
  `DefaultMidnightWorkRuntime` and WorkManager 2.10.1.
- Connected-device instrumentation was not run because this task only requires
  the JVM focus and Android-test compilation; no device was available in the
  workspace.
