# Hermes 执行流水线整改方案

> 状态（2026-06-09）：**Phase A / B / C / D 全部已实现，`:app:testDebugUnitTest` 全量通过。** 下方复选框保留为实现记录。

> 目标读者：实现此方案的 AI 工作者 / 开发者。逐任务实现，步骤用复选框（`- [ ]`）跟踪。

## 背景与问题

Hermes 第一版（见 `2026-06-09-hermes-execution-pipeline.md`）在 `AgentService.createAndApplyTaskOperations(...)` 的每个 attempt 里，于 apply 之前串了三道会 `continue` 消耗重试次数的评审闸，并与 apply 时的真实策略错误**共用同一个 `POLICY_REWRITE_ATTEMPTS = 5` 预算**：

1. Context Scout（云端，`attempt > 1` 即触发）
2. Coder（云端）
3. **确定性 preflight**（`TaskOperationsPreflight.review`）—— 最后一轮 REWRITE 时**直接 throw 让任务失败**
4. **Hermes 云端 Reviewer**（云端）
5. 本地 guard preflight
6. apply → `AndroidSourceGuard` / `DependencyGuard`

实测症状：**经常卡在执行任务阶段失败**。根因：

- **预算蚕食**：三道前置评审 + 真实策略错误共用 5 次预算，代码常常一次都没真正 apply 就把次数耗光。
- **确定性 preflight 误报 + 硬失败**（`TaskOperationsPreflight.java`）：
  - `MAX_OPERATIONS_PER_TASK = 8` 太低，"搭页面"类任务（drawable+selector+layout+Activity+strings）必然超限 → 每轮被判 "Too many operations" → 永不满足 → attempt 5 **throw**。
  - `firstMissingResourceReference` 拿引用比对**被截断/摘要过的 snapshot** 建的资源索引 → 项目里真实存在但没进快照的资源被判 "missing" → 误报 REWRITE。
  - 正则 `\bR\.(\w+)\.(\w+)` 会把 `android.R.id.home` 这类框架资源误判成缺失的项目资源。
  - 最后一轮是 throw 而非 fallback，与原设计"任何失败都 fallback"相悖。
- **Hermes 云端 Reviewer 是反模式**：弱/同级模型复审 Coder 产物投 ok/rewrite/fallback，倾向 rewrite 就空转烧次数；真正决定能否构建的是 `AndroidSourceGuard` + 真实编译器，它都没改变。
- **Context Scout 鸡肋**：与已实现的"失败后按报错重聚焦快照"高度重叠，且在全新任务（`attempt > 1`）也空跑。

**整改原则**：apply 路径回到「Coder → 确定性源码守卫 → 真实构建」；前置评审只保留**零误报、反映真实构建破坏**的确定性检查，且**永不硬失败**、**不蚕食策略错误预算**。删除/下线净负的弱复审与多余侦察。

**技术栈**：Java、`:app` 模块、OpenAI-compatible、`org.json`、JUnit 4、Gradle `testDebugUnitTest`。

---

## Phase A — 止血 + 去误报（最高优先，改动小、风险低）

### 任务 A1：确定性 preflight 永不让任务硬失败

文件：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] 在 `createAndApplyTaskOperations(...)` 的确定性 preflight 分支（当前约 L427–L439）：
  - 删除 `if (attempt == POLICY_REWRITE_ATTEMPTS) { throw new IllegalArgumentException("Deterministic preflight blocked ...") }`。
  - 改为：仅当 `attempt < POLICY_REWRITE_ATTEMPTS` 时才走 REWRITE（`continue`）；最后一轮**放行**到 apply，交给 `AndroidSourceGuard` / 真实构建裁决。
  - 形态：
    ```java
    if (deterministicReview.decision == HermesReview.Decision.REWRITE
            && hasText(deterministicReview.rewriteInstruction)
            && attempt < POLICY_REWRITE_ATTEMPTS) {
        previousFailure = HermesReviewerPolicy.rewriteContext(deterministicReview);
        retryContext = mergeRetryContext(retryContext, previousFailure);
        instruction = LocalGuardInstructionComposer.forPreflightRewrite(instruction, previousFailure);
        snapshot = sourceSnapshot(sourceDir);
        continue;
    }
    // 否则放行，进入 Hermes/local 评审与 apply
    ```
- [ ] 不再有任何 `throw` 来自前置评审。

### 任务 A2：收紧 `TaskOperationsPreflight`，删除误报源

文件：`app/src/main/java/com/androidbuilder/agent/TaskOperationsPreflight.java`

- [ ] `MAX_OPERATIONS_PER_TASK`：`8 → 30`（仅防御荒谬批量，正常多文件任务放行）。或保留检查但仅在 `> 30` 时返回 REWRITE，文案改为"操作数异常多，考虑拆分"。
- [ ] **删除缺失资源跨快照比对**：移除 `firstMissingResourceReference(...)` 调用及其分支（当前约 L64–L74），并删除随之变为死代码的 `ResourceIndex` / `ResourcePath` 内部类、`VALUE_RESOURCE_PATTERN` / `ID_DECLARATION_PATTERN` / `XML_RESOURCE_REF_PATTERN` / `JAVA_RESOURCE_REF_PATTERN` / `SECTION_PATTERN`。
  - 理由：资源是否存在由 `AndroidSourceGuard` 在**完整写入树**上判定才可靠；在截断快照上比对必然误报（含 `android.R.*`）。
