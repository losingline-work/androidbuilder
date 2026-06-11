# Hermes 自主执行第二阶段实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把现有 Hermes 契约、预检、审查和日志能力整合为更自主的任务执行系统：能按风险调度、识别重复失败、选择修复策略、自动触发必要构建、展示决策轨迹，并在中断后安全恢复。

**架构：** 不重写 `AgentService` 主流程，先在其周围新增小而稳定的 policy/model 类。执行链路变为：任务契约解析 -> Hermes 调度决策 -> Context Scout/文件操作生成 -> 契约与确定性预检 -> Hermes Reviewer -> apply -> 任务完成策略 -> 可选自动构建/失败治理。UI 侧只读取现有 `project_tasks` 与 `ai_conversations`，新增轻量 view model，不先扩数据库。

**技术栈：** Java、Android XML/Views、SQLite、`org.json`、JUnit 4、Gradle `testDebugUnitTest`、现有 `ai_conversations` 日志表。

---

## 文件结构

- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskDecision.java`  
  职责：描述下一步执行策略，字段包含 `action`、`reason`、`requiresContextScout`、`requiresBuildAfter`、`retryMode`。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskScheduler.java`  
  职责：根据 `HermesTaskContract`、任务状态、失败次数和构建要求决定是否执行、侦察、缩小范围或构建。
- 创建：`app/src/main/java/com/androidbuilder/agent/FailureFingerprint.java`  
  职责：保存可比较的失败摘要，包括 `source`、`code`、`path`、`symbol`、`normalizedMessage`。
- 创建：`app/src/main/java/com/androidbuilder/agent/FailureFingerprintPolicy.java`  
  职责：从 `HermesReview`、`LocalGuardResult`、policy error、build log 中抽取稳定指纹，判断重复失败。
- 创建：`app/src/main/java/com/androidbuilder/agent/RepairPlaybook.java`  
  职责：保存修复策略条目，字段包含 `id`、`matcher`、`hint`、`focusTerms`。
- 创建：`app/src/main/java/com/androidbuilder/agent/RepairPlaybookPolicy.java`  
  职责：匹配缺资源、DAO/API 不一致、布局 ID 缺失、Gradle 依赖不可用、Java lambda 等常见问题，输出小范围修复提示。
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`  
  职责：接入 scheduler、fingerprint、playbook、自动构建请求标记和 Hermes run event。
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`  
  职责：让 Context Scout 和 Reviewer prompt 接收 scheduler 决策、失败指纹、playbook hint。
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesDecisionTimelinePolicy.java`  
  职责：从 `AiConversationRecord` 中解析 Hermes 轨迹，生成 UI 可展示的阶段列表。
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesDecisionTimelineItem.java`  
  职责：UI 展示模型，字段包含 `phase`、`role`、`decision`、`summary`、`createdAt`。
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`  
  职责：任务列表按阶段折叠、显示当前任务摘要，增加 Hermes 决策轨迹入口。
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 创建测试：`HermesTaskSchedulerTest`、`FailureFingerprintPolicyTest`、`RepairPlaybookPolicyTest`、`HermesDecisionTimelinePolicyTest`
- 修改测试：`AgentServiceRetryPolicyTest`、`OpenAiClientTest`、`ProjectTaskListDisplayPolicyTest`

---

## 任务 1：任务级自主调度

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesTaskSchedulerTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskDecision.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskScheduler.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] **步骤 1：编写失败的调度测试**

测试用例：

```java
@Test
public void highRiskContractRequiresContextScoutBeforeCoding() {
    HermesTaskContract contract = new HermesTaskContract(
            Collections.emptyList(),
            Collections.singletonList("app/src/main/java/com/example/RecordDao.java"),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList("DAO callers must stay synchronized."),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            "high",
            false);

    HermesTaskDecision decision = HermesTaskScheduler.decide(contract, "", 0, false);

    assertEquals(HermesTaskDecision.Action.CODE, decision.action);
    assertTrue(decision.requiresContextScout);
    assertFalse(decision.requiresBuildAfter);
}
```

再加一个用例：

```java
@Test
public void buildRequiredAfterContractRequestsBuildAfterTask() {
    HermesTaskContract contract = new HermesTaskContract(
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), "medium", true);

    HermesTaskDecision decision = HermesTaskScheduler.decide(contract, "", 0, false);

    assertTrue(decision.requiresBuildAfter);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesTaskSchedulerTest
