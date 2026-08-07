# Task 9 Report: History, Usage, Summary, and Event-log Repositories

## Commits

- Support: `9ec94bf` (`feat(history): add repository compatibility seams`)
  - Added the domain/repository contracts and compatibility adapters for the
    legacy JSON stores.
  - Added `HistoryPage`, `HistoryCursor`, `HistoryRecord`, and
    `HistoryAggregate` without Room annotations.
- RED: `d4ca138` (`test(history): add Room repository red coverage`)
  - Added focused DAO and repository behavior tests before the Room
    implementations existed.
  - The first focused run failed at test compilation because the three Room
    repository implementations were intentionally absent.
- GREEN: `eee24b5` (`feat(history): implement Room repositories and aggregation`)
  - Completed the Room DAO queries and Room-backed repository implementations.

## Behavior delivered

- History keyset pagination uses `(recorded_at, id)` in descending order and
  clamps every requested page to 200 rows.
- History repository validates ISO-4217 currency codes, enforces account,
  currency, and half-open timestamp ranges, and provides COUNT and DISTINCT
  queries.
- History writes use fixed 500-row chunks. Summary writes use the same chunk
  bound and preserve the canonical `(date, account_id, currency)` key.
- The history aggregate is computed in SQLite from adjacent records (with
  indexed predecessor/successor lookups) and preserves the existing
  `RecordAggregator` formulas for open/close, consumption, top-ups, grants,
  average balance, sample count, and closing counters.
- Usage snapshots persist every usage record under the stable v1 UUID derived
  from account, capture time, and identity discriminator. Reads reconstruct
  the complete record list and use DAO keyset pages capped at 200.
- Event logs are written in 500-row chunks and returned newest-first with a
  caller limit.
- No production repository API exposes `getAllRecords()`; legacy adapters are
  compatibility-only readers for the pre-migration stores.

## Verification

Focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.local.history.HistoryDaoTest" --tests "com.balancesentinel.app.data.repository.HistoryRepositoryTest" --tests "com.balancesentinel.app.data.repository.UsageRepositoryTest" --tests "com.balancesentinel.app.data.repository.EventLogRepositoryTest" --rerun-tasks --no-parallel
```

Result: 8 tests completed, 0 failures, 0 errors, 0 skipped. The suite includes
the 90,000-row history case; it completes in approximately two minutes under
Robolectric.

Additional command:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`. `git diff --check` is clean.

## Remaining risks and follow-up

- Insights, cleanup, export, and migration consumers still use the legacy
  stores; Tasks 10-12 must install the Room repositories and remove those
  production legacy calls.
- Existing Room schema lint continues to report the three pre-existing
  foreign-key index warnings for event-log and monitoring columns.
- Robolectric's SQLite does not support window functions, so the aggregate
  query deliberately uses indexed correlated predecessor/successor lookups.
  This is semantically equivalent but should be checked on the target Android
  SQLite version during the later device/instrumentation gate.
