# Background Context Negotiation Design

## Goal

Reduce repeated regeneration after coding errors by adding a short, automatic context negotiation step before retry and repair code generation. The user should not need to participate during the negotiation, but every cloud-model exchange must remain visible in the AI conversation logs.

## Approved Approach

Use a bounded background negotiation loop for retry-like flows. The cloud model first reviews the approved plan, current task, current source snapshot, recent user requirements, and previous failure summary. It returns structured JSON describing whether it has enough context, which files or symbols it needs focused, and a concise patch intent. The app then gathers a more focused source snapshot and sends the final file-operation generation prompt.

This should be enabled first for high-value error paths, not for every first-pass task:

- A task operation attempt after local validation rejects generated operations.
- Re-running a `failed` project task.
- Build repair before generating file operations.

## Why This Helps

The current retry flow applies model output in a temporary source directory. If validation fails, that candidate directory is deleted and the next attempt sees only the unchanged source tree plus an error message. Across a later task retry, the failed task summary is stored but not sent back to the model. Those gaps make the model likely to recreate files or re-plan from the task title instead of repairing the precise failure.

Negotiation gives the model a controlled chance to ask for focused context before code generation, while preserving the existing final JSON file-operation contract.

## Data Contract

The negotiation response must be compact JSON:

```json
{
  "ready": false,
  "neededFiles": [
    "app/src/main/java/com/example/DBHelper.java"
  ],
  "focusTerms": [
    "DBHelper",
    "CategoryDao"
  ],
  "riskNotes": [
    "Keep DAO constructor calls and declarations synchronized."
  ],
  "patchIntent": "Modify the existing DAO and Activity only; do not recreate the project."
}
```

Field rules:

- `ready`: boolean. `true` means the model can proceed with the supplied context; `false` means the app should focus additional files or terms if possible.
- `neededFiles`: optional array of relative POSIX source paths. Unsafe paths are ignored.
- `focusTerms`: optional array of class names, resource names, or error terms. Empty and overly long terms are ignored.
- `riskNotes`: optional array of short notes. These are logged and may be summarized into the final generation prompt.
- `patchIntent`: required non-empty text, capped before use in the final prompt.

Invalid JSON or an empty `patchIntent` should not block generation. The app logs the failed negotiation and falls back to the existing prompt path.

## Flow

1. Build the baseline source snapshot using the existing `sourceSnapshot(...)` policy.
2. Detect whether negotiation is needed for this call.
3. Send a cloud negotiation request with:
   - approved engineering plan
   - task title and instruction
   - recent user requirements
   - baseline source snapshot
   - previous failure summary, policy error, or build-log triage when available
4. Parse the negotiation JSON.
5. If it names files or focus terms, build a second focused snapshot using those hints plus the failure text.
6. Send the final `createTaskOperations(...)` prompt with:
   - approved plan
   - recent requirements
   - focused source snapshot
   - original task instruction
   - previous failure summary
   - negotiation patch intent and risk notes
7. Apply operations through the existing `FileOperationsWriter` validation path.

The loop is limited to one negotiation round by default. A second round may be allowed only when the first response names focused files that were successfully added to the snapshot and the model still says `ready=false`. No flow may exceed two negotiation calls before final generation.

## Logging

Use the existing AI conversation logging mechanism for every negotiation call.

Suggested titles:

- `云端 AI · 上下文协商 #1`
- `云端 AI · 上下文协商 #2`
- `Cloud AI · context negotiation #1`
- `Cloud AI · context negotiation #2`

The logged request should include a truncated view of the plan, task, recent requirements, failure summary, and source snapshot. The logged response should include the raw negotiation JSON or the parse failure message. Logs are diagnostic only and must never block generation.

## Prompt Constraints

The final generation prompt should include an explicit retry/repair section:

```text
This is a retry or repair of an existing source tree.
Do not recreate the project.
Modify only the files needed for the current task.
Use the shown source as authoritative.
If a file was omitted, do not invent its API; rely only on shown files and the negotiated focus context.
```

When a failed task is retried, include its stored failure summary in the final prompt. This makes retries carry the reason for the previous failure instead of starting from the original task instruction alone.

## Components

- `OpenAiClient`: add a negotiation API and system prompt that returns the structured JSON contract.
- New parser/model classes: parse and validate `ready`, `neededFiles`, `focusTerms`, `riskNotes`, and `patchIntent`.
- `AgentService`: decide when negotiation is needed, record negotiation logs, build focused snapshots, and append negotiation intent to final task-operation prompts.
- Existing `ConversationContextPolicy`: continue supplying recent user requirements; no change is required unless later tests show the window is too small.
- Existing `FileOperationsWriter`: unchanged. Validation remains the authority for accepting or rejecting operations.

## Safety And Limits

- Maximum two negotiation calls before final generation.
- Ignore unsafe or non-source file paths.
- Cap patch intent, risk notes, and focus terms before inserting them into prompts.
- Negotiation failure falls back to existing generation behavior.
- No user-visible blocking confirmation is introduced.
- No generated operations are applied until the final `TaskOperations` JSON passes existing validation.

## Testing

Unit tests should cover:

- Parsing a valid negotiation JSON response.
- Rejecting unsafe `neededFiles` paths.
- Falling back when negotiation JSON is invalid.
- Including failed task `resultSummary` in retry generation context.
- Adding negotiation `patchIntent` to the final task-operation prompt.
- Enforcing the maximum negotiation-call limit.

Integration-level tests should simulate a failed task retry and verify that the retry prompt contains the previous failure summary plus a no-recreate instruction.
