# 重试草稿记忆：从"盲重写"到"最小修正"

> **面向 AI 代理的工作者：** 本计划自包含，可零上下文执行。逐任务实现；每步先写失败测试、再实现、再全量测试、再独立提交。
>
> **状态：** 提案（未实现）。
> **全量测试命令：** `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> **基线：** 开工前全量测试必须为绿。

---

## 背景与诊断（已对代码核实）

本仓库是手机端 AI 开发 Agent：云端模型按任务生成文件操作（write = **整文件替换**）→ 确定性守卫校验 → 落盘 → 构建。任务执行循环在 `AgentService.createAndApplyTaskOperations(...)`（attempt 循环，`POLICY_REWRITE_ATTEMPTS = 5`；每次 attempt 的 `operationsJson` 在约 L1150 取得、L1158 解析）。

**当前重试的上下文处理**：

- **项目层是增量的**（正确）：scratch 是当前源码树副本，retry 上下文（`ContextNegotiationPolicy.retryContext`）明确要求 "Do not recreate the project / Modify only the files needed"。
- **任务层是无记忆盲重写**（问题所在）：守卫拒绝后，模型只收到"错在哪"的提示（守卫错误 + `PolicyRewriteInstruction` 指引 + 本地规则 hint），**看不到自己上一稿写了什么**，然后从零重新生成该任务的全部文件操作。失败任务重新派发时更甚：scratch 已删，唯一记忆是 `task.resultSummary` 一行摘要。

**这造成两个代价**：

1. **慢**：上稿 5 个文件 4 个本来正确，重试仍要全部重新生成（输出 token = 全任务体量）。
2. **错误震荡**："修了 A、B 又错"的机制性原因——重写时正确部分也被重新生成，引入新错。

**附带的两个卫生问题**：

- `RetryContextPolicy.merge`（无测试）对一般 signal 无条件追加 "Additional retry signal:" 块，5 次重试滚雪球且可能互相矛盾；
- 重聚焦不一致：策略错误路径会按报错重聚焦快照（`sourceSnapshot(sourceDir, policyError.getMessage())`），但确定性 preflight / Hermes reviewer 的 rewrite 分支是 `sourceSnapshot(sourceDir)` 全量重置，丢失聚焦。

## 设计总览

引入**修正模式（correction mode）**：重试时把上一稿作为记忆，让模型只返回需要变更的文件，其余由本地按路径合并复用。三条硬性不变量（执行时不得破坏）：

1. **守卫零旁路**：合并后的操作集走与现状完全相同的管线（确定性 preflight → 预算化 Hermes 审查 → 本地守卫 → `FileOperationsWriter.apply` 内的全部守卫）。修正模式只改"模型生成什么"，不改"什么能落盘"。
2. **草稿永不直接落盘**：循环内草稿只参与合并后再过守卫；**跨派发**的持久化草稿只作参考文本（树可能已被其它任务改变），绝不参与合并。
3. **可降级**：无上稿 / 上稿解析失败 / 结构性错误（空操作、非 JSON）/ 修正模式连续两次同错误 → 自动回退现状的全量重写。最坏行为 = 现状行为。

```
attempt 1（全量） → 守卫拒绝
attempt 2（修正）：prompt = 现有上下文 + [上稿清单 + 错误点名文件的草稿内容] + 修正指令
                   响应 = 仅变更文件的操作
                   本地合并（按路径覆盖上稿）→ 守卫 → 通过即落盘
                   同错误再次出现 → attempt 3 回退全量
任务最终失败 → 最后一稿持久化 → 下次派发作为参考上下文（不合并）
```

---

## Phase 1：循环内修正模式（核心）

### 任务 1.1：操作合并策略 `TaskOperationsMergePolicy`

- [ ] 新建 `app/src/test/java/com/androidbuilder/agent/TaskOperationsMergePolicyTest.java`（先失败）：
  - `correctionOverridesSamePathAndKeepsOthers`：上稿 {A.java, B.xml, C.java}，修正 {B.xml'} → 合并为 {A, B', C}，顺序保持上稿序；
  - `correctionAddsNewPaths`：修正含上稿没有的 D.java → 追加在末尾；
  - `deleteOverridesWrite`：修正以 delete 覆盖上稿同路径 write；
  - `summaryPrefersCorrectionWhenPresent`：修正 summary 非空用修正的，否则沿用上稿；
  - `pathKeyIsNormalized`：`./app//src/main/...` 与 `app/src/main/...` 视为同路径（用 `PathValidator.normalizeGeneratedPath`）。
