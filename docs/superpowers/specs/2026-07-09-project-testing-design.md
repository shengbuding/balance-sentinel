# 钱包哨兵全项目测试设计

> **目标：** 从当前 15.8% 行覆盖率提升到 60%+，覆盖所有核心逻辑和关键边界。

## 当前状态

- 24 单元测试 + 4 仪表测试，全部通过
- 行覆盖率 15.8%，指令 11.4%
- 引擎层 85.3%（较好），其余大面积空白

## 四层测试策略

### 第 1 层：纯逻辑（引擎 + 模型 + 工具）
- 目标 85%+ 行覆盖
- 标准 JUnit + 参数化测试
- 优先级模块：RecordAggregator 边界、DailyEngine 跨天、IntradayEngine 趋势、FormatUtils

### 第 2 层：Store + Repository（数据持久化）
- 目标 70%+ 行覆盖
- Robolectric + MockK（EncryptedSharedPreferences 需 mock）
- 边界：空数据、超限裁剪、JSON 解析失败、并发去重
- 优先级：ApiKeyManager、NotificationHelper、MidnightScheduler、LogExporter

### 第 3 层：ViewModel
- 目标 80%+ 行覆盖
- MockK 模拟所有依赖，验证状态流转
- 优先级：HomeViewModel（最复杂）、InsightsViewModel

### 第 4 层：Service + Receiver + Screen
- Service：抽取纯逻辑，Service 本体做集成验证
- Receiver：独立测 onReceive 分支
- Screen：Compose 仪表测试或至少 smoke test

## 非目标

- 不追求 100% 覆盖
- 不测纯样板代码（CustomIcons、Theme、CrashLogger、WidgetProvider 静态注册类）
- 不重测已有充分覆盖的模块

## 执行顺序

1. 第 1 层补齐（引擎/工具边界用例）
2. 第 2 层补齐（未测的 Store/Repository）
3. 第 3 层补齐（ViewModel）
4. 第 4 层关键路径（Service + Receiver + Screen smoke）
