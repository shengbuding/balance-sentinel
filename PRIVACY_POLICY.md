# 钱包哨兵 — 隐私政策

**最后更新日期：2026 年 7 月 5 日**

## 概述

钱包哨兵（Balance Sentinel）是一款 DeepSeek API 余额监控工具。我们高度重视您的隐私和数据安全。

## 我们收集的信息

### 您在设备上输入的数据
- **DeepSeek API Key**：用于查询您的 DeepSeek 账户余额和使用量。API Key 仅存储在本设备的加密存储（Android EncryptedSharedPreferences）中，不会上传到任何第三方服务器。

### 自动采集的数据
钱包哨兵**不会**自动采集以下任何信息：
- 个人身份信息（姓名、邮箱、电话等）
- 设备标识符（IMEI、Android ID、广告 ID 等）
- 位置信息
- 联系人、通话记录、短信
- 已安装应用列表
- 浏览历史或使用行为

## 数据使用方式

您的 API Key 仅用于向 DeepSeek 官方 API（api.deepseek.com）发起余额查询请求。所有数据传输通过 HTTPS 加密。

查询到的余额数据仅存储在设备本地，用于：
- 在 App 首页展示余额
- 在桌面小组件展示余额
- 生成余额变化趋势洞察
- 在余额低于阈值时发送本地通知

## 数据共享

我们**不会**将您的任何数据分享、出售或传输给任何第三方。

## 数据安全

- API Key 使用 Android EncryptedSharedPreferences（AES-256 加密）存储
- 所有网络请求通过 HTTPS 加密传输
- 应用备份已禁用（`allowBackup=false`），防止通过 adb backup 或 Google Backup 泄露数据
- 日志输出自动脱敏 API Key（替代为 `sk-***`）

## 数据删除

您可以随时在 App 的「数据管理」页面删除所有存储的数据（包括 API Key、余额记录、日志）。删除操作不可撤销。

卸载 App 会自动清除所有本地数据。

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 查询 DeepSeek API 余额 |
| FOREGROUND_SERVICE | 后台定时刷新，保持小组件数据更新 |
| POST_NOTIFICATIONS | 余额告警通知 |
| RECEIVE_BOOT_COMPLETED | 开机后自动恢复后台刷新 |
| SCHEDULE_EXACT_ALARM | 精确定时刷新 |
| WAKE_LOCK | 刷新期间防止 CPU 休眠 |

## 第三方服务

本应用仅连接 DeepSeek 官方 API（api.deepseek.com）。不集成任何第三方分析、广告或追踪 SDK。

## 儿童隐私

本应用不面向 13 岁以下儿童，不会故意收集儿童的个人信息。

## 政策更新

隐私政策如有更新，将在 App 内提示。建议定期查看本页面。

## 联系我们

如有隐私相关问题，请通过 GitHub Issues 联系：
[https://github.com/username/balancesentinel/issues](https://github.com/username/balancesentinel/issues)

---
*本隐私政策适用于钱包哨兵 Android 应用（包名：com.balancesentinel.app）。*
