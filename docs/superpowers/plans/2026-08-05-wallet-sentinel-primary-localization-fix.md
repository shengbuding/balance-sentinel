# Wallet Sentinel Primary Localization Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the two Chinese strings that leak into the English primary screen and protect the rendered English UI with device-level regression tests.

**Architecture:** Keep Android string resources as the single source of user-visible copy. Extract the existing bottom navigation block into a real file-level composable so its rendered locale behavior can be tested directly; keep the home empty-state test at the full `HomeScreen` boundary.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android string resources, Compose UI instrumentation tests, Gradle, Android 15/API 35 emulator.

## Global Constraints

- Work only in `C:\Users\Administrator\DeepSeekBalance\.worktrees\wallet-sentinel-hardening` on branch `wallet-sentinel-hardening`.
- Use strict RED then GREEN: production files must not change until both new device tests have been observed failing for the expected Chinese-text leakage.
- The primary navigation English label must render exactly `Console`; the default resource must render exactly `控制台`.
- The disabled empty-state guidance must reuse `R.string.home_add_account`, rendering `Add Account` in English and `添加账户` in the default locale.
- Preserve navigation selection and callbacks exactly; this task changes displayed copy and testability, not navigation behavior.
- Do not alter console screen copy, internal Chinese comments, diagnostic logs, notifications, or unrelated localization debt.
- Do not add dependencies or change Android/Gradle versions.
- Run Gradle commands serially, always with `--rerun-tasks`; never run two Gradle clients concurrently.
- Commit RED separately from GREEN and leave tracked/index state clean.

---

### Task 1: Localize primary navigation and empty-state guidance

**Files:**
- Modify: `app/src/androidTest/java/com/balancesentinel/app/MainActivityTest.kt`
- Modify: `app/src/androidTest/java/com/balancesentinel/app/ui/screen/HomeScreenTest.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/MainActivity.kt`
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `Screen`, `HomeScreen`, `R.string.home_add_account`, the existing four-item Material 3 navigation bar.
- Produces: `@Composable internal fun AppNavigationBar(currentScreen: Screen, onScreenSelected: (Screen) -> Unit)` and `R.string.nav_console`.

- [ ] **Step 1: Add the failing English home empty-state test**

In `HomeScreenTest`, add `assertDoesNotExist` and a test that renders a no-account `HomeScreen` through the existing `setEnglishContent` helper:

```kotlin
@Test
fun `empty account guidance does not leak Chinese in English locale`() {
    val vm = createViewModel()
    setEnglishContent {
        HomeScreen(viewModel = vm, onNavigateToSettings = {})
    }

    composeTestRule.onNodeWithText("添加第一个账户").assertDoesNotExist()
    composeTestRule.onNodeWithText("Add Account").assertIsDisplayed()
}
```

The mutation caught is restoring the current literal `Text("添加第一个账户")` instead of resolving `home_add_account` through the English configuration.

- [ ] **Step 2: Add the failing English navigation test**

In `MainActivityTest`, add the same `setEnglishContent` pattern already present in `HomeScreenTest`, then render the production `AppNavigationBar` contract:

```kotlin
@Test
fun `primary navigation does not leak Chinese in English locale`() {
    setEnglishContent {
        AppNavigationBar(currentScreen = Screen.HOME, onScreenSelected = {})
    }

    composeTestRule.onNodeWithText("控制台").assertDoesNotExist()
    composeTestRule.onNodeWithText("Console").assertIsDisplayed()
}
```

The test must exercise the real production navigation composable, not a duplicate test-only bar.

- [ ] **Step 3: Run the device tests and verify RED**

Start the existing ignored `wallet_sentinel_api35` AVD with a clean, no-snapshot API 35 boot. Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest --rerun-tasks --no-parallel
```

Expected: the suite fails because the English render still contains `添加第一个账户` and `控制台`, and `Console` is absent. Record the exact failing test names and counts in the report. Commit only the failing tests:

```powershell
git add app/src/androidTest/java/com/balancesentinel/app/MainActivityTest.kt app/src/androidTest/java/com/balancesentinel/app/ui/screen/HomeScreenTest.kt
git commit -m "test: expose primary locale leaks"
```

- [ ] **Step 4: Implement the minimal resource-backed UI**

Add this exact resource to both resource sets:

```xml
<!-- app/src/main/res/values/strings.xml -->
<string name="nav_console">控制台</string>

<!-- app/src/main/res/values-en/strings.xml -->
<string name="nav_console">Console</string>
```

Move the existing `NavigationBar` block, unchanged except for callback wiring and the console copy, into:

```kotlin
@Composable
internal fun AppNavigationBar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentScreen == Screen.HOME,
            onClick = { onScreenSelected(Screen.HOME) },
            icon = {
                Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.home_title))
            },
            label = { Text(stringResource(R.string.home_title)) }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.INSIGHTS,
            onClick = { onScreenSelected(Screen.INSIGHTS) },
            icon = {
                Icon(CustomIcons.TrendingUp, contentDescription = stringResource(R.string.insights_title))
            },
            label = { Text(stringResource(R.string.insights_title)) }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.CONSOLE_SELECT || currentScreen == Screen.CONSOLE,
            onClick = { onScreenSelected(Screen.CONSOLE_SELECT) },
            icon = {
                Icon(CustomIcons.Analytics, contentDescription = stringResource(R.string.nav_console))
            },
            label = { Text(stringResource(R.string.nav_console)) }
        )
        NavigationBarItem(
            selected = currentScreen == Screen.SETTINGS,
            onClick = { onScreenSelected(Screen.SETTINGS) },
            icon = {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            },
            label = { Text(stringResource(R.string.settings_title)) }
        )
    }
}
```

Call it from the existing `Scaffold(bottomBar = ...)`. In `EmptyAccountsHint`, replace the literal with:

```kotlin
Text(stringResource(R.string.home_add_account))
```

Do not change any other copy.

- [ ] **Step 5: Run focused and full GREEN verification**

Run serially:

```powershell
.\gradlew.bat connectedDebugAndroidTest --rerun-tasks --no-parallel
.\gradlew.bat testDebugUnitTest lintDebug --rerun-tasks --no-parallel
git diff --check a1c0ccae1c754c509243b631ac48a2f284789d91..HEAD
```

Expected: all device and JVM tests pass; lint has zero errors; diff check is clean. Manually launch the debug app on the same English API 35 emulator after clearing app data, complete the permission/onboarding flow, and capture evidence showing `Console` plus `Add Account` with neither reported Chinese literal visible.

- [ ] **Step 6: Report, self-review, and commit GREEN**

The report must contain root cause, RED command/output, GREEN commands/output, device identity, screenshots, changed files, self-review, and any concern. Commit implementation and report artifacts that belong to the task:

```powershell
git add app/src/main app/src/androidTest docs/superpowers/plans/2026-08-05-wallet-sentinel-primary-localization-fix.md
git commit -m "fix: localize primary navigation copy"
```