```

预期：FAIL，缺少 `HermesTaskScheduler` 和 `HermesTaskDecision`。

- [ ] **步骤 3：实现最小调度策略**

实现：

```java
final class HermesTaskDecision {
    enum Action { CODE, BUILD, PAUSE }
    final Action action;
    final String reason;
    final boolean requiresContextScout;
    final boolean requiresBuildAfter;
    final String retryMode;
}
```

策略：
- `riskLevel=high` 或 `riskNotes` 非空：`requiresContextScout=true`
- `buildRequiredAfter=true`：任务完成后请求构建
- 同一任务失败次数 >= 2：`retryMode="narrow_scope"`
- 默认：`Action.CODE`

- [ ] **步骤 4：在 `AgentService.executePlan` 中记录调度决策**

在 `runningTask` 切到 running 后、生成文件操作前：

```java
HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(runningTask.instruction);
HermesTaskDecision decision = HermesTaskScheduler.decide(contract, runningTask.resultSummary, runningTaskFailureCount, false);
recordHermesRunEvent(projectId, job.id, HermesRunEventFactory.forTaskDecision(job.id, runningTask, decision));
```

如果没有现成失败次数，先用 `resultSummary` 是否非空和本轮 retry 次数保守计算，不新增数据库列。

- [ ] **步骤 5：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesTaskSchedulerTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：PASS。

---

## 任务 2：重复失败指纹与反复治理

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/FailureFingerprintPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/FailureFingerprint.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/FailureFingerprintPolicy.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/RetryContextPolicy.java`

- [ ] **步骤 1：编写失败指纹测试**

测试：

```java
@Test
public void normalizesMissingResourceErrorsToSameFingerprint() {
    FailureFingerprint left = FailureFingerprintPolicy.fromPolicyError(
            "Generated source policy blocked missing XML resource reference: @color/primary in app/src/main/res/values/styles.xml.");
    FailureFingerprint right = FailureFingerprintPolicy.fromPolicyError(
            "AAPT failed: resource color/primary not found in styles.xml.");

    assertEquals(left.code, right.code);
    assertEquals("@color/primary", left.symbol);
    assertEquals("app/src/main/res/values/styles.xml", left.path);
}
```

再加重复判断：

```java
assertTrue(FailureFingerprintPolicy.isRepeated(Arrays.asList(left, right), left, 2));
```

- [ ] **步骤 2：实现指纹提取**

规则：
- 包含 `resource ... not found` 或 `missing XML resource reference`：`code="MISSING_RESOURCE"`
- 包含 `cannot find symbol`：`code="MISSING_JAVA_SYMBOL"`
- 包含 `constructor ... cannot be applied`：`code="API_SIGNATURE_MISMATCH"`
- 包含 `Could not find <group:artifact:version>`：`code="MISSING_DEPENDENCY"`
- 其余：`code="UNKNOWN_BUILD_OR_POLICY_ERROR"`

路径通过正则提取 `app/src/...`；符号提取 `@type/name`、`R.xxx.yyy` 或 javac symbol。

- [ ] **步骤 3：把重复失败接进 retryContext**

在 `AgentService.createAndApplyTaskOperations` 中，每次 rewrite/policy error 生成指纹；如果同一指纹达到 2 次：

```java
retryContext = mergeRetryContext(retryContext,
        "Repeated failure detected: " + fingerprint.code
                + "\nSwitch strategy: modify only the smallest file set named by this fingerprint. Do not broaden the patch.");
```

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.FailureFingerprintPolicyTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：PASS。

---