- [ ] 新建 `app/src/main/java/com/androidbuilder/agent/TaskOperationsMergePolicy.java`：final + 私有构造 + `static TaskOperations merge(TaskOperations previousDraft, TaskOperations correction)`。按归一化路径建 LinkedHashMap（上稿序），修正逐条覆盖/追加。
- [ ] 已知局限（写进类 Javadoc）：修正无法"撤回"上稿中的某个文件（只能覆盖内容或 delete）；坏文件由守卫兜底拦截并触发下一轮修正。

```bash
git add -A && git commit -m "feat: merge correction operations over the previous task draft"
```

### 任务 1.2：草稿上下文构建 `TaskDraftContextPolicy`

- [ ] 新建测试（先失败）覆盖：
  - `manifestListsAllDraftPaths`：输出含全部草稿路径清单（每行 `- path (write|delete, N chars)`）；
  - `errorNamedFilesIncludedInFull`：错误文本点名 `CategoryManageActivity.java` 与类型 `CategoryDao` 时，对应草稿文件全文包含（类型→`类型名.java` 匹配，复用 `BuildLogContextExtractor.referencedJavaTypes` 的思路）；
  - `respectsCharBudget`：超出 `maxChars` 时点名文件优先、其余截断并带 `...[truncated]` 标记；
  - `emptyDraftYieldsEmpty`。
- [ ] 新建 `app/src/main/java/com/androidbuilder/agent/TaskDraftContextPolicy.java`：
  - `static String correctionSection(TaskOperations previousDraft, String errorMessage, int maxChars)` —— 结构：`Your previous draft (rejected) — manifest:` + 清单 + `Offending draft files:` + 点名文件全文；
  - `static String advisorySection(TaskOperations previousDraft, String errorMessage, int maxChars)` —— 跨派发参考版，首行加 `Reference only — the source tree may have changed; regenerate against the CURRENT snapshot:`；
  - 预算常量 `DRAFT_SECTION_LIMIT = 12000`。

```bash
git add -A && git commit -m "feat: build correction context from the previous task draft"
```

### 任务 1.3：模式决策 `DraftCorrectionPolicy`

- [ ] 新建测试（先失败）：
  - 无上稿（null）→ 全量；
  - 错误为结构性（含 `Task operation list is empty` / `did not contain a JSON object` / `Unsupported file operation action`）→ 全量；
  - 正常守卫错误且有上稿 → 修正；
  - **同错误签名连续第 2 次**（签名 = 错误首行 trim+lowercase）→ 全量（防局部极小值死循环），之后计数清零可再回修正。
- [ ] 新建 `app/src/main/java/com/androidbuilder/agent/DraftCorrectionPolicy.java`：`static boolean shouldCorrect(boolean hasPreviousDraft, String errorMessage, int sameErrorStreak)` + `static String errorSignature(String errorMessage)`。

```bash
git add -A && git commit -m "feat: decide correction vs full-regeneration per retry"
```

### 任务 1.4：接线（prompt + 循环）

- [ ] `OpenAiClient.createTaskOperations(...)` 增加参数 `String previousDraftSection`（空串 = 全量模式）。非空时用户 prompt 在 `Current source tree` 之前插入该段，并追加修正指令（逐字）：
  > `You are CORRECTING your previous draft for this task, not rewriting it. Return only operations for files you are changing or adding; every other operation from your previous draft is preserved automatically and must NOT be resent. Keep the same JSON contract (summary + operations).`
  
  同步 `taskOperationsRequestForAiLog` 带上 `Mode: correction` / `Mode: full` 标记（便于 AI 日志统计收敛率）。`OpenAiClientTest` 加 prompt 断言。
