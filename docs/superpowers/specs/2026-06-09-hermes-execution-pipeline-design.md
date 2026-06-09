# Hermes 执行流水线设计

## 目标

在 App 内加入 Hermes 风格的多角色执行流水线，用来提升自动生成 Android 项目的构建成功率。Hermes 负责编排规划、上下文收集、代码生成、写入前审查和构建失败分诊；现有本地构建后端和确定性校验防线继续保留。

## 已确认方案

Hermes 作为当前 `AgentService` 编码流程之上的编排层。第一版聚焦执行质量和修复质量，不替换 Embedded Runtime、Termux、Gradle 或 APK 安装流程。

Hermes 将目前云端模型承担的宽泛职责拆成边界清晰的角色：

- `HermesPlanner`：把用户需求和已批准工程计划拆成更小的工作包，包含预期文件、验收检查和风险提示。
- `HermesContextScout`：编码前收集正确的项目上下文，包括最近需求、历史失败、相关源码、已完成任务摘要和构建日志片段。
- `HermesCoder`：只生成小范围 `TaskOperations` JSON patch。
- `HermesReviewer`：写入前审查模型生成的 operations；当 patch 过宽、跨文件不一致或明显可能失败时，要求重写。
- `HermesBuildTriage`：把构建失败分类为源码、资源、Gradle/依赖、环境/工具链等类别，并生成聚焦修复上下文。
- `HermesOrchestrator`：负责状态机、角色调用上限、日志、fallback 行为和任务流转。

## 非目标

- 第一版不替换 `EmbeddedRuntimeBackend`、`ExternalTermuxBackend`、`LocalBuildServer` 或 APK 安装流程。
- 第一版不自动运行无上限修复循环。除非后续设计明确调整，否则 Build 和 Repair 仍由用户触发。
- 不削弱 `FileOperationsWriter`、`DependencyGuard`、`AndroidSourceGuard` 或必需工程文件校验。
- 不要求新增模型供应商。Hermes 使用现有 OpenAI-compatible 设置和本地 guard 能力。

## 与后台上下文协商的关系

已设计的后台上下文协商不再是独立竞争路径，而是 Hermes 的第一阶段。

映射关系：

- `ContextNegotiation` 成为第一版 `HermesContextScout` 的响应形态。
- `patchIntent` 成为 `HermesCoder` 的输入。
- `riskNotes` 成为 `HermesReviewer` 和 retry context 的输入。
- AI 对话日志标题逐步改为角色化名称，例如 `Hermes · Context Scout`、`Hermes · Coder`、`Hermes · Reviewer`、`Hermes · Build Triage`。

现有 `Background Context Negotiation` 实现计划仍可优先执行。新增类名应能独立工作，也能后续平滑迁移到 Hermes 命名空间。

## 流水线

目标流水线：

```text
user requirement
  -> HermesPlanner
  -> task queue
  -> HermesContextScout
  -> HermesCoder
  -> HermesReviewer
  -> FileOperationsWriter / guards
  -> build or next task
  -> HermesBuildTriage on build failure
  -> focused repair task
```

第一版启用范围：

- 执行已规划任务
- 重试 failed 任务
- 修复构建失败

直接的一次性项目生成可以暂时保留现有流程，等 Hermes 在计划执行链路稳定后再接入。

## 角色协议

所有角色输出都应是 compact JSON。角色可以在 JSON 字段里包含短文本，但主协议不能返回 markdown 或自由文本。

### HermesPlanner

输入：

- 用户需求或已批准工程计划
- package/applicationId
- 最近对话上下文
- 依赖模式

输出：

```json
{
  "tasks": [
    {
      "title": "Add transaction DAO",
      "instruction": "Create or update DBHelper and TransactionDao consistently.",
      "expectedFiles": [
        "app/src/main/java/com/example/DBHelper.java",
        "app/src/main/java/com/example/TransactionDao.java"
      ],
      "acceptanceChecks": [
        "DAO constructor calls match declarations.",
        "DBHelper constants used by DAO exist."
      ],
      "riskNotes": [
        "Keep model fields and adapter bindings synchronized."
      ]
    }
  ]
}
```

第一版可以继续使用现有 `ImplementationTaskParser`，后续再增加可选字段。若可选字段缺失，Hermes 回退到只使用 `title` 和 `instruction`。

### HermesContextScout

输入：

