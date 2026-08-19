# OpenAI 额度接口说明

更新日期：2026-08-20

## 结论

当前不能把个人 ChatGPT Plus/Pro/Codex 订阅额度作为钱包哨兵的正式预设供应商。OpenAI 没有公开面向个人订阅的稳定 REST API，用于返回 5 小时、每周、每月剩余百分比和重置时间。

项目中的 `OPENAI` 仍表示 OpenAI Platform API：用户输入 API Key，应用访问 `https://api.openai.com/v1`，按 API 用量计费。它与 ChatGPT 订阅是两套独立的认证和计费体系，不能把 API Key 的用量或费用解释成 Plus/Pro/Codex 剩余额度。

## 可用接口范围

| 数据 | 是否可通过官方 API 获取 | 说明 |
|---|---|---|
| ChatGPT Plus/Pro/Codex 个人订阅剩余百分比 | 否 | 官方目前引导用户使用 Usage Dashboard 或 Codex CLI `/status`。 |
| ChatGPT 个人订阅重置时间 | 否 | 没有公开第三方客户端可依赖的 REST 合约。 |
| Platform 组织历史 Token 用量 | 是 | 使用组织级 Usage 接口和相应权限；这是历史用量统计。 |
| Platform 组织费用/成本 | 是 | 使用组织级 Costs 接口和相应权限；不是订阅余额。 |
| Business/Enterprise Codex 工作区聚合统计 | 有条件 | 需要匹配工作区的组织凭据，面向管理员报表，不适用于个人订阅。 |

## 安全边界

应用不应采集或复用以下凭据来读取订阅页面：

- ChatGPT Cookie、CSRF 或浏览器 localStorage；
- ChatGPT OAuth/session token 或 Codex `auth.json`；
- Codex access token。

这些凭据不是通用的 Android 余额 API 凭据，泄露后可能代表用户执行受信任的 Codex 自动化；网页内部接口也可能随时变化。应用同样不调用未公开的 `chatgpt.com/backend-api` 等网页接口。

用户需要查看个人订阅额度时，应打开官方页面：

<https://chatgpt.com/codex/settings/usage>

## 后续设计

如果 OpenAI 将来发布个人订阅额度 API，应新增独立的 `OPENAI_SUBSCRIPTION` 供应商类型，不复用现有 `OPENAI`。新增实现必须单独定义认证、权限、刷新策略、隐私提示和失败降级，并为 5 小时、每周、每月窗口映射到现有百分比额度模型。

官方参考：

- [Codex Pricing](https://developers.openai.com/codex/pricing)
- [Codex Authentication](https://developers.openai.com/codex/auth)
- [Codex Access Tokens](https://developers.openai.com/codex/enterprise/access-tokens)
- [Codex Analytics API](https://developers.openai.com/codex/enterprise/analytics-api)
- [Platform Usage API](https://platform.openai.com/docs/api-reference/usage)
