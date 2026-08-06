# Task 3 Report

## Commits

- Support: `1117d4e` (`feat: add account migration support seams`)
- RED: `8dedf94` (`test: add task3 stable account identity red`)
- GREEN: `5e7866c` (`feat: migrate legacy accounts to stable Room identities`)
- GREEN seam: `2485596` (`feat: wire encrypted credential store into migration seam`)

## RED

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
```

Compilation and test fixture setup succeeded under Robolectric. The expected behavior failure occurred through the existing `ApiKeyManager` entry point: `AccountRepositoryTest.apiKeyChangeKeepsStableAccountUuidThroughExistingManagerEntryPoint` failed with `ComparisonFailure` because the old `saveAccount` recomputed the account ID from the replacement API key. No missing-class, compilation, or environment failure occurred.

## GREEN

The same focused command passed. Test result XML reports 3 tests, 0 failures, 0 errors, 0 skipped. Coverage includes deterministic UUID mapping and rerun deduplication with `legacyStorageId` retention, plus credential-rotation identity stability.

## Scope and risks

- Legacy JSON is read and retained; this task does not clear legacy preferences, migrate history, or publish global `CLEANED`.
- API keys remain outside Room; Room stores only a generation reference.
- Migration currently writes final account rows as `VERIFIED` inside the migration transaction. A future coordinator may split the persisted `PENDING`/verification transition around credential validation.
- `DeepSeekApp` exposes a construction seam but does not invoke the new migration during startup yet; startup wiring is intentionally deferred to the account mutation/recovery coordinator task.

## Self-review

Focused tests pass and `compileDebugKotlin` passed. `git diff --check` is clean. Remaining concern is the direct VERIFIED insert noted above; no Room schema or Task 2 DAO identity was changed.

## Fix Round 1

### Commits

- RED: `ee3d7b8` (`test: expose task3 migration ledger gaps`)
- Startup support seam: `4868cc8` (`refactor: add startup migration runner seam`)
- Startup RED: `65e8526` (`test: expose missing startup room migration`)
- GREEN: `ce48506` (`fix: harden resumable legacy account migration`)

### RED evidence

Focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
```

Production and test compilation succeeded. Six tests ran; three behavior tests failed as expected:

- `operationManifestExistsBeforeCredentialStaging`: no operation or generation manifest existed when the external credential write began.
- `migrationRequiresCredentialStore`: a null store was accepted and migration continued to Room writes.
- `verificationCrashLeavesAccountHiddenAndOperationWrittenStage`: the account was already VERIFIED/visible when the durable ledger had not safely reached verification.

Startup RED command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.DeepSeekAppTest.startup invokes resumable room account migration" --rerun-tasks --no-parallel
```

Compilation succeeded; the single test failed with an assertion because `DeepSeekApp.onCreate()` never invoked the injected Room migration runner. An earlier test-harness attempt used an unavailable Robolectric builder API and failed compilation; it was corrected before the RED commit, and the committed RED is the behavior-level assertion failure above.

### GREEN evidence

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.DeepSeekAppTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
git diff --check
```

Results:

- Focused migration/repository suite: 8 tests, 0 failures, 0 errors, 0 skipped.
- `DeepSeekAppTest`: 6 tests, 0 failures, 0 errors, 0 skipped.
- Debug Kotlin compilation: successful.
- `git diff --check`: clean.
- Room schema/export: unchanged; no schema file diff.

### Finding resolutions

1. Account UUIDs now use independent `UUID.randomUUID()` values. The full `legacyStorageId -> accountId -> credentialGeneration` mapping is serialized into the PREPARED operation before external writes and is decoded on recovery; no domain UUID is derived from an API key or legacy hash.
2. `DeepSeekApp.onCreate()` now launches the resumable migration through an application-lifetime `SupervisorJob + Dispatchers.IO` scope. There is no production `runBlocking`.
3. Room rows are inserted as `PENDING`. Account state, operation stage, and metadata stage transition atomically to VERIFIED only after credential readback and row verification; verified repository queries cannot expose ROOM_WRITTEN accounts.
4. `CredentialStore` is non-null. Migration requires ENCRYPTED_PREFERENCES readback whose payload exactly matches the source; Missing, corrupt, wrong-generation, or mismatched readback stops before Room account writes.
5. PREPARED operation targets and the complete staged generation manifest are durable before credential staging. The operation advances through CREDENTIALS_STAGED, ROOM_WRITTEN, and VERIFIED.
6. DISCOVERED, VALIDATED, CREDENTIALS_STAGED, ROOM_WRITTEN, and VERIFIED are observable durable stages. Crash/retry tests cover every stage, no duplicate rows, legacy JSON byte retention, valid staged credential readback, and visibility only after verification.
7. The tautological ID test was replaced by end-to-end ApiKeyManager-to-Room key rotation coverage, canonical lowercase UUID syntax, corrupt legacy JSON preservation, startup invocation, and operation ledger assertions.

### Remaining risks

- `CredentialStore` remains the existing payload-level API rather than a keyed multi-generation store. The operation manifest owns deterministic per-account generation references, while readback validation proves the staged encrypted payload as a whole. Later account mutation work may introduce physical per-generation credential slots.
- Migration intentionally retains legacy JSON, does not migrate history, and does not declare global CLEANED, per Task 3 scope.

## Fix Round 2

### Commits

- RED: `179832a` (`test: expose order independent migration mapping gaps`)
- GREEN: `e4d708a` (`fix: reuse legacy mappings across migration reshapes`)

### RED evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
```

Compilation succeeded. The suite ran 11 tests; the three new behavior tests failed with `SQLiteConstraintException` from the unique `accounts.legacy_storage_id` index:

- Multi-account API-key rotation changed the legacy JSON order and created a new operation/random mapping.
- Explicit legacy list reorder created a new operation/random mapping.
- Adding an account created a new operation whose old account received a new random mapping.

These failures came through the existing `ApiKeyManager` reader and real in-memory Room, not missing classes or fixtures.

### GREEN evidence

Commands:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.migration.LegacyAccountMigrationTest" --tests "com.balancesentinel.app.data.repository.AccountRepositoryTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
git diff --check
```

Results:

- Focused suite: 11 tests, 0 failures, 0 errors, 0 skipped.
- Debug Kotlin compilation: successful.
- `git diff --check`: clean.
- Room schema/export: unchanged.

### Resolution

- Operation identity now uses a canonical sorted set of trimmed legacy storage IDs, so API-key rotation and list reorder reuse the same operation/manifest.
- When creating a new operation, existing Room rows are indexed by `legacyStorageId`; their independent `accountId` and `activeCredentialGeneration` are reused before generating IDs for genuinely new legacy accounts.
- Recovery maps the persisted manifest by `legacyStorageId` into the current payload order before writing, preventing positional mismatches after reorder.
- Adding an account creates only the new mapping while retaining every prior mapping; all rows remain unique and VERIFIED.

### Remaining risk

The operation ID intentionally changes when the legacy account set changes (for example, adding an account), while the mapping reuse path preserves all existing account identities and generations. The existing payload-level CredentialStore limitation remains as documented in Round 1.