- [ ] `AgentService.createAndApplyTaskOperations` 循环改造：
  - 局部变量 `TaskOperations previousDraft = null; String lastErrorSignature = ""; int sameErrorStreak = 0;`
  - 每次成功 `TaskOperationsParser.fromJson` 后：若本轮为修正模式，先 `operations = TaskOperationsMergePolicy.merge(previousDraft, operations)`；随后 `previousDraft = operations`（守卫通过与否都更新——拒绝时它就是下一轮的"上稿"）。
  - 捕获 `IllegalArgumentException policyError` 后：更新 `sameErrorStreak`（签名相同 +1，否则归 1）；`DraftCorrectionPolicy.shouldCorrect(...)` 为真则下一轮传 `TaskDraftContextPolicy.correctionSection(previousDraft, policyError.getMessage(), DRAFT_SECTION_LIMIT)`，否则传空串并将 `previousDraft` 置 null（全量重来）。
  - 确定性 preflight / Hermes reviewer 的 REWRITE 分支同样适用（它们的 rewrite 文本即 errorMessage）。
  - 解析失败（`fromJson` 抛出且非 lenient 恢复）路径：`previousDraft` 保持不变（上一份有效草稿仍可用），但本轮无新草稿。
- [ ] 线程安全说明（写注释即可）：以上全部为方法局部状态，并行 worker 各自独立。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "feat: retry task generation as correction over the previous draft"
```

---

## Phase 2：跨派发草稿记忆

### 任务 2.1：草稿持久化 `TaskDraftStore`

- [ ] 新建测试（`TemporaryFolder`，先失败）：save→load 往返（error/summary/operations 完整）；corrupt 文件 load 返回 null；delete 幂等；超过 300KB 的草稿 save 直接跳过（返回 false）。
- [ ] 新建 `app/src/main/java/com/androidbuilder/agent/TaskDraftStore.java`：
  - 目录：`files/projects/{projectId}/task-drafts/task-{taskId}.json`（`AppRepository.sourceDir(projectId).getParentFile()` 下；或在 `AppRepository`（约 L523 旁）加 `public File taskDraftFile(long projectId, long taskId)`，二选一，后者更显式）；
  - 格式：`{"taskId":n,"error":"...","summary":"...","operations":[{"action","path","content"},...]}`；
  - `static boolean save(File file, TaskOperations draft, String error)` / `static TaskOperations load(File file)` / `static String loadError(File file)` / `static void delete(File file)`，全部异常容忍（load 失败返回 null，绝不抛）。

### 任务 2.2：生命周期接线

- [ ] **写**：`createAndApplyTaskOperations` 耗尽 attempts 抛出前（最终 `throw lastPolicyError` 处），若 `previousDraft != null` → save（需要 taskId：该方法签名已有 task 上下文，确认后透传）。
- [ ] **读**：`HermesAgentWorker.runTask` 对 `"failed"` 任务（现有 `initialFailureContext = task.resultSummary` 处）：load 草稿，非 null 则把 `TaskDraftContextPolicy.advisorySection(draft, storedError, DRAFT_SECTION_LIMIT)` 拼进 initialFailureContext（**参考文本，不进合并**——树可能已变，正文已含"以当前快照为准"声明）。
- [ ] **删**：三处——任务被标 `done`（`executePlan` 合并成功循环内，约"updateProjectTask(result.task.id, \"done\", ...)"处）；`AppRepository.replaceProjectTasks`（L205）与 `clearProjectTasks`（L227）删除整个 task-drafts 目录。
- [ ] 并发说明：同一任务不会被并发派发（调度器 `failed_retry` 单批 + 派发预算），单写者成立，无需加锁。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "feat: persist the last rejected draft across task dispatches"
```

---

## Phase 3：上下文卫生

### 任务 3.1：retry signal 封顶 + 去重