## 任务 3：Hermes 修复策略库

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/RepairPlaybookPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/RepairPlaybook.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/RepairPlaybookPolicy.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`

- [ ] **步骤 1：编写 playbook 匹配测试**

测试：

```java
@Test
public void missingResourcePlaybookNamesSmallestFix() {
    FailureFingerprint fingerprint = new FailureFingerprint(
            "policy", "MISSING_RESOURCE", "app/src/main/res/values/styles.xml",
            "@color/primary", "resource missing");

    RepairPlaybook match = RepairPlaybookPolicy.match(fingerprint);

    assertEquals("missing_resource", match.id);
    assertTrue(match.hint.contains("@color/primary"));
    assertTrue(match.hint.contains("values"));
}
```

- [ ] **步骤 2：实现内置策略**

内置条目：
- `missing_resource`：补 values/drawable/layout/string/color，或改引用到已有资源
- `missing_java_symbol`：补方法/字段，或改调用到已存在 API
- `api_signature_mismatch`：同步 DAO/model/adapter/activity 方法签名
- `missing_dependency`：删除不可用依赖，改用 catalog 或 Android SDK
- `java_lambda`：改匿名内部类

- [ ] **步骤 3：在修复链路优先使用 playbook**

在 `rewritePolicyFailureWithLocalRules` 和 `triageBuildFailureWithCloudGuard` 前：

```java
FailureFingerprint fingerprint = FailureFingerprintPolicy.fromPolicyError(policyError);
RepairPlaybook playbook = RepairPlaybookPolicy.match(fingerprint);
if (playbook != null) {
    return LocalGuardResult.rewrite("Hermes playbook matched: " + playbook.id, playbook.hint);
}
```

云端仍作为 fallback，不删除现有云端分诊。

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.RepairPlaybookPolicyTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：PASS。

---

## 任务 4：`buildRequiredAfter` 自动构建验证

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`
- 创建或修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`

- [ ] **步骤 1：编写策略测试**

在 `AgentServiceRetryPolicyTest` 增加：

```java
@Test
public void taskCompletionMessageMentionsBuildWhenContractRequiresIt() {
    String message = AgentService.taskCompletionMessageForTest("Update Gradle", false, true, false);

    assertTrue(message.contains("build"));
}
```

- [ ] **步骤 2：实现完成后构建标记**

在任务完成后：

```java
HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(runningTask.instruction);
boolean shouldBuild = HermesTaskScheduler.decide(contract, "", 0, false).requiresBuildAfter;
```

如果 `shouldBuild` 为 true：
- `ProjectPlanStatus` 仍按是否有下一任务更新
- assistant message 明确提示“此任务要求构建验证”
- UI 可自动调用已有 build 入口，第一版先只显示明确 CTA，不直接静默构建，避免用户手机后台耗电失控

- [ ] **步骤 3：可选自动构建开关**

如果项目已有设置页开关位置，添加：
- `auto_build_after_contract_task`
- 默认 false
- 开启后 ProjectActivity 在收到 `requiresBuildAfter` 标记时触发已有构建方法

如果没有稳定设置入口，本阶段不做设置项，只做 CTA。

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
./gradlew assembleDebug
```

预期：PASS。

---

## 任务 5：任务列表按阶段折叠与当前任务优先

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectTaskListDisplayPolicy.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/test/java/com/androidbuilder/ui/ProjectTaskListDisplayPolicyTest.java`

- [ ] **步骤 1：编写分组测试**

测试：

```java
@Test
public void groupsTasksByHermesContractProducesSignal() {
    List<ProjectTaskRecord> tasks = Arrays.asList(
            task("Gradle", instructionWithProduces("foundation")),
            task("Data", instructionWithProduces("data")),
            task("DAO", instructionWithProduces("data")));

    List<ProjectTaskListDisplayPolicy.Group> groups = ProjectTaskListDisplayPolicy.groups(tasks, true);

    assertEquals("foundation", groups.get(0).key);
    assertEquals("data", groups.get(1).key);
    assertEquals(2, groups.get(1).tasks.size());
}
```

- [ ] **步骤 2：实现折叠策略**

规则：
- 默认只展开 `running`、`failed`、第一个 `pending`
- `done` 组默认折叠
- 组名优先取 `HermesTaskContract.produces[0]`
- 没有 produces 时按标题关键词归类：`Gradle/Foundation`、`Data`、`UI`、`Stats`、`Settings`、`Polish`

- [ ] **步骤 3：更新 UI**

`ProjectActivity.TaskAdapter` 中：
- 卡片顶部显示组摘要：`数据层 · 2/3 完成`
- 展开按钮只影响当前组
- 当前任务始终可见

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.ui.ProjectTaskListDisplayPolicyTest
./gradlew assembleDebug
```

预期：PASS，任务很多时默认不再铺满整页。

---

## 任务 6：Hermes 决策轨迹面板

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/ui/HermesDecisionTimelinePolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesDecisionTimelinePolicy.java`
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesDecisionTimelineItem.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`

- [ ] **步骤 1：编写日志解析测试**

测试：

