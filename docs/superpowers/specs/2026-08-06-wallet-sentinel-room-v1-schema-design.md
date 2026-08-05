# Wallet Sentinel Room v1 Canonical Schema Design

Status: approved design input. This document freezes the proposed v1 schema for
Task 2 implementation. It covers the Task 2 brief, the design, later Tasks 3
through 19, and legacy model preservation needed by Task 10. It does not change
production behavior.

## Conventions

- Every UUID is a canonical lowercase UUID `String` stored as SQLite `TEXT`.
  Database primary keys are stable domain identifiers, never API-key-derived
  identifiers or display order.
- All timestamps are epoch milliseconds in `INTEGER` columns and Kotlin `Long`.
  Dates are ISO-8601 local dates (`yyyy-MM-dd`) in `TEXT`; Zone IDs are IANA IDs
  in `TEXT`.
- Kotlin `Boolean` is stored as SQLite `INTEGER` (`0` or `1`). Kotlin enums are
  stored as their stable uppercase string literals in `TEXT`, through
  `DatabaseConverters`; ordinals are forbidden.
- Account-owned columns called `account_id` always have a foreign key to
  `accounts(id)` with `ON DELETE CASCADE`, including nullable account IDs in
  `event_logs`. A NULL event-log account ID denotes a global event.
- The domain/repository boundary validates UUIDs, ISO currency, enum values,
  finite amounts, non-negative counters, JSON structure, and cadence ranges
  before DAO writes. Room's declarative v1 schema supplies PK, UNIQUE, indexes,
  and FKs. No SQL CHECK or trigger is introduced: Room does not model CHECK
  constraints in its exported entity schema, and duplicating validation in
  callbacks would make exported v1 incomplete.
- JSON is only used for opaque, non-queryable compound payloads. It is always
  non-null `TEXT`, canonicalized by the owner before persistence. No credential
  payload is stored in Room.
- Every newly persisted calculable amount, balance, and alert threshold uses
  Kotlin `Double` with SQLite `REAL`. Legacy `Float` values convert only in the
  legacy mapper. Event-log amount fields are the explicit non-aggregated
  historical-text exception described below.
- All tables and columns are snake_case. `INTEGER PRIMARY KEY` values marked
  generated use Room `autoGenerate = true`; their Kotlin value is `Long`.

## Stable enum literals

| Kotlin enum | Persisted literals |
| --- | --- |
| `AccountState` | `PENDING`, `VERIFIED` |
| `MutationOperationType` | `ACCOUNT_REPLACE`, `ACCOUNT_DELETE`, `CONFIG_IMPORT`, `LEGACY_ACCOUNT_MIGRATION`, `LEGACY_DATA_MIGRATION`, `HISTORY_DATA_IMPORT` |
| `MutationStage` | `PREPARED`, `CREDENTIALS_STAGED`, `ROOM_WRITTEN`, `VERIFIED`, `PUBLISHED`, `ACTIVE`, `CLEANED`, `COMPLETED`, `FAILED` |
| `BalanceRecordSource` | `REFRESH`, `IMPORT`, `LEGACY_MIGRATION` |
| `RefreshRunSource` | `MANUAL`, `BACKGROUND`, `FOREGROUND`, `WIDGET` |
| `RefreshRunState` | `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `CANCELLED`, `INTERRUPTED` |
| `RefreshAccountResultState` | `RUNNING`, `SUCCEEDED`, `AUTHENTICATION_FAILED`, `NETWORK_FAILED`, `RATE_LIMITED`, `RESPONSE_INVALID`, `SCRIPT_POLICY_DENIED`, `SCRIPT_TIMEOUT`, `ACCOUNT_STALE`, `PERSISTENCE_FAILED`, `CANCELLED`, `INTERRUPTED`, `SKIPPED` |
| `RefreshErrorCategory` | `AUTHENTICATION`, `NETWORK`, `RATE_LIMIT`, `RESPONSE`, `SCRIPT_POLICY`, `SCRIPT_TIMEOUT`, `ACCOUNT_STALE`, `PERSISTENCE`, `CANCELLED`, `INTERRUPTED`, `UNKNOWN` |
| `EventLogType` | `MANUAL`, `AUTO`, `SCHEDULE`, `MISSED`, `SERVICE_DIED`, `SERVICE_START`, `WATCHDOG` |
| `DownloadState` | `QUEUED`, `RUNNING`, `CANCELLING`, `CANCELLED`, `FAILED`, `COMPLETED` |
| `MonitoringObservedState` | `STOPPED`, `STARTING`, `RUNNING`, `ABNORMAL`, `PLATFORM_LIMITED`, `PAUSED` |
| `MonitoringSessionEndReason` | `USER_STOPPED`, `SERVICE_DESTROYED`, `PLATFORM_TIMEOUT`, `PROCESS_RECOVERY`, `PLATFORM_LIMITED`, `PAUSED` |
| `LegacyMigrationStage` | `NONE`, `DISCOVERED`, `VALIDATED`, `CREDENTIALS_STAGED`, `ROOM_WRITTEN`, `VERIFIED`, `ACTIVE`, `CLEANED`, `FAILED` |

`provider_type` retains the existing stable `ProviderType.id` literals:
`openai`, `anthropic`, `gemini`, `mistral`, `cohere`, `deepseek`, `qwen`,
`wenxin`, `zhipu`, `moonshot`, `doubao`, `baichuan`, `model_ark`, `custom`.

## Canonical tables

### `accounts`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `display_order` | `INTEGER` / `Int` | no | none |
| `label` | `TEXT` / `String` | no | none |
| `provider_type` | `TEXT` / `ProviderType` | no | none |
| `provider_config_json` | `TEXT` / `String` | no | `{}` |
| `active_credential_generation` | `TEXT` / `String` | no | none |
| `revision` | `INTEGER` / `Long` | no | `0` |
| `state` | `TEXT` / `AccountState` | no | `PENDING` |
| `legacy_storage_id` | `TEXT` / `String?` | yes | `NULL` |
| `created_at` | `INTEGER` / `Long` | no | none |
| `updated_at` | `INTEGER` / `Long` | no | none |

PK: `id`. Indexes: `display_order` and unique `legacy_storage_id` (SQLite allows
multiple NULLs). No FK. `provider_config_json` contains only the current
non-sensitive `extraSettings`, `usageScript`, `usageScriptEnabled`, and
`authorizedScriptOrigins`; API keys and `extraCredentials` remain in
`CredentialStore`. `PENDING` is written during migration before verification;
only `VERIFIED` accounts are exposed by the Room account repository. Display
queries order by `(display_order, id)`; display order is intentionally not unique
so a batch reorder or swap cannot fail on an intermediate duplicate. The
repository publishes a dense final ordering in the enclosing transaction.

### `mutation_operations`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `operation_type` | `TEXT` / `MutationOperationType` | no | none |
| `stage` | `TEXT` / `MutationStage` | no | `PREPARED` |
| `targets_json` | `TEXT` / `String` | no | `[]` |
| `staged_generation_manifest_json` | `TEXT` / `String` | no | `[]` |
| `manifest_version` | `INTEGER` / `Int` | no | `1` |
| `batch_cursor` | `INTEGER` / `Long` | no | `0` |
| `baseline_revision` | `INTEGER` / `Long` | no | none |
| `error_code` | `TEXT` / `String?` | yes | `NULL` |
| `error_message` | `TEXT` / `String?` | yes | `NULL` |
| `created_at` | `INTEGER` / `Long` | no | none |
| `updated_at` | `INTEGER` / `Long` | no | none |
| `published_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `completed_at` | `INTEGER` / `Long?` | yes | `NULL` |

