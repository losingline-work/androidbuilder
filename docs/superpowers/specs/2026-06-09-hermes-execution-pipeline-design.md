# Hermes Execution Pipeline Design

## Goal

Add an in-app Hermes-style multi-role execution pipeline to improve generated Android project build success. Hermes should coordinate planning, context gathering, code generation, review, and build-failure triage inside Android Builder while preserving the existing local build backends and validation guards.

## Approved Approach

Introduce Hermes as an orchestration layer above the current `AgentService` coding flow. The first version should focus on execution and repair quality, not on replacing Embedded Runtime, Termux, Gradle, or APK installation.

Hermes breaks the current broad cloud-model responsibility into bounded roles:

- `HermesPlanner`: turns requirements and approved plans into smaller work packages with expected files, acceptance checks, and risk notes.
- `HermesContextScout`: gathers the right project context before coding, including recent requirements, previous failures, relevant source files, completed-task summaries, and build-log excerpts.
- `HermesCoder`: produces small `TaskOperations` JSON patches only.
- `HermesReviewer`: reviews generated operations before write/apply and asks for a rewrite when the patch is too broad, inconsistent, or likely to fail validation.
- `HermesBuildTriage`: classifies build failures into source, resource, Gradle/dependency, or environment/tooling categories and creates focused repair context.
- `HermesOrchestrator`: owns the state machine, role call limits, logging, fallback behavior, and transition to the next task.

## Non-Goals

- Do not replace `EmbeddedRuntimeBackend`, `ExternalTermuxBackend`, `LocalBuildServer`, or APK install flow in the first version.
- Do not auto-run unbounded repair loops. User-triggered Build and Repair remain the outer control points unless a later design explicitly changes this.
- Do not weaken `FileOperationsWriter`, `DependencyGuard`, `AndroidSourceGuard`, or required-project-file validation.
- Do not require a new model provider. Hermes uses the existing OpenAI-compatible settings and local guard capabilities.

## Relationship To Background Context Negotiation

The background context negotiation design becomes Hermes phase 1 rather than a separate competing path.

Mapping:

- `ContextNegotiation` becomes the first `HermesContextScout` response shape.
- `patchIntent` becomes part of the `HermesCoder` prompt input.
- `riskNotes` become `HermesReviewer` and retry context input.
- AI conversation log titles move toward role-based names such as `Hermes · Context Scout`, `Hermes · Coder`, `Hermes · Reviewer`, and `Hermes · Build Triage`.

The existing `Background Context Negotiation` implementation plan can still be executed first. It should name new classes in a way that can either stand alone or be promoted into Hermes namespaces later.

## Pipeline

The target pipeline is:

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

For the first implementation, this pipeline should be enabled for:

- executing a planned task
- retrying a failed planned task
- repairing a build failure

Direct one-shot project generation may keep the current flow until Hermes proves stable.

## Role Contracts

All role outputs should be compact JSON. A role may include short human-readable strings inside JSON fields, but it must not return markdown or free-form prose as the primary contract.

### HermesPlanner

Input:

- user requirement or approved engineering plan
- package/applicationId
- recent conversation context
- dependency mode

Output:

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

First version may keep the existing `ImplementationTaskParser` and add optional fields later. If optional fields are absent, Hermes falls back to title and instruction.

### HermesContextScout

Input:

- current task
- approved plan
- current source snapshot
- recent user requirements
- failed task summary, policy error, or build-log triage when present
- completed task summaries

Output:

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

This is the same contract as the background context negotiation design.

### HermesCoder

Input:

- approved plan
- task title and instruction
- focused source snapshot
- recent requirements
- context scout patch intent
- risk notes and previous failure summary

Output:

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

This reuses the existing `TaskOperations` contract. The coder must prefer one or two focused writes and must not recreate the project unless the task explicitly requires project skeleton creation.

### HermesReviewer

Input:

- task
- focused source snapshot
- generated operations
- risk notes
- dependency mode

Output:

```json
{
  "decision": "ok",
  "summary": "Patch is focused and DBHelper/DAO APIs are synchronized.",
  "rewriteInstruction": ""
}
```

Allowed `decision` values:

- `ok`: proceed to local apply/validation.
- `rewrite`: do not apply; retry HermesCoder with `rewriteInstruction`.
- `fallback`: reviewer unavailable; proceed to existing local guards.

HermesReviewer complements `LocalGuardHeuristics`, local llama review, and deterministic guards. It never replaces final validation.

### HermesBuildTriage

Input:

- build phase
- build log excerpt
- focused source snapshot
- dependency mode

Output:

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

Categories:

- `source`
- `resource`
- `gradle_dependency`
- `environment`
- `unknown`

When `repairableByModel=false`, Hermes must not generate source repair operations. The UI should keep the existing non-repairable behavior.

## Orchestrator State Machine

First-version state machine:

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

The existing project-plan and task statuses can remain the durable storage source initially. Hermes-specific role activity can be logged through `ai_conversations` and build logs rather than requiring new tables in the first version.

If role-level resumability becomes necessary, add a later migration for `hermes_runs` and `hermes_steps`.

## Logging And Observability

Every role call must write an AI conversation log entry.

