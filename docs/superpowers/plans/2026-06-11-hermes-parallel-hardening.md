# Hermes 并行执行加固计划（清理泄漏 / 合并降级 / 快照瘦身 / 降噪补漏 / 单任务耗时治理）

> **面向 AI 代理的工作者：** 本计划自包含，可在没有任何对话上下文的情况下执行。逐任务实现，用复选框（`- [ ]`）跟踪进度。每个任务先写失败测试、再实现、再验证、再提交。
>
> **状态：** 提案（未实现）。
> **技术栈：** Java、Android `:app` 模块、JUnit 4、Gradle。
> **全量测试命令（macOS 本机）：** `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> **基线要求：** 开工前先跑一次全量测试，必须是绿的；每个任务完成后再跑一次。

---

## 背景

本仓库是一个"手机端 AI 开发 Agent"App：云端模型按计划拆任务、生成文件操作、写入生成项目源码、在手机上用内置 Gradle 构建 APK。最近引入了 **Hermes 并行执行**：

- `HermesParallelScheduler.nextBatch(...)` 按任务契约（`allowedPaths` 等）+ 文件锁（`HermesFileLockPolicy`）选出一批互不冲突的任务；
- 每个任务由 `HermesAgentWorker.runTask(...)` 在**独立 scratch 副本**（`jobs/{jobId}/agents/task-{taskId}-agent-{i}/source`）里执行；
- 全部完成后由 `HermesMergeCoordinator.merge(...)` 统一合并回真实源码树（每个结果走 `FileOperationsWriter.apply`，该方法本身是事务性的：临时目录 + 确定性守卫校验 + 原子替换）。

架构是健全的，但有四个具体问题需要修：一个存储泄漏、一个"一票否决"式合并、一次无谓的全树读取、两条绕过降噪的新消息。

### 关键不变量（实现时必须依赖，不要破坏）

1. **同批任务文件级互斥**：`HermesParallelScheduler.nextBatch` 只把文件锁不冲突的任务放进同一批（`HermesParallelScheduler.java` 选择循环），且依赖未满足的任务不会 ready（`HermesTaskGraph.readyTasks`）。因此**逐个结果合并/跳过不会破坏同批其它任务的文件级正确性**。
2. **`FileOperationsWriter.apply` 单次调用是事务性的**：失败时真实源码树不被该次调用污染。
3. **失败任务自动重试**：调度器发现 ready 列表首位是 failed 任务时，会单独发一个 `failed_retry` 批（`HermesParallelScheduler.java` 开头几行）。所以"部分失败、其余合并"之后，失败任务下一轮自然重跑。

---

## 任务 1：清理计划执行的 agents 草稿目录（存储泄漏，最高优先）

### 现状

- 计划执行路径：`AgentService.executeParallelBatch(...)`（约 L794 起）把每个 agent 的**完整源码树副本**放在 `jobDir/agents/task-{taskId}-agent-{i}/source`，merge 之后**没有任何清理**。每执行一批 = 新 jobId = 留下 N 份全树副本，永久累积在手机存储里。
- 对照：**repair 路径已经清理了**——`AgentService` 约 L534 在开始前 `FileUtils.deleteRecursively(agentsRoot)`（`repair-agents`），且每个分片在 finally 里删除自己的 `agentRoot`（约 L668）。本任务只修计划执行路径。
- scratch 目录布局（由 `HermesAgentWorker.runTask` 创建）：`task-{id}-agent-{i}/source/`（全树副本）+ `task-{id}-agent-{i}/agent.log`（该 agent 的执行日志）。

### 目标行为

- 批次**全部结果合并成功**：整个 `jobDir/agents` 目录删除。
- **部分失败/合并被拒**：删除每个 `task-*-agent-*/source` 子目录（大头），**保留 `agent.log`** 供排查。

### 步骤

- [ ] **步骤 1：新建清理策略类 + 失败测试**

创建 `app/src/test/java/com/androidbuilder/agent/HermesScratchCleanupTest.java`（用 `org.junit.rules.TemporaryFolder`，参考同目录其它测试）：

- `deletesEverythingOnFullSuccess`：搭好 `agentsRoot/task-1-agent-0/{source/Foo.java, agent.log}`，调 `HermesScratchCleanup.afterMerge(agentsRoot, true)`，断言 `agentsRoot` 不存在。
- `keepsAgentLogsOnPartialFailure`：同样布局两个 task 目录，调 `afterMerge(agentsRoot, false)`，断言每个 `source` 目录已删、每个 `agent.log` 仍在。
- `toleratesMissingRoot`：对不存在的目录调用不抛异常。

创建 `app/src/main/java/com/androidbuilder/agent/HermesScratchCleanup.java`：final 类 + 私有构造 + `static void afterMerge(File agentsRoot, boolean fullSuccess)`。`fullSuccess` 为真时 `FileUtils.deleteRecursively(agentsRoot)`；否则遍历 `agentsRoot.listFiles()`，对每个子目录删除其 `source` 子目录。全程容忍 null/不存在，绝不抛异常（清理失败不能影响主流程）。

- [ ] **步骤 2：在 `AgentService.executePlan` 接线**

merge 处理完成后（成功分支与 catch 分支都要覆盖）调用清理。推荐做法：在 `executePlan` 里、`HermesMergeCoordinator.merge(...)` 调用之后的代码路径上：

```java
boolean fullSuccess = merge.success && firstAgentError(results) == null;
HermesScratchCleanup.afterMerge(new File(jobDir, "agents"), fullSuccess);
```

注意 catch 块里也要清理（partial 模式，保留日志）：catch 中拿不到 `results` 时按 `fullSuccess=false` 处理。`jobDir` 在方法开头已初始化，catch 中可用。

- [ ] **步骤 3：跑聚焦测试 + 全量测试，全绿后提交**

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.HermesScratchCleanupTest
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "fix: clean Hermes agent scratch trees after merge"
```

