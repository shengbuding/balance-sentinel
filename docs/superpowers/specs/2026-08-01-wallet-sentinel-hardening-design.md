# 钱包哨兵全量加固设计

**日期：** 2026-08-01
**基线：** `0e858065053f33b68a4f2173358ab97482f0c772`
**状态：** 已获用户确认

## 目标

本设计修复代码审查确认的凭据泄露、WebView 跨源注入、备份破坏性导入、脚本执行失控、刷新调度漂移、余额解析错误、历史数据丢失、多币种告警串号、账户生命周期竞态、Release 调试数据泄露、Console 会话失效及发布门禁失效。

完成后，主页、单账户刷新、前台服务和 Widget 使用同一条刷新与提交管线；所有外部数据在进入缓存、历史和告警前经过严格验证；涉及删除或凭据替换的操作均有显式的数据安全边界。

## 非目标

- 不在本次工作中全面迁移到 Room 或重写全部调度为 WorkManager。
- 不为没有公开、稳定余额 API 的模型供应商猜测余额端点。
- 不改变现有余额、历史和告警页面的主要交互结构。
- 不恢复或推断脱敏备份中已经不存在的凭据。

## 已确认的产品决策

1. 无凭据备份默认采用非破坏性合并。匹配本机账户时保留本机凭据并更新非敏感字段；无法匹配的脱敏账户跳过；默认流程绝不删除现有账户。
2. 只有包含完整凭据并经过用户明确确认的导入，才允许全量替换账户。
3. 自定义脚本默认只允许账户 `baseUrl` 同源 HTTPS 请求。额外域名必须逐项授权；明文 HTTP、环回、链路本地和私网地址始终禁止。
4. 导入的自定义脚本默认禁用，直至用户确认脚本请求的额外域名。
5. 仅具有已验证余额契约的供应商提供开箱即用查询。其他供应商必须配置自定义余额脚本，不再执行通用端点猜测。

## 总体架构

采用共享领域管线，而不是在各入口分别修补。

### RefreshCoordinator

`RefreshCoordinator` 是主页、单账户刷新、前台服务和 Widget 的唯一刷新入口。它负责：

- 为账户分配单调递增的请求代次；
- 取消或作废同一账户的旧请求；
- 调用 `AccountBalanceRefresher`；
- 仅允许仍为最新代次的结果进入 `RefreshResultCommitter`；
- 返回每账户的成功、失败、跳过和过期结果，不吞掉异常。

同一进程内所有入口共享同一个协调器实例。账户配置另带持久化 `revision`，因此即使请求来自不同生命周期对象，提交前也能检测删除或编辑。

### AccountBalanceRefresher

`AccountBalanceRefresher` 负责将 `AccountInfo` 转换为供应商配置、选择唯一供应商实现、执行网络请求并返回严格类型化结果。它不写缓存、历史、日志或告警。

返回数据必须满足：

- 必需金额字段存在；
- 金额可解析为有限数；
- 只有 API 明确返回的数值零才被接受为零余额；
- 币种和单位来自唯一供应商契约；
- 响应格式变化返回解析失败，不降级为假零。

### RefreshResultCommitter

`RefreshResultCommitter` 在同一进程锁内执行提交。提交前重新读取账户并比较 `id`、`revision` 和请求代次。任一不匹配即返回过期结果，不产生任何副作用。

有效结果按以下顺序提交：

1. 更新 Provider 缓存；
2. 更新 Widget 账户与币种缓存；
3. 批量写入原始历史记录；
4. 写刷新日志；
5. 更新用量快照；
6. 执行按账户与币种隔离的告警；
7. 通知 UI 与 Widget 重绘。

失败结果保留最后一次有效余额，不写历史、不触发告警，也不将缓存改为零。

## 账户身份与并发

`AccountInfo` 新增带默认值的 `revision: Long = 0`，保证旧 JSON 可反序列化。任何影响查询或展示的编辑都递增 revision。修改 API Key 时新账户获得新 ID；旧账户的飞行请求因账户不存在而无法提交。

API Key 修改需要迁移历史、摘要、用量和告警设置，并显式删除旧 Widget 缓存与 Provider 缓存。删除账户时也清理上述数据。缓存清理与保存使用同一个锁，避免读改写覆盖其他账户或币种。

手动全量刷新和单账户刷新共享相同提交语义。单账户刷新不再绕过历史、日志、用量和告警。

## 供应商与余额契约

每个内置余额供应商只有一个端点、认证方式、响应解析器和单位换算定义。预置脚本不得复制另一套解析逻辑。

初始原生支持矩阵为：

- DeepSeek
- StepFun
- SiliconFlow
- OpenRouter
- Novita
- ModelArk

Novita 的 `availableBalance` 单位换算只保留一处，并用固定响应 fixture 验证。具体换算以供应商契约测试为准，所有调用路径必须返回相同数值。

Moonshot、Doubao、Baichuan、Qwen、Zhipu、Wenxin、OpenAI、Anthropic、Gemini、Mistral、Cohere 和 Custom 不再串行尝试五个猜测端点。若没有内置余额契约，必须提供经过验证的自定义脚本。