PK: `id`. Indexes: `(stage, updated_at)` and `(operation_type, stage)`.
There is no account FK: targets and generation manifests can contain multiple
accounts and must survive account deletion for recovery/audit. Error strings are
stable, redacted diagnostics only; no API key, credential JSON, raw response, or
backup content may be stored.

`HISTORY_DATA_IMPORT` is the sole Task 13 operation type for a streaming history
import. Its `manifest_version` identifies the canonical target/record manifest
shape and its `batch_cursor` is the recoverable committed-batch cursor. It uses
the same `PREPARED -> ROOM_WRITTEN -> VERIFIED -> ACTIVE -> CLEANED` recovery
stages as legacy data migration; credentials are not present in its manifest.

### `app_metadata`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Int` | no | `0` |
| `local_revision` | `INTEGER` / `Long` | no | `0` |
| `active_data_generation` | `TEXT` / `String` | no | `LEGACY` |
| `legacy_migration_stage` | `TEXT` / `LegacyMigrationStage` | no | `NONE` |
| `updated_at` | `INTEGER` / `Long` | no | none |

PK: `id`; the DAO owns the singleton row `id = 0` and creates it exactly once.
No FK or secondary index. `active_data_generation` is an opaque generation
identifier (initially `LEGACY`), not a credential generation. The publish DAO
increments `local_revision` with a compare-and-set update, never assigns an
imported or legacy revision.

### `app_settings`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Int` | no | `0` |
| `background_refresh_interval_seconds` | `INTEGER` / `Int?` | yes | `900` |
| `foreground_monitoring_interval_seconds` | `INTEGER` / `Int` | no | `30` |
| `alert_enabled` | `INTEGER` / `Boolean` | no | `0` |
| `alert_threshold` | `REAL` / `Double` | no | `0.0` |
| `change_alert_enabled` | `INTEGER` / `Boolean` | no | `0` |
| `change_alert_threshold` | `REAL` / `Double` | no | `0.0` |
| `change_alert_period_minutes` | `INTEGER` / `Int` | no | `0` |
| `log_max_entries` | `INTEGER` / `Int` | no | `100` |
| `snooze_duration_minutes` | `INTEGER` / `Int` | no | `60` |
| `show_total_balance_in_notification` | `INTEGER` / `Boolean` | no | `1` |
| `updated_at` | `INTEGER` / `Long` | no | none |

PK: `id`; DAO-owned singleton `id = 0`. No FK or secondary index.
`NULL` background cadence means disabled. Repository validation requires a
non-null background cadence to be at least 900 seconds; Task 7 maps old values
under 900 to foreground cadence and stores 900 as the background cadence.
Language, onboarding, permission history, update preferences, and individual
widget layouts remain device preferences and are deliberately absent.

### Account settings and runtime tables

`account_alert_settings`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `account_id` | `TEXT` / `String` | no | none |
| `currency` | `TEXT` / `String` | no | none |
| `balance_alert_enabled` | `INTEGER` / `Boolean` | no | `0` |
| `change_alert_enabled` | `INTEGER` / `Boolean` | no | `0` |

PK: `(account_id, currency)`. FK: `account_id -> accounts(id) ON DELETE
CASCADE`. No additional index.

`notification_wallet_selections`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `account_id` | `TEXT` / `String` | no | none |
| `currency` | `TEXT` / `String` | no | none |
| `display_order` | `INTEGER` / `Int` | no | none |

