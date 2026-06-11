# Hermes 执行流水线第一版实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有计划执行与构建修复链路中接入 App 内置 Hermes 第一版流水线，让云端模型先做上下文侦察，再做文件操作生成，并在写入前通过 Reviewer 做一次结构化预审，降低反复重建项目和跨文件 API 不一致导致的失败率。

**架构：** 第一版不替换现有 `AgentService`、`OpenAiClient`、`FileOperationsWriter` 和本地 guard，而是在它们之上新增 Hermes-compatible 的 `Context Scout`、`Reviewer` 和策略类。`Context Scout` 负责把失败摘要、构建日志、相关源码转成 focused context 与 `patchIntent`；`Reviewer` 输出 `ok/rewrite/fallback`，由 `AgentService` 在 apply 前决定继续、重写或回退到现有 guard。

**技术栈：** Java、Android app module、OpenAI-compatible Chat Completions、`org.json`、JUnit 4、Gradle `testDebugUnitTest`。

---

## 文件结构

- 创建：`app/src/main/java/com/androidbuilder/model/ContextNegotiation.java`  
  职责：保存 `HermesContextScout` 的结构化响应，包括 `ready`、`neededFiles`、`focusTerms`、`riskNotes` 和 `patchIntent`。
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java`  
  职责：解析和裁剪 `Context Scout` JSON，忽略 unsafe path，拒绝无 JSON 或空 `patchIntent`。
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`  
  职责：决定何时触发 context scout、生成 focused source 文本、生成给 Coder 的 retry/repair context。
- 创建：`app/src/main/java/com/androidbuilder/model/HermesReview.java`  
  职责：保存 `HermesReviewer` 的决策、摘要和重写指令。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesReviewParser.java`  
  职责：解析 `ok/rewrite/fallback` Reviewer JSON，裁剪文本并拒绝非法 decision。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesReviewerPolicy.java`  
  职责：把 Reviewer 决策转换为是否重试、是否 fallback、给 Coder 的重写上下文。
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`  
  职责：新增 `negotiateTaskContext(...)`、`reviewTaskOperations(...)` 和可测试 prompt builder；让 `createTaskOperations(...)` 支持附加 retry context。
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`  
  职责：在 failed task、policy retry 和 build repair 中调用 `Context Scout`；在 apply 前调用 `HermesReviewer`，失败时 fallback 到现有本地 guard。
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesReviewParserTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesReviewerPolicyTest.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`

## 任务 1：解析 `HermesContextScout` JSON

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java`
- 创建：`app/src/main/java/com/androidbuilder/model/ContextNegotiation.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java`

- [ ] **步骤 1：编写失败的解析器测试**

测试覆盖：

```java
ContextNegotiation result = ContextNegotiationParser.fromJson("{"
        + "\"ready\":false,"
        + "\"neededFiles\":[\"app/src/main/java/com/example/DBHelper.java\",\"../secret.txt\"],"
        + "\"focusTerms\":[\"DBHelper\",\"RecordDao\"],"
        + "\"riskNotes\":[\"Keep DAO signatures synchronized.\"],"
        + "\"patchIntent\":\"Modify existing DAO only; do not recreate the project.\""
        + "}");
```

断言：

```java
assertFalse(result.ready);
assertEquals(1, result.neededFiles.size());
assertEquals("app/src/main/java/com/example/DBHelper.java", result.neededFiles.get(0));
assertTrue(result.patchIntent.contains("do not recreate"));
```

另写测试验证：