---

## 任务 2：合并按结果降级，去掉"一票否决"（次高优先）

### 现状（`HermesMergeCoordinator.java`）

1. `plan(...)`（L22-51）：任何路径冲突 → 全批 `blocked`，`mergeableResults` 清空。另有一个顺序问题：归属（owners）按输入顺序判定，但 `sortByTaskOrder` 在归属**之后**才执行（L48）——冲突时"谁是 owner"取决于输入顺序而非任务顺序。
2. `merge(...)`（L53-82）：逐结果跑 `HermesTaskContractGuard` 和 `TaskOperationsPreflight`，**任何一个 REWRITE → 整批 `MergeResult.failed`**，所有成功 agent 的云端花费作废。
3. apply 循环（L74-80）：中途某个 `writer.apply` 抛异常 → 返回 failed，但**前面已应用进真实源码树的结果**也被上层标记为失败——状态与文件不一致，下一轮白白重跑。
4. 上层 `AgentService.executePlan`（约 L327-352）：`merge.success == false` 时 `markMergeFailure(results, summary)` 把**全部**结果标失败并抛异常；`firstAgentError(results)` 在合并成功后仍会因任一 agent 失败而抛异常，导致整个 job 标失败。

### 目标行为

- **冲突降级为跳过**：路径冲突时，按任务顺序（sortOrder 小者优先，再按 id）保留先者，后者作为"skipped"带原因返回，不再全批 blocked。
- **守卫拒绝只打击违规者**：契约/preflight REWRITE 只把该结果标失败（带 summary 作为原因），其余照常合并。
- **apply 失败只打击当事者**：`writer.apply` 抛异常（事务性，树未污染）→ 该结果标失败，继续合并后续结果。
- **上层语义**：合并成功者标 done；失败/跳过者标 failed（resultSummary = 原因，调度器下一轮自动 `failed_retry`）；**仅当没有任何结果合并成功且批内确有任务时**才让整个 job 失败（维持现状的失败语义）；部分成功时 job 正常走 `generated/ready_for_build`。

### 步骤

- [ ] **步骤 1：改写 `HermesMergeCoordinatorTest` 为按结果断言（先写失败测试）**

更新/新增用例（构造 `HermesAgentResult` 的方式参考现有测试）：

