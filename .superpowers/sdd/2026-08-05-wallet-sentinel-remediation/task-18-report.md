# Task 18 report: bounded foreground monitoring sessions and leases

## Status

GREEN. Foreground monitoring now uses a process-bound lease and a durable
session ledger. The service renews its lease while active, refuses automatic
restart after platform limits, persists `PLATFORM_LIMITED`/`PAUSED` state, and
leaves ordinary background refresh to WorkManager.

## Commits

- RED: `97c10ac` (`test: add red coverage for bounded monitoring leases`)
- Support: `49015c3` (`chore: add monitoring lease support seams`)
- GREEN: `2f6d0d4` (`feat: bound foreground monitoring with process leases`)
- Fix round 1: `3b74d59` (`fix: harden monitoring lease lifecycle and budget limits`)
- Fix round 1 cleanup: `0cf100e` (`chore: remove exact alarm diagnostic residue`)
- Fix round 2: `bcead57` (`fix: preserve platform limited monitoring state`)
- Fix round 3: `8b2101e`, `9453887` (injectable WorkManager fallback and immediate
  timeout stop/persist)

## Verification

Focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.service.*" --tests "com.balancesentinel.app.work.MonitoringHealthWorkerTest" --tests "com.balancesentinel.app.data.repository.RefreshSchedulerTest" --tests "com.balancesentinel.app.receiver.BootReceiverTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 62 tests, 0 failures, 0 errors, 0 skipped.

Additional compile gate: `compileDebugKotlin --no-parallel` passed.

Scoped independent review of `d0b75f1..9453887`: no Critical and no Important
findings. The review confirmed lease renewal, single-session/active-slot
transactions, rolling budget/pruning, API 35 timeout handling, non-sticky
lifecycle, process identity, user-only budget reset, WorkManager fallback,
boot no-restart behavior, and retired runtime exact-alarm/KeepAlive paths.

## Deferred

`PROJECT_INDEX.md` and historical audit/design material still mention the
retired `KeepAliveReceiver`/exact-alarm mechanism. Runtime code, manifest,
ProGuard, privacy, and Play Console permission text no longer contain those
paths.

Task 19 is unblocked. Task 19 was not started in this task.