新增和编辑账户界面根据 `ProviderConfigs.getConfigFields` 动态渲染字段，并完整传递 `extraCredentials`。密码字段不得出现在普通日志或无凭据备份中。

## 自定义脚本安全

### ScriptNetworkPolicy

脚本请求使用结构化 URL 解析，并在请求、每次重定向和 DNS 解析后重复校验：

- scheme 必须为 `https`；
- 默认 origin 必须与账户 `baseUrl` 完全一致；
- 额外 host 必须存在于该账户的已授权域名集合；
- 禁止 URL 用户信息和 IP 字面量绕过；除账户 `baseUrl` 已登记端口外，额外授权 origin 只允许 443；
- 禁止 localhost、环回、链路本地、私网、组播及未指定地址；
- 域名解析结果中只要包含被禁止地址，请求即失败；
- 重定向目标必须重新满足相同策略。

内置预置脚本使用代码内不可变 host 集合。导入脚本保存为禁用状态；导入预览只使用占位凭据，在受指令数和墙钟限制的 Rhino 沙箱中提取请求配置，绝不发起网络请求。能够确定的额外 origin 逐项展示，用户确认后才写入账户授权集合并启用；无法确定静态 origin 的脚本保持禁用并拒绝导入执行。

### Rhino 执行限制

每个 Rhino Context 设置非零 `instructionObserverThreshold`。观察器使用单调时钟检查截止时间，而不是比较单次回调的指令数。请求配置脚本和 extractor 分别拥有独立截止时间。

脚本同时在可取消的专用执行器中运行。到达墙钟限制后取消任务并关闭该次执行资源。HTTP timeout 只控制网络阶段，不能替代脚本执行限制。

执行超时、策略拒绝和解析失败返回类型化错误，不写任何余额副作用。

## 备份与导入

备份格式升级到 schema v2，并显式记录 `credentialsIncluded`。

无凭据导出执行以下处理：

- 脱敏 `apiKey`；
- 清空所有 `extraCredentials` 值；
- 移除自定义脚本及脚本授权域名；
- 保留账户 ID、标签、供应商类型和非敏感设置，以便与本机账户匹配。

`BackupImportPlanner` 先生成不可变导入计划，包含：匹配并更新、保留本机凭据、创建、跳过、冲突和删除数量。UI 展示预览后才允许应用。

默认合并模式：

- 匹配现有 ID 的脱敏账户保留本机 `apiKey`、`extraCredentials`、脚本和授权域名；
- 更新标签、供应商类型和允许导入的非敏感设置；
- 无法匹配且没有完整凭据的账户跳过；
- 不删除导入文件中缺失的本机账户。

全量替换模式仅在 `credentialsIncluded=true`、所有待创建账户具有完整必需凭据且用户二次确认时可用。旧 schema v1 文件按实际字段推断凭据完整性，但永远先进入预览。

## Console WebView 与会话

每个平台声明登录和 Dashboard 的允许 origin。WebView 主框架导航只允许这些 origin；其他 HTTP(S) 链接交给系统浏览器，非 HTTP(S) scheme 默认拒绝。

`localStorage` 仅在当前页面 origin 与目标 Dashboard origin 精确匹配时注入。Cookie 也只写入登记域名。平台页面跳转到未授权来源时不得注入任何会话数据。

会话读取统一通过有效性检查。超过 30 天的会话立即从加密存储删除并视为未登录；UI 不再使用 `session != null` 或硬编码永不过期判断。

登出时：

1. 删除目标平台的加密会话；
2. 删除目标 origin 的 WebStorage；
3. 清空运行时 CookieManager，防止未知或 HttpOnly Cookie 保留；
4. 其他平台仍可在下次打开时从各自有效的加密会话重新注入。

## 调度与服务健康

刷新心跳和服务存活心跳分离。`RefreshScheduler` 持久化 `expectedNextAt`、最近存活心跳和当前刷新状态。

Keepalive 仅在以下条件同时成立时判定服务失效：

- 当前时间超过 `expectedNextAt` 加明确容差；
- 最近存活心跳超过容差；
- 不处于服务启动宽限期或刷新中的有效截止时间内。

普通 watchdog 意图不得取消仍有效的下一次刷新计划。只有显式 `ACTION_REFRESH_NOW` 才立即执行。服务重启和 Widget 接管同样调用共享刷新协调器。

Android 后台启动使用合规的前台服务入口；无法启动时记录类型化失败并安排系统允许的后续重试，不静默吞掉。

## 告警隔离

余额告警开关、最近告警余额、异动锚点、锚点时间、最近异动告警和通知 ID 全部使用 `(accountId, currency)` 作为身份。

PendingIntent requestCode 同样由账户和币种的稳定组合生成。升级时保留逐币种启用设置，删除旧的账户级余额锚点和去重值，让各币种在首次刷新时独立建立新锚点。

## 历史摘要与清理

服务在响应完成时获取记录时间戳，不在网络请求前预先捕获。历史写入提供批量接口，避免主线程逐条重写大 JSON。

