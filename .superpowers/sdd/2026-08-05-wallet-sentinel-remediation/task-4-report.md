# Task 4 Report

## Commits

- Support: `72045d9` (`feat: add account mutation coordination seams`)
- RED: `a00b8d9` (`test: expose account mutation recovery gaps`)
- GREEN: `dab21ce` (`feat: add resumable room account mutations`)

## RED evidence

Command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AccountMutationRecoveryTest" --rerun-tasks --no-parallel
```

Compilation and fixture setup succeeded. Four behavior tests ran; three failed as
expected through the legacy-only coordinator scaffold:

- `replacement persists PREPARED operation before credential staging` found no
  durable operation before the injected credential write.
- `replacement publishes complete account while preserving stable UUID` observed
  no Room publication after the credential write.
- `delete hides and cascades before cleanup failure and remains recoverable`
  observed cleanup failure aborting before Room deletion.

The corruption test passed and confirmed the RED fixture was exercising a real
behavior gap rather than a missing-class or environment failure.

## GREEN evidence

Required focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --tests "com.balancesentinel.app.data.repository.AccountMutationRecoveryTest" --rerun-tasks --no-parallel
```

Results: `AccountLifecycleManagerTest` 11/11 and
`AccountMutationRecoveryTest` 5/5 passed, with 0 failures, errors, or skips.

Additional startup regression command included `DeepSeekAppTest` 6/6 passed.

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
git diff --check
```

Debug Kotlin compilation succeeded and the diff check was clean. No Room schema
or identity files changed.

## Protocol implemented

- `RoomAccountMutationCoordinator` persists a PREPARED operation with targets,
  baseline revision, deterministic staged generation references, and a payload
  fingerprint before writing credentials.
- Credential writes are read back and validated for encrypted generation and
  exact payload equality. Failures restore the old payload, clean the staged
  reference, and mark the operation failed without publishing Room rows.
- Room publication uses the existing `MutationPublisher` transaction and CAS;
  account UUIDs remain stable across key rotation, and verified account Flow
  visibility changes only after the transaction succeeds.
- Deletion publishes the Room cascade before external cleanup. Cleanup errors
  leave the operation PUBLISHED for idempotent retry and never resurrect the
  deleted account.
- Startup invokes `RoomAccountMutationRecovery`; PREPARED operations are
  abandoned safely, staged operations are fingerprint-verified and resumed, and
  PUBLISHED operations retry cleanup.
- Existing synchronous lifecycle calls remain behind an explicit legacy adapter;
  new suspend callers use the Room coordinator without production `runBlocking`.

## Remaining risks

- `CredentialStore` is still the Task 3 payload-level API. Generation references
  and cleanup seams are durable, but physical per-generation encrypted slots are
  deferred as the plan requires.
- The synchronous legacy adapter still performs its historical external-store
  cleanup. UI, Service, and Widget call-site migration is intentionally deferred
  to Tasks 5 and 6.

## Self-review

The implementation is limited to coordinator/recovery/lifecycle seams and
tests; Room v1 schema, persistent enums, and Task 2 DAO contracts are unchanged.
All persistence-facing code is suspend/IO based, external cleanup is never
pretended to be part of Room rollback, and cleanup retries are represented by a
durable PUBLISHED operation.
