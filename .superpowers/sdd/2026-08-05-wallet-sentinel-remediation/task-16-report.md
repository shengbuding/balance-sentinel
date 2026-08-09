# Task 16 report - WorkManager ordinary refresh and bounded retries

## Commits

- Support: `20761c5` - add WorkManager runtime/testing 2.10.1 and the injectable `WorkRuntime` seam.
- Initial RED: `19348e5` - behavior tests were added first; this run was a compile RED before the test seam was complete.
- Executable RED: `9b6dd0b` - scheduler/runtime test seam plus inert planner/worker shell. Focused run compiled and failed behavior assertions only.
- GREEN: `ea31c67` - WorkManager-backed scheduler, CoroutineWorker, bounded retry planner, widget migration, and startup reconcile.
- Fix-round RED: `615e8c6` - added behavior coverage for terminal retry cancellation, disabling background refresh, and the distinct background trigger; focused run compiled and failed only those assertions.
- Fix-round GREEN: `4eae8b0` - tagged one-shot retries for bulk cancellation, cancelled stale account retries on terminal results, cancelled all retry-tagged work when background refresh is disabled, and routed WorkManager refreshes through `RefreshTrigger.BACKGROUND`.

## Verification

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.work.Refresh*" --rerun-tasks --no-parallel
```

Result: BUILD SUCCESSFUL; 10 tests completed, 0 failed, 0 skipped for the initial GREEN baseline.

The executable RED run at `9b6dd0b` completed 9 tests with 4 expected behavior failures: two retry-planner assertions and two worker assertions. There were no compile failures or WorkManager test-database/environment failures in that run.

The fix-round RED run at `615e8c6` completed 12 tests with 3 expected behavior failures: two worker assertions (terminal cancellation and `BACKGROUND` trigger) and one scheduler assertion (bulk retry cancellation on disablement). The fix-round GREEN run at `4eae8b0` completed 12 tests with 0 failures and 0 skips.

GREEN covers:

- stable unique periodic reconciliation with connected-network constraint and UPDATE policy;
- the 15-minute WorkManager floor for background cadence;
- stable per-account one-shot retry names, bounded exponential delay plus jitter, and finite attempts;
- process-rebuild reconciliation without duplicate work;
- cancellation of the legacy Widget Alarm PendingIntent during reconcile;
- one shared Widget periodic work chain while manual widget clicks remain immediate;
- CoroutineWorker execution through the unified RefreshGateway;
- terminal `Committed`, permanent, stale, and skipped outcomes clear any queued account retry while retryable failures continue through the bounded planner;
- all one-shot retry requests carry a shared tag, allowing disablement reconciliation to cancel the complete retry queue;
- periodic and one-shot WorkManager refreshes use `RefreshTrigger.BACKGROUND`, which records as the Room `BACKGROUND` run source;
- startup reconciliation after settings reach a Ready snapshot.

Generic worker exceptions retain the existing WorkManager retry/failure policy; this fix round does not change that policy because periodic WorkManager execution remains a rescheduled chain.

Static gate: `git diff --check` passed before GREEN. Known risk: the focused tests use an injectable WorkRuntime recording seam because WorkManager TestInitHelper opens a conflicting SQLite database under this repository's Robolectric/Room setup; production DefaultWorkRuntime uses real WorkManager 2.10.1.

Final GREEN HEAD before this report commit: `4eae8b0e1f6615b8d0c17b7d292286ebde576f85`.