PK: `(account_id, currency)`. Unique index: `display_order`. FK:
`account_id -> accounts(id) ON DELETE CASCADE`. The global total entry is held
by `app_settings.show_total_balance_in_notification`; it is never represented as
a fake account row. The settings repository assigns dense non-negative display
orders during a replacement write.

`alert_runtime_state`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `account_id` | `TEXT` / `String` | no | none |
| `currency` | `TEXT` / `String` | no | none |
| `last_alerted_balance` | `REAL` / `Double?` | yes | `NULL` |
| `anchor_balance` | `REAL` / `Double?` | yes | `NULL` |
| `anchor_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `last_change_alerted_balance` | `REAL` / `Double?` | yes | `NULL` |
| `last_change_alerted_at` | `INTEGER` / `Long?` | yes | `NULL` |

PK: `(account_id, currency)`. FK: `account_id -> accounts(id) ON DELETE
CASCADE`. No extra index. Legacy `-1` sentinels migrate to NULL.

`snooze_state`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `account_id` | `TEXT` / `String` | no | none |
| `snoozed_until` | `INTEGER` / `Long` | no | none |

PK: `account_id`. FK: `account_id -> accounts(id) ON DELETE CASCADE`. No
additional index. A zero timestamp is not persisted: clearing snooze deletes the
row.

### History tables

`balance_records`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Long` | no | generated |
| `account_id` | `TEXT` / `String` | no | none |
| `currency` | `TEXT` / `String` | no | none |
| `recorded_at` | `INTEGER` / `Long` | no | none |
| `total_balance` | `REAL` / `Double` | no | none |
| `granted_balance` | `REAL` / `Double` | no | `0.0` |
| `topped_up_balance` | `REAL` / `Double` | no | `0.0` |
| `source` | `TEXT` / `BalanceRecordSource` | no | `REFRESH` |

PK: `id`. Index: exactly `(account_id, currency, recorded_at, id)`. FK:
`account_id -> accounts(id) ON DELETE CASCADE`. The terminal `id` supports stable
keyset pagination when multiple samples share a millisecond.

`daily_summaries`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `date` | `TEXT` / `String` | no | none |
| `account_id` | `TEXT` / `String` | no | none |
| `currency` | `TEXT` / `String` | no | none |
| `open_balance` | `REAL` / `Double` | no | none |
| `close_balance` | `REAL` / `Double` | no | none |
| `consumed_balance` | `REAL` / `Double` | no | none |
| `topped_up_balance` | `REAL` / `Double` | no | none |
| `granted_balance` | `REAL` / `Double` | no | `0.0` |
| `average_balance` | `REAL` / `Double` | no | none |
| `sample_count` | `INTEGER` / `Int` | no | none |
| `topped_up_balance_close` | `REAL` / `Double` | no | `0.0` |
| `granted_balance_close` | `REAL` / `Double` | no | `0.0` |
| `generated_at` | `INTEGER` / `Long` | no | none |

Composite PK and required uniqueness: `(date, account_id, currency)`. Additional
index: `(account_id, currency, date)`, which serves Task 9/12 account-and-currency
range queries without relying on the date-leading primary key. FK: `account_id ->
accounts(id) ON DELETE CASCADE`.

### Usage tables

`usage_snapshots`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `account_id` | `TEXT` / `String` | no | none |
| `captured_at` | `INTEGER` / `Long` | no | none |
| `identity_discriminator` | `TEXT` / `String` | no | `''` |

PK: `id`. Unique index: `(account_id, captured_at, identity_discriminator)`. FK:
`account_id -> accounts(id) ON DELETE CASCADE`. The same index serves range
queries. `id` is deterministically `UUID.nameUUIDFromBytes` (UUID v3) over the
UTF-8 literal `wallet-sentinel:usage-snapshot:v1|<account_id>|<captured_at>|<identity_discriminator>`.
For a refresh the discriminator is the stable refresh-run UUID; for import or
legacy migration it is the stable source-record ordinal within the canonical
input. Thus retry/recovery recreates the same primary key, while two snapshots
for one account in the same millisecond remain distinct. No identifier depends on
an API key.

`usage_records`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `snapshot_id` | `TEXT` / `String` | no | none |
| `record_ordinal` | `INTEGER` / `Int` | no | none |
| `model_name` | `TEXT` / `String` | no | none |
| `total_tokens` | `INTEGER` / `Long` | no | `0` |
| `prompt_tokens` | `INTEGER` / `Long` | no | `0` |
| `completion_tokens` | `INTEGER` / `Long` | no | `0` |

Composite PK: `(snapshot_id, record_ordinal)`. Index: `(snapshot_id, model_name)`.
FK: `snapshot_id -> usage_snapshots(id) ON DELETE CASCADE`. `record_ordinal` is
the zero-based source-list position and therefore preserves every source item,
including repeated `model_name` values, during import and recovery.

