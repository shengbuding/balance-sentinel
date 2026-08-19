# Subscription Insights and widget follow-up

Date: 2026-08-19
Status: COMPLETE

## Symptoms

- OpenCode Go exposed only its monthly quota on the Home account card even
  though the provider returns 5-hour, weekly, and monthly subscription windows.
- Insights called percentage-based quota data "percentage" and did not present
  subscription usage independently from currency balances.
- Subscription charts used balance-oriented axes, had no useful time ticks, and
  made reset timestamps difficult to read.
- Subscription widgets did not consistently show every available quota window,
  its reset time, or the correct used-percentage trend.
- A single-account widget trend could include samples from other subscription
  accounts.
- Subscription Insights still plotted used percentage, so the line climbed as
  quota was consumed instead of starting near 100% remaining and falling.
- Selecting an account reused the selected chart query scope for the account
  filter chips, hiding other accounts until `All accounts` was selected again.
- Subscription widgets always promoted the monthly window, could not persist a
  chosen window, and long wallet names could wrap into the quota area.
- In the all-account subscription view, the chart could become a large blue
  band when different accounts refreshed at different timestamps.
- Room aggregation and midnight archival still interpreted `%` quota resets as
  monetary recharge/grant deltas, so correct live statistics could become wrong
  after crossing midnight.
- Legacy continuity gap rows reset weekly and 5-hour remaining values to zero,
  creating artificial 100% usage points on dates without refreshes.

## Root causes

- The shared quota model, Insights projection, and widget snapshot did not carry
  all provider quota windows through the complete data path.
- Subscription history stores remaining percentage, while the UI chart and
  widget communicate used percentage. The widget sparkline consumed the stored
  values directly, reversing its meaning.
- The single-account widget history query was not scoped to the configured
  account ID.
- Balance chart defaults were reused for subscription axes and labels.
- `InsightsViewModel` incorrectly built `eligibleAccounts` from
  `scopedAccounts`; those collections have different responsibilities.
- Widget configuration stored only account and currency, while the resolver and
  renderer independently assumed a monthly primary value.
- The quota history projection discarded account identity before merging. In
  all-account mode, points from different accounts were therefore connected in
  timestamp order without carry-forward values, producing artificial full-height
  zigzags. A year of daily summaries mixed with dense recent samples also made
  the narrow chart overdraw the same pixels.
- `HistoryDao.aggregateSemantic()` is deliberately a money-accounting query,
  but both `RoomHistoryRepository.aggregate()` and `archiveDateSeries()` used
  it for every currency marker, including `%`.
- Both continuity implementations carried the monthly close, but only the Room
  implementation also carried the weekly and rolling 5-hour close columns.

## Fix

- Added OpenCode Go as a built-in provider, with 5-hour, weekly, and monthly
  quota windows plus their reset timestamps.
- Generalized quota contracts so other subscription-style providers can expose
  the same windowed data without provider-specific UI logic.
- Added a separate Insights subscription category that appears only when
  percentage quota data exists; its charts distinguish each quota window.
- Renamed the user-facing percentage category to `Subscription`/`订阅`.
- Fixed subscription chart bounds to 100% at the top and 0% at the bottom,
  added X-axis time ticks, localized reset timestamps, and added a live reset
  countdown that updates once per second.
- Draw subscription trends as remaining quota (`100 - used`): a full quota is
  the highest 100% point and consumption moves the line downward toward 0%.
- Keep all accounts that support the selected currency in the filter row while
  limiting chart data to the selected account.
- Adapted widgets to display all available subscription windows, their used
  percentages, and reset information.
- Added per-widget subscription-window selection for 5 hours, weekly, and
  monthly. New and legacy widget configurations default to the shortest window
  (`rolling_5h`), and the primary value follows the saved choice.
- Kept wallet titles on one marquee-enabled line in all four widget layouts so
  long names cannot increase the title row or displace quota values.
- Convert persisted remaining-percentage history to used percentage with
  `100 - remaining` when drawing widget sparklines.
- Preserve account identity while building quota observations. All-account
  history now carries each account's last known value forward and uses the
  highest used percentage (the most constrained subscription) at each timestamp;
  dense histories are reduced to at most 240 worst-case samples for legible
  rendering. Single-account histories retain their existing behavior.
- Scope single-account widget trend queries to the configured account.
- Replaced the Compose countdown `produceState` implementation with
  `remember` plus `LaunchedEffect`, resolving the final lint error.
