# Play Console 权限声明 — 钱包哨兵

提交到 Google Play Console 的权限声明表单内容。复制到「应用内容 → 敏感应用权限」和「应用内容 → 特殊应用访问权限」页面。

---

## 一、所有权限清单

| 权限 | 类型 | 用途 |
|---|---|---|
| `INTERNET` | 普通 | 调用 AI 供应商 API 查询余额 |
| `FOREGROUND_SERVICE` | 敏感 | 后台持续刷新余额 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 敏感 | 前台服务类型：网络数据同步 |
| `WAKE_LOCK` | 普通 | 确保后台刷新不被 CPU 休眠打断 |
| `POST_NOTIFICATIONS` | 运行时 | 余额不足时推送通知（Android 13+） |
| `RECEIVE_BOOT_COMPLETED` | 普通 | 设备重启后自动恢复后台刷新 |
| `SCHEDULE_EXACT_ALARM` | 特殊应用访问 | 用户授权后恢复保留通知和监控计划 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 特殊应用访问 | 引导用户打开电池优化豁免设置 |
| `REQUEST_INSTALL_PACKAGES` | 特殊应用访问 | 用户确认后安装 GitHub Release 更新 |

**上架不需要额外声明的权限（普通权限）：** INTERNET、WAKE_LOCK、RECEIVE_BOOT_COMPLETED

**需要 Play Console 声明的：**
- 前台服务（FOREGROUND_SERVICE_DATA_SYNC）
- 精确闹钟（SCHEDULE_EXACT_ALARM，按用户授权启用）
- 电池优化豁免引导（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
- APK 安装意图（REQUEST_INSTALL_PACKAGES）

---

## 二、前台服务声明

> Play Console 路径：应用内容 → 前台服务权限 → 添加声明

### 服务类型

**dataSync**

### 核心用途（英文，250 字符以内）

```
The app needs a persistent foreground service to periodically
fetch AI provider API balance data at user-configured intervals
(1-60 minutes). This data powers balance alerts, desktop
widgets, and consumption insights. Without a foreground
service, Android would kill the background process, causing
missed refresh cycles and delayed low-balance alerts.

The foreground notification clearly shows the current refresh
status so users always know the service is active.
```

### 核心用途（中文，供参考）

```
应用需要前台服务以按用户设定的间隔（1-60 分钟）定期获取
AI 供应商 API 余额数据。该数据驱动余额预警、桌面小组件和
消耗洞察。没有前台服务，Android 会杀死后台进程，导致刷新
周期丢失和余额预警延迟。

前台通知清晰显示当前刷新状态，用户始终知道服务正在运行。
```

### 视频演示

需要录制一段 30 秒以内的视频，展示：
1. 应用中启用后台刷新的操作
2. 前台通知出现在通知栏
3. 通知内容显示当前刷新状态

### 用户可见的通知文案

应用前台通知在代码中的实际内容：
- **标题：** 「钱包哨兵服务运行中」
- **内容：** 「正在监控 X 个账户的余额」

### 为什么不能使用更轻量的替代方案（WorkManager / AlarmManager）

WorkManager 的最小间隔是 15 分钟且无法保证准时执行（受 Doze 模式影响显著延迟）。用户需要在 1-60
分钟内获取实时余额数据，WorkManager 无法满足此精度要求。

AlarmManager 单独使用可以被系统冻结（特别是国产 ROM），前台服务是唯一确保进程不被随时杀死的机制。

---

## 三、精确闹钟与后台恢复声明

> Play Console 路径：应用内容 → 特殊应用访问权限 → 闹钟和提醒 → 添加声明


### 核心功能描述（英文，250 字符以内）

```
The app uses SCHEDULE_EXACT_ALARM only when the user grants it, to re-publish the
retained monitoring notification and schedule the next recovery check. If access
is unavailable, it falls back to an inexact idle-allowed alarm. The receiver never
force-starts a background dataSync service.
```

### 核心功能描述（中文，供参考）

```
应用在用户授权后使用 SCHEDULE_EXACT_ALARM 恢复保留的监控通知并安排下一次
检查；未授权时使用非精确的 idle 闹钟作为回退。该接收器不会绕过系统限制
强行启动后台 dataSync 服务。用户主动监控仍由前台服务承载，普通维护和降级
任务使用 WorkManager。
```

### 当前调度策略

用户发起的短间隔刷新由有界前台 dataSync 会话承载；WorkManager 负责周期性
降级、维护和更新下载。KeepAliveReceiver 只恢复缓存通知，不执行余额网络刷新。

- WorkManager：系统可能延迟后台任务，界面会明确显示降级状态。

- 精确闹钟：默认每 120 秒检查一次，部分受影响的 OEM 使用 90 秒；Android
  Doze、厂商后台策略、强行停止和系统配额仍可能延迟或阻止唤醒。

前台 dataSync 会话受 Android 15 滚动预算约束，超限后立即停止；应用不会通过
KeepAliveReceiver 自动重启被系统停止的前台服务。

### 用户如何控制此权限

用户在「设置 → 权限与后台」中查看通知、精确闹钟和电池优化状态，并可打开系统
授权页；「设置 → 桌面小组件设置 → 刷新间隔」用于配置刷新频率。

---

## 四、Data Safety 表单

> Play Console 路径：应用内容 → 数据安全

### 是否收集用户数据

**是** — 应用收集以下用户数据：

| 数据类型 | 用途 | 是否加密 | 用户可否删除 |
|---|---|---|---|
| API Key（用户 AI 供应商 API 密钥） | 查询余额 | 是（Android Keystore 加密存储） | 是（应用内删除账户） |
| 用户配置的账户标签 | 账户标识 | 否 | 是（应用内删除账户） |
| 余额历史记录 | 消耗分析与趋势图表 | 否 | 是（数据管理页清除） |
| 刷新日志 | 故障排查 | 否 | 是（数据管理页清除） |

### 数据传输

- 用户 API Key 和自定义凭据仅通过 HTTPS 发送到用户配置的供应商端点（包括官方 API、自定义平台和 NewAPI）
- 无第三方数据共享
- 无广告 SDK

### 数据保留

所有数据仅存储在设备本地。删除账户时同时删除关联的 API Key、余额记录、日志和缓存。无远程服务器存储。

---

## 五、提交清单

上架前逐项确认：

- [ ] 「前台服务权限」声明已提交（dataSync 类型）
- [ ] 「特殊应用访问权限 → 闹钟」声明已提交
- [ ] 「数据安全」表单已填写
- [ ] 隐私政策 URL 已填写（可链接到 GitHub 上 `PRIVACY_POLICY.md`）
- [ ] 应用截图（手机 + 平板，各 2-8 张）
- [ ] 应用图标（512×512 PNG）
- [ ] 功能图形（1024×500 PNG）
- [ ] 内容分级问卷已完成
- [ ] 目标受众和内容已声明
- [ ] 应用类别：财务 / 工具
- [ ] 联系信息（邮箱 + 网址）已填写

---

## 六、更新记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-20 | 多供应商支持 | v1.3.1 新增13个内置 AI 供应商支持 |
| 2026-07-18 | 更新文档 | v1.2.1 文档全面审查更新 |
| 2026-07-05 | 初始编写 | v1.0.0 上线前 Play Console 权限申报 |