- [ ] 新建 `RetryContextPolicyTest`（该类目前**没有测试**，先把现状锁住再改）：
  - 现状行为锁定：单文件改写触发 `SINGLE_FILE_OVERRIDE` 且剥离 Negotiated 块（照现实现写 2 例）；
  - 新行为：`merge` 累积超过 2 个 "Additional retry signal:" 块时只保留**最近 2 个**；完全相同的 signal（trim 后等值）不重复追加。
- [ ] 实现：`RetryContextPolicy` 增加 `MAX_RETRY_SIGNALS = 2`；`merge` 先按 `"Additional retry signal:"` 分隔解析 base + signals，去重、截尾、重组。

### 任务 3.2：重聚焦一致化

- [ ] `AgentService` attempt 循环中，确定性 preflight 与 Hermes reviewer 的 REWRITE 分支把 `snapshot = sourceSnapshot(sourceDir)` 改为 `snapshot = sourceSnapshot(sourceDir, previousFailure)`（`previousFailure` 此刻即 rewrite 上下文，含点名文件/符号，与策略错误路径对齐）。无新测试要求（`sourceSnapshot` 聚焦已有覆盖），全量测试守护。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "fix: cap retry signals and refocus snapshots on rewrite hints"
```

---

## Phase 4：任务前置缺失的合法出口（blocked 信号 + 受控扩界）

> 来源：真机失败案例（job #23 / task 637）。任务指令"资源缺失就停下"与系统规则"禁止空操作"形成死锁：模型服从指令返回空操作 → 被判违规 → 被迫越界一次写 26 个文件 → 巨批内 id 不一致（`main_container` vs `R.id.fragment_container`）→ 失败。Scout 已正确诊断"先补资源"却无执行机制落地。

### 任务 4.1：`blocked` 结构化出口

- [ ] 测试先行（`TaskOperationsParserTest`）：
  - `{"summary":"...","blocked":true,"blockedReason":"layouts missing","prerequisiteWork":"create manifest/values/layouts ..."}` → 解析为带 blocked 标志的结果（不抛 "Task operation list is empty"）；
  - `blocked:true` 但 `blockedReason` 为空 → 仍按空操作违规处理（防滥用）；
  - 常规含操作响应不受影响。
- [ ] 实现：`TaskOperations` 增加 `blocked` / `blockedReason` / `prerequisiteWork` 字段（默认 false/空）；`TaskOperationsParser` 在操作数组为空时先查 blocked 字段。

### 任务 4.2：orchestrator 受控扩界（每派发最多一次）

- [ ] `AgentService.createAndApplyTaskOperations` 收到 blocked 结果时**不计入策略重试预算**，改走扩界分支（每派发最多 1 次，局部布尔守住）：
  - instruction 由 orchestrator 显式改写（逐字模板）：
    > `The original task boundary is lifted by the orchestrator for this retry. First create the missing prerequisites described below, then complete the original task in the same response. Keep every new resource id consistent with the Java that references it.`
    > `Prerequisites: ` + prerequisiteWork
  - snapshot 按 prerequisiteWork 重聚焦；
  - 扩界后的巨批靠既有机制收敛：守卫拦截 → **修正模式**只重发出错文件（这正是 Phase 1 的价值场景）。
- [ ] 第二次 blocked（已扩界过）→ 任务失败，`resultSummary = "blocked: " + blockedReason`（跨派发草稿记忆 Phase 2 会把它带给下次尝试）。
- [ ] AI 日志：扩界重试的 request 标记 `Mode: scope-expanded`。
- [ ] 测试：扩界判定抽纯函数（如 `BlockedTaskPolicy.shouldExpandScope(blocked, alreadyExpanded)`）+ 单测；instruction 模板拼接单测。

### 任务 4.3：prompt 与改写指引同步

- [ ] `taskOperationsSystemPromptText`：把"不得返回空操作"改为"不得返回空操作；若前置文件缺失导致任务无法安全执行，返回 blocked 结构（blocked/blockedReason/prerequisiteWork）而不是硬写"；
- [ ] `PolicyRewriteInstruction` 的 empty-operations 分支同步提及 blocked 出口；
- [ ] `TaskOperationsPromptPolicyTest` 断言新文案。

### 任务 4.4：契约完成度校验（治上游欠交付）

> 本案上游根因：资源任务被标 done 却几乎没产出（树里只剩 1 个 drawable），后续任务全部踩空。

- [ ] `HermesTaskContractGuard`（或新 `ContractCompletenessPolicy`）增加完成度检查：任务契约声明了 `expectedFiles` 且实际产出覆盖率 < 50% 且缺失 ≥ 3 个文件时，返回 REWRITE（消息列出缺失清单），走既有 preflight 预算，**绝不硬失败**；
- [ ] 阈值取宽松值防误报（expectedFiles 本就是模型自报的期望，不是合同义务）；无契约/无 expectedFiles 的任务不检查；
- [ ] 单测覆盖：欠交付触发、覆盖率达标放行、无契约跳过。

## 验收标准

1. 全量 `:app:testDebugUnitTest` 通过；新增 5 个测试类（MergePolicy / DraftContextPolicy / CorrectionPolicy / DraftStore / RetryContextPolicy）全绿。
2. AI 日志中重试请求带 `Mode: correction` 标记；修正请求含上稿清单与点名文件，且指令明确"不要重发未变更文件"。
3. 守卫零旁路可验证：合并路径上没有任何绕过 `TaskOperationsPreflight` / Hermes 审查 / 本地守卫 / `FileOperationsWriter` 的代码路径（review diff 确认）。
4. 失败任务重新派发的请求含 `Reference only` 草稿参考段；任务 done 后对应草稿文件消失；replace/clear tasks 后 task-drafts 目录消失。
5. 连续两次同签名错误后，下一次请求回到 `Mode: full`（AI 日志可见）。
6. 真机定量（执行者无法本地完成则如实标注未验证）：同一守卫拒绝场景下，修正重试的响应长度（AI 日志 responseText）明显小于首稿；重试引入新错误（"修 A 坏 B"）的现象相比治理前下降。
7. **Phase 4**：前置缺失场景下模型返回 blocked 不再被判违规；AI 日志可见 `Mode: scope-expanded` 的扩界重试；每派发扩界 ≤ 1 次；二次 blocked 的任务 resultSummary 以 `blocked: ` 开头；欠交付任务（expectedFiles 覆盖率过低）在 done 前被 REWRITE 提示补齐。

## 范围外

- diff/patch 粒度的输出格式（风险高，整文件替换保持不变；修正模式已把输出缩到"仅变更文件"）；
- 修复流（repairBuild）的修正模式——它走同一个 `createAndApplyTaskOperations`，Phase 1 天然生效，无需单独改造；
- 跨任务的草稿共享 / 项目级记忆库。

## 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 模型不守"只发变更文件"，仍全量重发 | 合并语义按路径覆盖，结果等价于现状，只是没省到 token；通过 AI 日志 Mode 统计观察遵从率 |
| 修正在错误文件上反复打转 | 同签名错误 2 次即回退全量（任务 1.3） |
| 跨派发草稿与当前树脱节 | 仅作参考文本不合并 + 明示"以当前快照为准" |
| 草稿残留占存储 | done/replace/clear 三处清理 + 300KB 上限 |
| 上稿本身带守卫违规被合并保留 | 守卫零旁路不变量：违规文件必然再次被拦并成为下一轮修正目标 |
| 模型滥用 blocked 逃避工作 | blockedReason 必填否则按空操作违规；每派发仅扩界 1 次；二次 blocked 即失败留痕 |
| 扩界巨批引入批内不一致 | 扩界模板明确要求 id 与引用一致；不一致由守卫拦截后进修正模式（单文件收敛），不再全量重掷 |
| expectedFiles 完成度误报 | 宽松阈值（<50% 且缺 ≥3 文件）+ 仅 REWRITE 提示不硬失败 + 无契约跳过 |

每个 Phase（乃至每个任务）独立提交，可单独 revert；Phase 1 是核心收益，Phase 2/3 可独立取舍，Phase 4 解决"前置缺失死锁"（真机 job #23 案例）。
