# 数据安全一致性审计报告

日期：2026-07-05
审计范围：所有源码、依赖、数据流

## 第三方依赖审查

| 依赖 | 用途 | 数据传输 | 风险 |
|------|------|----------|------|
| OkHttp 4.x | HTTP 客户端 | 仅 api.deepseek.com | 无 |
| kotlinx-serialization-json | JSON 序列化 | 无网络传输 | 无 |
| AndroidX (core, activity, lifecycle, compose) | Jetpack 库 | 无网络传输 | 无 |
| Compose (UI, Material3, Icons) | UI 框架 | 无网络传输 | 无 |
| security-crypto | EncryptedSharedPreferences | 无网络传输 | 无 |
| JUnit, MockK, Robolectric | 测试 | 仅测试环境 | 无 |

**结论：零分析 SDK，零广告 SDK，零追踪 SDK。**

## 隐式数据外传扫描

| 检查项 | 结果 |
|--------|------|
| WebView | 无 |
| 自定义 URL Scheme | 无 |
| 隐式 Intent 携带敏感数据 | 无 |
| 第三方 HTTP 请求（除 api.deepseek.com） | 无 |
| 剪贴板上传 | 无 |
| 后台网络请求 | 仅 api.deepseek.com |

**结论：所有网络请求仅发往 api.deepseek.com，零数据外传。**

## 数据存储审查

| 数据类型 | 存储方式 | 加密 |
|----------|----------|------|
| API Key | EncryptedSharedPreferences (AES-256) | 是 |
| 余额缓存 | SharedPreferences (明文) | 否 |
| 刷新日志 | SharedPreferences (明文) | 否 |
| 崩溃日志 | 本地文件 | 否 |
| 日摘要 | SharedPreferences (明文) | 否 |

**结论：API Key 加密存储，其他数据虽明文但仅存本地。**

## 扫描详情

### sendBroadcast 使用（全部为内部 Widget 更新广播）
- `BalanceRefreshService.kt:378` — 刷新完成后通知 Widget 更新
- `HomeViewModel.kt:423` — 手动刷新后通知 Widget 更新

### startActivity 使用（全部为系统设置跳转）
- `SettingsScreen.kt:430` — 跳转系统电池优化设置
- `BatteryOptimizationHelper.kt:72` — 请求忽略电池优化

### 网络安全配置
- `network_security_config.xml` — 强制证书固定，仅信任 api.deepseek.com 的真实证书，拒绝 MITM 代理