```java
assertThrows(IllegalArgumentException.class, () -> ContextNegotiationParser.fromJson("not json"));
assertThrows(IllegalArgumentException.class, () -> ContextNegotiationParser.fromJson("{\"ready\":true,\"patchIntent\":\"\"}"));
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationParserTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `ContextNegotiation` 或 `ContextNegotiationParser`。

- [ ] **步骤 3：实现模型与解析器**

实现要点：

```java
public class ContextNegotiation {
    public final boolean ready;
    public final List<String> neededFiles;
    public final List<String> focusTerms;
    public final List<String> riskNotes;
    public final String patchIntent;
}
```

`ContextNegotiationParser.fromJson(...)` 要：

```java
JSONObject json = new JSONObject(extractJson(raw));
String patchIntent = cap(json.optString("patchIntent", "").trim(), MAX_PATCH_INTENT_CHARS);
if (patchIntent.isEmpty()) {
    throw new IllegalArgumentException("Context negotiation patchIntent is empty.");
}
```

路径过滤必须调用：

```java
String path = PathValidator.normalizeGeneratedPath(raw);
if (AgentService.isTextSourceFile(path)) {
    values.add(path);
}
```

- [ ] **步骤 4：运行解析器测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationParserTest
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/androidbuilder/model/ContextNegotiation.java app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java
git commit -m "feat: parse hermes context scout responses"
```

## 任务 2：实现上下文协商策略

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`

- [ ] **步骤 1：编写失败的策略测试**

测试覆盖：

```java
assertFalse(ContextNegotiationPolicy.shouldNegotiate(false, 1, "", ""));
assertTrue(ContextNegotiationPolicy.shouldNegotiate(true, 1, "previous task failed", ""));
assertTrue(ContextNegotiationPolicy.shouldNegotiate(false, 2, "", "Generated source policy blocked missing method."));
```

并验证 `focusText(...)` 包含 `neededFiles`、`focusTerms` 和失败摘要，`retryContext(...)` 包含：

```text
Do not recreate the project
Previous failure summary
Negotiated patch intent
Negotiated risk notes
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `ContextNegotiationPolicy`。

- [ ] **步骤 3：实现策略类**

核心 API：

```java
final class ContextNegotiationPolicy {
    static final int MAX_NEGOTIATION_ROUNDS = 2;

    static boolean shouldNegotiate(boolean retryLikeFlow, int attempt, String previousFailure, String policyError) {
        return (retryLikeFlow && hasText(previousFailure)) || attempt > 1 || hasText(policyError);
    }

    static String focusText(ContextNegotiation result, String failureText) { ... }

    static String retryContext(String previousFailure, ContextNegotiation result) { ... }
}
```

`retryContext(...)` 必须固定加入：

```text
This is a retry or repair of an existing source tree.
Do not recreate the project.
Modify only the files needed for the current task.
Use the shown source as authoritative.
```

- [ ] **步骤 4：运行策略测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java
git commit -m "feat: add hermes context policy"
```

## 任务 3：扩展 `OpenAiClient` 的 Context Scout 与 Coder prompt

**文件：**
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`

- [ ] **步骤 1：编写失败的 prompt 测试**

在 `OpenAiClientTest` 追加测试：

```java
String system = OpenAiClient.contextNegotiationSystemPromptForTest(false);
String user = OpenAiClient.contextNegotiationUserPromptForTest(
        "# Engineering Plan\nUpdate records",
        "Fix DAO mismatch",
        "Make RecordDao constructor match callers",
        "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
        "- Keep CSV export",
        "Generated source policy blocked constructor argument mismatch.",
        false);

assertTrue(system.contains("Return only compact JSON"));
assertTrue(system.contains("neededFiles"));
assertTrue(system.contains("patchIntent"));
assertTrue(user.contains("Previous failure summary"));
assertTrue(user.contains("Current source snapshot"));
```

再追加 Coder retry context 测试：