- `conflictSkipsLaterTaskAndMergesEarlier`：两个成功结果触同一路径 → sortOrder 小者出现在 `mergedResults`，大者出现在新的 `failedResults`（原因含 "Path conflict"），`merge.success == true`。
- `contractRejectionOnlyFailsOffendingResult`：一个结果违反契约、一个干净 → 干净者合并，违规者带契约 summary 进 `failedResults`。
- `applyExceptionOnlyFailsOffendingResult`：可用非法操作（如 Kotlin 文件触发 `FileOperationsWriter` 的守卫异常）构造 apply 失败 → 另一个结果仍合并成功。
- `allFailedYieldsNoMergedResults`：全部违规 → `mergedResults` 为空、`failedResults` 全员。
- 保留既有通过用例的意图（全部干净 → 全部合并）。

- [ ] **步骤 2：实现 `MergeResult` 的按结果形态**

`MergeResult` 增加 `public final List<FailedResult> failedResults`（`FailedResult` 含 `HermesAgentResult result` + `String reason`，内部静态类即可）。`plan(...)` 先 `sortByTaskOrder(mergeable)` 再判归属；冲突的后者不再进全局 `conflicts` 阻塞，而是生成 FailedResult。`merge(...)` 把三类失败（冲突跳过 / 守卫拒绝 / apply 异常）都收进 `failedResults`，继续处理后续结果；`success` 定义改为"流程完成"（恒 true，除非输入为空），由调用方根据 `mergedResults`/`failedResults` 决策。删除不再使用的 `blocked/failed` 工厂方法与 `canMergeAll` 字段（同步清理引用处与测试）。

- [ ] **步骤 3：改 `AgentService.executePlan` 的上层处理**

- 合并成功者：维持现有 done 流程（更新 run 记录、task done、写日志）。
- `failedResults`：`repository.updateProjectTask(task.id, "failed", reason)`，run 记录标 failed，日志追加一行。
- 整体失败判定：`merge.mergedResults.isEmpty() && !batch.tasks.isEmpty()` 时，沿用现有失败路径（汇总原因抛 `IllegalStateException`）；否则继续正常完成流程。
- 移除 `markMergeFailure(...)` 的"全批标失败"用法（方法若无其它调用则删除）；`firstAgentError(...)` 不再用于抛错中断（部分成功时不抛），仅在"无一成功"分支参与组装原因。
- 完成消息：部分失败时在现有完成消息后追加一句（中英双语），如 `"；N 个任务失败，将自动重试"` / `"; N task(s) failed and will be retried"`。

