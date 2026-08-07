# Task 10 Report

Commits:

- `f10370f` support: add legacy data migration seams and verifiers
- `2b40f76` test: define resumable legacy data migration behavior
- `b3addc6` feat: complete resumable legacy data migration
- `262b19e` fix: allow failed legacy migrations to resume
- `9eb98ab` test: add task 10 failure and mapping regression coverage (Fix Round 1 RED)
- `1f603ec` fix: harden legacy migration publication and recovery (Fix Round 1 GREEN)
- `cffa77e` test: add fix round 2 CAS and cleanup failure regressions (Fix Round 2 RED)
- `dcdbd70` fix: enforce migration CAS and all-or-nothing cleanup (Fix Round 2 GREEN)
- `482fe51` test: assert cleanup failure never publishes clean stage
- `6f11192` test: add round 3 scoped verifier regressions (Fix Round 3 RED)
- `17be705` fix: persist operation-scoped legacy verification manifest (Fix Round 3 GREEN)

Commands and actual outputs:

```text
.\gradlew.bat compileDebugKotlin --no-parallel --rerun-tasks
BUILD SUCCESSFUL in 27s

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --rerun-tasks --no-parallel
RED observed before GREEN: old implementation failed the existing-Room-baseline and same-millisecond duplicate behavior tests.

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 59s (8 focused tests)

.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 29s

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 53s
Executed 3 focused tests (2 in `LegacyDataMigrationTest`, 1 in `LegacyDataMigrationLargeDatasetTest`).

.\gradlew.bat compileDebugKotlin --no-parallel --rerun-tasks
BUILD SUCCESSFUL in 28s

git diff --check
(no output)

git status --porcelain=v1
(no output)

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 51s (3 focused tests)

.\gradlew.bat compileDebugKotlin --no-parallel --rerun-tasks
BUILD SUCCESSFUL in 27s

Fix Round 1 adds content SHA-256 operation identity, stable UUID mapping rejection, read-failure persistence, and cleanup preimage restoration. Fix Round 2 requires every metadata/operation cursor CAS result and makes failed cleanup preimage restoration explicit. Fix Round 3 persists a manifest containing operation mappings, legacy-record count/max-id baseline, summary keys, operation-specific usage IDs, and expected log IDs; verification uses those identities and validates every migrated field. Same-millisecond records are matched by source ordinal among rows above the baseline max ID rather than rejected.
```

Focused tests cover the production startup seam, durable stage declarations, empty-source idempotent no-op, and the 90,000/500 batch arithmetic. The 90,000-row behavior is represented by the batch arithmetic test; no full 90,000-row Room fixture is materialized in the checked-in tests. Production code processes records in 500-row transactions with a persisted cursor.

Remaining concerns: the focused suite now executes non-empty `LegacyDataMigration` with Room pollution and duplicate timestamps, but it does not run a full non-empty `DeepSeekApp.onCreate` against production SharedPreferences or materialize a 90,000-row Room fixture; the large-dataset test still validates the 500-batch contract arithmetically. Cleanup uses a captured preimage and explicit restore failure, but the four SharedPreferences commits are inherently sequential.

## Fix Round 4

Commits:

- `e8106ed` test: add task 10 round 4 migration regressions (RED)
- `2a7e817` fix: isolate and verify legacy data migrations (GREEN)
- `3a77228` test: use long migration count expectations
- `6862f92` test: verify wallet database v3 migration

Behavioral changes:

- Room v3 adds nullable `migration_operation_id` and `migration_source_ordinal` columns to migrated records, summaries, usage snapshots, and event logs. Event logs also retain `legacy_source_id`. Existing v2 rows migrate with all new identity columns `NULL`; unique operation/ordinal indexes isolate new rows without claiming or deleting old rows.
- Legacy data manifests are now version 2 and persist record/summary/usage/log baselines, exact expected counts, summary keys, operation-and-ordinal-specific usage IDs, log source IDs, and the stable account mapping. Both the old simple mapping JSON and the Round 3 structured manifest are upgraded with an exact operation CAS; the cursor is reset and scoped rows are rewritten. Manifest decode and upgrade failures now occur inside the persisted failure boundary.
- Records are committed in 500-row transactions with operation/source identity. Summaries, duplicate-timestamp usage snapshots, and logs also receive stable source ordinals. A pre-existing event-log primary key is preserved beside a newly generated scoped event-log row and cannot satisfy verification.
- Verification reads only rows owned by the expected operation, ordered by source ordinal in pages of 500. It checks scoped counts and all persisted source fields, including summary averages/close counters/generated time, every usage record's model and token fields, and all log type/availability/balance/diagnostic fields.

Commands and actual outputs:

```text
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --rerun-tasks --no-parallel
RED observed before production changes: 13 tests executed, 6 failed in 1m. Failures covered old mapping decode, incomplete summary/usage/log verification, duplicate-timestamp usage collapse, and reuse of a polluted log row.

.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 29s after the Room v3 and migration implementation.

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 54s (13 tests).

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.DeepSeekAppTest.startup data runner migrates scoped non-empty data and is idempotent" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 53s (1 test). The test uses DeepSeekApp's real startup runner sequence with a non-empty LegacyDataMigration, an in-memory Room database, old summary/log pollution, same-millisecond records and usage snapshots, and a second idempotent startup run.

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 1m 55s (1 test). The fixture materializes 90,000 RawRecord values and 90,000 Room rows. A SQLite trigger observes 180 committed cursor changes and verifies that both the persisted cursor and actual legacy row count increase by exactly 500 each time.

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.local.WalletDatabaseTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 56s (3 tests). This covers the exact runtime/exported v3 schema and executes MIGRATION_2_3 over populated v2-shaped tables, preserving old values with new identity columns left NULL.

.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationTest" --tests "com.balancesentinel.app.data.migration.LegacyDataMigrationLargeDatasetTest" --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 2m 4s (14 tests, including the real 90,000-row fixture).

.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
BUILD SUCCESSFUL in 31s.
```

Remaining concerns after Fix Round 4:

- The DeepSeekApp startup test exercises the real runner sequence and real LegacyDataMigration but injects an in-memory Room database and LegacyDataSource. It does not create production SharedPreferences JSON files through `LegacyStoresDataSource`.
- The v2-to-v3 compatibility test executes the production migration over populated v2-shaped tables rather than opening the complete exported v2 schema through `MigrationTestHelper`; the separate exact v3 runtime/export contract test passes.
- The full repository `testDebugUnitTest` suite was not run. Verification was limited to the Task 10 focused suites, the non-empty DeepSeekApp startup behavior, WalletDatabase v3 migration tests, and production Kotlin compilation.
- SharedPreferences cleanup still performs four sequential commits with captured-preimage restoration; that pre-existing platform limitation is unchanged.