### `event_logs`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Long` | no | generated |
| `account_id` | `TEXT` / `String?` | yes | `NULL` |
| `refresh_run_id` | `TEXT` / `String?` | yes | `NULL` |
| `event_type` | `TEXT` / `EventLogType` | no | none |
| `total_balance_text` | `TEXT` / `String` | no | `''` |
| `currency_text` | `TEXT` / `String` | no | `''` |
| `is_available` | `INTEGER` / `Boolean` | no | `0` |
| `granted_balance_text` | `TEXT` / `String` | no | `''` |
| `topped_up_balance_text` | `TEXT` / `String` | no | `''` |
| `recorded_at` | `INTEGER` / `Long` | no | none |
| `message` | `TEXT` / `String` | no | `''` |
| `interval_seconds` | `INTEGER` / `Int?` | yes | `NULL` |
| `expected_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `alarm_method` | `TEXT` / `String?` | yes | `NULL` |
| `miss_reason` | `TEXT` / `String?` | yes | `NULL` |

PK: `id`. Index: `(recorded_at, id)` for newest-first pages. FKs:
`account_id -> accounts(id) ON DELETE CASCADE` and `refresh_run_id ->
refresh_runs(id) ON DELETE SET NULL`. This is intentionally separate from
`refresh_runs`; a run records batch truth while logs retain independent user and
scheduler diagnostics. Legacy `RefreshLogEntry` maps without parsing or loss:
each legacy amount/currency string, including `""` and non-numeric text, is
stored byte-for-byte in its matching `*_text` column. The fixed convention is
that `''` means absent/empty legacy text; NULL is never written to these columns.
Event logs perform no amount aggregation or numeric filtering.

### Refresh run tables

`refresh_runs`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `source` | `TEXT` / `RefreshRunSource` | no | none |
| `owner_process_session_id` | `TEXT` / `String?` | yes | `NULL` |
| `state` | `TEXT` / `RefreshRunState` | no | `RUNNING` |
| `started_at` | `INTEGER` / `Long` | no | none |
| `completed_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `account_count` | `INTEGER` / `Int` | no | `0` |
| `success_count` | `INTEGER` / `Int` | no | `0` |
| `failure_count` | `INTEGER` / `Int` | no | `0` |
| `cancelled_count` | `INTEGER` / `Int` | no | `0` |
| `error_code` | `TEXT` / `String?` | yes | `NULL` |

PK: `id`. Indexes: `(state, started_at)` and `(owner_process_session_id, state)`.
No FK. Aggregate counts and run state are derived only from committed account
results; they are persisted so process recreation can render the true batch
state.

`refresh_account_results`:

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `run_id` | `TEXT` / `String` | no | none |
| `account_id` | `TEXT` / `String` | no | none |
| `account_revision` | `INTEGER` / `Long` | no | none |
| `state` | `TEXT` / `RefreshAccountResultState` | no | `RUNNING` |
| `error_category` | `TEXT` / `RefreshErrorCategory?` | yes | `NULL` |
| `error_code` | `TEXT` / `String?` | yes | `NULL` |
| `retryable` | `INTEGER` / `Boolean` | no | `0` |
| `retry_after_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `data_timestamp` | `INTEGER` / `Long?` | yes | `NULL` |
| `stale` | `INTEGER` / `Boolean` | no | `0` |
| `attempt_count` | `INTEGER` / `Int` | no | `0` |
| `started_at` | `INTEGER` / `Long` | no | none |
| `completed_at` | `INTEGER` / `Long?` | yes | `NULL` |

Composite PK: `(run_id, account_id)`. FK: `run_id -> refresh_runs(id) ON DELETE
CASCADE`. `account_id` is a historical identity, deliberately not an accounts FK.
Indexes: `(account_id, completed_at)` and `(run_id, state)`. `RUNNING` is inserted
before work; only a terminal state is eligible for aggregation. If the account
is edited, its revision CAS prevents an old refresh from committing; if it is
deleted, the run recorder can still turn its existing `RUNNING` result into
`ACCOUNT_STALE` and derive a complete aggregate. This is consistent with account
ownership: balances, summaries, usage, and settings are account-owned and
cascade; refresh-account results are run-owned audit rows and cascade only when
their refresh run is deleted.

### `download_operations`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `owner_id` | `TEXT` / `String` | no | none |
| `tag` | `TEXT` / `String` | no | none |
| `source_url` | `TEXT` / `String` | no | none |
| `temporary_path` | `TEXT` / `String` | no | none |
| `target_path` | `TEXT` / `String` | no | none |
| `state` | `TEXT` / `DownloadState` | no | `QUEUED` |
| `downloaded_bytes` | `INTEGER` / `Long` | no | `0` |
| `total_bytes` | `INTEGER` / `Long?` | yes | `NULL` |
| `error_code` | `TEXT` / `String?` | yes | `NULL` |
| `error_message` | `TEXT` / `String?` | yes | `NULL` |
| `active_tag` | `TEXT` / `String?` | yes | `NULL` |
| `active_target_path` | `TEXT` / `String?` | yes | `NULL` |
| `created_at` | `INTEGER` / `Long` | no | none |
| `updated_at` | `INTEGER` / `Long` | no | none |
| `completed_at` | `INTEGER` / `Long?` | yes | `NULL` |

PK: `id`. Unique indexes: `active_tag`, `active_target_path`. No FK. For
`QUEUED`, `RUNNING`, and `CANCELLING`, the repository sets `active_tag = tag` and
`active_target_path = target_path`; for terminal states it writes both as NULL in
the same DAO update as `state`. SQLite permits multiple NULLs, so these two Room
unique indexes enforce at most one active downloader for either a release tag or
a target path. This is deliberately not a partial custom SQLite index, which
Room would omit from the exported schema identity. `owner_id` identifies the
operation owner that alone may delete its temporary file.

### `maintenance_checkpoint`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Int` | no | `0` |
| `last_completed_date` | `TEXT` / `String?` | yes | `NULL` |
| `zone_id` | `TEXT` / `String` | no | `UTC` |
| `last_success_at` | `INTEGER` / `Long?` | yes | `NULL` |