- Route Room `%` aggregation and midnight archival through the shared
  `RecordAggregator` quota algorithm. Monetary currencies keep the optimized
  SQL accounting path unchanged, and duplicate timestamps retain Room row-ID
  order.
- Carry monthly, weekly, and 5-hour remaining values across legacy continuity
  gaps while keeping all three consumption counters at zero.
- Use the most recent real historical refresh timestamp when no live quota
  snapshot exists, and ignore accounts without any daily points when calculating
  an all-account historical average.
- For the all-account current subscription value, use each eligible account's
  live snapshot when available and otherwise its latest historical quota point;
  history-only accounts therefore remain in the arithmetic mean.
- Add localized date ticks below the subscription daily-usage chart so daily
  percentage-point history can be read without inferring dates from the line.
- In Subscription Insights, show the latest refreshed account only when the
  account filter is `All Accounts`. Merge that account and its next-refresh
  timestamp into one block; selected-account views show only their own next
  refresh and countdown, never the latest-account label or the previous refresh
  timestamp.

## Regression coverage

- OpenCode Go response parsing and built-in provider contract coverage.
- Quota model normalization and reset-time parsing coverage.
- Insights subscription availability, series, axis, and asynchronous loading
  coverage.
- Widget quota snapshot, account filtering, rendering, and sparkline semantic
  coverage.
- Remaining-quota chart conversion (`used=0 -> remaining=100`,
  `used=100 -> remaining=0`).
- Same-currency account filters remain complete after selecting one account,
  while the chart remains scoped to that account.
- Added a regression covering two subscription accounts with interleaved refresh
  timestamps; the merged series is monotonic in time and no longer alternates
  between unrelated account values.
- Subscription-window persistence, legacy default migration, resolver routing,
  provider rendering, and long-title RemoteViews layout coverage.
- Room repository and midnight cleanup regressions prove quota-reset usage stays
  `170 / 130 / 200` percentage points for monthly / weekly / 5-hour histories,
  including duplicate timestamps.
- Legacy continuity regressions prove gap days preserve all three remaining
  quota windows without fabricating usage.

## Verification

Final command:

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

- Build: successful.
- Debug JVM tests: 1,531 total, 0 failures, 0 errors, 3 skipped.
- Debug lint: 0 errors, 177 warnings, 4 informational findings.
- `git diff --check`: no whitespace errors; only configured LF/CRLF conversion
  warnings were reported.

## Debug APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`
- Package: `com.balancesentinel.app`
- Version: `versionName=v1.5.0-dirty`, `versionCode=698`
- Size: 40,459,681 bytes (38.59 MiB)
- Built: 2026-08-19 23:38:08 +08:00
- SHA-256:
  `ED37EA554879946BAAE2BDCB6F2175BCFE8366DD9EED768EF5C5948C60658DED`

## Working-tree state

The implementation and this handoff remain local and uncommitted. No commit,
push, pull request, GitHub Release, workflow dispatch, or artifact upload was
performed. `master` still points to the same commit as `origin/master`; the
working tree contains the subscription implementation and its tests on top.

## OpenAI subscription API follow-up (2026-08-20)

- The existing `OPENAI` provider means OpenAI Platform API access through a user-supplied API key; it does not represent ChatGPT Plus/Pro/Codex subscription access.
- OpenAI does not expose a public personal REST API for subscription remaining percentages or 5-hour/weekly/monthly reset times. Platform organization Usage/Costs APIs expose historical API usage and cost, not subscription balance.
- Do not collect ChatGPT cookies, OAuth/session tokens, `auth.json`, Codex access tokens, or call undocumented `chatgpt.com/backend-api` endpoints. Keep personal subscription viewing as an official Usage Dashboard link.
- If an official personal subscription API is released later, add a separate `OPENAI_SUBSCRIPTION` provider with its own authentication and permission model instead of reusing `OPENAI`.
- Detailed notes: `docs/openai-usage-api.md`.

## Status

DONE. The implementation, regression tests, lint gate, and debug APK are ready
for device testing and independent review.

## v1.5.1 release follow-up (2026-08-20)

The subscription implementation and OpenAI API boundary documentation are being
published as formal `v1.5.1`. Local Debug/Release JVM, lint, Kover, and APK
gates passed; the signed APK and GitHub Release are produced by the
`v1.5.1` tag workflow. The working tree remains subject to the release commit
and tag until the workflow completes.