```java
@Test
public void extractsHermesEventsFromAiConversationMetadata() {
    AiConversationRecord record = new AiConversationRecord(
            1, 1, "hermes", "Hermes · orchestrator · task_execution",
            "phase: task_execution", "decision: code", "code",
            "provider=hermes-orchestrator\nhermesRunId=7:3\nhermesRole=orchestrator\nhermesPhase=task_execution",
            7L, 1000L);

    List<HermesDecisionTimelineItem> items = HermesDecisionTimelinePolicy.fromRecords(Collections.singletonList(record));

    assertEquals("task_execution", items.get(0).phase);
    assertEquals("orchestrator", items.get(0).role);
    assertEquals("code", items.get(0).decision);
}
```

- [ ] **步骤 2：实现 timeline policy**

只读取 `source="hermes"` 或 metadata 包含 `hermes` 的记录。
摘要来源优先级：
1. `responseText` 第一行 decision
2. `requestText` 中 reason
3. `title`

- [ ] **步骤 3：ProjectActivity 增加入口**

在日志/任务区域增加一个小按钮或标题行：
- 中文：`Hermes 决策`
- 英文：`Hermes decisions`

点击后显示 BottomSheet 或 Dialog，列表展示：
- 阶段
- 角色
- 决策
- 摘要
- 时间

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.ui.HermesDecisionTimelinePolicyTest
./gradlew assembleDebug
```

预期：PASS。

---

## 任务 7：安全中断恢复与守护续跑

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesRecoveryPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesRecoveryPolicy.java`
- 修改：`app/src/main/java/com/androidbuilder/data/AppRepository.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`
- 修改：`app/src/main/java/com/androidbuilder/util/ActiveWorkRegistry.java`

- [ ] **步骤 1：编写恢复策略测试**

测试：

```java
@Test
public void runningCodingJobCanRecoverToPlannedWithFailedTask() {
    HermesRecoveryPolicy.Decision decision = HermesRecoveryPolicy.decide("coding", "generating", true);

    assertEquals(HermesRecoveryPolicy.Action.MARK_TASK_FAILED_AND_ALLOW_RESUME, decision.action);
}
```

另一个测试：

```java
@Test
public void buildingJobRequiresUserRetryInsteadOfSilentResume() {
    HermesRecoveryPolicy.Decision decision = HermesRecoveryPolicy.decide("generated", "building", false);

    assertEquals(HermesRecoveryPolicy.Action.SHOW_REBUILD_PROMPT, decision.action);
}
```

- [ ] **步骤 2：实现恢复策略**

规则：
- `project_plan.status=coding` 且存在 `running` task：标记 failed，允许继续执行下一步
- `build_job.status=building`：不静默继续，提示重新构建
- `generating/repairing_build_failure`：提示继续修复或重新执行
- app foreground 时只做恢复提示，不自动启动网络调用

- [ ] **步骤 3：接入 UI**

在 `recoverInterruptedWorkIfNeeded()` 中：
- 使用 `HermesRecoveryPolicy`
- 展示更具体的提示
- 保留现有安全兜底：如果无法判断，仍标记 interrupted

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesRecoveryPolicyTest
./gradlew assembleDebug
```

预期：PASS。

---

## 任务 8：端到端验证与收尾

**文件：**
- 修改：受前面任务影响的测试文件
- 不新增生产代码，除非验证发现真实缺口

- [ ] **步骤 1：跑全量单元测试**

```bash
./gradlew testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 2：跑 Debug 构建**

```bash
./gradlew assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：手工流程验证**

在 App 中验证：
- 新计划拆分后任务列表默认折叠
- 执行高风险任务时出现 Hermes Context Scout / Orchestrator 日志
- 人为制造缺资源错误时 playbook 生成小范围提示
- 同一错误重复后 retryContext 出现 repeated failure 提示
- Hermes 决策面板能看到 task_execution、contract preflight、review
- 中断运行后回到项目页出现恢复提示

- [ ] **步骤 4：整理结果**

输出：
- 改动文件清单
- 验证命令
- 已知限制
- 下一步建议

---

## 执行建议

推荐顺序：
1. 先做任务 1、2、3：这是“变聪明”的核心，能减少反复。
2. 再做任务 5、6：这是“看得懂”的核心，能让你判断 Hermes 是否真的在工作。
3. 最后做任务 4、7：构建验证和中断恢复有更高行为影响，适合在前面稳定后接入。

每个任务完成后至少运行对应定向测试；任务 3、6、7 完成后运行 `./gradlew assembleDebug`。