PK: `id`; DAO-owned singleton `id = 0`. No FK or secondary index. The checkpoint
advances only after a full date completes; Task 17 uses its Zone ID to schedule
the next local midnight.

### `monitoring_state`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `INTEGER` / `Int` | no | `0` |
| `desired` | `INTEGER` / `Boolean` | no | `0` |
| `observed_state` | `TEXT` / `MonitoringObservedState` | no | `STOPPED` |
| `process_session_id` | `TEXT` / `String?` | yes | `NULL` |
| `lease_expires_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `current_monitoring_session_id` | `TEXT` / `String?` | yes | `NULL` |
| `foreground_session_started_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `foreground_session_ended_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `last_user_foreground_reset_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `state_reason` | `TEXT` / `String?` | yes | `NULL` |
| `updated_at` | `INTEGER` / `Long` | no | none |

PK: `id`; DAO-owned singleton `id = 0`. FK: `current_monitoring_session_id ->
monitoring_sessions(id) ON DELETE SET NULL`. No secondary index. `state_reason`
is a stable redacted reason for `PLATFORM_LIMITED` or `PAUSED`, not a stack trace.
Task 18 invalidates leases whose `process_session_id` differs from the current
application process session; desired state alone never proves observed running.
It is a current-session projection only, not the source of dataSync budget truth.

### `monitoring_sessions`

| Column | SQLite / Kotlin | Null | Default |
| --- | --- | --- | --- |
| `id` | `TEXT` / `String` | no | none |
| `process_session_id` | `TEXT` / `String` | no | none |
| `started_at` | `INTEGER` / `Long` | no | none |
| `ended_at` | `INTEGER` / `Long?` | yes | `NULL` |
| `active_slot` | `TEXT` / `String?` | yes | `NULL` |
| `end_reason` | `TEXT` / `MonitoringSessionEndReason?` | yes | `NULL` |
| `recovered_at` | `INTEGER` / `Long?` | yes | `NULL` |

PK: `id`. Indexes: `(ended_at, started_at)` for interval candidates,
`(process_session_id, ended_at)` for process recovery, and unique `active_slot`.
An open dataSync row always has `active_slot = 'DATA_SYNC'`; close and recovery
set `ended_at` and set `active_slot = NULL` in the same transaction. SQLite's
unique nullable index therefore prevents more than one open dataSync session.
No account FK. This is the Task 18 ownership addition: create
`data/local/monitoring/MonitoringSessionEntity.kt` and
`MonitoringSessionDao.kt`; `WalletDatabase` owns both alongside the existing
monitoring state entity/DAO.

At time `now`, define:

```text
effectiveCutoff = min(
    now,
    max(now - 86_400_000, lastUserForegroundResetAt ?: Long.MIN_VALUE)
)
```

`MonitoringSessionDao.listOverlapping(effectiveCutoff, now)` returns candidates
with an indexable closed/open `UNION ALL` (or two equivalent DAO queries): closed
`ended_at > effectiveCutoff AND started_at < now`; open
`ended_at IS NULL AND started_at < now`. The `(ended_at, started_at)` index serves
the closed branch and its NULL partition serves the open branch. The DAO never
sums durations.

Task 18 owns a pure `MonitoringBudgetCalculator`, which computes union duration
using half-open intervals:

```text
intervals = candidates
    .map { [max(it.startedAt, effectiveCutoff), min(it.endedAt ?: now, now)) }
    .filter { it.end > it.start }
    .sortedBy(start, then end)

merged = []
for interval in intervals:
    if merged is empty or interval.start > merged.last.end:
        append interval
    else:
        merged.last.end = max(merged.last.end, interval.end)

usedMillis = sum(merged.end - merged.start)
```

The `<=` overlap/adjacency branch merges both overlapping and adjacent intervals.
Duplicates and dirty overlapping rows therefore cannot be double-counted. A row
ending exactly at `effectiveCutoff`, or starting exactly at `now`, contributes
zero; an open row contributes only through `now`.

One Room transaction starts a session row with the active slot and updates the
state projection; another ends that same row, clears its active slot, and
clears/updates the projection.
Process recreation conservatively ends an old open session at recovery time with
`PROCESS_RECOVERY` and `recovered_at = recoveryTime` in the same transaction.
When the user foreground reset occurs, state records the reset instant and future
budget calculations exclude earlier intervals; it never creates a second fixed
window or silently retains pre-reset usage. Safe pruning deletes only closed rows
where `ended_at <= effectiveCutoff`; a session crossing the cutoff is retained.
This is bounded after either 24-hour expiry or a foreground reset without the
incorrect conjunction from the prior proposal. Task 18 tests sessions spanning a
fixed calendar boundary, duplicate/overlapping/contiguous sessions, exact cutoff
and now boundaries, process recovery, and a user-foreground reset to prove no path
receives budget twice.

## Database converters

`DatabaseConverters` contains paired `@TypeConverter` methods for every enum in
the stable-enum table above and `ProviderType`. Each `fromStorage` converter uses
an exhaustive literal map and throws `IllegalArgumentException` for an unknown
literal. It must not silently default an unknown provider to DeepSeek. Booleans,
UUIDs, epoch millis, nullable values, and JSON strings need no converter:
entities use their primitive/String representations directly. `@TypeConverters`
is declared on `WalletDatabase`.

## Task 2 DAO boundary

