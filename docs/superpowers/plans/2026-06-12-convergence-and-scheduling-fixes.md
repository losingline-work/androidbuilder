# 执行循环收敛性与调度修复（5 个已确认 bug）

> **面向 AI 代理的工作者：** 本计划自包含，可零上下文执行。每个任务先写失败测试、再实现、再全量测试、再独立提交。
>
> **状态：** 提案（未实现）。
> **全量测试命令：** `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> **基线：** 开工前全量测试必须为绿。

---

## 背景：真机证据

任务「drawable and layout XML」（task 653）失败的决策轨迹显示连续 rewrite：`Unusually many file operations: 78 → 79 → 80`——**每次重试操作数 +1**，烧光预算后失败；随后依赖其产物的「Java source wiring」任务照常派发（重演了 job#23 的踩空灾难）。对代码逐项核实后确认 5 个 bug，并对 6 项相邻疑点做了审计（见附录，执行时不必重查）。

### Bug 链解释（78→79→80 的成因）

1. preflight 拒绝"操作太多"→ 该错误不在 `DraftCorrectionPolicy` 的禁修正清单 → 进入**修正模式**；
2. 修正模式按路径**合并上稿**，操作数只增不减 → 每轮 +1；
3. "同签名错误 2 次回退全量"的保险丝失效：`errorSignature` 只归一化空白，`…: 78` 与 `…: 79` 签名不同 → streak 永远是 1；
4. 深层矛盾：`MAX_OPERATIONS_PER_TASK = 30` 是"5–12 个小任务"时代定的，现在任务是 3–6 个粗阶段，资源阶段合法需要 40–80 个文件；且提示语 "Split this into smaller tasks" 模型根本做不到（没有拆任务机制）。

---

## 任务 1：错误签名归一化数字（修保险丝）

**现状**：`DraftCorrectionPolicy.errorSignature`（约 L23）仅 `replaceAll("\\s+", " ")` + 小写。`AgentService` 内部类 `FailureStreak`（约 L79-84）是唯一消费方，单点修复即可。

- [ ] 测试先行（`DraftCorrectionPolicyTest`）：
  - `"Unusually many file operations for one task: 78."` 与 `"... : 80."` 的 `errorSignature` 相等；
  - `"missing XML id: R.id.toolbar in A.java"` 与 `"missing XML id: R.id.title in A.java"` 仍不相等（数字归一不影响标识符区分——`toolbar`/`title` 无数字）；
  - 既有签名断言若含数字需同步更新。
- [ ] 实现：签名链加 `.replaceAll("\\d+", "#")`。
- [ ] 权衡（写进 Javadoc）：`R.id.btn1` 与 `R.id.btn2` 会被视为同签名、提前回退全量——方向安全（全量是保守路径），可接受。

```bash
git add -A && git commit -m "fix: normalize digits in retry error signatures"
```

## 任务 2："操作过多"类错误禁用修正模式

**现状**：`DraftCorrectionPolicy.isStructuralError` 清单（empty/no-json/unsupported-action/unsafe-path/unterminated）不含 "unusually many"。修正合并对该错误**机制上有害**（合并只能增不能减）。

- [ ] 测试先行：`shouldCorrect(true, "Unusually many file operations for one task: 45.", 0)` 返回 false。
- [ ] 实现：`isStructuralError` 增加 `signature.startsWith("unusually many file operations")`。

```bash
git add -A && git commit -m "fix: never use correction merge for oversized operation batches"
```

## 任务 3：操作数上限对齐粗任务策略 + 可执行提示

**现状**：`TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK = 30`（L24）；拒绝提示要求 "Split this into smaller tasks"（模型无此能力）。任务拆分策略已是 3–6 个粗阶段（`tasksSystemPromptText`："cohesive coarse phase"），资源阶段合法需要 40–80 文件。

- [ ] 测试先行（`TaskOperationsPreflightTest`）：50 个操作 → OK；61 个 → REWRITE；拒绝文案含 "trim"/"defer" 而非 "Split this into smaller tasks"。既有 31 个边界用例同步更新。
- [ ] 实现：上限 30 → **60**；rewrite 文案改为（逐字）：
  > `Too many file operations: N (cap 60). Trim the batch instead of splitting tasks: keep only files this task strictly needs, merge values resources into fewer files, drop duplicate or decorative drawables, and defer non-essential assets to a later task.`

```bash
git add -A && git commit -m "fix: align the operation cap with coarse task granularity"
```

## 任务 4：失败耗尽后的顺序安全闸

**现状**：任务派发预算 `HermesDispatchBudget.MAX_DISPATCHES_PER_EXECUTION = 2`；预算耗尽的失败任务被 `allowedTasks` 从候选**剔除**（AgentService 循环约 L344）→ `HermesTaskGraph` 看不到它 → 后续任务（契约未声明 `dependsOn` 时）照常 ready 并派发，在缺前置产物的树上注定失败（真机两案皆此）。

**设计**：恢复保守顺序语义——存在"失败且预算耗尽"的任务时，sortOrder 在其之后的任务不再派发；**显式声明了 `dependsOn` 且全部满足**的任务放行（模型明确说了不依赖失败者）。

- [ ] 测试先行（新 `SequentialFailureGateTest`）：
  - 任务 3 failed 且预算耗尽 → 任务 4/5（无 dependsOn）被拦，返回空；
  - 任务 4 显式 `dependsOn` 指向已 done 任务的 produces → 放行；
  - 失败任务预算未耗尽 → 不拦（交给现有 failed_retry）；
  - 无失败任务 → 原样返回。
- [ ] 实现：新建 `app/src/main/java/com/androidbuilder/agent/SequentialFailureGate.java`：
  `static List<ProjectTaskRecord> filter(List<ProjectTaskRecord> allTasks, List<ProjectTaskRecord> allowed, Map<Long, Integer> dispatchCounts, Set<String> doneProduces)`
  —— 在 `allTasks` 中找 `"failed".equals(status)` 且 `!HermesDispatchBudget.allows(...)` 的最小 sortOrder；过滤 `allowed` 中 sortOrder 更大且（无 dependsOn 或 dependsOn 未全满足）的任务。契约解析复用 `HermesTaskContractCodec.extractFromInstruction`，produces 满足判定与 `HermesTaskGraph.dependenciesSatisfied` 同语义（token 化对齐）。
- [ ] 接线：AgentService 循环在 `HermesDispatchBudget.allowedTasks(...)` 之后调用；被闸门拦下且无可派发任务而结束循环时，完成/失败消息改为（中英）：
  > `任务「X」已失败且重试耗尽，其后续任务已暂停以避免在缺失前置产物上空跑；请点击执行下一步重试，或调整需求后重新生成计划。`
- [ ] 既有"仍有 N 个任务失败"消息分支保留（部分失败但循环正常走完的情形）。

```bash
git add -A && git commit -m "fix: pause downstream tasks after an exhausted upstream failure"
```

## 任务 5：修正模式 drop 语义（删除类错误可收敛）

**现状**：合并策略只能**覆盖/新增**，无法从上稿中移除一个操作。删除类错误——如契约守卫的 `"Generated operations touched forbiddenPaths: X. Rewrite the operations without touching X."`——在修正模式下**机制上无法修复**（被禁路径的 op 永远被合并保留），只能靠 streak 保险丝烧 2 次后回全量。另有隐患：`FileOperationsWriter.applyToDirectory` 对未知 action 的分支是 `delete 否则一律 write`——任何非常规 action 会被**当成 write 落盘**。

- [ ] 测试先行：
  - `TaskOperationsParserTest`：`{"action":"drop","path":"app/src/main/res/values/extra.xml","content":""}` 可解析；
  - `TaskOperationsMergePolicyTest`：修正含 drop(P) → 合并结果不含路径 P 的任何操作，drop 自身也不残留；
  - `FileOperationsWriterTest`：操作 action 为 `"drop"`（或任意未知值）时 `apply` 抛 `IllegalArgumentException`（防线收紧，不再默认当 write）；
  - AgentService 侧：非修正模式响应中的 drop 操作在 apply 前被剥离（剥离逻辑抽 `static TaskOperations stripDrops(TaskOperations)` 进 `TaskOperationsMergePolicy` 并单测）。
- [ ] 实现：
  - `TaskOperationsParser.operationFromJson` 允许 `"drop"`（content 忽略）；
  - `TaskOperationsMergePolicy.merge`：上稿铺底后，修正中的 drop(P) 把路径 P 从结果移除；merge 输出保证不含 drop；
  - 非修正路径在 parse 后调用 `stripDrops`；
  - `FileOperationsWriter.applyToDirectory`：action 不是 write/delete → 抛异常（最后防线）；
  - 修正指令文本（OpenAiClient 中上稿修正段）追加一句（逐字）：
    > `To remove a file from your previous draft that should not be written at all, return {"action":"drop","path":"..."} for it.`
- [ ] 契约 forbiddenPaths 错误**不加**禁修正清单：drop 使其可收敛，保险丝（任务 1 修复后）兜底。

```bash
git add -A && git commit -m "feat: let correction retries drop operations from the previous draft"
```

---

## 附录：已审计、无需修复的相邻疑点

执行者不必重查以下各项（本计划撰写时已逐一核实）：

| 疑点 | 结论 |
| --- | --- |
| blocked 判定与修正合并的时序 | ✓ 正确：blocked 在 merge 之前判定（AgentService ~L1202 vs ~L1220），blocked 结果不会与上稿合并 |
| 扩界重试的上稿污染 | ✓ 正确：扩界时 `previousDraft = null`（~L1207），下一轮全量 |
| 扩界是否烧重试预算 | ✓ 正确：`attempt--; continue;`（~L1215），不烧 |
| 二次 blocked 的语义 | ✓ 正确：直接抛出，resultSummary 以 `blocked: ` 开头（BlockedTaskPolicy.blockedSummary） |
| 契约/确定性预检的重写预算 | ✓ 正确：受 `PREFLIGHT_REWRITE_BUDGET` 限制，不与策略错误预算混淆 |
| 修复路径（repairBuild）的畸形 JSON 恢复 | ✓ 正确：lenient 解析仅在特定畸形门控下启用 |

## 验收标准

1. 全量 `:app:testDebugUnitTest` 通过；新增/更新的测试全绿。
2. 复现场景一（操作过多）：同一任务上 "Unusually many" 错误最多出现 2 次（签名归一后保险丝生效），且第二次后回全量；上限 60 内的资源阶段批次直接放行。
3. 复现场景二（上游失败）：某任务失败且重试耗尽后，无显式 dependsOn 的后续任务不再派发，执行结束消息明确"已暂停后续任务"。
4. 修正模式可通过 drop 移除上稿操作；任何 drop/未知 action 永远不会落盘（writer 抛异常防线 + stripDrops）。
5. 真机回归（无法本地完成则如实标注）：重跑"记账 App"类项目，确认不再出现 78→79→80 式递增循环，也不再出现"上游失败、下游空跑"。

## 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 顺序闸门牺牲失败后的并行度 | 仅在"失败且预算耗尽"时生效；显式 dependsOn 满足者放行；单独提交可 revert |
| 上限 60 仍不够某些超大资源阶段 | 新提示语指导模型裁剪/延后；仍超限则保险丝回全量 + 最终失败留痕，不再死循环 |
| drop 被全量模式滥用 | 非修正路径 stripDrops + writer 对未知 action 抛异常双防线 |
| 数字归一误并相近错误 | 回退方向是全量重写（保守路径），最坏多花一次全量调用 |

五个任务相互独立、各自提交，可单独 revert。建议顺序：1 → 2 → 3（三个一行级修复先止血）→ 5 → 4（涉及调度语义，最后做）。