- 当前任务
- 已批准计划
- 当前源码快照
- 最近用户需求
- failed task summary、policy error 或 build-log triage
- 已完成任务摘要

输出：

```json
{
  "ready": false,
  "neededFiles": [
    "app/src/main/java/com/example/DBHelper.java"
  ],
  "focusTerms": [
    "DBHelper",
    "TransactionDao"
  ],
  "riskNotes": [
    "Check caller and constructor signatures together."
  ],
  "patchIntent": "Modify existing DBHelper and TransactionDao only; do not recreate the project."
}
```

该协议与后台上下文协商设计保持一致。

### HermesCoder

输入：

- 已批准计划
- 任务标题和任务指令
- focused source snapshot
- 最近需求
- Context Scout 的 patch intent
- 风险提示和历史失败摘要

输出：

```json
{
  "summary": "Added TransactionDao methods and synchronized DBHelper constants.",
  "operations": [
    {
      "action": "write",
      "path": "app/src/main/java/com/example/TransactionDao.java",
      "content": "..."
    }
  ]
}
```

这里复用现有 `TaskOperations` 协议。Coder 应优先返回一到两个聚焦写入操作；除非任务明确要求创建工程骨架，否则不得重新创建整个项目。

### HermesReviewer

输入：

- 当前任务
- focused source snapshot
- generated operations
- risk notes
- dependency mode

输出：

```json
{
  "decision": "ok",
  "summary": "Patch is focused and DBHelper/DAO APIs are synchronized.",
  "rewriteInstruction": ""
}
```

允许的 `decision` 值：

- `ok`：继续进入本地 apply/validation。
- `rewrite`：不要 apply；携带 `rewriteInstruction` 重试 HermesCoder。
- `fallback`：Reviewer 不可用；继续走现有本地 guard。

HermesReviewer 补充 `LocalGuardHeuristics`、本地 llama review 和确定性 guard，但不替代最终校验。

### HermesBuildTriage

输入：

- build phase
- build log excerpt
- focused source snapshot
- dependency mode

输出：

```json
{
  "category": "source",
  "repairableByModel": true,
  "focusedFiles": [
    "app/src/main/java/com/example/StatisticsActivity.java"
  ],
  "repairInstruction": "Add the missing CategorySummary.total field or update StatisticsActivity to use an existing getter.",
  "environmentAdvice": ""
}
```

分类值：

- `source`
- `resource`
- `gradle_dependency`
- `environment`
- `unknown`

当 `repairableByModel=false` 时，Hermes 不得生成源码修复 operations。UI 应继续保持现有不可修复行为。

## Orchestrator 状态机

第一版状态机：

```text
idle
  -> planning
  -> planned
  -> task_context
  -> task_coding
  -> task_review
  -> applying
  -> generated
  -> build_requested
  -> build_failed
  -> build_triage
  -> repair_context
  -> repair_coding
  -> repair_review
  -> generated
```

初期仍以现有 project plan 和 project task 状态作为持久化来源。Hermes 角色活动先通过 `ai_conversations` 和 build log 记录，不在第一版强制新增数据库表。

如果后续需要角色级恢复和调试，再新增 `hermes_runs` 和 `hermes_steps` 迁移。

## 日志与可观测性

每个角色调用都必须写入 AI conversation log。

建议标题：

- `Hermes · Planner`
- `Hermes · Context Scout #1`
- `Hermes · Coder #1`
- `Hermes · Reviewer #1`
- `Hermes · Build Triage`
- `Hermes · Repair Context Scout #1`
- `Hermes · Repair Coder #1`
- `Hermes · Repair Reviewer #1`

每条日志应包含：

- role name
- model/provider/endpoint metadata
- linked build job，若存在
- 截断后的请求上下文
- 原始结构化响应或解析失败信息
- 状态，例如 `success`、`rewrite`、`fallback`、`failed`

日志仅用于诊断，不得阻塞代码生成或修复。

## 安全限制

- Context Scout：每个任务或修复尝试最多 2 次调用。
- Coder：每个角色周期最多 2 次云端生成，然后回退到现有 policy retry 行为。
- Reviewer：每组 generated operations 最多 1 次云端 review。本地确定性 guard 仍然运行。
- Build Triage：每次用户触发 Repair 最多 1 次云端 triage。
- Orchestrator 必须通过内存 attempt counter 和现有 task failure status 避免无限循环。
- 任一 Hermes 角色解析失败、超时或返回 unsafe path 时，流程 fallback 到现有行为，并记录日志。

