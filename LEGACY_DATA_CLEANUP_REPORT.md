# 旧版数据清理报告

**日期**：2026年7月20日

---

## 🔍 问题发现

### 故障现象
通知栏排序功能序号显示异常，只选中2个钱包但序号显示到5。

### 根本原因
ID迁移（4字节→8字节）后，排序列表中同时存在新旧ID：
- 新版16位ID：`23eb4454041c2235_CNY`
- 旧版8位ID：`23eb4454_CNY`
- 错误ID：`ef1fOf79_USD`

---

## 🔧 修复措施

### 1. 通知栏排序列表清理
- 文件：`WidgetPrefs.kt`
- 方法：`cleanupInvalidEntries()`
- 功能：只保留16位ID的有效条目

### 2. 全局旧版数据清理
- 文件：`WidgetPrefs.kt`
- 方法：`cleanupLegacyIdData()`
- 功能：清理所有使用8位ID的旧数据

### 3. 清理的数据类型
- 预警启用状态 (`alert_enabled_*`)
- 异动提醒状态 (`change_alert_enabled_*`)
- 上次预警余额 (`last_alerted_balance_*`)
- 余额变化锚点 (`previous_balance_*`)
- 余额变化时间 (`previous_balance_time_*`)
- 上次异动余额 (`last_change_alerted_balance_*`)
- 上次异动时间 (`last_change_alerted_time_*`)
- 暂停状态 (`snooze_until_*`)
- 通知栏选中状态 (`notification_selected_*`)

---

## 📊 排查结果

### 需要清理的数据

| 数据类型 | 存储格式 | 受影响 |
|----------|----------|--------|
| 预警开关 | `alert_enabled_${accountId}_${currency}` | ✅ |
| 异动开关 | `change_alert_enabled_${accountId}_${currency}` | ✅ |
| 预警记录 | `last_alerted_balance_${accountId}` | ✅ |
| 变化锚点 | `previous_balance_${accountId}` | ✅ |
| 变化时间 | `previous_balance_time_${accountId}` | ✅ |
| 异动记录 | `last_change_alerted_balance_${accountId}` | ✅ |
| 异动时间 | `last_change_alerted_time_${accountId}` | ✅ |
| 暂停状态 | `snooze_until_${accountId}` | ✅ |
| 通知选中 | `notification_selected_${accountId}_${currency}` | ✅ |
| 排序列表 | `notification_wallet_order` (JSON) | ✅ |

### 已迁移的数据

| 数据类型 | 存储格式 | 迁移状态 |
|----------|----------|----------|
| 原始记录 | RawRecordStore | ✅ 已迁移 |
| 日汇总 | DailySummaryStore | ✅ 已迁移 |
| Widget数据 | BalanceWidgetDataStore | ✅ 已迁移 |

---

## ✅ 修复验证

### 测试场景
1. 全未选中状态，选中总余额 → 序号 #1 ✅
2. 全未选中状态，选中钱包一 → 序号 #1 ✅
3. 选中总余额后，选中钱包一 → 序号 #2 ✅

### 清理效果
- 清理前：排序列表包含6个条目（含重复和无效ID）
- 清理后：排序列表只保留有效条目

---

## 📋 预防措施

### 1. 数据迁移规范
- 迁移时同时更新所有关联数据
- 迁移后清理旧格式数据
- 添加数据验证逻辑

### 2. ID格式验证
- 新版ID：16位hex字符串
- 旧版ID：8位hex字符串
- 验证方法：检查ID长度

### 3. 启动时清理
- 应用启动时自动清理无效数据
- 记录清理日志便于排查

---

## 📁 相关文件

| 文件 | 修改内容 |
|------|----------|
| `WidgetPrefs.kt` | 添加清理方法 |
| `DeepSeekApp.kt` | 启动时调用清理 |

---

**报告生成时间**：2026年7月20日
