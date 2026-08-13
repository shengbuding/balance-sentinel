# Wallet account migration recovery

Date: 2026-08-13
Status: DONE

## Symptom

Build 682 (`v1.4.2-493-g7c1a12c`, versionCode 682) repeatedly recorded:

`IllegalStateException: No stable account mapping for legacy id 1ac8657256cd4df2`

The affected production device was a OnePlus PJZ110 running Android 16. The
exception was non-fatal, but the startup migration stopped and the UI could
show the account-corruption message.

## Root cause

The legacy account JSON was committed with a normalized account ID before all
RawRecord, DailySummary, Usage, and Widget stores had been rewritten. If the
process stopped in that interval, the next process no longer had the temporary
ID map. `LegacyDataMigration` then saw valid history referring to the old
8/16-character hexadecimal ID without a Room mapping and failed closed with
`No stable account mapping`.

This was an interrupted multi-store migration, not an API-key or encrypted
credential corruption. A second compatibility issue was that old account
payloads may use their legacy storage ID while Room uses a stable UUID; the UI
repository must reconcile both IDs and keep the Room revision at zero for a
fresh migration.

## Fix

- Run account-mutation recovery before legacy data migration.
- Migrate Usage IDs together with RawRecord, DailySummary, and Widget IDs.
- Canonicalize valid hexadecimal legacy IDs case-insensitively.
- Create a deterministic hidden `PENDING` orphan Room account for a valid
  orphan ID, with `legacy-orphan:<id>` generation.
- Reuse only that exact orphan marker through a compare-and-set hydration;
  conflicting mappings fail closed.
- Repair/re-run v1/v2/FAILED legacy-data manifests without discarding progress
  fields.
- Normalize migrated credential revisions to zero, retain the legacy JSON
  source, and preserve hidden orphans during configuration replacement.
- Require credentials only from VERIFIED accounts for config recovery.

## Reproduction and upgrade evidence

The isolated `emulator-5556` (`Wallet_Sentinel_API_35`, API 35) was used; no
physical device was touched.

1. A same-signature 682 APK was installed.
2. An instrumentation fixture wrote a real
   `EncryptedSharedPreferences("deepseek_secure_prefs")` account plus one
   RawRecord, DailySummary, UsageSnapshot/UsageRecord, and Widget cache entry
   for `1ac8657256cd4df2`.
3. Restarting 682 reproduced the original exception in logcat:
   `No stable account mapping for legacy id 1ac8657256cd4df2`.
4. Before installing 683, logcat was cleared. The 683 APK was installed with
   `adb install -r` and verified as versionCode 683. The package replacement
   receiver only schedules work, so the app was then launched once to execute
   startup migration.
5. The first 683 verifier passed:
   - normal account was `VERIFIED`; encrypted credentials were `Valid`;
   - UI repository returned the stable UUID while accepting the legacy payload
     ID;
   - exactly one hidden `PENDING` orphan existed;
   - one balance record, daily summary, usage snapshot, and usage record were
     retained and linked to the orphan;
   - the Widget cache entry remained present;
   - no new `No stable account mapping` was logged.
6. After force-stopping and launching 683 again, the same verifier passed with
   no duplicate orphan and unchanged migration counts. A UI hierarchy dump did
   not contain the `account_data_corrupt` message in either locale.

The transient `HomeViewModel: loadCachedBalances: no accounts` message is an
allowed startup timing observation: the Room account migration runs on an IO
scope and can briefly expose an empty VERIFIED flow. It did not correspond to
`AccountLoadState.Corrupt` in the final verifier or UI dump.

## Regression tests

- Full `:app:testDebugUnitTest --rerun-tasks --no-parallel`: passed.
- Added focused coverage in `DeepSeekAppTest`, `LegacyAccountMigrationTest`,
  `LegacyDataMigrationTest`, and `ConfigImportCoordinatorTest`.
- `git diff --check`: passed before final cleanup.
- Debug APK 683 assembled successfully and was installed/verified on the
  isolated emulator.

## Known limitations

- The connected-device upgrade proof uses an isolated API-35 emulator rather
  than the reported Android 16 handset.
- Existing Room foreign-key-index and deprecation warnings remain non-blocking.
- The three previously recorded review issues were intentionally left as
  record-only per user direction.

## Final status

DONE. The fix is committed on `master`; no push was performed.

## Final follow-up (2026-08-14)

The remaining account-corruption symptom was traced to completed legacy account
migration metadata drift: Room kept a verified account at revision `0` while
the encrypted credential payload retained the old non-zero revision. Repairing
the payload alone was insufficient because the existing Room observer did not
re-read it. Build 685 therefore performs the repair only for rows still owned
by the legacy migration generation, then executes a revision-preserving
`accounts.updated_at` update so the observable Room table emits again.

Build 685 also permits a credential-free configuration export while account
reconciliation is corrupt. It reconstructs verified accounts from Room
metadata, omits credentials/scripts/grants, and still blocks token-inclusive
export. Empty-account exports retain global settings. Debug reports include
only non-secret account consistency metadata.

Verification on the retained-data emulator used `versionCode=685`,
`v1.4.2-496-g0a43e2e`: the home screen recovered one account, historical Room
rows remained present, no new migration/account-corruption exception appeared,
and a real UI configuration export produced `credentialsIncluded=false` with
an empty API key and no extra credentials. Full unit-test/lint/build gates
recorded 1396 tests with 0 failures and 0 errors. The reported physical OnePlus
has not been verified with build 685; its old attachments identify build 682.
