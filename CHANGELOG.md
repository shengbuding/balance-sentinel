# Changelog

所有用户可见的发布变化记录在此。版本号遵循 `vMAJOR.MINOR.PATCH`，详细审查证据见 `TEST_REPORT.md` 与 `docs/claude-code-review-handoff.md`。

## [1.5.1] - 2026-08-20

### Added

- 新增 OpenCode Go 内置供应商，支持 5 小时、每周、每月订阅窗口及各自重置时间。
- 洞察和桌面小组件支持订阅窗口选择、剩余率趋势、刷新倒计时、时间刻度和长钱包名滚动显示。
- 新增 OpenAI API 与 ChatGPT 个人订阅额度的接口边界说明，并提供官方 Usage Dashboard 入口说明。

### Changed

- 订阅图表统一以剩余率呈现：100% 为满值，使用量增加时曲线向 0% 下降。
- 全部账户订阅历史按账户保留、时间排序并合并，支持稀疏历史和每日汇总；Room 归档使用订阅专用的百分比消耗语义。
- 订阅账户的刷新时间、下次刷新时间和历史日汇总展示适配不同账户筛选状态。

### Fixed

- 修复全部账户订阅图表因不同账户刷新时间交错而出现大面积蓝色折线的问题。
- 修复选择单个账户后其他同币种账户筛选项消失的问题，并隔离单账户小组件历史。
- 修复订阅窗口在跨日归档、连续性补点和小组件 sparkline 中方向相反或被误判为金额充值的问题。

### Verification

- Debug/Release JVM：各 1,531 tests，0 failures，0 errors，3 skipped。
- Debug/Release lint：0 errors；Kover verify 通过。
- Debug/Release APK：本地构建成功；正式签名 APK 由 `v1.5.1` tag workflow 生成并上传。

## [1.5.0] - 2026-08-18

### Added

- 使用精确闹钟、WorkManager 和前台服务恢复后台监控与常驻通知。
- 首次使用和设置页提供通知、精确闹钟、电池优化授权状态与再次引导入口。
- 自定义账户支持 Usage Script 展示字段、字段名称、余额计算字段和 NewAPI 预设。
- Insights 支持按当前币种和账户资格展示跨账户趋势，并修复全账户图表起点与稀疏数据合并。
- 通知栏钱包支持总额虚拟行、账户排序和删除账户后的顺序重映射。
- 预留受限的上位机配置/历史数据同步契约，包含大小、摘要、有效期和导入顺序策略；当前未实现网络传输。

### Changed

- 充值/消耗推断统一覆盖内置、自定义和脚本账户，并保留真实零余额。
- 设置、预警、权限引导和自定义账户编辑界面重新组织，窄屏下账户卡片列保持对齐。
- Console 登出和清除登录状态同时清理本地会话、Cookie 与 WebStorage。
- Room v6、旧设置迁移和配置导入统一处理账户过滤与通知总额顺序。

### Fixed

- 修复后台限制或服务重启后通知栏消失、精确闹钟权限回退和用户停止竞态。
- 修复单一美元账户在洞察中错误出现第二币种、历史币种覆盖当前余额的问题。
- 修复全账户图表从零绘制、末尾垂直跳线、充值检测仅支持 DeepSeek 的问题。
- 修复自定义脚本嵌套 JSON 路径、额外凭据回退、余额字段投影和 Console 清理状态问题。

### Verification

- Debug/Release JVM：各 1,486 tests，0 failures，0 errors，3 skipped。
- Debug/Release lint：0 errors；Kover verify 通过。
- Release APK：`com.balancesentinel.app`，`versionName=v1.5.0`。

## [1.4.2] - 2026-07-30

- 全面代码审查与安全加固版本。历史验证记录见 `PRODUCTION_AUDIT.md` 和 `RELEASE_REVIEW_REPORT.md`。
