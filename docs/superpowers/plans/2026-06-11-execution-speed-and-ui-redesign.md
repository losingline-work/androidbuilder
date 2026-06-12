# 执行提速 + 执行过程显示 + 时间线信息架构重设计

> **面向 AI 代理的工作者：** 本计划自包含，可零上下文执行。逐任务实现，复选框跟踪；每个任务先写失败测试、再实现、再全量测试、再提交。
>
> **状态：** 提案（未实现）。
> **全量测试命令：** `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> **基线：** 开工前全量测试必须是绿的。

---

## 背景与三个问题

本仓库是手机端 AI 开发 Agent App：云端模型拆任务 → Hermes 并行 agent 在 scratch 树执行 → 合并 → 手机 Gradle 构建。前序计划（`2026-06-11-hermes-parallel-hardening.md`）已落地：云端调用预算化、结构化输出关思考、3 分钟空闲超时、每次云端调用记录 `durationMs`、任务粒度已是 3–6 个粗粒度阶段。但用户仍反馈三个问题：

1. **执行过程还是很慢**；
2. **执行过程中界面显示不合理**（看不出在干什么、进行到哪）；
3. **时间线列表逻辑混乱**。

### 问题 1 的剩余根因（已核实）

- **批间 UI 往返开销**：`AgentService.executePlan(...)` 每次调用只执行**一个批次**就返回（约 L278 起：每批 `createBuildJob` 新建 job 行、约 L358 每批 `zipDirectory` 全树打包）；批次结束后由 `ProjectActivity`（约 L846-849）发现 plan 仍是 `planned` 再**从 UI 重新触发下一批**。每批固定成本 = 新 job 记录 + 全树 zip + 多次全树 hash + 消息/刷新往返。6 个任务 3 个批次 = 3 × 固定成本 + 3 次 UI 回弹。
- **Coder 请求负载**：每次调用都驮完整 plan 全文 + 最多 18K 源码快照，prefill 慢（前序计划 5.7 的 `max_tokens` 可选项也未做）。
- **没有耗时汇总视图**：`durationMs` 已逐条记录在 `ai_conversations`，但无聚合，无法回答"时间花在云端生成还是本地开销"。

### 问题 2 的根因

- 执行计划期间，界面同时有**两个状态源**：时间线底部的 `OPERATION_STATUS` 行（流式字数 + 用时）和任务卡（`row_project_tasks_card.xml`，含 per-task 状态 + `tasksAgentSummary`）。两者信息重叠又都不完整。
- **流式进度是全局单值**：`OpenAiClient.ProgressListener` 无调用上下文，并行 agent 的字数交错跳变。
- **看不到阶段与重试**：用户无法知道当前任务处于 侦察/生成/审查/合并 哪一步、第几次重写（共 5 次）。
- **Hermes 决策埋在纯文本对话框**（`ProjectActivity.showHermesDecisions()` 约 L649：全量 dump 到一个 TextView Dialog），与任务无关联。

### 问题 3 的根因

时间线（`ProjectTimelinePolicy`：MESSAGE / TASK_GROUP / BUILD_LOG / OPERATION_STATUS）把**四种性质不同的内容**混排：对话、任务面板、构建产物、瞬时状态。最大的噪声源：

- **计划全文是一条巨型 markdown 消息气泡**（`AgentService.plan()` 把整份 plan `addMessage` 进对话）；
- 执行期"状态行 + 任务卡"双显示；
- 构建卡只有日志，没有就地行动（安装/修复仍依赖底部按钮区）。

### 设计原则（三分法）

时间线只承载三类内容，各有明确角色：

| 类别 | 内容 | 行为 |
| --- | --- | --- |
| **对话** | 用户需求、实质性 assistant 回复（能力评估、错误说明） | 按时间排，永久 |
| **里程碑卡** | 计划卡（折叠）、执行汇总卡、构建卡（带就地行动） | 按时间排，永久，可展开 |
| **活动面板** | 执行中的任务卡（唯一状态源：批次/agent/阶段/字数/attempt/用时） | 仅活动期间存在于列表末尾，结束后折叠为执行汇总卡 |

---

## Phase A：提速（结构性）

### 任务 A1：耗时汇总视图（测量先行）

**现状**：`AgentService.recordCloudAiCall` 已在 metadata 写 `durationMs=`；AI 日志页（`ProjectActivity` 的 log query 面板，policy 为 `ProjectLogQueryPolicy`）逐条展示但无聚合。

**实现**：
- [ ] 新建 `app/src/main/java/com/androidbuilder/ui/AiCallDurationSummaryPolicy.java`（+ 测试）：输入 `List<AiConversationRecord>`，按标题归类（包含 "task operations"/"文件操作生成" → coder；"review"/"审查" → reviewer；"Context Scout"/"侦察" → scout；"plan"/"计划" → planner；其余 → other），输出每类 `count / totalMs / avgMs` 与全部合计。metadata 中 `durationMs=` 的解析要容错（缺失按 0 计）。
- [ ] AI 日志面板顶部（log query header）显示一行汇总，如 `云端 23 次 · 总 31m · coder 18m / reviewer 6m / scout 2m`。字符串中英双语。
- [ ] 测试：`AiCallDurationSummaryPolicyTest` 覆盖归类、缺失 durationMs、空列表。

```bash
git add -A && git commit -m "feat: aggregate cloud call durations in the AI log panel"
```

### 任务 A2：批间内联——一次点击跑完全部批次

**现状**：`executePlan` 每调用一次只跑一个批次（新建 1 个 build job + 全树 zip）；批间靠 `ProjectActivity` L846-849 的回调重新触发。

**目标**：`executePlan` 内部 **while 循环连续执行批次**直到无 ready 任务或一批全军覆没；**整个执行只建 1 个 build job、只在结束时 zip 一次**；批间不再经过 UI。

**实现要点**：
- [ ] 把"取批次 → 派发 → 合并 → 标记结果"的主体抽进 `while (true)` 循环：`HermesParallelScheduler.nextBatch(...)` 返回空批即跳出；某批 `mergedResults.isEmpty() && !batch.tasks.isEmpty()` 时按现有失败语义抛出（保持 job 失败行为）。
- [ ] 每批结束后调用 `listener`/repository 更新让 UI 可刷新（现有 `HermesExecutionRunRecord` 与 agent run 记录已逐批写库，UI 的 refresh 由现有回调驱动，无需新通道；确认 `executePlanAsync` 的 onComplete 只在整个循环结束后触发一次）。
- [ ] `zipDirectory` 与 `repository.updateBuildJob(..., "generated", "ready_for_build", ...)` 移到循环之后只做一次；每批的 `HermesScratchCleanup.afterMerge(...)` 保留在批内。
- [ ] `ProjectActivity` L846-849 的"plan 仍是 planned 就再触发"的自动续跑逻辑**删除**（保留 `autoExecutingPlan` 标志用于 UI 状态；onComplete 后若 plan 已 `generated` 提示可构建）。注意中断恢复（`recoverInterruptedWorkIfNeeded`）语义不变：循环中途 App 被杀，running 任务仍会被恢复逻辑标 failed。
- [ ] 失败任务不阻塞循环：某批部分失败时（`failedResults` 非空但有合并成功者），**继续下一批**（失败任务会被调度器以 `failed_retry` 单批重试一次；若再次失败且无其它 ready 任务，循环自然结束，完成消息里报告失败数——沿用现有"N 个任务失败"文案）。为避免死循环，**同一任务在一次执行运行内最多被重新派发 1 次**（循环内维护 `Map<Long,Integer> dispatchCounts`，超限即从候选中剔除并保持 failed）。
- [ ] 测试：现有 `AgentServiceRetryPolicyTest` 模式下新增纯函数测试不易覆盖循环本体；把"同任务最多重派 1 次"的判定抽成包内静态纯函数（如 `HermesDispatchBudget.allows(dispatchCounts, taskId)`）并单测。

```bash
git add -A && git commit -m "perf: run all Hermes batches in one execution pass"
```

### 任务 A3：Coder 负载减肥（可选，A1 数据出来后再定）

- [ ] 仅当 A1 显示 coder 平均耗时仍 > 90s 时执行：`createTaskOperations` 请求体加 `max_tokens=16384`（前序计划 5.7 原文）；plan 全文超过 6000 字符时按当前任务标题/指令关键词截取相关小节（保留"# 工程计划"头与约束小节）。截断逻辑独立成 `PlanContextTrimPolicy` + 单测。

---

## Phase B：执行过程显示（一个活动面板讲清楚一切）

### 任务 B1：执行期单一状态源

**现状**：执行计划时 `OPERATION_STATUS` 行与任务卡同时显示。`ProjectOperationStatus.shouldShow(message, busy, autoExecutingPlan, latestJob)` 在 `autoExecutingPlan` 时返回 true。

**实现**：
- [ ] `ProjectOperationStatus.shouldShow` 增加参数 `boolean taskPanelLive`（= 任务卡存在且有 running/预测 running 任务）：`taskPanelLive == true` 时返回 false——执行计划期间状态行让位给任务卡。规划中/修复中/构建中（无任务面板时）行为不变。
- [ ] `ProjectActivity` 调用处传入 `taskPanelLive`（从 `taskItems` 与 `autoExecutingPlan` 推导）。
- [ ] 更新 `ProjectOperationStatusTest`：新增"任务面板活跃时隐藏""构建中无任务面板仍显示"两例。

### 任务 B2：per-agent 实时进度（替代全局交错计数）

**现状**：`OpenAiClient.ProgressListener.onProgress(answerChars, reasoningChars)` 无上下文；`ProjectActivity.onModelStreamProgress` 写全局 `operationProgress`，并行时数字交错。

**实现**：
- [ ] `ProgressListener` 改为 `onProgress(String callTag, int answerChars, int reasoningChars)`；`OpenAiClient` 各 `create*` 方法接受可选 `callTag`（由 AgentService 传 `"task:" + taskId` / `"repair:" + shardIndex` / `"plan"`），`completeChat` 透传给监听器。无 tag 的调用传空串。
- [ ] `AgentService` 新增线程安全的内存进度表 `Map<String, StreamProgress>`（volatile snapshot 读取），并暴露 `Map<String, StreamProgress> streamProgressSnapshot()`；`ProjectActivity` 的监听器只触发节流刷新，渲染时按 taskId 取对应 agent 的进度。
- [ ] 任务卡内每个 running 任务行展示：`生成中 · 1.2k 字 · 用时 2m10s`（思考阶段显示 `思考中 · N 字`）。复用现有 `formatStreamCount`/`formatDuration`。
- [ ] 进度表在任务结束（done/failed/merge）时清除对应条目。
- [ ] 测试：进度表的纯逻辑（put/clear/snapshot）抽成 `StreamProgressRegistry` 类单测；UI 渲染不强求单测。

### 任务 B3：阶段可见（侦察/生成/审查/合并/重写 x/5）

**现状**：agent run 记录有 status（running/merge_pending/done/failed），无阶段；attempt 次数只在日志里。

**实现**：
- [ ] `StreamProgressRegistry` 的条目增加 `phase` 与 `attempt` 字段；`createAndApplyTaskOperations` 在关键转换点更新：scout 调用前 `scouting`、coder 调用前 `coding(attempt n)`、Hermes 审查前 `reviewing`、本地守卫/合并阶段 `merging`、策略拒绝后回到 `coding(attempt n+1)`。
- [ ] `HermesAgentRunDisplayPolicy.item(...)` 或任务行渲染处把 phase+attempt 拼进副标题：`批次 1 · Agent 2 · 生成中（第 2/5 次）· 1.2k 字 · 2m10s`。
- [ ] 阶段文案中英双语（strings.xml）。
- [ ] 测试：扩展 `HermesAgentRunDisplayPolicyTest`（或新建渲染 policy 测试）覆盖 phase/attempt 的拼接。

### 任务 B4：Hermes 决策轨迹按任务下沉

**现状**：`showHermesDecisions()` 把全部决策 dump 进一个纯文本 Dialog，与任务无关联。

**实现**：
- [ ] `recordHermesReviewAi` / `recordCloudAiCall`（Hermes 相关标题）在 metadata 中追加 `taskId=<id>`（coder/审查/侦察调用处都能拿到当前 task）。
- [ ] `HermesDecisionTimelinePolicy` 增加 `forTask(List<AiConversationRecord> records, long taskId)`：按 metadata `taskId=` 过滤，最多返回最近 5 条。
- [ ] 任务卡中已完成/失败的任务行点击展开"决策轨迹"区（时间 · 角色 · decision · 一行摘要），数据来自 `forTask`。全量 Dialog 入口保留。
- [ ] 测试：`HermesDecisionTimelinePolicyTest` 增加 taskId 过滤用例（含无 taskId 的旧记录被忽略）。

---

## Phase C：时间线信息架构（按三分法重排）

### 任务 C1：计划折叠卡（消灭巨型气泡）

**现状**：plan 全文作为一条 assistant 消息进时间线（`AgentService.plan()` 中 `repository.addMessage(projectId, "assistant", plan, null)`）。

**实现**：
- [ ] 新增 `ProjectTimelinePolicy.Kind.PLAN_CARD`：当某条消息内容以 `# 工程计划` / `# Engineering Plan` 开头时，时间线产出 PLAN_CARD 而非 MESSAGE（sourceIndex 仍指向该消息，**不改库**，纯显示层识别——与既有降噪机制同构）。
- [ ] 新建 `row_plan_card.xml`（复用 `Widget.AndroidBuilder.Card` 与 build-log 卡的展开/收起交互）：默认显示"工程计划"标题 + 前 2 行小节名摘要 + 展开/收起 + 复制按钮；展开显示全文。
- [ ] 摘要提取独立成 `PlanCardSummaryPolicy`（取 markdown 二级标题/小节名，最多 4 个）+ 单测。
- [ ] `ProjectTimelinePolicyTest` 增加：计划消息产出 PLAN_CARD、普通消息不受影响、PLAN_CARD 不破坏 BUILD_LOG 锚定（计划消息无 linkedBuildJobId，锚定逻辑不变，写一条回归用例锁定）。

