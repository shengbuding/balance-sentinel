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

## Fix Round 1

### Review findings addressed

The fresh scoped review identified three load-bearing gaps:

1. Recovery attempted to decode Task 3 legacy migration operations and one bad
   row could abort all later account recovery.
2. A process death after credential commit but before the stage update could
   leave PREPARED state without a safe resume path; rollback failure had no
   durable retry signal.
3. Multiple coordinator instances could interleave read/prepare/publish and a
   later rollback could overwrite an earlier successful credential write.

### RED

- RED commit: `72d9802` (`test: expose mutation recovery isolation and races`).
- Added behavior tests for legacy-operation filtering and bad-operation
  isolation, process death between credential write and stage update, deterministic
  coordinator serialization, startup recovery invocation, and durable rollback
  retry signaling.
- Before the fix, the new tests failed with a recovery JSON decode exception,
  missing PREPARED operation after simulated process death, and concurrent write
  interleaving.

### GREEN

- Fix commit: `d5c346c` (`fix: serialize and isolate account recovery`).
- Recovery now filters to account replace/delete operations and isolates each
  malformed/conflicting row. PREPARED rows are fingerprint-verified and resumed
  when their staged payload is present; failed rollback/cleanup leaves a
  `ROLLBACK_PENDING` PREPARED row for retry.
- A process-wide suspend `Mutex` covers save/delete/recover critical sections;
  it suspends callers and never blocks the main thread.
- Required focused command including `AccountLifecycleManagerTest`,
  `AccountMutationRecoveryTest`, and `DeepSeekAppTest` passed with 11/11,
  10/10, and 7/7 tests respectively. Debug Kotlin compilation and
  `git diff --check` passed, with no Room schema or identity diff.

## Fix Round 2

### Review finding addressed

The fresh re-review found that a `PREPARED + ROLLBACK_PENDING` operation could
be mistaken for an interrupted staged write. Recovery compared the current
credential payload only with the desired payload fingerprint, then retried the
old CAS publication using the stale expected revision. That could publish a
new credential payload after Room had rejected the publication, leaving Room
and credentials inconsistent.

### RED

- RED commit: `35b9e77` (`test: expose rollback recovery payload confusion`).
- The regression first exercises a Room CAS failure after desired credentials
  are staged and then injects a rollback write failure. After the failure is
  cleared, recovery must restore the old payload and leave the Room account
  unchanged while terminating the operation as failed. The pre-fix run failed
  at the post-recovery assertions; compilation and fixtures succeeded.

### GREEN

- GREEN commit: `305ee5c` (`fix: restore durable rollback before account publish`).
- The PREPARED manifest now carries the prior validated payload and a canonical
  fingerprint. Recovery verifies that snapshot, writes it back, reads it back,
  cleans the staged generation, and only then marks the operation `FAILED`.
  Any write/readback/cleanup failure leaves `PREPARED + ROLLBACK_PENDING`; it
  never enters the desired-payload publication path. `markStage` is not used
  for this branch, so rollback error state is preserved.
- This is a minimal device-consistency compensation for the current single-slot
  `CredentialStore`; full physical per-generation credential storage remains a
  later scope. The manifest snapshot contains credential material in the
  existing Room ledger and is therefore a retained payload-level security risk,
  with no logging or UI exposure.

### Verification

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.AccountLifecycleManagerTest" --tests "com.balancesentinel.app.data.repository.AccountMutationRecoveryTest" --tests "com.balancesentinel.app.DeepSeekAppTest" --rerun-tasks --no-parallel
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
git diff --check
```

Results: `AccountLifecycleManagerTest` 11/11, `AccountMutationRecoveryTest`
10/10, and `DeepSeekAppTest` 7/7 passed. Debug Kotlin compilation passed,
`git diff --check` was clean, and no Room schema or identity files changed.

## Fix Round 3

### Review finding addressed

The Round 2 re-review found that the immediate `rollbackBeforePublish` path
treated a successful `CredentialStore.write(oldPayload)` call as a completed
rollback without reading the value back. A store that silently persisted the
wrong or damaged payload could therefore leave Room on the old account state
while the operation was incorrectly terminated as `FAILED`.

### RED

- RED commit: `93c5de5` (`test: expose unverified account rollback writes`).
- The behavior test injects a Room CAS conflict, makes the first rollback write
  return normally while retaining a wrong payload, and asserts that the
  operation remains `PREPARED + ROLLBACK_PENDING`. Before the fix, the test
  compiled and ran but failed its stage assertion because the operation was
  already marked `FAILED` and excluded from recovery.

### GREEN

- GREEN commit: `c032884` (`fix: verify account rollback writes`).
- Immediate rollback and startup recovery now share one rollback write gate.
  The gate validates the durable snapshot fingerprint before writing, then
  reads through `CredentialStore.read()` and verifies generation, payload
  structure, and the exact fingerprint. A write exception, corrupt/missing
  readback, or mismatch leaves the existing `ROLLBACK_PENDING` signal intact.
  Recovery terminates the operation only after the same gate and staged
  generation cleanup both succeed.

### Verification

The required serial focused suite passed with `AccountLifecycleManagerTest`
11/11, `AccountMutationRecoveryTest` 11/11, and `DeepSeekAppTest` 7/7.
`compileDebugKotlin --rerun-tasks --no-parallel` passed, `git diff --check`
was clean, and no Room schema or identity files changed.
