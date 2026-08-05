# Task 2 Report: Room v1 Schema and Atomic Publication

## Commit Boundaries

- Existing Room dependency support: `21aa686`
- Schema/API support: `318aebb7ca468e250127254ee4a0b4e739284da1`
- RED tests: `445eb2f792394884707c1c5fc7550194474bcf53`
- GREEN implementation and this report: `445eb2f..HEAD`

The schema/API support commit is not GREEN. It defines the complete 19-table v1
schema and DAO/publication surface, exports the Room schema, and compiles, while
`MutationPublisher.publish` still throws the explicit not-implemented exception.

## Schema and API Support

- Added `WalletDatabase` version 1 with exactly 19 entities and schema export
  enabled.
- Added the required suspend/Flow DAO surface, exhaustive stable enum and
  provider converters, typed publication DTOs, conflict type, and internal
  transaction observer.
- Kept `refresh_account_results.account_id` as retained historical identity
  without an account FK. Account-owned state uses cascade ownership.
- Kept credential material outside Room; publication persists only active
  credential generation references.
- Did not wire Room into Application, Repository, UI, or Service code.

Exported schema:

`app/schemas/com.balancesentinel.app.data.local.WalletDatabase/1.json`

Identity hash: `eb8fe9271b06473c65e36c9120a43b44`.

Support compile command:

```powershell
.\gradlew.bat compileDebugKotlin compileDebugUnitTestKotlin --rerun-tasks --no-parallel
```

Result: passed.

## RED

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.local.WalletDatabaseTest" --tests "com.balancesentinel.app.data.local.DatabaseConvertersTest" --tests "com.balancesentinel.app.data.local.account.AccountDaoTest" --tests "com.balancesentinel.app.data.local.publication.MutationPublisherTest" --tests "com.balancesentinel.app.data.local.monitoring.MonitoringSessionDaoTest" --rerun-tasks --no-parallel
```

Result: failed as expected, with 19 tests run, 13 passing, and 6 failures. Every
failure was a `MutationPublisherTest` failure caused only by:

```text
UnsupportedOperationException("Room v1 publication is not implemented")
```

The RED suite compiled and all schema, converter, DAO, FK/unique, monitoring,
history import, duplicate usage model, and raw event-log contract tests passed.

## GREEN

`MutationPublisher` now performs one `RoomDatabase.withTransaction` in the
required order:

1. Verify the operation exists, is `VERIFIED`, and owns the input baseline.
2. Apply typed Create/Update/Delete account mutations with revision CAS.
3. Apply each selected settings-table replacement independently.
4. Increment metadata revision, optionally with generation/stage CAS.
5. Mark the operation `PUBLISHED` and return the new revision.

Zero-row account, metadata, or operation writes raise
`PublicationConflictException`. SQLite write conflicts are mapped to the same
conflict boundary. Observer callbacks remain inside the transaction after each
durable step.

Focused test command: same as RED.

Result: passed, 19 tests total. This includes real in-memory Room rollback checks
for `AFTER_ACCOUNT_ROWS`, `AFTER_SETTINGS_ROWS`, `AFTER_METADATA`, and
`AFTER_OPERATION_PUBLISHED`.

Final compile command:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: passed.

## Self Review

- Confirmed the committed JSON and runtime PRAGMAs freeze all 19 tables,
  columns, defaults, PK order, indexes, unique constraints, and FKs.
- Confirmed every stable enum/provider literal round trips and every unknown raw
  literal fails through a real DAO reader.
- Confirmed account revisions only increase through matching CAS predicates and
  stale account/metadata publications roll back earlier writes.
- Confirmed all five settings write arms are independently selected and app
  settings singleton identity/time are publisher-owned.
- Confirmed no test or production builder uses `allowMainThreadQueries()`.
- Confirmed no external credential rollback is claimed.
- Ran `git diff --check`; no whitespace errors.

## Remaining Concerns

- Room reports three child-index warnings for `event_logs.account_id`,
  `event_logs.refresh_run_id`, and
  `monitoring_state.current_monitoring_session_id`. The canonical v1 schema does
  not define those indexes, so adding them here would change the frozen schema
  identity.
- Verification used the five focused Task 2 test classes and the required Debug
  compile, not the repository-wide test suite.