```java
String prompt = OpenAiClient.taskOperationsUserPromptForTest(
        "# Engineering Plan\nUpdate DAO",
        "Fix DAO",
        "Synchronize constructor",
        "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
        "- Keep export screen",
        "This is a retry or repair of an existing source tree.\nDo not recreate the project.");

assertTrue(prompt.contains("Additional retry/repair context"));
assertTrue(prompt.contains("Do not recreate the project"));
assertTrue(prompt.contains("Execute exactly this task"));
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：FAIL，包含 `cannot find symbol`，指向新增的 `OpenAiClient.*ForTest` 方法。

- [ ] **步骤 3：实现 `createTaskOperations` 重载和 prompt builder**

新增重载：

```java
public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, boolean chinese) throws Exception
```

旧签名保留并委托新签名：

```java
return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, "", chinese);
```

`taskOperationsUserPrompt(...)` 统一生成 `Approved engineering plan`、`Recent user requirements and clarifications`、`Additional retry/repair context`、`Current source tree` 和 `Execute exactly this task`。

- [ ] **步骤 4：实现 `negotiateTaskContext` 和测试 helper**

新增：

```java
public String negotiateTaskContext(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) throws Exception
```

系统 prompt 必须说明：

```text
Do not write code and do not return file operations.
Return only compact JSON with keys ready, neededFiles, focusTerms, riskNotes, and patchIntent.
patchIntent must ... modify existing files, not recreate the project.
```

- [ ] **步骤 5：运行 `OpenAiClientTest` 验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/OpenAiClient.java app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java
git commit -m "feat: add hermes context scout prompts"
```

## 任务 4：解析 `HermesReviewer` JSON

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesReviewParserTest.java`
- 创建：`app/src/main/java/com/androidbuilder/model/HermesReview.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesReviewParser.java`

- [ ] **步骤 1：编写失败的 Reviewer 解析测试**

测试 `ok`：

```java
HermesReview result = HermesReviewParser.fromJson("{"
        + "\"decision\":\"ok\","
        + "\"summary\":\"Patch is focused.\","
        + "\"rewriteInstruction\":\"\""
        + "}");
assertEquals(HermesReview.Decision.OK, result.decision);
assertEquals("Patch is focused.", result.summary);
```

测试 `rewrite`：

```java
HermesReview result = HermesReviewParser.fromJson("{"
        + "\"decision\":\"rewrite\","
        + "\"summary\":\"DAO and caller disagree.\","
        + "\"rewriteInstruction\":\"Rewrite RecordDao and caller together.\""
        + "}");
assertEquals(HermesReview.Decision.REWRITE, result.decision);
assertTrue(result.rewriteInstruction.contains("RecordDao"));
```

测试非法 decision：

```java
assertThrows(IllegalArgumentException.class, () -> HermesReviewParser.fromJson("{\"decision\":\"maybe\"}"));
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesReviewParserTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `HermesReview` 或 `HermesReviewParser`。

- [ ] **步骤 3：实现模型和解析器**

`HermesReview`：

```java
public class HermesReview {
    public enum Decision { OK, REWRITE, FALLBACK }
    public final Decision decision;
    public final String summary;
    public final String rewriteInstruction;
}
```

`HermesReviewParser`：

```java
String decision = json.optString("decision", "").trim().toLowerCase(Locale.ROOT);
if ("ok".equals(decision)) return new HermesReview(HermesReview.Decision.OK, summary, rewrite);
if ("rewrite".equals(decision)) return new HermesReview(HermesReview.Decision.REWRITE, summary, rewrite);
if ("fallback".equals(decision)) return new HermesReview(HermesReview.Decision.FALLBACK, summary, rewrite);
throw new IllegalArgumentException("Hermes reviewer decision is invalid: " + decision);
```

- [ ] **步骤 4：运行 Reviewer 解析测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesReviewParserTest
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/androidbuilder/model/HermesReview.java app/src/main/java/com/androidbuilder/agent/HermesReviewParser.java app/src/test/java/com/androidbuilder/agent/HermesReviewParserTest.java
git commit -m "feat: parse hermes reviewer responses"
```

## 任务 5：实现 Reviewer prompt 与策略

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesReviewerPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesReviewerPolicy.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`