Suggested titles:

- `Hermes · Planner`
- `Hermes · Context Scout #1`
- `Hermes · Coder #1`
- `Hermes · Reviewer #1`
- `Hermes · Build Triage`
- `Hermes · Repair Context Scout #1`
- `Hermes · Repair Coder #1`
- `Hermes · Repair Reviewer #1`

Each log should include:

- role name
- model/provider/endpoint metadata
- linked build job when available
- truncated request context
- raw structured response or parse failure
- status such as `success`, `rewrite`, `fallback`, or `failed`

Logs are diagnostic and must never block code generation or repair.

## Safety Limits

- Context Scout: maximum 2 calls per task or repair attempt.
- Coder: maximum 2 cloud generations per role cycle before falling back to existing policy retry behavior.
- Reviewer: maximum 1 cloud review per generated operation set. Local deterministic guards still run.
- Build Triage: maximum 1 cloud triage per user-triggered repair.
- Orchestrator must avoid infinite loops by carrying attempt counters in memory and relying on existing task failure statuses for durable retry.
- If any Hermes role fails to parse, times out, or returns unsafe paths, the flow falls back to existing behavior and records the failure in logs.

## Integration Points

### AgentService

`AgentService` remains the first integration point. It should delegate role work to smaller classes instead of growing into a larger monolith.

Suggested first-version classes:

- `HermesOrchestrator`
- `HermesContextScout`
- `HermesReviewer`
- `HermesBuildTriage`
- `HermesPrompts`
- `HermesJsonParsers`

`AgentService` should keep UI-facing async methods:

- `planAsync`
- `executePlanAsync`
- `repairBuildAsync`

Internally, execution and repair can call Hermes when enabled.

### OpenAiClient

`OpenAiClient` should expose role-specific methods:

- `createHermesContextScout(...)`
- `createHermesTaskOperations(...)` or reuse `createTaskOperations(...)` with role context
- `createHermesReview(...)`
- `createHermesBuildTriage(...)`

Prompt builders should have package-private `ForTest` helpers, matching existing test style.

### Local Guard

HermesReviewer should run before final apply, but deterministic validation remains authoritative:

1. HermesReviewer cloud/local review
2. `LocalGuardHeuristics`
3. local llama guard when enabled
4. `DependencyGuard`
5. `DatabaseContractNormalizer`
6. `AndroidSourceGuard`
7. required project file validation

### Build Failure Classifier

Existing `BuildFailureClassifier` should remain the UI gate for whether Repair is available. HermesBuildTriage can produce more focused repair instructions after the user chooses Repair.

## Rollout Plan

### Phase 1: Context Scout

Implement the background context negotiation design and use Hermes-compatible names or adapters. This phase improves retry context without changing task storage or build backends.

### Phase 2: Reviewer

Add HermesReviewer before applying operations. It should catch broad rewrites and cross-file API mismatches before temporary source application.

### Phase 3: Build Triage

Add HermesBuildTriage to repair flow so build logs become focused repair tasks instead of large raw prompt sections.

### Phase 4: Planner Task Metadata

Extend implementation tasks with optional `expectedFiles`, `acceptanceChecks`, and `riskNotes`. Keep backward compatibility with existing task JSON.

### Phase 5: Durable Hermes Runs

Add `hermes_runs` and `hermes_steps` tables only if role-level resume/debugging needs exceed what `ai_conversations`, `project_tasks`, and `build_jobs` already provide.

## Testing Strategy

Unit tests:

- Parse each role JSON contract.
- Reject unsafe paths and invalid decisions.
- Ensure Context Scout output becomes focused source context.
- Ensure Reviewer `rewrite` prevents apply and feeds a rewrite instruction to coder.
- Ensure Build Triage `environment` category does not trigger model source repair.
- Verify role call limits.
- Verify fallback when a role returns invalid JSON.

Integration-style tests:

- Simulate a failed task retry and assert final coder prompt includes previous failure summary and no-recreate instruction.
- Simulate broad rewrite operations and assert HermesReviewer asks for rewrite before apply.
- Simulate a javac build log and assert Build Triage creates a focused repair instruction.

Manual verification:

- Run `./gradlew testDebugUnitTest`.
- Run `./gradlew assembleDebug --stacktrace`.
- Exercise one project creation, one planned task execution, and one manual repair on device or emulator.

## First-Version Decisions

- Hermes should be enabled for retry and repair paths first. Normal first-pass planned task execution may opt in after Phase 1 and Phase 2 are stable.
- Role logs should use the existing AI conversation log storage first. A dedicated UI filter is useful but not required for the first implementation.
- Planner metadata should remain backward-compatible and optional. Store it inside existing task JSON/instruction parsing first; defer database migrations until durable Hermes runs are needed.

## First Implementation Recommendation

Start with Phase 1 and Phase 2 together:

1. Implement Hermes-compatible Context Scout using the already approved background context negotiation plan.
2. Add a minimal HermesReviewer with `ok/rewrite/fallback`.
3. Keep Build Triage and Planner metadata for follow-up phases.

This gives the biggest build-success improvement with limited risk: better context before coding and an extra rewrite gate before applying generated operations.
