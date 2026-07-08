# 钱包哨兵 — 隐私政策 / Wallet Sentinel — Privacy Policy

**最后更新日期 / Last Updated：2026 年 7 月 8 日 / July 8, 2026**

---

## 概述 / Overview

钱包哨兵（Balance Sentinel）是一款 DeepSeek API 余额监控工具。我们高度重视您的隐私和数据安全。

Wallet Sentinel (Balance Sentinel) is a DeepSeek API balance monitoring tool. We take your privacy and data security seriously.

---

## 我们收集的信息 / Information We Collect

### 您在设备上输入的数据 / Data You Enter on Your Device
- **DeepSeek API Key**：用于查询您的 DeepSeek 账户余额和使用量。API Key 仅存储在本设备的加密存储（Android EncryptedSharedPreferences）中，不会上传到任何第三方服务器。
- **DeepSeek API Key**: Used to query your DeepSeek account balance and usage. The API Key is stored only in encrypted storage on this device (Android EncryptedSharedPreferences) and is never uploaded to any third-party server.

### 自动采集的数据 / Automatically Collected Data
钱包哨兵**不会**自动采集以下任何信息 / Wallet Sentinel does **NOT** automatically collect any of the following:
- 个人身份信息（姓名、邮箱、电话等）/ Personal identity information (name, email, phone, etc.)
- 设备标识符（IMEI、Android ID、广告 ID 等）/ Device identifiers (IMEI, Android ID, advertising ID, etc.)
- 位置信息 / Location information
- 联系人、通话记录、短信 / Contacts, call logs, SMS
- 已安装应用列表 / Installed app list
- 浏览历史或使用行为 / Browsing history or usage behavior

---

## 数据使用方式 / How We Use Data

您的 API Key 仅用于向 DeepSeek 官方 API（api.deepseek.com）发起余额查询请求。所有数据传输通过 HTTPS 加密。

Your API Key is used exclusively to query the official DeepSeek API (api.deepseek.com) for balance information. All data transmission is encrypted via HTTPS.

查询到的余额数据仅存储在设备本地，用于 / Retrieved balance data is stored only locally on your device for:
- 在 App 首页展示余额 / Displaying balance on the app home screen
- 在桌面小组件展示余额 / Displaying balance on desktop widgets
- 生成余额变化趋势洞察 / Generating balance trend insights
- 在余额低于阈值时发送本地通知 / Sending local notifications when balance drops below thresholds

---

## 数据共享 / Data Sharing

我们**不会**将您的任何数据分享、出售或传输给任何第三方。

We do **NOT** share, sell, or transmit any of your data to any third party.

---

## 数据安全 / Data Security

- API Key 使用 Android EncryptedSharedPreferences（AES-256 加密）存储 / API Key stored with Android EncryptedSharedPreferences (AES-256 encryption)
- 所有网络请求通过 HTTPS 加密传输 / All network requests encrypted via HTTPS
- 应用备份已禁用（`allowBackup=false`），防止通过 adb backup 或 Google Backup 泄露数据 / App backup disabled (`allowBackup=false`) to prevent data leaks via adb backup or Google Backup
- 日志输出自动脱敏 API Key（替代为 `sk-***`；崩溃日志同样经过脱敏处理）/ API Key auto-redacted in log output (replaced with `sk-***`; crash logs are also sanitized)

---

## 数据删除 / Data Deletion

您可以随时在 App 的「数据管理」页面删除所有存储的数据（包括 API Key、余额记录、日志）。删除操作不可撤销。

You can delete all stored data (including API Key, balance records, and logs) at any time from the app's Data Management page. Deletion is irreversible.

卸载 App 会自动清除所有本地数据。 / Uninstalling the app automatically clears all local data.

---

## 权限说明 / Permissions

| 权限 / Permission | 用途 / Purpose |
|------|------|
| INTERNET | 查询 DeepSeek API 余额 / Query DeepSeek API balance |
| FOREGROUND_SERVICE | 后台定时刷新，保持小组件数据更新 / Background scheduled refresh, keep widget data updated |
| POST_NOTIFICATIONS | 余额告警通知 / Balance alert notifications |
| RECEIVE_BOOT_COMPLETED | 开机后自动恢复后台刷新 / Auto-restart background refresh after boot |
| SCHEDULE_EXACT_ALARM | 精确定时刷新 / Precise scheduled refresh |
| WAKE_LOCK | 刷新期间防止 CPU 休眠 / Prevent CPU sleep during refresh |

---

## 第三方服务 / Third-Party Services

本应用连接以下服务 / This app connects to the following services:
- DeepSeek 官方 API（api.deepseek.com）— 余额查询和用量统计 / Balance query and usage statistics
- GitHub API（api.github.com）— 检查应用更新 / Check for app updates

不集成任何第三方分析、广告或追踪 SDK。 / No third-party analytics, advertising, or tracking SDKs are integrated.

---

## 儿童隐私 / Children's Privacy

本应用不面向 13 岁以下儿童，不会故意收集儿童的个人信息。

This app is not directed at children under 13 and does not knowingly collect personal information from children.

---

## 政策更新 / Policy Updates

隐私政策如有更新，将在 App 内提示。建议定期查看本页面。

If the privacy policy is updated, you will be notified within the app. We recommend checking this page periodically.

---

## 联系我们 / Contact Us

如有隐私相关问题，请通过 GitHub Issues 联系 / For privacy-related inquiries, please contact us via GitHub Issues:
[https://github.com/shengbuding/balance-sentinel/issues](https://github.com/shengbuding/balance-sentinel/issues)

---
*本隐私政策适用于钱包哨兵 Android 应用（包名：com.balancesentinel.app）。*
*This privacy policy applies to the Wallet Sentinel Android app (package: com.balancesentinel.app).*