- [ ] **步骤 1：编写失败的 Reviewer 策略测试**

测试：

```java
HermesReview rewrite = new HermesReview(
        HermesReview.Decision.REWRITE,
        "DAO and caller mismatch.",
        "Rewrite DAO and caller together.");

assertTrue(HermesReviewerPolicy.shouldRetry(rewrite, 1, 5));
assertFalse(HermesReviewerPolicy.shouldRetry(rewrite, 5, 5));
assertTrue(HermesReviewerPolicy.rewriteContext(rewrite).contains("HermesReviewer requested rewrite"));
```

并验证 `fallback` 不触发重试：

```java
HermesReview fallback = new HermesReview(HermesReview.Decision.FALLBACK, "Reviewer unavailable.", "");
assertFalse(HermesReviewerPolicy.shouldRetry(fallback, 1, 5));
```

- [ ] **步骤 2：编写失败的 Reviewer prompt 测试**

在 `OpenAiClientTest` 追加：

```java
String system = OpenAiClient.hermesReviewSystemPromptForTest(false);
String user = OpenAiClient.hermesReviewUserPromptForTest(
        "Fix DAO",
        "Synchronize DAO and caller",
        "--- app/src/main/java/com/example/RecordDao.java ---\nclass RecordDao {}",
        "{\"summary\":\"Changed DAO\",\"operations\":[]}",
        "Patch existing DAO only.");

assertTrue(system.contains("ok"));
assertTrue(system.contains("rewrite"));
assertTrue(system.contains("fallback"));
assertTrue(user.contains("Generated operations JSON"));
assertTrue(user.contains("Context Scout notes"));
```

- [ ] **步骤 3：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesReviewerPolicyTest
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：FAIL，分别指向 `HermesReviewerPolicy` 和 `OpenAiClient` Reviewer helper。

- [ ] **步骤 4：实现 `HermesReviewerPolicy`**

核心 API：

```java
final class HermesReviewerPolicy {
    static boolean shouldRetry(HermesReview review, int attempt, int maxAttempts) { ... }
    static boolean shouldFallback(HermesReview review) { ... }
    static String rewriteContext(HermesReview review) { ... }
}
```

`rewriteContext(...)` 生成：

```text
HermesReviewer requested rewrite before applying generated operations.
Reviewer summary:
...
Rewrite instruction:
...
```

- [ ] **步骤 5：实现 `reviewTaskOperations` 和 prompt helper**

`OpenAiClient` 新增：

```java
public String reviewTaskOperations(String taskTitle, String taskInstruction, String sourceSnapshot, String operationsJson, String contextScoutNotes, boolean chinese) throws Exception
```

系统 prompt 返回 compact JSON：

```json
{"decision":"ok","summary":"...","rewriteInstruction":""}
```

允许 decision：`ok`、`rewrite`、`fallback`。

- [ ] **步骤 6：运行策略与 prompt 测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesReviewerPolicyTest
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/HermesReviewerPolicy.java app/src/main/java/com/androidbuilder/agent/OpenAiClient.java app/src/test/java/com/androidbuilder/agent/HermesReviewerPolicyTest.java app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java
git commit -m "feat: add hermes reviewer prompts"
```

## 任务 6：接入 `AgentService` 执行闭环

**文件：**
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] **步骤 1：编写失败的集成策略测试**

在 `AgentServiceRetryPolicyTest` 追加：

```java
assertTrue(AgentService.contextNegotiationRoundsForTest() <= 2);
assertTrue(AgentService.policyRewriteAttemptsForTest() >= 5);
```

并添加纯文本日志测试：

```java
String request = AgentService.taskOperationsRequestForAiLogForTest(
        "# Plan",
        "Fix DAO",
        "Instruction",
        "Snapshot",
        "Retry context",
        2);

assertTrue(request.contains("Attempt: 2"));
assertTrue(request.contains("Additional retry/repair context"));
assertTrue(request.contains("Retry context"));
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：FAIL，包含 `cannot find symbol`，指向新增的 `AgentService.*ForTest` 方法。