## 集成点

### AgentService

`AgentService` 仍是第一集成点。它应把角色工作委托给更小的类，避免继续膨胀成更大的单体。

第一版建议类：

- `HermesOrchestrator`
- `HermesContextScout`
- `HermesReviewer`
- `HermesBuildTriage`
- `HermesPrompts`
- `HermesJsonParsers`

`AgentService` 保持 UI 面向的异步方法：

- `planAsync`
- `executePlanAsync`
- `repairBuildAsync`

内部执行和修复流程在启用时调用 Hermes。

### OpenAiClient

`OpenAiClient` 暴露角色方法：

- `createHermesContextScout(...)`
- `createHermesTaskOperations(...)`，或复用带 role context 的 `createTaskOperations(...)`
- `createHermesReview(...)`
- `createHermesBuildTriage(...)`

Prompt builder 应提供 package-private 的 `ForTest` helper，保持现有测试风格。

### Local Guard

HermesReviewer 在最终 apply 前运行，但确定性校验仍是权威：

1. HermesReviewer cloud/local review
2. `LocalGuardHeuristics`
3. 启用时的本地 llama guard
4. `DependencyGuard`
5. `DatabaseContractNormalizer`
6. `AndroidSourceGuard`
7. 必需工程文件校验

### Build Failure Classifier

现有 `BuildFailureClassifier` 继续作为 UI 是否展示 Repair 的入口判断。用户选择 Repair 后，HermesBuildTriage 可以进一步生成聚焦修复指令。

## 分阶段落地

### Phase 1: Context Scout

实现后台上下文协商设计，并使用 Hermes-compatible 命名或适配器。该阶段提升 retry context，不改变任务存储或构建后端。

### Phase 2: Reviewer

在 apply operations 前增加 HermesReviewer。它应在临时源码应用前捕获过宽重写和跨文件 API 不一致。

### Phase 3: Build Triage

在 repair flow 中加入 HermesBuildTriage，让构建日志变成聚焦修复任务，而不是大段原始 prompt。

### Phase 4: Planner Task Metadata

为实现任务增加可选 `expectedFiles`、`acceptanceChecks` 和 `riskNotes`。保持与现有 task JSON 的向后兼容。

### Phase 5: Durable Hermes Runs

只有当 `ai_conversations`、`project_tasks` 和 `build_jobs` 不能满足角色级恢复/调试需求时，才新增 `hermes_runs` 和 `hermes_steps` 表。

## 测试策略

单元测试：

- 解析每个角色 JSON 协议。
- 拒绝 unsafe path 和非法 decision。
- 确认 Context Scout 输出会变成 focused source context。
- 确认 Reviewer 的 `rewrite` 会阻止 apply，并把 rewrite instruction 传给 Coder。
- 确认 Build Triage 的 `environment` 分类不会触发模型源码修复。
- 验证角色调用上限。
- 验证角色返回 invalid JSON 时会 fallback。

集成风格测试：

- 模拟 failed task retry，断言最终 Coder prompt 包含历史失败摘要和 no-recreate 指令。
- 模拟过宽 rewrite operations，断言 HermesReviewer 会在 apply 前要求 rewrite。
- 模拟 javac build log，断言 Build Triage 会生成聚焦修复指令。

手动验证：

- 运行 `./gradlew testDebugUnitTest`。
- 运行 `./gradlew assembleDebug --stacktrace`。
- 在真机或模拟器上执行一次项目创建、一次计划任务执行和一次手动 Repair。

## 第一版决策

- Hermes 先在 retry 和 repair 路径启用。普通首次 planned task execution 可在 Phase 1 和 Phase 2 稳定后再接入。
- 角色日志先使用现有 AI conversation log 存储。专用 UI filter 有价值，但不是第一版必需项。
- Planner metadata 保持向后兼容且可选。第一版先存放在现有 task JSON / instruction 解析中；durable Hermes runs 需要时再做数据库迁移。

## 第一版实现建议

从 Phase 1 和 Phase 2 一起开始：

1. 基于已经确认的后台上下文协商计划，实现 Hermes-compatible Context Scout。
2. 添加最小 HermesReviewer，支持 `ok/rewrite/fallback`。
3. Build Triage 和 Planner metadata 放到后续阶段。

这样能用较低风险换来最高的构建成功率收益：编码前上下文更准，应用 generated operations 前多一道 rewrite gate。
