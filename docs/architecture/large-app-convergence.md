# 大型 App 生成收敛架构:reconciled-from-bodies AppContract

> 由 13 个子代理的设计工作流综合得出(理解现状 → 4 个竞争方案 → 对抗式评审打分 → 综合)。对抗评审已剔除「过度宣称」和依赖已损坏的 parser round-trip 的部分。

## 核心思路

A reconciled-from-bodies AppContract: seed a complete, deterministically-linted API+resource contract right after plan(), but treat it as a living ledger that real foundation-tier bodies overwrite — then project dense, consumed-only, non-truncatable signature slices into every batch and derive task dependsOn from it, so callers generate against compiled-shaped truth instead of a 6000-char-truncated guess, while AndroidSourceGuard stays the unchanged zero-bypass final gate.

## 为什么是这个组合

The four designs converge on the same correct diagnosis (no authoritative complete contract) but the critiques expose that three of them overclaim 'impossible-by-construction' for failures that are actually model-compliance-dependent, and two rest on a parser round-trip that is verifiably BROKEN in this codebase. My synthesis keeps only what survives inspection.

From Contract-First (scores 7/5/5): keep the plan-time AppContract, the closed-world F4 ownership check (its cleanest impossible-by-construction win), the kind-tagged F3 table, the fail-closed posture, and the non-truncatable contract layer. DISCARD its central correctness claim — I verified the two regex families genuinely diverge (digest CLASS_DECLARATION consumes modifiers and allows <> in extends; guard JAVA_CLASS does neither) and that ClassInfo.line() emits dotted qualifiedName 'Outer.Entry' that the guard's simple-name JAVA_CLASS cannot re-parse (JavaApiDigest.java:108,453 vs AndroidSourceGuard.java:316). So I adopt the critique's keepIdea: build the linter's SymbolTable DIRECTLY from typed model objects, keep line()-format ONLY as the model-facing string. This kills the round-trip risk while keeping the in-context density.

