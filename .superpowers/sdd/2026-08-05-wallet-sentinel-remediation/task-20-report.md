# Task 20 report: typed routes and deep-link support layer

## Status

GREEN. Typed `AppRoute` and `DeepLinkResolver` now provide canonical route
parsing, strict account/currency validation, malformed URI rejection, and one
compatibility path for legacy extras. Notification, widget, and snooze identity
keys are normalized by account and currency, while stale deleted-account widget
links are suppressed.

The task deliberately does not migrate `MainActivity` or expose a manifest VIEW
entry; runtime route consumption is owned by Task 21.

## Commits

- Support: `116bce8`, `9e0dcec`
- RED: `4753e8d`, `9142505`
- GREEN: `8533f11`, `e828e22`
- Fix round 1: `3d79c74`
- Fix round 2: `089f711`

## Verification

Focused tests: `DeepLinkResolverTest`, `NotificationHelperTest`, and
`SnoozeReceiverTest` — 31 tests, 0 failures, 0 errors, 0 skipped.

Independent scoped review of `16fc0c7..089f711`: no Critical and no Important
findings.

## Deferred

`MainActivity`/NavHost does not yet consume typed routes or apply account and
currency selection; Task 21 owns that migration. The manifest does not advertise
an unsupported external VIEW entry until that consumer exists.

Task 21 is unblocked. Task 21 was not started in this task.