- [ ] **步骤 3：在 `createAndApplyTaskOperations(...)` 中加入 Context Scout**

实现要点：

```java
String retryContext = "";
ContextNegotiation negotiation = null;
if (ContextNegotiationPolicy.shouldNegotiate(retryLikeFlow, attempt, previousFailure, policyErrorText)) {
    negotiation = negotiateContextWithFallback(...);
    snapshot = sourceSnapshot(sourceDir, ContextNegotiationPolicy.focusText(negotiation, previousFailure));
    retryContext = ContextNegotiationPolicy.retryContext(previousFailure, negotiation);
}
```

第一版触发条件：

- `repairBuild(...)` 传入 build log 或 triage instruction 作为 `previousFailure`。
- policy retry 捕获 `IllegalArgumentException` 后，下一轮传入 policy error。
- 已失败任务再次执行时，从 `ProjectTaskRecord.resultSummary` 作为 `previousFailure`。

日志标题使用：

```text
Hermes · Context Scout #1
Hermes · Repair Context Scout #1
```

任何解析失败或云端失败都 fallback 到现有行为，并写入 AI conversation log。

- [ ] **步骤 4：在 apply 前加入 HermesReviewer**

实现顺序：

```text
TaskOperationsParser.fromJson
HermesReviewer cloud review
LocalGuardHeuristics / local llama guard
FileOperationsWriter.apply
```

Reviewer `rewrite` 且还有 attempt 时：

```java
instruction = LocalGuardInstructionComposer.forPreflightRewrite(
        instruction,
        HermesReviewerPolicy.rewriteContext(review));
snapshot = sourceSnapshot(sourceDir);
continue;
```

Reviewer `fallback`、解析失败或调用失败时继续走现有本地 guard。

日志标题使用：

```text
Hermes · Reviewer #1
Hermes · Repair Reviewer #1
```

- [ ] **步骤 5：运行 `AgentServiceRetryPolicyTest` 验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/AgentService.java app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java
git commit -m "feat: wire hermes pipeline into task execution"
```

## 任务 7：回归验证

**文件：**
- 所有上述变更文件。

- [ ] **步骤 1：运行 Hermes 相关聚焦测试**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationParserTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest --tests com.androidbuilder.agent.HermesReviewParserTest --tests com.androidbuilder.agent.HermesReviewerPolicyTest
```

预期：PASS。

- [ ] **步骤 2：运行 Agent/OpenAI 相关测试**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest --tests com.androidbuilder.agent.TaskOperationsPromptPolicyTest
```

预期：PASS。

- [ ] **步骤 3：运行完整单元测试**

运行：

```bash
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 4：运行可行的 Debug 构建**

运行：

```bash
./gradlew assembleDebug --stacktrace
```

预期：exit 0。若因为环境或既有改动失败，记录完整失败摘要，不宣称构建通过。

- [ ] **步骤 5：Commit 验证后文档状态**

如计划文件勾选了已完成项，提交：

```bash
git add docs/superpowers/plans/2026-06-09-hermes-execution-pipeline.md
git commit -m "docs: add hermes implementation plan"
```

## 自检

- 规格覆盖：覆盖了 Hermes 设计中的 Phase 1 `Context Scout` 和 Phase 2 `Reviewer`；`Build Triage`、Planner metadata、durable Hermes runs 明确留到后续阶段。
- 安全边界：所有云端角色失败都 fallback；确定性 guard 与 `FileOperationsWriter` 仍是最终权威。
- 循环限制：Context Scout 最多 2 轮，Reviewer 每个 attempt 只调用 1 次，整体仍受 `POLICY_REWRITE_ATTEMPTS` 限制。
- 日志要求：每个 Hermes 角色调用写入现有 `ai_conversations`，标题角色化。
