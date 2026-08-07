# Task 8 Report

## Scope and Commits

- Task 8 RED baseline: `15c8d61` (`test(import): add bounded parser and atomic coordinator red coverage`). It was not recreated or amended.
- GREEN: `692fdc7` (`fix(import): complete task 8 bounded atomic import`).
- Scope is limited to bounded configuration parsing, canonical import planning, stale-plan rejection, revision/fingerprint validation, Room publication/rollback, and Data Management preview/apply behavior.

## Implementation

- `BoundedInput` enforces the 4 MiB payload ceiling and decodes UTF-8 only after bounded byte collection, preserving code points split across read chunks.
- `ConfigImportParser` validates account count (256), ordinary strings (16 KiB), scripts (256 KiB), and JSON depth (32) before deserialization. The exact boundary and `+1` cases are covered.
- `ImportFingerprint` produces a canonical SHA-256 over sorted account data, imported settings, and the baseline revision. `ImportCoordinator.preview` samples the revision once, so the plan cannot contain a self-inconsistent baseline/fingerprint.
- `BackupImportPlanner` records `baselineRevision` and fingerprint in every Room-backed plan. `applyAsync` rejects stale account/settings/revision state before writes; publication and compensating rollback run through the Room repository mutex. Failed publication restores the Room snapshot and local revision, while account rollback failures remain suppressed on the original error.
- The synchronous `apply` compatibility path remains available only for planners without a Room repository. Room-backed production imports must use `applyAsync`, preserving the atomic publication seam.
- `DataManagementViewModel` keeps preview state, replace confirmation, script enablement, and canonical origin grants coherent. The test fixture uses an in-memory settings repository and restores the default provider after each test so Room observer jobs cannot leak across Robolectric sandboxes.

## GREEN Evidence

Focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.repository.ConfigImportParserTest" --tests "com.balancesentinel.app.data.repository.BackupImportPlannerTest" --tests "com.balancesentinel.app.data.repository.ImportCoordinatorTest" --tests "com.balancesentinel.app.ui.viewmodel.DataManagementViewModelTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 50 tests completed, 0 failures, 0 errors, 0 skipped.

The focused run includes the two new regression assertions for revision single-sampling and UTF-8 code-point preservation. The previously reported stale synchronous-entrypoint assertions and ViewModel reset/origin failures are green under the compatibility/test-lifecycle adaptations.

Production compile:

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 17 actionable tasks executed. Existing Room foreign-key index warnings and unrelated Kotlin/deprecation warnings remain.

Whitespace check:

```powershell
git diff --check 6a763ad7f27475a953d099814b19cabd2f8330dd..HEAD
```

Result: passed with no whitespace errors.

## Remaining Risks

- Account credentials are persisted in the legacy credential store while settings are published in Room, so cross-store atomicity is compensating rollback rather than one physical transaction. Rollback failures are retained as suppressed exceptions.
- Historical unbounded reads outside configuration import and repository-wide legacy tests are outside Task 8 scope and remain for their designated later tasks.
- Existing Room child-FK index and unrelated compiler/deprecation warnings are unchanged.