Task 2 freezes the v1 schema, not a permanently closed DAO API. The methods in
this section are the minimum implementation surface needed to exercise v1 and
the publication transaction. Later tasks may add `suspend` and `Flow` query
methods, pagination, and aggregate projections, but may not alter any v1 table,
column, index, FK, default, or enum literal. All read/write DAO methods are
`suspend` unless returning a `Flow`; blocking DAO methods and main-thread access
are prohibited.

| DAO | Required v1 API |
| --- | --- |
| `AccountDao` | `observeVerified()`, `get(id)`, `getAllForMigration()`, `insertCreate()`, `updateWhereRevision()`, `deleteWhereRevision()` |
| `MutationOperationDao` | `insertPrepared()`, `get(id)`, `listRecoverable()`, `updateStage()`, `markPublished()`, `markCompleted()` |
| `AppMetadataDao` | `get()`, `ensureSingleton()`, `incrementRevisionIfCurrent()`, `advanceMetadataAndRevisionIfCurrent()` |
| `AppSettingsDao` | `get()`, `observe()`, `ensureSingleton()`, `upsert()` |
| `SettingsDao` | `replaceAccountAlertSettings()`, `replaceNotificationSelections()`, `replaceAlertRuntimeStates()`, `replaceSnoozes()`, `upsertAlertRuntimeState()`, `setSnooze()`, `clearSnooze()` |
| `HistoryDao` | `insertBalanceBatch()`, `upsertSummaries()`, keyset page/range/count/distinct/aggregate queries, `deleteRawForDate()` |
| `UsageDao` | `upsertSnapshotWithRecords()`, range/page/count queries, `deleteByAccount()` |
| `EventLogDao` | `insertAll()`, newest-first page/limit, `deleteBefore()` |
| `RefreshRunDao` | `insertRun()`, `insertRunningResult()`, `completeAccountAtomically()`, `deriveAndUpdateAggregate()`, `interruptRunsWithoutOwner()` |
| `DownloadOperationDao` | `insertActive()`, `get(id)`, `observe(id)`, `transitionOwnedOperation()`, `listActive()` |
| `MaintenanceCheckpointDao` | `getOrCreate()`, `advanceAfterCompleteDate()` |
| `MonitoringStateDao` | `getOrCreate()`, `observe()`, `setDesired()`, `renewLease()`, `projectSessionStart()`, `projectSessionEnd()` |
| `MonitoringSessionDao` | `insertStart()`, `endOpenForRecovery()`, `endCurrent()`, `listOverlapping(cutoff, now)`, `pruneClosedThrough(cutoff)` |

The exact higher-level repositories introduced in later tasks own validation,
pagination limits, and mapping. DAOs remain persistence boundaries and do not
read credentials or legacy preferences.

## Typed publication transaction

Task 2 supplies a typed, not-yet-wired `MutationPublisher` whose only durable
scope is Room:

```kotlin
suspend fun publish(input: MutationPublication): PublicationResult
```

The frozen Task 2 DTO shape is:

```kotlin
sealed interface AccountMutation {
    data class Create(
        val id: String,
        val displayOrder: Int,
        val label: String,
        val providerType: ProviderType,
        val providerConfigJson: String,
        val activeCredentialGeneration: String
    ) : AccountMutation

    data class Update(
        val id: String,
        val expectedRevision: Long,
        val displayOrder: Int,
        val label: String,
        val providerType: ProviderType,
        val providerConfigJson: String,
        val activeCredentialGeneration: String
    ) : AccountMutation

    data class Delete(
        val id: String,
        val expectedRevision: Long
    ) : AccountMutation
}

sealed interface AppSettingsWrite {
    data object Unchanged : AppSettingsWrite
    data class ReplaceAll(val value: AppSettingsValues) : AppSettingsWrite
}

data class AppSettingsValues(
    val backgroundRefreshIntervalSeconds: Int?,
    val foregroundMonitoringIntervalSeconds: Int,
    val alertEnabled: Boolean,
    val alertThreshold: Double,
    val changeAlertEnabled: Boolean,
    val changeAlertThreshold: Double,
    val changeAlertPeriodMinutes: Int,
    val logMaxEntries: Int,
    val snoozeDurationMinutes: Int,
    val showTotalBalanceInNotification: Boolean
)

sealed interface AccountAlertSettingsWrite {
    data object Unchanged : AccountAlertSettingsWrite
    data class ReplaceAll(val values: List<AccountAlertSettingEntity>) : AccountAlertSettingsWrite
}

sealed interface NotificationSelectionsWrite {
    data object Unchanged : NotificationSelectionsWrite
    data class ReplaceAll(val values: List<NotificationWalletSelectionEntity>) : NotificationSelectionsWrite
}

sealed interface AlertRuntimeStatesWrite {
    data object Unchanged : AlertRuntimeStatesWrite
    data class ReplaceAll(val values: List<AlertRuntimeStateEntity>) : AlertRuntimeStatesWrite
}

sealed interface SnoozesWrite {
    data object Unchanged : SnoozesWrite
    data class ReplaceAll(val values: List<SnoozeStateEntity>) : SnoozesWrite
}

data class SettingsPublication(
    val appSettings: AppSettingsWrite,
    val accountAlertSettings: AccountAlertSettingsWrite,
    val notificationSelections: NotificationSelectionsWrite,
    val alertRuntimeStates: AlertRuntimeStatesWrite,
    val snoozes: SnoozesWrite
)

sealed interface MetadataPublication {
    data object Unchanged : MetadataPublication
    data class CompareAndSet(
        val expectedActiveDataGeneration: String,
        val expectedLegacyMigrationStage: LegacyMigrationStage,
        val newActiveDataGeneration: String,
        val newLegacyMigrationStage: LegacyMigrationStage
    ) : MetadataPublication
}

data class MutationPublication(
    val operationId: String,
    val baselineRevision: Long,
    val accountMutations: List<AccountMutation>,
    val settings: SettingsPublication,
    val metadata: MetadataPublication,
    val publishedAt: Long
)

data class PublicationResult(
    val operationId: String,
    val newLocalRevision: Long
)
```

