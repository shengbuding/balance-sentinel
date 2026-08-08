# Task 13 report: 90k historical streaming import/export

## Status

DONE_WITH_CONCERNS: implementation and all runnable JVM/compile gates are green. The required connected instrumentation gate could not execute because `adb devices -l` reported no connected devices.

Baseline: `c767683ab3f50c165ed71d9492ce71404f498c02`

RED commit: `70c07ee` (`test: define task 13 streaming history contract`)

## Implementation

- Added Android `JsonReader` / `JsonWriter` streaming with a 500-row export page and 500-item import chunk.
- Enforced 256 MiB files, 100,000 raw records, 50,000 summaries, 10,000 usage snapshots, 10,000 logs, 256 KiB UTF-8 fields, and JSON depth 32.
- Export reads Room with keyset/offset pages inside a consistent read transaction, validates into a temporary file, then publishes to SAF.
- Import parses and applies chunks inside one Room transaction. A malformed tail, count/size violation, or write failure rolls back all earlier chunks.
- Raw records, summaries, usage snapshots, and logs retain merge/idempotency behavior without constructing a full `DataExport` on the URI workflow.
- `DataManagementViewModel` owns history operations and always exits `ACTIVE`; cold-start counts remain DAO `COUNT` / `DISTINCT` queries.
- Added Room exported-schema assets to Android tests plus schema identity and v1-to-v4 migration instrumentation gates.
- Preserved the existing interactive `HistoryRepository.MAX_PAGE_SIZE = 200`; the exporter uses its own fixed DAO page size of 500.

## TDD evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.DataExporterTest" --tests "com.balancesentinel.app.data.repository.DataExporterImportTest" --tests "com.balancesentinel.app.data.repository.HistoryStreamingLargeDatasetTest" --rerun-tasks --no-parallel
```

RED output before production code:

```text
> Task :app:compileDebugUnitTestKotlin FAILED
Unresolved reference 'HistoryJsonLimits'.
Unresolved reference 'HistoryJsonReader'.
Unresolved reference 'HistoryJsonWriter'.
BUILD FAILED in 39s
```

Final GREEN output after implementation:

```text
DataExporterImportTest: tests=3 failures=0 errors=0
DataExporterTest: tests=39 failures=0 errors=0
HistoryStreamingLargeDatasetTest: tests=4 failures=0 errors=0
BUILD SUCCESSFUL in 59s
33 actionable tasks: 33 executed
```

The large-dataset test streams and re-imports 90,000 raw rows, verifies every export request is 500 rows, and verifies every import delivery is at most 500 rows.

## Additional JVM regressions

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.HistoryRepositoryTest" --tests "com.balancesentinel.app.data.repository.UsageRepositoryTest" --rerun-tasks --no-parallel
```

Output:

```text
BUILD SUCCESSFUL in 1m 52s
33 actionable tasks: 33 executed
```

The full `DataManagementViewModelTest` run was also executed. Its first combined adjacent run exposed the accidental 500-row change to the established interactive repository cap; production was corrected to keep that cap at 200 and use a dedicated 500-row export DAO query. No remaining JVM failure is known.

## Android compile and schema gate

Command:

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin --rerun-tasks --no-parallel
```

Output:

```text
BUILD SUCCESSFUL in 29s
31 actionable tasks: 31 executed
```

The compiler emitted only existing/deprecation warnings, including the legacy `MigrationTestHelper` constructor warning; there were no errors.

## Connected instrumentation blocker

Command:

```powershell
adb devices -l
```

Output:

```text
List of devices attached
```

Command (quoted property is required under this PowerShell invocation):

```powershell
.\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.balancesentinel.app.data.local.WalletDatabaseMigrationTest" --no-parallel
```

Output:

```text
> Task :app:connectedDebugAndroidTest FAILED
Execution failed for task ':app:connectedDebugAndroidTest'.
> com.android.builder.testing.api.DeviceException: No connected devices!
BUILD FAILED in 37s
71 actionable tasks: 37 executed, 34 up-to-date
```

This is an external device-availability blocker, not a compile or test assertion failure. No substitute instrumentation result is claimed.

## Final checks

Command:

```powershell
git diff --check
```

Output: exit code 0; only Git CRLF conversion warnings were printed.

## Concerns

- `WalletDatabaseMigrationTest` compiled but was not executed on-device because no emulator/device was connected.
- The legacy compatibility APIs `buildExport` and `importFromUri` still materialize `DataExport` for existing small-object callers/tests. The user-facing URI export/import path used for large history is fully streaming.
