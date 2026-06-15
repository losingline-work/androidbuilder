# The elegant fix: compile-driven validation (use the real compiler, not regex)

## Verdict (multi-agent confirmed)

The recurring pain is **65–75% guard false positives** — the regex guard (`AndroidSourceGuard` + `SymbolTable`) is a reimplementation of the Java type system, and you cannot reimplement a type checker with regex. Only **15–20%** are genuine model errors; **10–15%** orchestration.

- project-9: **68 of 102 rejections** were one false positive — `ChartConfig.apply(Context, PieChart)` is valid (PieChart IS-A Chart) but the regex can't model library type hierarchies.
- Map.Entry collisions, `new X()`-as-method, lambda-in-comments, boxing/widening — every patched case was the guard wrong about **valid** code, never a real model bug.
- `SymbolTable.isAssignable` already hard-codes "defer library types to javac" — an explicit admission the guard can't type-check.

**The device already has the real compiler.** `gradle :app:compileDebugJavaWithJavac` is real javac with the real classpath (MPAndroidChart, material, …), no dexing — ground truth. The on-device runtime (`EmbeddedRuntimeBackend`) already runs Gradle with a dependency init script and already mines javac diagnostics (`summarizeFailure`).

## The architecture

1. **Demote the guard's type-checking half** (`validateCustomMethodCalls`, `validateConstructorCalls`, `validateClassFieldAccess`, `validateCustomFieldAccess`, all of `SymbolTable` type resolution). Stop hard-rejecting cross-file type errors pre-merge. **Merge optimistically.**
2. **Keep the guard's POLICY half** (cheap, no false positives): Kotlin/lambda/DataBinding/ViewBinding ban, `R.*` resource existence, synthetic-view access, banned-dependency policy.
3. **Add a compile-only gate**: between `abResolveDebugDeps` and `assembleDebug` in `EmbeddedRuntimeBackend`, run `gradle :app:compileDebugJavaWithJavac` (same env/init script, just a different task). Real javac, real classpath, no dex → fast, ground-truth diagnostics with `file:line` + candidate signatures.
4. **Auto-loop the EXISTING repair**: on a `JAVA_COMPILE` failure that is `repairableByModel` (`BuildFailureClassifier`), auto-call the existing `repairBuild(...)` (today a manual button) capped at ~6 rounds, and drive the **StubReconciler from the real javac errors** (exact missing symbol + expected types — no guessing).

## Why it's durable

- Removes the 65–75% false-positive class **by construction** — javac knows the real type system; nothing to patch.
- Reuses almost everything: env, classpath, log capture, diagnostic extraction, repair path. Mostly **deletion** of regex type-checking + wiring an existing Gradle task.
- "If `compileJava` passes, it builds." Ground truth.
- The remaining genuine model errors become **precise** (`file:line`, expected signature), making both model-repair and stubbing reliable.

## Honest limits

It does not make the model produce a perfect app. It removes the **false** battles and makes the **real** ones tractable & auto-repairable/stubbable. Cost: a compile-gate failure runs javac (seconds) vs the instant-but-wrong regex guard — but the false positives were causing 20-minute loops, so net far faster.

## Staged plan

- **Stage A (the unlock)**: demote guard type-checks to advisory/off (keep policy); add the `compileDebugJavaWithJavac` gate; auto-loop the existing build-repair on JAVA_COMPILE failures.
- **Stage B**: parse javac diagnostics → drive StubReconciler with real expected types.
- **Stage C (optional)**: dependency-tier generation, each tier compiled against the real frozen lower tiers.