### 任务 C2：构建卡行动就地化

**现状**：构建成功/失败后，安装与修复按钮在屏幕底部操作区，与构建卡分离。

**实现**：
- [ ] `row_build_log.xml` 头部按钮区增加一个**条件按钮**：成功且有 APK → "安装"（点击走现有 `installLatest()` 等价逻辑，但安装**该卡对应 job** 的 APK）；失败且 `BuildFailureClassifier.repairableByModel` → "修复"（等价 `repairLatest()`）。运行中不显示。
- [ ] 按钮可见性判定独立成 `ProjectBuildCardActionPolicy`（输入 job 状态/apkPath/可修复性，输出 NONE/INSTALL/REPAIR）+ 单测。底部全局按钮区保留不动（两个入口并存，先不做减法）。

### 任务 C3：执行汇总卡（活动面板的归宿）

**现状**：执行完成后任务卡仍以完整形态停在时间线锚点处，历史项目里多个执行的任务卡堆叠。

**实现**：
- [ ] 任务卡在**无 running/预测 running 任务且全部 done**时默认收起为单行：`✓ 已完成 6/6 任务 · 总用时 18m`（点击展开完整任务列表；现有 `tasksToggle` 交互复用）。存在 failed 任务时默认展开。
- [ ] 折叠判定与摘要文案独立成 `ProjectTaskListDisplayPolicy` 的新方法（该类已存在）+ 单测：全 done → 折叠摘要；含 failed → 展开；执行中 → 展开。
- [ ] 总用时 = 各任务 `completedAt - startedAt` 的 max(完成时间) - min(开始时间)（跨批次的墙钟时间），缺时间戳容错为省略时长。

