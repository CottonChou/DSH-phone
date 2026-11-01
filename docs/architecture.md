# DSH-phone 架构

本文按代码现状描述 DSH-phone 的结构。

路径缩写：`core/…` = `core/src/main/kotlin/com/solace/dshphone/core/`，`app/…` = `app/src/main/java/com/solace/dshphone/`。

## 1. 模块与分层

- `:core`：纯 Kotlin（JVM），不依赖安卓。主循环、模型客户端、工具、唯一执行入口、安全策略、收件箱、证据账本、记忆、触发器都在这里，可以在电脑上用单测和假模型跑。
- `:app`：安卓宿主。无障碍服务、内置浏览器、界面、通知、悬浮胶囊、保活入口、加密存储，以及 `:core` 各接口的安卓实现。

```
来源    对话界面 │ 定时/条件触发（规划中接入 UI）
         └─────────┬─────────┘
         TaskCenter（每个对话一个 Run、并行上限、排队）
                     │
         TaskRunner ── 一次运行：装能力、建 AgentLoop、收尾
                     │
         AgentLoop（只调度）← RunInbox（插话、回答、确认、交还、取消）
           HttpChatModel · ToolDispatcher → ActionExecutor（唯一执行入口）
                     │  CapabilityApi（核心不认识具体能力）
         phone（无障碍）   web（内置浏览器）
```

依赖方向：能力包依赖核心，核心不引用任何能力；装配只发生在 `TaskRunner`。

## 2. 主循环

`AgentLoop` 只做调度，判断各在一个小类里：

| 件 | 职责 |
|---|---|
| `HttpChatModel` | 组请求、调模型；429/5xx/断网有限重试（指数退避 + 总等待上限） |
| `TurnPolicy` | 空回复提醒、用户刚回答时的跟进提醒 |
| `StopRules` | 重复同一动作、连续失败、空回复循环 → 判定卡死并结束 |
| `ToolDispatcher` | 按名找工具、按属性折叠/展开能力组 |
| `ActionExecutor` | 唯一执行入口：安全策略 → 付款确认 → 对外节奏 → 执行 → 记录 |

**结束方式**：模型不带工具调用的回复就是回答；`end_not_done` 工具用于做不成时收尾（带 subtype：blocked / login_incomplete / captcha_incomplete / route_failed / device_unavailable / interaction_failed）。

**插话语义**：插话是补充。每步调模型前从 `RunInbox` 取走所有输入接进历史；插话一到就撤掉在等的模型请求（请求不写历史），带着完整历史重问；`closeUnlessSteered` 保证「回答交出前再看一眼收件箱」。

## 3. 工具系统

- 每个工具声明：风险（READ/WRITE/EXTERNAL）、所属能力、参数 schema、是否为入口（`entry`）。
- 开局只有通用工具 + 各能力的入口；入口调用成功后展开整组。没有入口的能力开局即全开。
- 展开决策来自属性，不写死工具名；模型凭记忆调了收起的工具照常执行并展开。

## 4. 安全

`HostSafetyPolicy` 在一切执行之前：

1. 包分类（前缀/关键词数据驱动）：自有、银行、支付、通讯、系统、普通。
2. 银行 → 硬拒绝；通讯对外动作 → 逐次确认且无人在场时拒绝；系统 → 默认只读 + 任务级授权（`TaskGrant`，随任务失效）；自有应用 → 不操作自己。
3. `RiskJudge` 认定不可逆与付款动作（按钮文案 + 屏上币种金额）。
4. `OutwardPacing` 对同一目标（站点/包名）的对外动作拉开随机间隔。

## 5. 运行与恢复

- `TaskCenter` 管运行：每对话一个 Run，并行上限（默认 2），超出 FIFO 排队；忙碌对话的新消息转为插话。
- `RunState` 暴露给 UI：QUEUED / RUNNING / ENDED / INTERRUPTED。
- 通知固定槽位：进度 / 提醒 / 需要你处理，锁屏只显示标题不显示内容。

## 6. 引擎

`EngineHost` 支持内置引擎与外部引擎（DSH 桥接）两种执行后端；崩溃分三类记录（启动失败 / 早期 / 后期），连续异常进入 SafeMode 并回退内置引擎。

## 7. 测试

`:core` 的测试在 JVM 上跑（`core/src/test/`）：主循环（回答/工具/收尾/插话/卡死）、安全策略各层、记忆敏感过滤、触发器求值、风险分级。构建不依赖安卓环境。