- [ ] **步骤 4：跑聚焦 + 全量测试，全绿后提交**

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.HermesMergeCoordinatorTest
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "feat: degrade Hermes merge per result instead of all-or-nothing"
```

---

## 任务 3：合并前快照瘦身（几行的快赢）

### 现状

`HermesMergeCoordinator.sourceSnapshot(...)`（L130-195）把**整棵源码树每个文件的全文**拼成一个大字符串，只为传给 `TaskOperationsPreflight.review(operations, snapshot)`。但当前 preflight（`TaskOperationsPreflight.java`）对快照的唯一用途是 `namespaceFor(...)` 里用 `NAMESPACE_PATTERN` 提取 `namespace "..."`——也就是只需要 `app/build.gradle` 的内容。每批一次全树 IO + 大字符串在手机上纯属浪费。

### 步骤

- [ ] **步骤 1：替换快照来源**

`merge(...)` 中 `String snapshot = sourceSnapshot(canonicalSource);` 改为只读 `app/build.gradle`（不存在则空串）：

```java
String snapshot = readNamespaceSource(canonicalSource);
// 私有方法：依次尝试 app/build.gradle、app/build.gradle.kts，FileUtils.readText，任何异常返回 ""
```

- [ ] **步骤 2：删除死代码**

删除 `sourceSnapshot` / `collectSnapshotFiles` / `isExcluded` / `relativePath` / `isWithinRoot` 及相关 import（先确认仓库内无其它引用：`grep -rn "sourceSnapshot" app/src/main/java/com/androidbuilder/agent/HermesMergeCoordinator.java` 之外无人调用这些私有方法）。

- [ ] **步骤 3：测试与提交**

`HermesMergeCoordinatorTest` 若有依赖全树快照的用例，改为在临时目录写一个含 `namespace "com.generated.app"` 的 `app/build.gradle` 即可。跑聚焦 + 全量，绿后：

```bash
git add -A && git commit -m "perf: stop reading the whole tree for merge preflight"
```

---

## 任务 4：并行调度消息纳入时间线降噪（两个字面量）

### 现状

- `AgentService.executePlan` 对多任务批发的消息是 `"并行执行下一批：" / "Executing next parallel batch: "`（约 L311）。
- 时间线降噪与云端历史修剪的**唯一规则源**是 `app/src/main/java/com/androidbuilder/agent/ConversationContextPolicy.isStatusChatter(...)`（UI 的 `ProjectTimelineMessageVisibilityPolicy.isChatter` 已委托给它）。该方法的"任务执行类 chatter"分组（约 L45）含 `"Executing next step:" / "执行下一步："` 等，但**没有**并行批的两个字面量 → 并行运行时时间线噪声回归，且这些消息会混进发给规划模型的历史。
- 完成消息前缀 `"Done: " / "已完成："`（`taskCompletionMessage`，约 L1569）已被现有模式覆盖，**无需改动**。

### 步骤

- [ ] **步骤 1：补测试（先失败）**

在 `app/src/test/java/com/androidbuilder/agent/ConversationContextPolicyTest.java` 与 `app/src/test/java/com/androidbuilder/ui/ProjectTimelineMessageVisibilityPolicyTest.java` 各加断言：

```java
assertTrue(...isChatter("assistant", "Executing next parallel batch: A, B"));
assertTrue(...isChatter("assistant", "并行执行下一批：A、B"));
```

- [ ] **步骤 2：实现**

`ConversationContextPolicy.isStatusChatter` 的任务执行分组追加 `text.contains("Executing next parallel batch:") || text.contains("并行执行下一批：")`。

- [ ] **步骤 3：测试与提交**

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "fix: treat parallel batch dispatch messages as status chatter"
```

---

## 任务 5：单任务执行耗时治理（动辄几十分钟 → 目标 5~8 分钟内）

### 诊断（已逐项核实，执行者可信赖）

单个任务的执行循环住在 `AgentService.createAndApplyTaskOperations(...)`（attempt 循环约 L981 起，`POLICY_REWRITE_ATTEMPTS = 5`）。耗时是三个乘数叠加：

**乘数一：一个任务最多 ~16 次云端往返**

| 调用 | 触发条件 | 次数上限 | 代码位置 |
| --- | --- | --- | --- |
| Context Scout 侦察 | 修复流/失败重试（带失败摘要）或策略错误后 | 2 轮 | `ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS = 2`，循环在 AgentService ~L987 |
| Coder（文件操作生成） | 每个 attempt | 5 | attempt 循环内 `createTaskOperations` |
| Hermes 云端 Reviewer | `HermesReviewerPolicy.shouldReviewOperations(...)`：重试/修复流、`attempt > 1`、有侦察信号、或操作数 > 2 —— 实际任务几乎都命中 | 每 attempt 1 次，最多 5 | `reviewOperationsWithHermes`（~L1083 调用，~L1198 定义） |
| 云端守卫提示 | 每次策略拒绝且本地规则（`rewritePolicyFailureWithLocalRules`）未命中 | 最多 4 | ~L1126 `rewritePolicyFailureWithCloudGuard` |

**乘数二：单次调用偏慢**——MiniMax/DeepSeek 思考模式默认开（`OpenAiClient.KEY_THINKING` 默认 true），但 Coder/Reviewer/Scout/任务拆分都是结构化 JSON 输出，不需要推理链；每次请求驮完整 plan + 最多 18K 快照（Reviewer 还另驮 operations JSON），prefill 大；输出无上限。

**乘数三：假死上限太宽**——`OpenAiClient.MODEL_READ_TIMEOUT_MS = 10 分钟`。流式下 `setReadTimeout` 即"无任何字节的空闲超时"（思考增量也算流量），健康流不会 3 分钟无字节；10 分钟意味着一条死连接白吃 10 分钟。

### 步骤（按杠杆排序，每步独立提交）

- [ ] **步骤 5.1：观测先行——云端调用记录耗时**

