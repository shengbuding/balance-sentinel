# 语言切换功能 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在设置页面添加中英双语切换，AppCompatDelegate.setApplicationLocales() 即时生效，无需重启；默认跟随系统语言。

**Architecture:** WidgetPrefs 持久化语言偏好 → DeepSeekApp.onCreate() 启动恢复 → SettingsScreen 新增导航卡片 + 单选 Dialog 触发切换 → Compose 自动重组反映变更。

**Tech Stack:** Kotlin + Jetpack Compose + AndroidX AppCompat 1.6+ (已集成) + SharedPreferences

## Global Constraints

- AppCompatDelegate.setApplicationLocales() — AndroidX 已集成，无新依赖
- 即时生效，不提示重启
- 未设置偏好时跟随系统语言
- 入口：设置主页「关于」卡片之前
- 新增语言只需加 values 资源文件夹 + 一行 Dialog 选项
- 无新测试文件（框架行为，单元测试无意义）

---

### Task 1: WidgetPrefs — 新增语言偏好存取

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/data/repository/WidgetPrefs.kt`

**Interfaces:**
- Produces: `WidgetPrefs.language: String?` — null = 未设置 = 跟随系统
- Produces: `companion object KEY_LANGUAGE = "pref_language"`

- [ ] **Step 1: 在 companion object 中新增 key 常量**

在 `WidgetPrefs.kt` 第 394 行附近（`KEY_NOTIFICATION_TOTAL` 之后），插入：

```kotlin
const val KEY_LANGUAGE = "pref_language"
```

- [ ] **Step 2: 新增 language 属性**

在第 51 行附近（`snoozeDurationMinutes` setter 之后，`getLastAlertedBalance` 之前），插入：

```kotlin
var language: String?
    get() = prefs.getString(KEY_LANGUAGE, null)
    set(value) {
        if (value != null) {
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        } else {
            prefs.edit().remove(KEY_LANGUAGE).apply()
        }
    }
```

- [ ] **Step 3: 构建验证**

```bash
cd C:/Users/Administrator/DeepSeekBalance
"$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain compileDebugKotlin --no-daemon 2>&1
```

预期: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/data/repository/WidgetPrefs.kt
git commit -m "feat: add language preference to WidgetPrefs"
```

---

### Task 2: 新增语言相关字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Produces: `R.string.settings_language`, `R.string.settings_language_desc`, `R.string.settings_language_dialog_title`, `R.string.settings_language_system`
- Consumes: `R.string.home_cancel`, `R.string.settings_confirm` (已存在，复用)

- [ ] **Step 1: 中文 strings.xml 新增 4 条**

在 `app/src/main/res/values/strings.xml` 中（现有 settings 字符串区域附近），插入：

```xml
<string name="settings_language">语言 / Language</string>
<string name="settings_language_desc">切换应用显示语言</string>
<string name="settings_language_dialog_title">选择语言</string>
<string name="settings_language_system">跟随系统</string>
```

- [ ] **Step 2: 英文 strings.xml 新增 4 条**

在 `app/src/main/res/values-en/strings.xml` 中对应位置，插入：

```xml
<string name="settings_language">Language</string>
<string name="settings_language_desc">Switch app display language</string>
<string name="settings_language_dialog_title">Select Language</string>
<string name="settings_language_system">Follow System</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: add language switching string resources"
```

---

### Task 3: DeepSeekApp — 启动时恢复语言偏好

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt`

**Interfaces:**
- Consumes: `WidgetPrefs.language: String?` (from Task 1)
- Uses: `androidx.appcompat.app.AppCompatDelegate`, `androidx.core.os.LocaleListCompat`

- [ ] **Step 1: 在 onCreate() 末尾添加语言恢复逻辑**

在 `DeepSeekApp.kt` 的 `onCreate()` 方法中，`CrashLogger.breadcrumb("App", "onCreate complete")` 之前，插入：

```kotlin
// 恢复用户语言偏好（未设置则跟随系统）
val savedLanguage = WidgetPrefs(this).language
if (savedLanguage != null) {
    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
        androidx.core.os.LocaleListCompat.forLanguageTags(savedLanguage)
    )
    CrashLogger.breadcrumb("App", "Locale restored: $savedLanguage")
}
```

- [ ] **Step 2: 构建验证**

```bash
cd C:/Users/Administrator/DeepSeekBalance
"$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain compileDebugKotlin --no-daemon 2>&1
```

预期: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/DeepSeekApp.kt
git commit -m "feat: restore language preference on app startup"
```

---

### Task 4: SettingsScreen — 语言导航卡片 + 选择 Dialog

**Files:**
- Modify: `app/src/main/java/com/balancesentinel/app/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Consumes: `WidgetPrefs.language: String?` (from Task 1)
- Consumes: `R.string.settings_language`, `R.string.settings_language_desc`, `R.string.settings_language_dialog_title`, `R.string.settings_language_system` (from Task 2)
- Consumes: `R.string.home_cancel`, `R.string.settings_confirm` (existing)
- Uses: `androidx.appcompat.app.AppCompatDelegate`, `androidx.core.os.LocaleListCompat`

- [ ] **Step 1: 新增 import 语句**

在 `SettingsScreen.kt` 的 import 区域末尾，添加：

```kotlin
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.balancesentinel.app.data.repository.WidgetPrefs
```

- [ ] **Step 2: 新增语言选择 Dialog Composable**

在文件末尾（在最后一个 `}` 之前），添加：

```kotlin
// ═══════════════════════════════════════════════════════════
// 语言选择 Dialog
// ═══════════════════════════════════════════════════════════

@Composable
private fun LanguageDialog(
    currentLanguage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    val languageOptions = remember {
        listOf(
            null to stringResource(R.string.settings_language_system),
            "zh" to "中文",
            "en" to "English"
        )
    }
    var selected by remember { mutableStateOf(currentLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column {
                languageOptions.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = code }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == code,
                            onClick = { selected = code }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel))
            }
        }
    )
}
```

- [ ] **Step 3: 在 SettingsMainPage 中新增语言导航卡片**

在 `SettingsMainPage` 的 `Column` 中，第 5 张卡片（「关于」）之前，插入：

```kotlin
// 5. 语言切换
val context = LocalContext.current
val widgetPrefs = remember { WidgetPrefs(context) }
val currentLang = widgetPrefs.language
var showLanguageDialog by remember { mutableStateOf(false) }

val languageLabel = when (currentLang) {
    "zh" -> "中文"
    "en" -> "English"
    else -> stringResource(R.string.settings_language_system)
}

SettingsNavCard(
    icon = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
    title = stringResource(R.string.settings_language),
    description = languageLabel,
    onClick = { showLanguageDialog = true }
)

if (showLanguageDialog) {
    LanguageDialog(
        currentLanguage = currentLang,
        onDismiss = { showLanguageDialog = false },
        onConfirm = { selected ->
            widgetPrefs.language = selected
            if (selected != null) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(selected)
                )
            } else {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.getEmptyLocaleList()
                )
            }
            showLanguageDialog = false
        }
    )
}
```

注意：`context` 变量可能已在 `SettingsMainPage` 的 lambda 中定义（第 124 行已有 `val context = LocalContext.current`），需删除此处重复的 `val context = LocalContext.current`，只保留 `val widgetPrefs = remember { WidgetPrefs(context) }`。

- [ ] **Step 4: 构建验证**

```bash
cd C:/Users/Administrator/DeepSeekBalance
"$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon 2>&1
```

预期: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/balancesentinel/app/ui/screen/SettingsScreen.kt
git commit -m "feat: add language switching card and dialog to settings"
```