每日摘要由指定日期的当前原始记录完整重算，并覆盖同一 `(date, accountId, currency)` 摘要。摘要写入使用同步、可报告结果的持久化操作。

清理步骤为：

1. 读取目标日期当前全部原始记录；
2. 聚合并覆盖摘要；
3. 重新读取摘要，验证 sampleCount、账户、币种和日期覆盖源记录；
4. 仅在验证成功时删除本次已覆盖的原始记录；
5. 任一步失败则保留原始记录供下次重试。

迟到记录会使下一轮重算覆盖旧摘要，因此不会永久遗漏。

## Release 调试数据

网络 Provider 只在 `BuildConfig.DEBUG` 时安装 `DebugInterceptor`。Release 构建不保存请求或响应内容。

Debug 构建中：

- 单个请求体和响应体最多保存 64 KiB；
- 总存储使用全局 LRU 字节预算；
- Authorization、Cookie、Set-Cookie、API Key、Secret Key、access token 和 refresh token 统一脱敏；
- 超出上限的内容标记为已截断；
- 复制到剪贴板前再次使用同一脱敏器处理。

## 发布门禁与源码健康

- 删除未实现且未使用的 `ConsoleWebViewActivity` Manifest 声明，或在调用链需要时实现真实 Activity；本设计选择删除未使用声明。
- 补齐当前缺失翻译，使当前 lint error 清零。
- 恢复 `abortOnError=true`。
- 审计并移除 lint baseline；历史 warning 不再被静默隐藏，可作为非阻断技术债显示在 CI。
- 将 `UsageScriptExecutor.kt` 中的字面 NUL 改为源码转义，保证 Git diff、搜索和静态扫描按文本处理。
- 修复 `CrashLogger` 测试共享全局状态，测试结束时恢复默认未捕获异常处理器和静态引用。

## 错误模型

刷新失败至少区分：

- `NetworkFailure`
- `AuthenticationFailure`
- `RateLimited`
- `ResponseSchemaFailure`
- `ScriptTimeout`
- `ScriptPolicyDenied`
- `AccountStale`
- `PersistenceFailure`

错误向 UI、刷新日志和服务诊断提供稳定分类，但不得包含原始凭据、完整响应体或 Cookie。认证、限流和传输错误不得触发其他猜测端点。

## 测试策略

所有行为修改遵循先失败测试、再最小实现、再重构的顺序。

### 单元与 Robolectric 测试

- Widget 对不同供应商使用正确 Provider，绝不调用 DeepSeek 服务处理其他 Key；
- 缺失、非数字、NaN 和 Infinity 金额返回失败，显式零成功；
- Novita 所有路径使用同一 fixture 并返回相同数值；
- 无凭据备份递归清理凭据和脚本，默认导入保留现有账户；
- v1/v2 导入计划覆盖合并、冲突、跳过和全量替换条件；
- 长刷新间隔不会触发 watchdog，超期且无心跳才触发；
- 多币种告警锚点、去重、通知 ID 和 PendingIntent 相互隔离；
- API Key 编辑清除旧缓存，删除或编辑期间完成的旧请求无法提交；
- 两个并发缓存写入不丢失账户或币种；
- 跨午夜迟到写入会重算摘要，摘要写入失败不删除原始记录；
- Console TTL 到期后删除会话，登出调用完整清理流程；
- Debug 日志执行截断、全局预算和敏感字段脱敏。

### MockWebServer 与安全测试

- 同源 HTTPS 请求通过；
- 未授权外域、HTTP、私网 IP、DNS 解析到私网和跨域重定向被拒绝；
- 已授权公网站点可请求；
- 导入脚本在用户授权前不可执行；
- Rhino 配置阶段和 extractor 的无限循环均在截止时间内失败并释放执行资源。

### WebView 与设备测试

- 允许 origin 才能获得 localStorage；外域导航不得注入；
- 外部链接使用系统浏览器；
- 登出后目标平台 Cookie 和 WebStorage 不再恢复登录；
- Android 目标版本下验证开机恢复、前台服务启动限制和长间隔调度。

## 验收标准

1. 所有新增回归测试均经历可观察的红灯和绿灯。
2. `testDebugUnitTest` 连续完整运行两次均为零失败，不能依赖任务缓存。
3. Debug 与 Release APK 构建成功。
4. lint 为 0 error，且错误可阻断构建。
5. 新增刷新、安全、导入和解析领域类有直接单元测试。
6. WebView 和后台服务设备测试若因环境不能执行，必须明确列出未验证项，不得以 JVM 测试替代。
7. 工作完成后进行一次完整差异代码审查，修复所有阻断或高优先级回归。

## 实施分组

后续实施计划分为以下可独立验证的工作流：

1. 余额供应商契约与共享刷新协调器；
2. 账户 revision、缓存与提交并发控制；
3. 脚本网络策略与 Rhino 截止；
4. 备份 schema、导入计划和确认 UI；
5. Console origin、TTL 和登出清理；
6. 调度、告警与历史清理；
7. Release 调试、lint、源码健康和全量回归。

共享模型和接口先落地，互不重叠的实现与测试可并行进行，最后统一集成验证。
