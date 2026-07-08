# 语言切换功能 — 设计文档

日期: 2026-07-08
状态: 已实现 (v1.2.0+)

---

## 1. 概述

在设置页面添加中英双语切换，使用 Android 平台 `LocaleManager.applicationLocales` API（minSdk=35，ComponentActivity 原生支持），即时生效无需重启。架构天然支持日后扩展更多语种（只需新增 values 资源文件夹 + 一行枚举值）。

## 2. 用户行为

- **默认**：跟随系统语言（首次安装无偏好时）
- **切换**：设置主页 → 「语言 / Language」卡片 → 弹出单选 Dialog → 选语言点确定 → 即时生效
- **恢复**：切换后写入 SharedPreferences，下次启动自动恢复

## 3. 架构

```
用户点击语言选项
    │
    ▼
WidgetPrefs.setLanguage("zh" | "en")
    │
    ▼
localeManager.applicationLocales = LocaleList.forLanguageTags(...)
    │
    ▼
Compose 自动重组 → 所有 stringResource() 立即切换
```

**启动恢复**：`DeepSeekApp.onCreate()` 末尾读取 pref，调用 `setApplicationLocales()` 恢复。

## 4. 改动文件

| 文件 | 改动 | 行数估算 |
|------|------|---------|
| `data/repository/WidgetPrefs.kt` | 新增 `getLanguage()` / `setLanguage()` | ~10 |
| `DeepSeekApp.kt` | `onCreate()` 末尾恢复语言偏好 | ~7 |
| `ui/screen/SettingsScreen.kt` | 主页新增导航卡片 + 语言选择 Dialog | ~80 |
| `values/strings.xml` | 新增 5 条语言相关字符串 | ~10 |
| `values-en/strings.xml` | 同上（英文版） | ~10 |

不涉及新文件、新依赖、新测试文件（语言切换是框架行为，单元测试无意义；UI 测试超出当前范围）。

## 5. 存储

- **Key**: `pref_language`
- **Value**: `"zh"` / `"en"` / `null`（未设置）
- **位置**: WidgetPrefs 管理的 SharedPreferences（与现有设置同文件）
- `null` = 未选择 = 跟随系统

## 6. UI

### 6.1 导航卡片（设置主页，在「关于」之前）

右侧副标题显示当前语言名称（"中文" / "English"），跟随系统时显示 "跟随系统"。

### 6.2 语言选择 Dialog

- 单选列表：中文 / English
- 打开时自动选中当前语言
- 确定 → 写入 pref + 调 `setApplicationLocales()` + 关闭
- 取消 / 外部点击 → 无变更关闭
- 选中的已是当前语言 → 确定无实际操作

## 7. 数据安全

所有持久化存储字段经逐项审查，均为语言无关（数字/JSON/enum 代码）。`RefreshLogEntry.message` 字段当前为硬编码中文，不受语言切换影响。语言切换不会造成任何数据冲突或丢失。

## 8. 扩展性

添加第三种语言仅需：

1. 创建 `values-XX/strings.xml`（翻译所有字符串）
2. 在语言选择 Dialog 的选项列表中加一行 `"XX" to "语言名"`

代码结构零改动。

## 9. 决策记录

| 决策 | 结论 |
|------|------|
| 切换方式 | 即时生效（LocaleManager），不提示重启 |
| 默认行为 | 跟随系统语言 |
| 入口位置 | 设置主页新增导航卡片（「关于」之前） |
| 底层 API | LocaleManager.applicationLocales（平台 API，minSdk=35） |
| 通知渠道 | 首次创建后不随语言切换更新（Android 限制，影响可忽略） |