`AgentService.recordCloudAiCall(...)`（私有方法，约 L"recordCloudAiCall" 处）在调用前后取 `System.currentTimeMillis()`，把 `"durationMs=" + elapsed` 追加进写给 `recordAiConversationSafely` 的 metadata（成功与失败路径都要）。无单测要求（方法依赖 Android Context），验收：真机/日志中 AI 记录含 `durationMs=`。

```bash
git add -A && git commit -m "feat: record cloud AI call duration in conversation logs"
```

- [ ] **步骤 5.2：结构化输出调用关闭思考模式（最大单点收益）**

现状：`OpenAiClient.completeChat(...)`（~L193）对所有调用统一用 `thinkingEnabledForProvider(prefs, provider)`。

实现：
1. 新增包内静态纯函数 `static boolean effectiveThinking(boolean userEnabled, boolean structuredOutput)`：`structuredOutput == true` 时恒返回 false，否则返回 `userEnabled`。
2. `completeChat` 增加 `boolean structuredOutput` 参数（或重载），`thinkingEnabled = effectiveThinking(thinkingEnabledForProvider(...), structuredOutput)`。
3. 调用方标注：`createTaskOperations`、`reviewTaskOperations`、`negotiateTaskContext`、`createImplementationTasks`、`createSpecJson`、`createProjectFilesJson` → `structuredOutput = true`；`createEngineeringPlan`（自由文本规划）→ `false`（沿用用户开关）。
4. 先写失败测试（`OpenAiClientTest`）：`assertFalse(OpenAiClient.effectiveThinkingForTest(true, true)); assertTrue(OpenAiClient.effectiveThinkingForTest(true, false)); assertFalse(OpenAiClient.effectiveThinkingForTest(false, false));`（按需加 ForTest 钩子）。

注意：思考关闭通过请求体 `thinking:{"type":"disabled"}` 实现（现有 `chatRequestBody` 已支持 thinkingEnabled=false 的分支），仅对 MiniMax/DeepSeek 生效（`supportsThinkingToggle` 已有判断），不要改动 OpenAI/custom 行为。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
git add -A && git commit -m "perf: disable thinking mode for structured-output cloud calls"
```

- [ ] **步骤 5.3：Hermes 云端 Reviewer 预算化（每任务最多 1 次）**

现状：`shouldReviewOperations` 的条件让 Reviewer 几乎每个 attempt 都打一次云端。

实现：
1. `HermesReviewerPolicy.shouldReviewOperations(...)` 增加参数 `int cloudReviewsUsed`，原条件满足且 `cloudReviewsUsed == 0` 才返回 true（**预算 = 每任务 1 次**）。
2. `AgentService.createAndApplyTaskOperations` 维护 `int cloudReviewsUsed`，调用成功（真正打了云端，含 fallback）后自增，透传给 gate。
3. 先改 `HermesReviewerPolicyTest`：原有触发用例补 `cloudReviewsUsed = 0` 仍触发；新增 `cloudReviewsUsed = 1` 时即便条件满足也返回 false。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.HermesReviewerPolicyTest
git add -A && git commit -m "perf: budget Hermes cloud review to once per task"
```

- [ ] **步骤 5.4：策略拒绝后不再打"云端守卫提示"调用**

现状（~L1123-1130）：拒绝后链路为 本地规则 → 未命中则云端守卫提示（一次完整云端调用）→ 仍未命中则本地模型。`PolicyRewriteInstruction.create(...)` 已对常见违规（lambda/synthetic view/构造器不匹配/缺资源/依赖）给出精准确定性提示，云端提示调用收益低、成本高。

实现：删除热路径中的 `rewritePolicyFailureWithCloudGuard(...)` 调用（方法与本地模型分支 `rewritePolicyFailureWithLocalGuard` 保留——本地模型分支受 `LocalGuardMode` 开关控制，默认仍可作为离线兜底；若该方法因此无引用则连同删除并清理测试）。拒绝后的指令组装退化为：本地规则命中 → `LocalGuardInstructionComposer.forPolicyRewrite(policyInstruction, hint)`；未命中 → 直接 `policyInstruction`。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest
git add -A && git commit -m "perf: drop cloud guard hint call from policy-rejection hot path"
```

- [ ] **步骤 5.5：Context Scout 轮次 2 → 1**

`ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS` 改为 1。同步更新 `ContextNegotiationPolicyTest.continuesNegotiationOnlyWhenContextScoutIsNotReadyAndRoundsRemain`（`shouldContinueNegotiation(notReady, 1)` 由 true 改为 **false**，因为 1 轮即达上限）。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest
git add -A && git commit -m "perf: cap context scout at one round"
```

