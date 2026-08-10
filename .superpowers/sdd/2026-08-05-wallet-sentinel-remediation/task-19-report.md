# Task 19 report: DeepSeek current and backup certificate pins

## Status

GREEN. DeepSeek now uses two verified SPKI pins scoped only to
`api.deepseek.com`. Current and backup certificates are stored as trusted DER
fixtures, XML/Kotlin pin parity is enforced, and pin failures map to a stable
network error without fallback.

## Commits

- Support: `f2f8ab4`
- RED: `1031a89`
- GREEN: `c0c0cf0`
- Fix round 1: `7b68538` (exact-host scope, offline DER fixtures, negative TLS coverage)
- Fix round 2: `a33570b` (dynamic MockWebServer host pinning)

## Verification

Focused command:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.balancesentinel.app.data.network.DeepSeekTlsPolicyTest" --tests "com.balancesentinel.app.data.network.NetworkSecurityConfigPinParityTest" --tests "com.balancesentinel.app.data.api.balance.BalanceQueryServiceTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; 15 tests, 0 failures, 0 errors, 0 skipped.

The verified chain was captured on August 10, 2026: current leaf
`sha256/IS95653JtE1/bNto9qa5E/NHBmBbRDmfaLM+btVVTCk=` and backup issuer/rotation
`sha256/eLVG2Nq6lNlY482AlhlwwHqvL3TsvXMFJx2ycA8gZpQ=`. Subject, issuer,
validity, extraction command, and DER fixtures are committed with the tests.

Independent scoped review of `34fe6c7..a33570b`: no Critical and no Important
findings.

## Deferred

Minor follow-ups: add a direct assertion that `cert2.der` fails the pinner,
decode parity pins before checking their Base64 length, and expand the backup
certificate extraction comment. These do not block the current pin contract.

Task 20 is unblocked. Task 20 was not started in this task.