`Create` has no caller-controlled `created_at`, `legacy_storage_id`, `state`, or
`revision`: the publisher assigns `created_at = updated_at = publishedAt`,
`legacy_storage_id = NULL`, initial revision `0`, and `VERIFIED` only after
staged credentials have been read back. The validated legacy migration writer is
the sole internal path that may set a legacy mapping and `PENDING`; it derives
those values from the operation manifest, not a general caller DTO. `Update` and
`Delete` require `expectedRevision` and cannot supply any protected field.
Updates execute `UPDATE accounts ... revision = revision + 1 WHERE id = :id AND
revision = :expectedRevision`; deletes use the equivalent revision predicate.
Any zero-row result is stale/conflict and aborts the enclosing transaction. Thus
a refresh recorded against revision N cannot commit after an edit has changed the
row to N+1. Imported account revisions are source metadata only and never enter
these mutations.

Settings publication is deliberately per table. A configuration import uses
`ReplaceAll` for `appSettings`, `accountAlertSettings`, and
`notificationSelections`, and `Unchanged` for `alertRuntimeStates` and `snoozes`
unless an explicit product operation authorizes replacing runtime state. Every
`ReplaceAll` deletes then inserts the complete contents of its one table in the
transaction; no `ReplaceAll` is a merge.

`AppSettingsValues` deliberately excludes the DAO-owned singleton `id` and
`updated_at`. For `AppSettingsWrite.ReplaceAll`, the publisher alone constructs
`AppSettingsEntity(id = 0, updatedAt = publishedAt, ...)`; a caller cannot replace
singleton identity or invent a different update time.

`MetadataPublication.Unchanged` is the only metadata option for ordinary account
publication. A migration uses `CompareAndSet`, which compares the expected active
data generation and migration stage in the metadata update predicate before it
writes both new values and increments `local_revision`. A caller can therefore
not replay an old metadata snapshot over a newer migration state.

Inside one `RoomDatabase.withTransaction` block it performs, in this order:

1. verifies the operation exists and is eligible for publication;
2. applies each typed account CAS mutation, then applies each independently
   selected settings-table replacement, including active credential generation
   references only;
3. increments `app_metadata.local_revision` from `baselineRevision`; a metadata
   `CompareAndSet` also predicates on its expected active generation and stage
   and writes its new values in this same statement;
4. marks the operation `PUBLISHED` with `published_at`.

Any zero-row account CAS, metadata CAS, or DAO error throws a stale/conflict
exception and therefore rolls back every Room write in the block. On success the
returned `PublicationResult(operationId, baselineRevision + 1)` is the sole
publication acknowledgment. No completion cleanup occurs here; later recovery
marks `COMPLETED` after external cleanup.

The only fault-injection seam is the internal constructor dependency
`TransactionStepObserver`, with points `AFTER_ACCOUNT_ROWS`,
`AFTER_SETTINGS_ROWS`, `AFTER_METADATA`, and `AFTER_OPERATION_PUBLISHED`.
`MutationPublisher`'s public API has no observer argument, test flag, or product
operation. Its production factory binds a private no-op observer; only the
internal test construction path supplies an observer that throws. This is not a
test-only production method: it is a non-exported transaction-step observation
dependency that permits a real Room transaction to fail after each durable write,
which is necessary to prove rollback rather than merely mock DAO calls. A thrown
test exception remains inside `withTransaction`; tests then query a fresh Room
transaction and assert every Room row remains pre-publication. External
credential writes are staged/read back before this API and are explicitly outside
Room rollback.

## Schema identity and export gate

`WalletDatabase` is version `1`, includes exactly the 19 tables in this proposal,
and is compiled with Room schema export enabled. Room emits and commits:

`app/schemas/com.balancesentinel.app.data.local.WalletDatabase/1.json`.

`WalletDatabaseTest` opens an in-memory database without
`allowMainThreadQueries()`, obtains the SQLite connection on the test dispatcher,
and asserts a hand-written, literal expected schema from `PRAGMA table_info`,
`PRAGMA index_list`/`PRAGMA index_info`, and `PRAGMA foreign_key_list`. Expected
values are never derived from entity annotations, DAO SQL, or a shared schema
builder. It fixes:

- the exact table set and columns, type affinity, nullability, default values,
  and primary-key order;
- all indexes and uniques, especially the balance-record ordering index, daily
  summary key, nullable account-owned FKs, both download active-slot uniques, and
  the monitoring-session overlap/recovery indexes and active-slot unique;
- every account-owned FK with `CASCADE`, the run-owned refresh-result cascade,
  and the usage child cascade; and
- singleton table primary keys and all persisted enum string fields.