From AppRegistry (7/5/5): keep ownerTask-tagged resources, deriving HermesTaskGraph dependsOn from contract ownership instead of free-text tokens, and the lift of JavaApiSymbols into a shared unit. DISCARD RegistryLock 'contradicting-write-is-REWRITE' (the critique's doom-loop: it weaponizes a wrong seed by force-rewriting correct bodies toward it). Adopt its strongest keepIdea: ANCHOR THE CONTRACT TO REAL BODIES — reconcile signatures from compiled-shaped foundation files rather than freezing pre-plan guesses; resolve any contract-vs-body disagreement in the body's favor. This single decision neutralizes the #1 fatal flaw shared by both contract designs (a self-consistent-but-wrong contract dooming all 24 files).

From FrozenTierLedger (7/6/5, highest robustness): keep producer-before-consumer ordering, the ONE shared resource artifact (F5), the protected/non-truncatable digest layer (the single highest-value low-risk fix — the verified 6000-char squeeze is the F1 root cause), all-edges-at-once reporting, and Tarjan SCC for genuine cycles. DISCARD the strict global sequential tier-freeze (its critique proved HermesParallelBatch is hostile to it) and the framing that the early gate adds safety (it is a superset-reducer at the same precision as merge). Adopt the honest framing: the early gate is a retry-efficiency optimization.

From GACR (5/4/4, lowest — and rightly so): the critique proved StuckFamilyPolicy ALREADY implements its core, so it must FOLD its three real deltas into existing machinery, not stand up four new classes. Keep the deterministic per-edge F2 contract row carrying the real declared signature (highest-leverage, lowest-risk delta — the guard's F2 message omits signatures), firing earlier, with a dedicated budget + attempt--. DISCARD the 8-file full-source cluster (the critique's char-count math shows 2-4x budget overflow) and the new createTaskOperationsReconcile endpoint (redundant with createTaskOperationsBatch).

The combination: a COMPLETE contract (kills truncation and the never-generated-callee blind spot at line 545 that pure repair cannot reach) that is RECONCILED FROM BODIES (kills the wrong-seed doom loop) and VALIDATED FROM TYPED OBJECTS (kills the broken round-trip / new-false-positive risk), projected through a NON-TRUNCATABLE layer (kills F1 truncation), with the merge guard UNCHANGED as final authority (zero-bypass preserved).

## 目标流水线

0. PLAN (unchanged): AgentService.plan() -> openAiClient.createEngineeringPlan -> free-text plan saved as ProjectPlanRecord (AgentService.java:307-313).

1. CONTRACT SEED (new cloud call): immediately after plan save (AgentService.java:313), openAiClient.createAppContract(plan, packageName) emits AppContract JSON — every class (simpleName/pkg/superType/methods/ctors/fields with realized=false), every ResourceSymbol (kind + ownerTaskTitle), every CallEdge. Model PRODUCES: the complete intended API + resource surface for the whole app. Persisted as AppContractRecord.

2. CONTRACT LINT (deterministic, no cloud): ContractLinter.validate builds a SymbolTable directly from typed ClassContract objects and DETERMINISTICALLY checks: every CallEdge resolves via hasMethod (F1) + hasMethodSignature/isAssignable (F2); every CtorSig edge via availableConstructors; every id-kind ResourceRef hits a kind=id symbol (F3); every referenced resource is owned by exactly one task (F4). PRODUCES: a defect list. Between steps: if non-empty, a bounded ~3-round signature-only contract-repair cloud call; if still unreconcilable, FAIL CLOSED to the user.

3. TASK SPLIT + PROJECTION (modified): ensureImplementationTasks runs as today, but ImplementationTaskNormalizer's canned phases (lines 177-189) now staple the projected contract slice into an extended HermesTaskContract (new apiSignatures/resourceSymbols/consumedSignatures fields): the Java wiring task gets owned class signatures PLUS consumed sibling API; the layout task gets the ids it must declare; the values task gets colors/strings it must declare. HermesTaskGraph.fromTasks derives dependsOn from contract ownership (consumer task depends on the task owning the ids/classes it consumes) instead of free-text tokens. Deterministically validated: ownerTaskTitle must match an exact canned-phase title or the slice is empty (brittle string-coupling noted as a stage risk).

4. BODY GENERATION (modified): per task, createTaskManifest constrained to the slice; ManifestBatchPolicy.batches orders producer-before-consumer (existing javaTier). buildSourceSnapshot adds a NON-TRUNCATABLE CONTRACT layer (consumed-only slice, dense line()-format + kind-tagged resource table with the 'string labels are NOT ids' RULE) ordered BEFORE the squeezable javaApiDigest in SourceSnapshotComposer.tailLayers. Each createTaskOperationsBatch generates against this. Model PRODUCES: file bodies. Between steps: NOTHING deterministic blocks here yet — generation is best-effort against complete context.

5. LEDGER RECONCILE (new, deterministic, at merge): when a foundation file (entity/db/util/dao tier) is accepted into the tree, AppContract.reconcileFrom(onDiskClassInfo) REPLACES that class's seed signatures with body-derived ones (realized=true); ResourceSymbol.produced flips when the owning resource file merges. So downstream consumers see compiled-shaped truth, not the seed guess. PRODUCES: an updated, persisted AppContractRecord.

6. MERGE (unchanged authority + earlier convergence): HermesMergeCoordinator.merge runs HermesTaskContractGuard.review (now also path-checks; optionally an ADDITIONAL contract-conformance REWRITE that resolves in the body's favor) then TaskOperationsPreflight.review then writer.apply -> AndroidSourceGuard.validate (FileOperationsWriter.java:50) as the UNCHANGED final gate. On rejection, the executeTaskWithRewrites catch block (AgentService.java:1534): StuckFamilyPolicy (now firing on first multi-edge rejection, fed the real declared signature from the ledger) drives one contract-grounded cluster regeneration under a dedicated budget with attempt--, instead of one-method-at-a-time. Between steps: every signature/resource fact the model used was either complete-by-construction (seed) or compiled-shaped (reconciled), so the guard converges in tier order rather than whack-a-mole.

## 契约设计

ONE typed model, persisted, reconciled-from-bodies, projected as dense text, validated by lifted guard symbols.

THE TYPE (new model/AppContract.java, immutable, JSON-codec'd like HermesTaskContract):
  AppContract { String packageName; List<ClassContract> classes; List<ResourceSymbol> resources; List<CallEdge> callEdges; }
  ClassContract { String simpleName; String pkg; String superType; List<MethodSig> methods; List<CtorSig> ctors; List<FieldSig> fields; String filePath; boolean realized; }  // realized=true once a compiled-shaped body has replaced the seed
  MethodSig { String name; List<String> paramTypes; String returnType; }   CtorSig { List<String> paramTypes; }   FieldSig { String name; String type; boolean isConstant; }
  ResourceSymbol { ResKind kind (id|string|color|drawable|layout|menu|dimen|style); String name; String ownerTaskTitle; boolean produced; }
  CallEdge { String callerClass; String calleeClass; String method; List<String> argTypes; }

HOW BUILT: (1) SEED at plan time. After AgentService.plan() saves the plan (AgentService.java:313), one new cloud call openAiClient.createAppContract(plan, packageName) (sibling of createImplementationTasks at OpenAiClient.java:131) emits the contract JSON. Parsed by new AppContractParser.fromJson (mirrors TaskManifestParser style). (2) RECONCILE from bodies. As each foundation file is merged, AppContract.reconcileFrom(onDiskClassInfo) REPLACES that class's seed signatures with the body-derived ones and sets realized=true; ResourceSymbol.produced flips when the owning resource file merges. Persisted as AppContractRecord beside ProjectPlanRecord; re-read on every task/batch so it is byte-identical across stateless cloud calls.

HOW VALIDATED (new agent/ContractLinter.java, NO cloud): build a JavaApiSymbols table DIRECTLY from the typed ClassContract objects — NOT by rendering line() and re-parsing (the broken round-trip both critiques killed). For this, REFACTOR AndroidSourceGuard: extract the inner JavaApiSymbols (AndroidSourceGuard.java:1189-1306), its builder, and isAssignable (800-873) into a package-visible SymbolTable type with an additional ingest path addClass(ClassContract) that populates fieldsByClass/methodsByClass/constructorsByClass/superClassByClass from typed fields. The guard's own validate() path is UNCHANGED (still ingests on-disk .java). ContractLinter then: for every CallEdge run hasMethod (F1) then hasMethodSignature+isAssignable (F2, inheriting F6 widening/boxing for free); for every ResourceSymbol-referencing edge resolve kind (F3: an id-kind ref must hit a kind=id symbol) and ownership (F4: every referenced resource must be DECLARED by exactly one ownerTask). Defects -> bounded ~3-round contract-repair cloud loop (signatures only, cheap). If still unreconcilable, FAIL CLOSED: surface defects to the user, do not generate bodies against an inconsistent contract (Contract-First keepIdea).

HOW FROZEN / KEPT IN-CONTEXT: only realized=true signatures are authoritative; consumers are shown the contract as the dense canonical one-line-per-class skeleton (the JavaApiDigest.ClassInfo.line() FORMAT, rendered fresh from typed objects) plus a kind-tagged resource truth table extending ResourceIndexDigest.render with the explicit RULE 'R.string label names are NOT view ids; the only view ids are [idsByLayout...]'. This rides a NEW non-truncatable CONTRACT layer in SourceSnapshotComposer.tailLayers ordered BEFORE the squeezable javaApiDigest, carrying CONSUMED-ONLY slices per batch. The merge-time guard still recomputes everything from bodies as the unchanged final authority.

## 每类失败如何被消灭

F1 (missing callee method) — CONVERGENT-BY-DESIGN, with one impossible-by-construction corner. Three mechanisms compound: (a) the non-truncatable CONTRACT layer removes the verified 6000-char digest truncation that made the callee literally invisible (AgentService.java:47, JavaApiDigest.java:69), so the model sees the real signature; (b) producer-before-consumer ordering (existing ManifestBatchPolicy.javaTier) + ledger reconciliation means a consumer is generated against the producer's REALIZED (compiled-shaped) signature, not a guess; (c) ContractLinter runs hasMethod over the COMPLETE declared method table BEFORE bodies via the lifted SymbolTable, so a callEdge to a phantom is reported at plan/contract time, all edges at once, not one merge-slice at a time. The impossible-by-construction corner the GACR critique proved unreachable by repair (AndroidSourceGuard.java:545 emits NO violation when the callee CLASS is entirely absent) is now caught: the linter's closed-world check requires every callEdge's calleeClass to exist in the contract, so a never-generated-callee is a plan-time defect. Residual (model omits the real method from the seed) is neutralized by body-reconciliation: the moment the callee body is generated with the method, the ledger gains it; if a consumer still calls a method no realized producer has, that is a real bug the unchanged merge guard rejects.

F2 (arg/signature mismatch) — CONVERGENT-BY-DESIGN. The contract stores paramTypes; ContractLinter runs hasMethodSignature+isAssignable (the exact JLS widening/boxing matcher at AndroidSourceGuard.java:800-873, inheriting F6 fixes). Honest bound (FrozenTierLedger critique): simpleType collapses generics, so List<Foo> vs List<Bar> stays invisible — the linter is a superset-reducer at the SAME precision as the final guard, just earlier. The genuinely new leverage (GACR keepIdea) is that on a mismatch the reconcile pass injects the REAL declared signature line (the guard's F2 message omits it), so the model reconciles arity/types with the answer in hand instead of blind.

F3 (string-label used as view id) — IMPOSSIBLE-BY-CONSTRUCTION at contract time, REINFORCED at generation. Resources are kind-tagged; mine_account is declared kind=string only; ContractLinter deterministically rejects any id-kind reference whose name is not kind=id, needing no model cooperation once kinds are frozen. The resource layer carries the explicit RULE 'R.string labels are NOT view ids; valid ids in fragment_mine.xml are [mine_menu_list]'. AndroidSourceGuard already partitions ids vs strings at merge (line 268) as the backstop.

F4 (cross-scope demand) — IMPOSSIBLE-BY-CONSTRUCTION. The cleanest win (both Contract-First and AppRegistry critiques agree). Every ResourceSymbol has exactly one ownerTask; ic_notification.xml's @color/brand_on_primary must appear as a declared color owned by the values task or ContractLinter fails at plan time with 'undeclared resource'. A closed-world check requiring no model cooperation. It can never reach compile.

F5 (cross-task contract drift) — IMPOSSIBLE-BY-CONSTRUCTION for the symbol set. There is ONE contract projected to BOTH the layout task and the Java wiring task; fragment_mine.xml's declared ids and the wiring task's consumed ids are the same frozen symbols, so list-vs-per-item-id disagreement cannot survive. This REQUIRES fixing the literal root cause: ImplementationTaskNormalizer's canned phases (resourceTask/drawableLayoutTask/'Java source wiring', lines 177-189) currently build contract-LESS tasks via task(title,instruction); they must staple the projected contract slice. Caveat (FrozenTierLedger critique): only shared symbols are pinned, not container hierarchy semantics.

F6 (already-fixed guard false positives) — UNTOUCHED. ContractLinter and the merge guard REUSE the identical isAssignable/inheritsExternalApi/isKnownInheritedPlatformMethod logic; Map.Entry collision, widening, boxing, platform-derived deferral behave identically. Crucially, the validator builds symbols from TYPED objects, not by re-parsing rendered text, so it never re-introduces the nested-class/qualified-name round-trip break that would have resurrected F6.

## 分阶段迁移路径


### Stage 1: 1 (风险 low)

**目标**: Kill the F1 truncation root cause with the single highest-value, lowest-risk fix: a protected, non-truncatable Java API digest layer, plus the F3 id-vs-string RULE — no new cloud call, no contract model yet.

**单独交付的价值**: Directly attacks F1/F2 (model can now see every callee signature for apps up to ~40 classes) and F3 (the string-label-as-id confusion gets an explicit deterministic warning), with zero new cloud calls, zero guard changes, and no new failure modes. This alone should measurably improve project-82/83.

**改动**:

- EDIT app/src/main/java/com/androidbuilder/agent/SourceSnapshotComposer.java: in tailLayers/compose (lines 42,78-83) add the JAVA_API_DIGEST layer with its OWN protected budget that is subtracted from the total BEFORE full-text, so it is never squeezed (mirror how RESOURCE_INDEX already gets a hard RULE at lines 10-11,125-132).
- EDIT app/src/main/java/com/androidbuilder/agent/AgentService.java: rebalance constants (lines 45-50) — raise the effective digest budget by giving it a reserved slice of SOURCE_SNAPSHOT_LIMIT=24000 and letting SOURCE_FILE_PREVIEW_LIMIT absorb truncation; at ~1 line/class a 40-class app fits in ~3-5KB.
- EDIT app/src/main/java/com/androidbuilder/agent/ResourceIndexDigest.java: in render (lines 42-61) add the explicit authoritative RULE line 'R.string label names are NOT view ids; valid view ids by layout: [idsByLayout]' using the already-built idsByLayout (lines 70-83); protect it from the 3000-char trim.
- NEW tests: SourceSnapshotComposerTest case asserting the digest layer survives when full-text overflows; ResourceIndexDigestTest asserting the id-vs-string RULE is always present.

**守卫安全**: Touches only the model-facing snapshot string; AndroidSourceGuard.validate and all merge-time checks are byte-for-byte unchanged. Strictly enlarges what the model SEES.


### Stage 2: 2 (风险 medium)

**目标**: Lift the guard's symbol model into a reusable SymbolTable that can ingest TYPED contract objects (not re-parsed text), with zero behavior change to the guard.

**单独交付的价值**: A clean, independently-tested refactor that removes duplicated symbol logic and is a prerequisite for any contract validator; it also de-risks the guard's most safety-critical code by giving it test coverage. No pipeline change.

**改动**:

- REFACTOR app/src/main/java/com/androidbuilder/agent/AndroidSourceGuard.java: extract the inner JavaApiSymbols (lines 1189-1306), its on-disk builder collectJavaApiSymbolsFromFile (311-362), and isAssignable/isPrimitiveOrBoxedAssignable/numericRank (800-873) into a new package-visible app/src/main/java/com/androidbuilder/agent/SymbolTable.java; the guard delegates to it. Its validate() path stays identical (still ingests on-disk .java).
- ADD to SymbolTable an addClass(typed fields) ingest path so a table can be populated WITHOUT regex re-parsing — this is the explicit fix for the broken ClassInfo.line() round-trip both critiques flagged.
- NEW tests: SymbolTableTest proving the on-disk path and the typed-ingest path produce identical hasMethod/hasMethodSignature/availableConstructors results for the same class, including a nested-class case (Outer.Entry) that the text round-trip would have broken.

**守卫安全**: Highest-regression-risk change because it touches the guard's core, so it is isolated to a pure extract-with-tests stage with a characterization test asserting AndroidSourceGuardTest still passes unchanged.


### Stage 3: 3 (风险 medium)

**目标**: Stop dropping the contract in normalization and pin F5/F4 with a SHARED RESOURCE artifact projected to both the layout and Java tasks — still no whole-app API contract, just resources.

**单独交付的价值**: Makes F4 impossible-by-construction and F5 impossible for the resource symbol set — the two cleanest structural wins — without needing the full API contract or a new cloud call. Resources are a closed, deterministically-extractable namespace, so this is high-certainty.

**改动**:

- EDIT app/src/main/java/com/androidbuilder/model/HermesTaskContract.java + HermesTaskContractCodec.java: add optional List<String> resourceSymbols (owned) and consumedResourceSymbols fields (backward-compatible, mirrors existing dependsOn/produces).
- EDIT app/src/main/java/com/androidbuilder/agent/ImplementationTaskNormalizer.java: replace task(title,instruction) in resourceTask()/drawableLayoutTask()/'Java source wiring' (lines 177-189) with a contract-carrying builder that staples the resource slice; derive the slice from a deterministic ResourceRegistry built by lifting ResourceSymbolsOverlay (already used in CompletedBatchContextPolicy) to app scope, tagging each symbol with kind + ownerTaskTitle.
- ADD a closed-world ownership check (the F4 win): in HermesTaskContractGuard.review (HermesTaskContractGuard.java:16-44) or a sibling, reject a task whose Java references a resource kind it is forbidden to create AND that no owner task will produce.
- EDIT app/src/main/java/com/androidbuilder/agent/HermesTaskGraph.java (fromTasks, line 22): derive dependsOn from resource ownership so the Java task waits for the layout/values task producing the ids/colors it consumes.
- NEW tests: ImplementationTaskNormalizerTest asserting canned phases now carry the resource slice; an F4 cross-scope-demand rejection test; an F5 test asserting both tasks bind the same id set.

**守卫安全**: Only ADDS a reject reason (cross-scope/undeclared resource) and enriches task context; AndroidSourceGuard stays the final gate. Brittle ownerTaskTitle string-coupling to canned-phase titles is the main risk, contained by an exact-match test.


### Stage 4: 4 (风险 high)

**目标**: Introduce the full AppContract (API + resources) as a SEEDED-THEN-RECONCILED ledger, linted deterministically, projected into every batch.

**单独交付的价值**: Delivers complete-by-construction API context (kills the never-generated-callee blind spot at AndroidSourceGuard.java:545 that repair cannot reach) and plan-time F1/F2 detection across the whole graph at once, with the wrong-seed doom loop neutralized by body-reconciliation.

**改动**:

- NEW model/AppContract.java + AppContractParser.java + AppContractRecord + repository.save/loadAppContract beside ProjectPlanRecord (persistence pattern at AgentService.java:313).
- EDIT app/src/main/java/com/androidbuilder/agent/OpenAiClient.java: add createAppContract(plan, packageName) + system prompt (sibling of createImplementationTasks at line 131).
- NEW app/src/main/java/com/androidbuilder/agent/ContractLinter.java: builds a SymbolTable via the Stage-2 typed addClass path and runs hasMethod/hasMethodSignature/availableConstructors per CallEdge + kind/ownership checks per resource; returns defects; bounded ~3-round repair loop; FAIL CLOSED on unreconcilable.
- EDIT app/src/main/java/com/androidbuilder/agent/AgentService.java: after plan save call createAppContract+ContractLinter; add AppContract.reconcileFrom(onDiskClassInfo) invoked when a foundation file merges so seed signatures are REPLACED by body-derived ones (realized=true) — the wrong-seed mitigation; feed the dense consumed-only slice into the Stage-1 contract layer.
- NEW tests: ContractLinterTest (F1/F2/F3/F4 each rejected pre-body), an AppContract.reconcileFrom test proving a body overrides a divergent seed, a fail-closed test.

**守卫安全**: The linter computes the SAME facts the guard recomputes and can only DELAY a merge, never force a body-rewrite the final guard would accept; disagreements resolve in the body's favor. AndroidSourceGuard.validate unchanged.


### Stage 5: 5 (风险 medium)

**目标**: Make REPAIR converge by folding GACR's real deltas into the existing StuckFamilyPolicy: fire earlier, inject the real declared signature, dedicated budget.

**单独交付的价值**: Breaks the whack-a-mole even on the residual cases the contract didn't fully pin (generics-shallow F2, intra-cycle clusters): one contract-grounded cluster regen replaces N single-method patches. Reuses existing seams, adds no new endpoint.

**改动**:

- EDIT app/src/main/java/com/androidbuilder/agent/StuckFamilyPolicy.java: in reconcileDirective (lines 78-95) inject the REAL declared signature for each flagged member, pulled from the reconciled AppContract / SymbolTable (the guard's F2 message at AndroidSourceGuard.java:562 omits it) — the highest-leverage GACR delta.
- EDIT app/src/main/java/com/androidbuilder/agent/AgentService.java catch block (line 1534): lower the stuck trigger to fire on the FIRST merge rejection yielding >=2 edges across >=2 files; drive ONE contract-grounded cluster regeneration via the existing resumeDraftEvictingRejectedFiles / ManifestResumePolicy plumbing over the dependency closure (callee + named callers, capped 3-4 files ranked by highest-degree shared callee), under a NEW dedicated clusterReconciles budget with attempt-- (mirror BlockedTaskPolicy at line 1395) so it never burns POLICY_REWRITE_ATTEMPTS.
- HANDLE the MAX_REPORTED_VIOLATIONS=10 cap (AndroidSourceGuard.java:16): rank reported edges by highest-degree shared callee so the densest part of the web is always inside the cap; warn the model more edges may remain.
- NEW tests: StuckFamilyPolicyTest asserting the real signature is injected; an AgentService retry test asserting attempt-- and budget isolation; a cluster-closure test.

**守卫安全**: Repair only feeds the SAME guard stronger, contract-consistent inputs; the guard re-validates the whole tree as the unchanged authority. No check is weakened or bypassed.


## 风险与缓解

R1 The contract is still model-generated, so an internally-consistent-but-semantically-wrong contract (e.g. calculate(long,long) when the app needs calculate(Transaction,Budget)) passes the linter and then freezes all 24 files to a wrong target — the worst case both Contract-First and AppRegistry critiques flagged. MITIGATION: never freeze the contract before bodies exist. The contract is SEEDED at plan time but treated as a LEDGER that is RECONCILED from real bodies as foundation tiers (entity/db/util/dao) are produced (AppRegistry keepIdea 'anchor to real bodies'). The on-disk symbol facts always win over the seed: when a generated foundation body differs from its seed line, the ledger line is REPLACED by the body-derived line, never the reverse. So the only frozen-and-authoritative signatures consumers see are ones already realized as compiled-shaped Java, not pre-plan guesses. The seed's only job is to let the linter pre-detect cross-scope/resource impossibilities (F4) and seed dependsOn edges; it is advisory for signatures.

R2 Contract-vs-guard disagreement creating a NEW false-positive class (the F6 family the constraints forbid). Both Contract-First and AppRegistry critiques proved the round-trip is broken: rendering ClassInfo.line() (qualifiedName 'Outer.Entry', extends with generics) and re-parsing through the guard's JAVA_CLASS (simple-name only, no generics in extends) does NOT reconstruct identical symbols, and nested classes break entirely. MITIGATION (load-bearing design decision): the early ContractLinter builds its JavaApiSymbols table DIRECTLY from typed ContractModel objects (MethodSig.name + paramTypes already typed), NOT by rendering text and re-parsing. The ClassInfo.line() rendering exists ONLY as the model-facing string in the snapshot layer; it is never fed back through a regex for validation. And any disagreement between the early linter and AndroidSourceGuard resolves in the guard's favor (the body on disk is truth) — the linter can only DELAY a merge, never force a body-rewrite the final guard would have accepted. This makes the early validator a pure retry-efficiency optimization that cannot introduce a false positive the merge guard wouldn't.

R3 Context budget bites at body time — a non-truncatable contract layer steals from squeezable layers and can starve the focused full-text of the file being written (Contract-First critique flaw #5). MITIGATION: the contract layer carries CONSUMED-ONLY slices per batch (the API surface of classes this batch's files actually call, derived from manifest intent + import refs), not the whole app. At ~1 line/class even a wide-fan-out Repository's consumed set is a few KB. Carve the CONTRACT layer from the existing 24000 budget by shrinking SOURCE_API_DIGEST_LIMIT (the squeezable whole-tree digest becomes redundant for frozen-tier classes and only covers not-yet-frozen siblings) and let SOURCE_FILE_PREVIEW_LIMIT absorb truncation, never the contract. Add a unit test asserting the contract layer survives when full-text overflows.

R4 Within-task generation still runs N separate createTaskOperationsBatch calls; the slice only helps if injected into EVERY batch prompt and surviving the budget (AppRegistry flaw #2). MITIGATION: inject the contract slice in buildSourceSnapshot's new layer which every batch already consumes via the shared snapshot string; additionally hoist the proven CompletedBatchContextPolicy 'AUTHORITATIVE API CONTRACT frozen' mechanism to PROJECT scope so a consumer batch sees real producer signatures verbatim from the ledger, cross-task, not just same-task.

R5 Amendment / cluster-regen loops re-introduce the budget problem (FrozenTierLedger flaw #5, GACR flaw on CLUSTER_FILE_CAP). MITIGATION: dedicated budgets with attempt-- (proven BlockedTaskPolicy pattern at AgentService.java:1395) so reconcile passes never cannibalize POLICY_REWRITE_ATTEMPTS; CLUSTER_FILE_CAP sized at 3-4 after a real char-count against SOURCE_SNAPSHOT_LIMIT=24000 (GACR critique), ranked by highest-degree shared callee so the densest part of the web is always inside the cap; spill to existing resumeDraftEvictingRejectedFiles.

R6 Parallel scheduler is hostile to topological freeze (FrozenTierLedger flaw #2: HermesParallelBatch runs tasks concurrently against one sourceDir). MITIGATION: do NOT impose a global sequential tier freeze. Keep parallelism; the ledger is a read-mostly projected contract, and the only ordering requirement is the existing producer-before-consumer javaTier within a task plus dependsOn edges derived from the contract (which the scheduler already honors via HermesTaskGraph). Foundation-tier reconciliation of the ledger happens at merge time (single-threaded in HermesMergeCoordinator.merge) where on-disk truth is already serialized.


## 验证

UNIT (per stage, deterministic, on-device, no cloud):
- Stage 1: SourceSnapshotComposerTest — build a snapshot from a synthetic 40-class tree; assert every class's line() appears in the contract/digest layer and that the layer is intact when full-text overflows SOURCE_FULL_TEXT_LAYER_LIMIT (proves the 6000-char truncation root cause is gone). ResourceIndexDigestTest — assert the 'R.string labels are NOT view ids' RULE and the idsByLayout grouping are always present and survive the 3000-char trim.
- Stage 2: SymbolTableTest — parametrized over the SAME class fed (a) as on-disk .java and (b) as typed addClass; assert identical hasMethod/hasMethodSignature/availableConstructors/isAssignable results, INCLUDING a nested-class (Outer.Entry) and a generics-in-extends case that the rejected text round-trip would have broken. Characterization: AndroidSourceGuardTest passes unchanged.
- Stage 3: ImplementationTaskNormalizerTest — assert canned phases now carry the resource slice (not empty contract). F4 test — a Java task referencing @color/brand_on_primary with no owning values task is rejected at contract/guard time, NOT at compile. F5 test — layout task and Java task bind the identical id set; a list-vs-per-item-id divergence is unrepresentable.
- Stage 4: ContractLinterTest — four cases mirroring the taxonomy: BudgetRepository->BudgetCalculator.calculate missing (F1) rejected; sumExpenseByCategoryInRange(long,long) arity wrong (F2) rejected; mine_account-as-id (F3) rejected; ic_notification @color undeclared (F4) rejected — all BEFORE any body. AppContractReconcileTest — a foundation body whose real signature differs from the seed OVERRIDES the seed (realized=true), proving the wrong-seed doom loop is neutralized. Fail-closed test — unreconcilable contract surfaces to user, no body generation.
- Stage 5: StuckFamilyPolicyTest — the reconcile directive now contains the real declared signature string. AgentServiceRetryPolicyTest — cluster regen does attempt-- and does not consume PREFLIGHT_REWRITE_BUDGET; closure includes callee + named callers within the cap.

INTEGRATION / REPLAY (the real proof): capture project-82/83 inputs (the plan + the user requirement that produced the 24-file 记账 app at build 302e1d1) as a fixture. Because every cloud call goes through openAiClient, add a record/replay harness (or a deterministic stub returning the actual project-82/83 model outputs) so the whole plan->contract->tasks->batches->merge pipeline can run on-device against captured responses. Assert: (1) ContractLinter flags the exact F1/F2/F3/F4 edges from the taxonomy at contract time; (2) with the contract injected, the merge-time AndroidSourceGuard violation count on first pass drops sharply versus the baseline run; (3) total cloud calls to converge (or to terminal failure) decreases — the convergence metric that matters. Track a regression gauge: number of POLICY_REWRITE_ATTEMPTS consumed and whether the run converges within the 5x2 budget, run before/after each stage on the project-82/83 replay, so each stage's contribution to convergence is measured independently. The end-state success criterion: project-82/83 converges within budget with the AppContract complete and reconciled, and AndroidSourceGuard.validate — unchanged — passes on the final tree.