- [ ] 保留：XML 良构校验 + 禁 DOCTYPE（`xmlError` / `DOCTYPE_PATTERN`）、`reviewJavaRImport`（子包 Java 用 R 缺 import，是真实高频构建破坏、低误报）、`MAX_OPERATIONS_PER_TASK`（放宽后）。
- [ ] `review(...)` 最终只剩：操作数 sanity → XML 良构 → R import。

文件：`app/src/test/java/com/androidbuilder/agent/TaskOperationsPreflightTest.java`

- [ ] 删除/改写依赖缺失资源比对的用例；新增：
  - 12 个小操作的批量返回 `OK`（不再因 >8 被判 REWRITE）。
  - 含 `android.R.id.home` 的 Java 写入返回 `OK`（不再误报）。
  - 缺 `import <ns>.R;` 的子包 Java 仍返回 `REWRITE`（保留的真实检查）。
  - 畸形 XML / DOCTYPE 仍返回 `REWRITE`。

---

## Phase B — 下线弱复审与多余侦察（单独一轮）

### 任务 B1：从执行闭环移除 Hermes 云端 Reviewer

文件：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] 在 `createAndApplyTaskOperations(...)` 删除 Hermes 云端 Reviewer 调用与其重试分支（当前约 L440–L458：`reviewOperationsWithHermes(...)` + `HermesReviewerPolicy.shouldRetry(...)` → `continue`）。
- [ ] 删除现在不再被调用的私有方法 `reviewOperationsWithHermes(...)` 及仅服务它的 `hermesReviewerTitle(...)`（若无其他引用）。
- [ ] 保留类 `HermesReview` / `HermesReviewParser` / `HermesReviewerPolicy` 与 `OpenAiClient.reviewTaskOperations(...)`（仍被确定性 preflight 复用 `HermesReview` 类型 + 其单测覆盖），仅"从热路径解线"，便于将来可选恢复。
- [ ] 决策记录：Reviewer 是"弱复审强模型产物"的反模式；裁决权交回 `AndroidSourceGuard` + 真实构建。

### 任务 B2：Context Scout 仅限 repair / 真实失败重试

文件：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`

- [ ] `shouldNegotiate(...)`：移除 `|| attempt > 1` 这一项。改为：
  ```java
  return (retryLikeFlow && hasText(previousFailure)) || hasText(policyError);
  ```
  即只在「修复流/带真实失败摘要」或「已发生策略错误」时侦察，全新任务的普通重试不再空跑 Scout。

文件：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`

- [ ] 更新：`shouldNegotiate(true, 2, "", "")` 由 true 改为 **false**（仅 `attempt>1` 不再触发）；保留 `repairFlow+previousFailure` / `policyError` 触发为 true 的用例。

---

## Phase C — 预算隔离（可选，进一步加固）

### 任务 C1：前置结构评审与策略错误预算分离

文件：`app/src/main/java/com/androidbuilder/agent/AgentService.java`

- [ ] 给"前置结构 REWRITE"（确定性 preflight + 本地 guard heuristics）单设一个小上限 `PREFLIGHT_REWRITE_BUDGET = 2`，与 `POLICY_REWRITE_ATTEMPTS`（真实策略错误）解耦：前置评审最多触发 2 次重写，之后即使仍建议 rewrite 也放行到 apply，把 5 次预算留给真正的 `AndroidSourceGuard` 策略错误循环。
- [ ] 仅当 A/B 落地后仍观察到"前置评审占用过多次数"时再做；否则可跳过。

---

## Phase D — 回归验证

- [ ] 受影响单测：
  ```bash
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest \
    --tests com.androidbuilder.agent.TaskOperationsPreflightTest \
    --tests com.androidbuilder.agent.ContextNegotiationPolicyTest \
    --tests com.androidbuilder.agent.HermesReviewParserTest \
    --tests com.androidbuilder.agent.HermesReviewerPolicyTest \
    --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
  ```
- [ ] 全量：`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
- [ ] 如可行：`./gradlew assembleDebug --stacktrace`（环境受限失败需如实记录，不谎报通过）。
- [ ] 真机抽测：对一个"搭页面 + 多文件"的任务跑执行，确认不再卡在执行阶段失败；翻 `AiConversationRecord`，确认每个 attempt 的云端往返从 ~3 次降回 ~1 次（无 Scout/Reviewer 空跑）。

## 验收标准

- 执行任务阶段不再因前置评审而 throw；apply 路径为 Coder → 确定性源码守卫 → 真实构建。
- `TaskOperationsPreflight` 仅保留零误报硬检查（XML/DOCTYPE/R-import/操作数 sanity）。
- 全新任务不再触发 Context Scout；Hermes 云端 Reviewer 不在热路径。
- 一次任务的云端调用次数显著下降；`POLICY_REWRITE_ATTEMPTS` 预算回归给真实修复循环。
- 全量单测通过。

## 风险与回滚

- 保留 Hermes/Scout 全部类与单测（仅解线），可随时恢复。
- 删除的"缺失资源比对"职责由 `AndroidSourceGuard` 在完整树上覆盖，能力不降反升（无截断误报）。
- 每个 Phase 独立可提交、可回滚；建议 A → B →（按需）C 顺序落地，每步跑全量单测。