### 任务 C4：时间线排序规则回归测试

- [ ] 在 `ProjectTimelinePolicyTest` 固化三分法排序的端到端用例：用户消息 → 计划卡 → 任务卡（锚点） → 构建卡 → 新用户消息 → …… 活动面板（OPERATION_STATUS 或活任务卡）只出现在末尾。防止后续改动再次漂移。

---

## 实施顺序与依赖

```
A1（测量）──┬→ A2（批间内联）→ A3（可选，看数据）
            │
B1（单状态源）→ B2（per-agent 进度）→ B3（阶段/attempt）→ B4（决策下沉）
            │
C1（计划卡）→ C2（构建卡行动）→ C3（汇总卡）→ C4（回归测试）
```

A、B、C 三线可独立推进、独立提交；B2 依赖 B1（避免双状态源下做进度）；建议整体顺序 A1 → B1 → C1（三个最小高感知项先行）→ 其余。

## 验收标准

1. 全量 `:app:testDebugUnitTest` 通过。
2. **速度**：一次"执行计划"点击连续跑完所有批次；全程只产生 1 个 build job、1 次全树 zip；AI 日志页能看到分类耗时汇总。
3. **过程显示**：执行期间界面只有任务卡一个状态源；每个 running 任务能看到 阶段 + 第 n/5 次 + 流式字数 + 用时；并行两个 agent 的数字互不串扰。
4. **列表**：计划是折叠卡不再刷屏；构建卡上能直接安装/修复；完成的执行收为一行摘要；时间线顺序有回归测试锁定。
5. 真机抽测（执行者无法本地完成时如实标注未验证）：6 任务项目从点击执行到 ready_for_build 的墙钟时间，与 A1 汇总中"云端总耗时"之差（=本地开销）< 1 分钟。

## 范围外

- 改造为 RecyclerView/DiffUtil（现 ListView 性能尚可，不动）。
- 推送式进度（WebSocket/SSE 服务端）；仍用现有回调+节流刷新。
- 底部全局按钮区的删减（C2 后观察使用习惯再定）。
- 修复路径的并行分片 UI（已有 repair shard 展示，不在本轮）。

## 风险与回滚

- A2 是行为变更最大项：批间不再回 UI。风险点在中断恢复与取消语义——计划里已要求保持 `recoverInterruptedWorkIfNeeded` 语义并写明派发预算防死循环；若真机异常，revert 该提交即可回到"一批一触发"。
- B/C 全部是显示层与 policy 类改动，DB 不动、agent 行为不动，单独 revert 均安全。
- 所有新逻辑遵循仓库惯例：final + 静态方法的 policy 类 + 对应 `*PolicyTest`，UI 仅做绑定。