The test compares the committed exported JSON as a generated-contract artifact,
using a hand-written literal JSON expectation or schema-field fixture. It does
not grep source text or derive its expectation from entity annotations.
`AccountDaoTest` adds real Room behavior coverage for
revision publication rollback, revision monotonicity, daily summary uniqueness,
account-delete cascades, retained run-owned refresh results, and unique active
download slots. The test gate also uses raw SQL to insert an unknown literal into
each persisted enum column and `accounts.provider_type`, then reads through the
corresponding DAO and asserts conversion fails. Every stable enum and provider
literal has hand-written literal-to-enum and enum-to-literal tests; no expectation
is generated from enum `entries`. Finally, it injects a real throwing
`TransactionStepObserver` separately at `AFTER_ACCOUNT_ROWS`,
`AFTER_SETTINGS_ROWS`, `AFTER_METADATA`, and `AFTER_OPERATION_PUBLISHED`, and
after each failure verifies with fresh Room queries that every publication row is
unchanged. No test claims an external credential store rolled back with Room.

Monitoring behavior tests use real Room rows to prove the `DATA_SYNC` active-slot
unique rejects a second open session and permits a new session only after close or
recovery clears the slot. Literal interval fixtures cover duplicate, overlapping,
adjacent, cutoff-ending, now-starting, and open intervals; the pure
`MonitoringBudgetCalculator` must return union duration. Separate DAO tests cover
the closed/open candidate branches, `ended_at <= effectiveCutoff` pruning,
foreground reset cutoff clamping, and conservative recovery of an old open row.

## Residual product ambiguity

No unresolved product decision blocks implementation. The two account states
(`PENDING`, `VERIFIED`) are the minimal states demanded by Task 3's verification
gate; deletion is physical and uses FK cascade, so adding an archive state would
be an unrequested product feature. Error codes/reasons remain stable repository
strings rather than a second product-visible enum, because the design specifies
error categories but does not define a user-facing error-code catalog.

## Architecture Review Fix Round 1

1. **ADDRESSED: account revision CAS.** Replaced `AccountDelta<AccountEntity>`
   with typed `Create`, `Update(expectedRevision)`, and
   `Delete(expectedRevision)` mutations. Protected fields are publisher-owned;
   account CAS zero rows abort the transaction and invalidate old refresh
   revision N after an N+1 edit.
2. **ADDRESSED: run-owned refresh history.** `refresh_account_results` retains
   only its run FK/cascade; `account_id` is indexed historical identity. Account
   deletion retains RUNNING audit rows so they can become `ACCOUNT_STALE` and the
   run aggregate remains complete.
3. **ADDRESSED: settings scope.** Publication now carries independent
   `Unchanged`/`ReplaceAll` choices for each settings table. Config import leaves
   alert runtime and snooze state unchanged by default.
4. **ADDRESSED: duplicate usage models.** `usage_records` now uses source-order
   `record_ordinal` in the PK and has a separate `(snapshot_id, model_name)`
   query index.
5. **SUPERSEDED BY ROUNDS 2/3: monitoring budget recovery.** The singleton keeps
   only current projection/reset state; durable session intervals and their union
   duration are the budget source of truth.
6. **ADDRESSED: metadata CAS.** Metadata publication is explicit `Unchanged` or
   full expected-old/new `CompareAndSet`; ordinary account publications cannot
   alter it, and migration updates revision and metadata together.
7. **ADDRESSED: history import ledger.** Added `HISTORY_DATA_IMPORT` and
   `manifest_version`; Task 13 owns its manifest/cursor and recoverable stages.
8. **ADDRESSED: schema/test gate.** Identity expectations are literal, unknown
   persisted enum/provider values fail DAO reads, enum literals are tested in
   both directions, and all four real Room rollback observation points are
   covered.

## Architecture Review Fix Round 2

A. **ADDRESSED: rolling 24-hour monitoring budget.** Added the nineteenth v1
table, `monitoring_sessions`, as the durable interval ledger. Budget is computed
from interval overlap after the last user-foreground reset, not a fixed-window
counter; atomic start/end/recovery, safe pruning, indexes, schema identity, DAO,
and Task 18 boundary tests are specified.

B. **ADDRESSED: all five settings replacements are implementable.**
`SettingsDao` now includes `replaceAlertRuntimeStates()` and `replaceSnoozes()`
beside the other replacement APIs, matching every per-table `ReplaceAll` arm.

C. **ADDRESSED: app-settings singleton ownership.** `AppSettingsWrite.ReplaceAll`
now accepts `AppSettingsValues`, which excludes `id` and `updated_at`; the
publisher constructs the singleton entity with fixed `id = 0` and
`updatedAt = publishedAt`.

## Architecture Review Fix Round 3

1. **ADDRESSED: union duration.** The DAO returns clipped-window candidates;
   `MonitoringBudgetCalculator` sorts, clips, merges overlapping/adjacent
   half-open intervals, and sums the union so duplicate/overlapping dirty rows
   never double-charge.
2. **ADDRESSED: one open session.** `monitoring_sessions.active_slot` is nullable
   with a Room-exported unique index. Open dataSync uses `DATA_SYNC`; close and
   recovery atomically clear it. Identity and real unique behavior are tested.
3. **ADDRESSED: bounded correct pruning.** `effectiveCutoff` is clamped to `now`;
   only closed rows ending at or before it are deleted, while crossing sessions
   remain available for overlap calculation.
4. **ADDRESSED: indexable overlap query.** The overlap index is
   `(ended_at, started_at)` and closed/open branches have explicit predicates;
   candidates are sorted and unioned after retrieval. Process recovery retains
   `(process_session_id, ended_at)`.
5. **ADDRESSED: DAO/support/test contract.** DAO surface now exposes
   `listOverlapping`; Task 18 owns the pure calculator. Schema tests freeze the
   field/index/unique and behavior tests cover active-slot enforcement,
   interval-union boundaries, reset, prune, and recovery.