- [ ] **步骤 5.6：流式空闲超时 10 分钟 → 3 分钟**

`OpenAiClient.MODEL_READ_TIMEOUT_MS` 改为 `3 * 60 * 1000`。依据：响应是流式的，`setReadTimeout` 语义是"单次 read 无字节的空闲上限"，思考/正文增量都会持续产生字节；3 分钟完全无字节≈死连接，快速失败让重试更早发生。同步更新 `OpenAiClientTest` 中断言 600000 的超时用例（改为 180000，并把方法名里的 TenMinutes 改掉）。同时更新超时报错文案无需改（已用秒数动态拼接）。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
git add -A && git commit -m "perf: tighten streaming inactivity timeout to 3 minutes"
```

- [ ] **步骤 5.7（可选，最后做）：Coder 输出上限**

仅对 `createTaskOperations` 请求体加 `max_tokens = 16384`（宽松上限，防失控长输出；MiniMax/DeepSeek 均支持）。若遇到截断导致 JSON 解析失败率上升，revert 本步即可。

### 预期账目

| 维度 | 治理前 | 治理后 |
| --- | --- | --- |
| 单任务云端调用上限 | ~16 次（2 scout + 5 coder + 5 reviewer + 4 守卫提示） | ≤ 8 次（1 scout + 5 coder + 1 reviewer + 0 守卫提示，预算化后 Hermes 预审 1 次） |
| 单次调用时长（MiniMax 思考开） | 1~5 分钟 | 结构化调用关思考后约减半 |
| 假死等待上限 | 10 分钟/次 | 3 分钟/次 |

---

## 范围外（明确不做，避免蔓延）

- `future.get()` 加超时降级（现有 10 分钟 HTTP read timeout 已兜底）。
- 并行下共享 progressListener 的计数交错（小 UX）。
- `isExclusiveBarrier` 的否定句误判（"不要改 gradle"被当屏障）与无契约任务的通配锁——先收集真机批宽数据再决定。
- 全树 hash 次数精简。

## 验收标准

1. 全量 `:app:testDebugUnitTest` 通过。
2. 多任务并行批成功后 `files/projects/{id}/jobs/{jobId}/agents/` 不存在；失败批仅剩 `agent.log`。
3. 同批一个任务被守卫拒绝时，其余任务状态为 done 且文件已落盘，仅违规任务为 failed，下一轮自动重试；job 不再因单个任务失败而整体失败（除非全军覆没）。
4. `HermesMergeCoordinator` 不再有全树读取（grep 无 `collectSnapshotFiles`）。
5. 并行批的派发消息不出现在时间线，也不进入规划历史（被 `isStatusChatter` 命中）。
6. AI 对话日志的 metadata 含 `durationMs=`，可量化每次云端调用耗时。
7. 单任务执行的云端调用：Hermes 云端审查每任务 ≤ 1 次、Scout ≤ 1 轮、策略拒绝后无"云端守卫提示"调用（可在 AI 日志中按标题清点）。
8. 真机抽测：一个含 1~2 次策略重写的任务端到端 ≤ 8 分钟（治理前为几十分钟）。

## 风险与回滚

- 每个任务独立提交，可单独 revert。
- 任务 2 改变 `MergeResult` 形态——是行为增强而非语义反转；若真机出现意外，revert 该提交即可回到全批失败的保守行为。
- 任务 1 清理失败被吞掉（best-effort），不会影响主流程。
- 任务 5 的每一步都是独立提交：若关思考后结构化输出质量肉眼可见下降（JSON 解析失败率上升），单独 revert 5.2；若审查预算化后跨文件不一致回升，单独 revert 5.3。确定性守卫（AndroidSourceGuard/DependencyGuard）不受任何一步影响，始终是最终权威。
