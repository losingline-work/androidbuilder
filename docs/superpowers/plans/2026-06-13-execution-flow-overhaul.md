# 执行流程大修(Execution Flow Overhaul)

> 执行者注意:本文档自包含,不依赖任何对话上下文。按阶段顺序执行,每个任务完成后跑全量测试并单独提交。
> 全量测试:`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`(当前基线 550 个,全绿)。
> 测试约定:final 策略类 + 静态方法 + `*Test`/`*PolicyTest`(JUnit4)。AgentService 不可单测——所有可测决策逻辑必须压进独立策略类,接线代码保持薄。
> 证据日志:`/Users/chedanchechedan/Downloads/androidbuilder-project-51-logs.txt`(6.3MB;若已不存在,本文档引用的关键证据均已内联)。

## 背景:一次 62 分钟失败的完整解剖

project-51 日志记录了任务 667(drawable and layout XML)与 668(Java source wiring)从 21:07 到 22:09 的失败全程:**约 62 分钟全部串行云端调用、5 份互不相同的清单(7/19/9/9/8 批)、两个任务共 4 个 dispatch,最终死于守卫误报**。法医结论:

- **种子**:667 的云端审查员(AI #508)指示模型"用内联字面量代替创建 strings.xml"→ strings.xml 从未诞生 → 668 的 Java 批引用 `R.string.bill_summary_title` 必然失败,之后所有失败都是这一颗种子的变体。
- **放大器 1**:批次 1 失败两次且无已接受批次时,`previousDraft=null` → 下一 attempt 重新生成**全新清单**(模型掷骰子,5 份清单互不相同)。
- **放大器 2**:跨 dispatch 草稿只作为 14000 字的"advisory 文本"注入提示词(`HermesAgentWorker.initialFailureContext`,L99 调 `TaskDraftStore.load`),`previousDraft` 从不复水 → 每个 dispatch 的修正/合并机制都从零开始。
- **dispatch 2 终结者**:`AndroidSourceGuard` 误报 "Generated source policy blocked missing model field: **App.ui** in BaseActivity.java"(还有 App.AppCompatActivity、App.Fragment、BillAdapter.Row 等)——把限定名/嵌套类型/内部类 this 误解析为字段访问,烧光该 dispatch 的尝试预算并污染后续重试上下文。(已在提交 9755b5b 修复,本计划阶段 1 记录其测试要求。)
- **dispatch 3 终结者(任务最终死因)**:终轮云端审查。确定性预检 #5 在 22:08 已对最终操作返回 ok,22:09 的审查员 #5 却返回 rewrite——其中混合了真问题(App.java 引用不存在的 NotificationChannels 类、未索引的 R.string.*)与**陈旧的 R-import 复读**(它鹦鹉学舌了请求里嵌的旧失败文本,而非实际操作内容;该操作早已包含 `import com.generated.app.R;`)。而 `HermesReviewerPolicy.shouldRetry` 要求 `attempt < maxAttempts`——**终轮审查结构上不可能产生任何效果**,这 32 秒调用直接导致重试耗尽。
- **R-import 的真实代价**(对抗验证修正):该确定性发现只触发了一次(22:04 预检 #4),消耗了一轮 274.7 秒的云端重写,模型随后自行修复。它不是终结原因,但 274 秒 vs 零成本的差距使自动修复(阶段 4.1)仍然成立。
- **回声放大**(对抗验证修正):`R.string.bill_summary_title` 批校验失败实际只发生**一次**(约 21:34,批次 3/19 之后),日志中的 5 次出现是同一事件在后续请求载荷里的引用回声——但这一次失败引发了整条修复螺旋(中途修复侦察 + 4 轮单发重写),阶段 3.2 确定性升级的依据不变。

## 已被对抗验证修正的两个旧结论(执行者勿按旧框架理解)

1. ~~"草稿只写不读"~~ → `TaskDraftStore.load` 存在且被调用(`HermesAgentWorker.java:99`),但**只渲染为 advisory 提示词文本**,从不作为 `previousDraft` 复水。修复方向是"复水",不是"补 load"。
2. ~~"批次提示词没有 blocked 出口"~~ → 批生成与单发共用 system prompt,blocked 契约在 `OpenAiClient.java:910` 已明确广告。模型**知道**可以 blocked 却选择不用——所以解法是**编排器确定性合成 blocked**(阶段 3),不是改提示词措辞。

## 不变量(执行中不得破坏)

- 守卫零旁路:`AndroidSourceGuard`/`DependencyGuard` 仍是落盘前最终权威;本计划只**提升守卫精度**(消灭误报),不放宽任何真拦截。
- 单违规守卫消息逐字兼容(现有 `assertEquals` 精确断言不得改动断言文本)。
- 批生成最终单次 apply + 守卫的时序不变;blocked → 扩域、correction、流式熔断等机制全部保留。
- 现有公开方法签名只增不改。

---

# 阶段 1:守卫误报修复(终结者,最高优先)

## 任务 1.1:复现并修复 "missing model field: App.ui" 类误报

**文件**:`AndroidSourceGuard.java`(JavaApiSymbols/字段一致性检查区域)+ `AndroidSourceGuardTest`。

**步骤**:
1. 从证据日志提取真实弹药:`grep -n "missing model field" androidbuilder-project-51-logs.txt` 找到全部误报(App.ui / App.AppCompatActivity / App.Fragment in BaseActivity.java、BaseFragment.java;BillAdapter.Row in BillListFragment.java);再从日志中提取这些文件被拒草稿的**完整内容**(操作 JSON 就在日志里)。
2. 把真实文件内容写成失败测试(脱敏精简到最小复现),确认现守卫误报。
3. 定位解析缺陷。高概率方向(执行者验证):字段访问检查把 `识别符.识别符` 模式过度匹配——`import com.generated.app.ui.…` / 注释 / 限定类名(`androidx.appcompat.app.AppCompatActivity` 中的 `app.AppCompatActivity` 若大小写不敏感)/ 内部类引用(`BillAdapter.Row` 作为类型而非字段)被当成"对已知类 App/BillAdapter 的字段访问"。修复必须:只在剥离注释/字符串后的内容上匹配;排除 import/package 行;排除后随大写开头标识符的情形(`X.Y` 中 Y 大写开头 = 嵌套类型引用,不是字段);排除完整限定名链中段。
4. 修复后:误报测试转绿;所有现存字段一致性的真阳性测试逐字不变。

**为什么第一**:这是 668 任务 10 次尝试全灭的直接死因,且守卫误报会污染所有重试上下文,任何流程优化都救不了被守卫无限拒绝的任务。

---

# 阶段 2:草稿全生命周期(真续作)

## 任务 2.1:dispatch 开始时复水 previousDraft

**文件**:`HermesAgentWorker.java`(L97-111 一带)、`AgentService.java`(`createAndApplyTaskOperationsInternal` 签名加 `TaskOperations initialDraft` 参数,L1150 改为用它初始化)、调用链透传。

**改动**:`HermesAgentWorker` 现在已 `TaskDraftStore.load(task.id)`——把加载结果**同时**作为 `initialDraft` 传入(advisory 文本照旧保留,两者互补:文本给模型看,对象给合并机制用)。`previousDraft = initialDraft`;有草稿且有失败上下文时,首个 attempt 自然走 correction(`DraftCorrectionPolicy.shouldCorrect` 现有逻辑),**批生成被禁用**(`shouldUseBatchedGeneration` 现有条件已覆盖)——跨 dispatch 不再从零重跑。

**测试**:复水决策逻辑若有分支(损坏草稿→null、空草稿→null)抽进策略类单测;`TaskDraftStore` round-trip 测试覆盖 find/replace 字段(已有则确认)。

## 任务 2.2:save 语义修复——扩容且不再自毁

**文件**:`TaskDraftStore.java` + 测试。

**改动**:
1. `MAX_BYTES = 300KB → 2MB`。实测(法医调查)草稿 JSON 密度约 6.7KB/文件,300KB ≈ 45 个文件,而合法清单可达 120 文件(`TaskManifest.MAX_FILES`)≈ 800KB——**当前上限恰好把最大的部分成果删掉**。
2. 超限时**不删既有草稿**(现行为:`save` 超限调 `delete()`,把之前存的小草稿一起毁掉)。改为:超限则保留磁盘上原草稿不动,仅跳过本次保存(注释写明理由)。

**测试**:超限保存不破坏已有文件;2MB 内正常写。

## 任务 2.3:草稿删除延迟到合并成功之后

**文件**:`AgentService.java`(L1404 的 `deleteTaskDraftSafely` 调用点、`HermesMergeCoordinator` 合并成功路径)。

**问题**:现在草稿在 scratch 树 apply 成功时立即删除,但 `HermesMergeCoordinator.merge` 之后还要对**正本树**重跑契约守卫 + 预检 + 重新 apply——合并阶段拒绝时草稿已经没了,下个 dispatch 颗粒无收。

**改动**:把任务级草稿删除从循环内成功点(L1404)移到合并协调器确认该任务结果成功合并之后(`markMergeFailedResults` 的反面路径)。注意 Hermes 并行下删除要按 taskId 精确。

**测试**:删除时机的决策若可抽策略则抽;否则以 `HermesMergeCoordinatorTest` 现有模式补一条"合并失败后草稿仍在"。

## 任务 2.4:blocked 中途与流中止不再丢弃已接受批次

**文件**:`AgentService.java`(批循环内 L1509-1511 blocked return、L1287 扩域置 null 处)。

**问题**:清单中途某批返回 blocked → 直接 return blocked ops,已接受批次既不保存也不入 `previousDraft`;扩域路径还把 `previousDraft` 置 null——扩域后从零重新生成。

**改动**:批循环遇 blocked 时,先 `saveTaskDraftSafely(accepted 组合草稿)` 再返回 blocked;扩域分支(L1287)不再无条件置 null——若磁盘/内存有非空草稿,保留它(扩域只是放宽边界,已写好的文件仍然有效)。扩域后的生成会因 `previousDraft != null` 走单发,correction 与否由现有 `DraftCorrectionPolicy` 决定。

**测试**:批循环 blocked 路径保存草稿(决策逻辑抽策略类);扩域不清草稿的分支逻辑单测。

---

# 阶段 3:批次流程收敛(不再掷骰子)

## 任务 3.1:清单持久化 + 断点续批,同任务永不重新清单

**文件**:`TaskDraftStore.java`(格式扩展)、`AgentService.java`(批循环)、新 `ManifestResumePolicy.java` + 测试。

**改动**:
1. 草稿文件格式增加可选字段:`manifestJson`(原始清单)、`acceptedPaths`(已接受批次的文件集)。向后兼容:无这些字段的旧草稿照常读。
2. 批生成开始前:若草稿带 manifest → **跳过清单调用**,`ManifestResumePolicy.remainingBatches(manifest, acceptedPaths)` 重算剩余批次(复用 `ManifestBatchPolicy.batches` 后过滤已接受),从断点继续;已接受操作直接进 `accepted` 与 `ResourceSymbolsOverlay`。
3. 每个批次被接受后立即增量保存草稿(manifest + 累计 accepted)。
4. 同一任务生命周期(跨 attempt、跨 dispatch)**只允许生成一次清单**:`BatchGenerationException` 后的重试若草稿里有 manifest 就续批,绝不再调 `createTaskManifest`。批次连续失败两次 → 不再回落到"重新清单",而是:命中任务 3.2 的确定性升级,或任务级失败(草稿完整保存,下个 dispatch 续批)。
5. 由此 `shouldUseBatchedGeneration` 的语义更新:`previousDraft` 带未完成 manifest → 走续批;带完整操作集 → 走单发 correction;为 null → 首次批生成。

**测试**:`ManifestResumePolicy` 剩余批次计算(含全部完成、部分完成、空 accepted);草稿格式 round-trip;旧格式兼容。

## 任务 3.2:缺失资源的确定性升级(替代模型重试)

**文件**:新 `BatchEscalationPolicy.java` + 测试;`AgentService.java` 批循环接线。

**问题**:批校验报 "missing string resource R.string.X in Y.java" 时,系统**确定性地知道**:该资源不在现有树、不在 overlay、也不在本任务清单的任何文件计划里——模型重试这一批是纯粹的赌博(日志实证:一次 bill_summary_title 批校验失败触发了中途修复侦察 + 4 轮单发重写的整条螺旋,失败文本在 5 个后续请求里回声)。

**改动**:`BatchEscalationPolicy.escalate(validationError, manifest, contract)`:当批校验错误属于"缺失资源"类(解析 `Batch validation: missing <label> R.<type>.<name>` 消息),且该资源名不可能由本清单产出(清单中没有任何 values/对应资源文件计划)→ 返回合成的 blocked `TaskOperations`:`blockedReason` = 校验错误原文,`prerequisiteWork` = "Create <res-kind> entry <name> (e.g. in app/src/main/res/values/strings.xml) before this task can proceed."。批循环在**第一次**命中此类错误时直接走 blocked 路径(不消耗第二次批尝试)→ 现有扩域机制接管。注意:本日志中任务 668 **没有 Hermes 契约**(契约缺失时 `allowsPath` 恒真)——判定依据用清单文件集而非契约,契约存在时再叠加契约判断。

**测试**:缺失资源错误 + 清单无 values 计划 → 合成 blocked(prerequisiteWork 含资源名);清单中有对应 values 文件计划 → 不升级(让批重试,模型可在本批内修);非资源类校验错误 → 不升级。

## 任务 3.3:扩域同步更新机器契约(修潜在死锁)

**文件**:`BlockedTaskPolicy.java` + 测试、`AgentService.java`(L1217 一带契约重提取处)。

**问题**(法医发现的潜在 bug):`scopeExpandedInstruction` 把原指令(含 `[HermesTaskContract]` 块)嵌入新指令,L1217 每个 attempt 重新 `extractFromInstruction` → 扩域后流式预检/批校验仍按**旧 allowedPaths** 拒绝越界写入——对带契约的任务,扩域是假的。

**改动**:`BlockedTaskPolicy.scopeExpandedInstruction` 重写嵌入的契约块:将 `prerequisiteWork` 指向的资源路径类(如 `app/src/main/res/values/**`)并入 allowedPaths(或最简单:扩域后指令中**剥除**契约块并在注释说明"扩域任务以指令文字为边界")。选择剥除方案时,确认 `HermesTaskContractGuard`/`TaskStreamPreflight` 对空契约的行为是放行(现状如此)。

**测试**:扩域后的指令提取不出旧 allowedPaths 限制;blocked → 扩域 → 写 values 文件不再被流式预检拒绝。

---

# 阶段 4:确定性自动修复层

## 任务 4.1:R-import 自动修复

**文件**:新 `JavaImportNormalizer.java` + 测试;`AgentService.java`(两处生成结果 canonicalize 之后接线);`TaskOperationsPreflight.java` 规则保留作兜底。

**改动**:`TaskOperationsPreflight` 的 R-import 规则(L86 一带)已经计算出**精确的修复指令**(目标文件 + 完整 import 行)——却用它烧云端重写轮次。新 normalizer 对每个 Java write 操作:若内容使用 `R.` 引用、package 声明属于应用命名空间的子包、且无 `import <namespace>.R;` 也无完整限定 `<namespace>.R.` 引用 → 在 package 行后插入 import。命名空间推导逻辑**复用** preflight 现有实现(抽为共享静态方法,不复制)。接线位置:单发路径 `canonicalizeAll` 之后、批路径批校验之前。`DatabaseContractNormalizer`(`FileOperationsWriter` 内)是现成的同类先例。

**测试**:缺 import 自动插入(位置正确:package 行后、首个现有 import 前);已有 import 不重复;完整限定引用不插入;非应用包 Java 不动;预检规则在 normalizer 之后永不触发(集成断言)。

## 任务 4.2:预检 REWRITE 规则机械可修性审计

**文件**:`TaskOperationsPreflight.java`(只审计,按发现决定)。

**改动**:列出全部返回 REWRITE 的规则,逐条判断是否机械可修(像 R-import 一样修复指令完全确定)。保守原则:只把"修复内容可由规则自身唯一确定"的规则转为 normalizer;有任何歧义的保持 REWRITE。在计划执行记录中写明每条的判断。

### 执行记录:`TaskOperationsPreflight.review` 全部 REWRITE 规则审计(2026-06-13)

`TaskOperationsPreflight.java` 中 `review()` 只有三处返回 REWRITE,逐条裁定:

| 规则 | 位置 | 机械可修? | 裁定 |
|------|------|-----------|------|
| 操作数超 `MAX_OPERATIONS_PER_TASK` | L41-45 | ❌ 否 | 删哪些文件是语义判断,规则无法唯一确定"该砍哪个"——保持 REWRITE。修正方向应是改进分批(已由阶段 3 的清单/续批承担),而非自动删文件。 |
| XML 非良构 | L48-53 | ❌ 否 | "把 XML 补完整"涉及补哪个标签/属性,无唯一确定的机械变换——保持 REWRITE。 |
| 子包 Java 用 `R.*` 缺 R import | L86 | ✅ 是 | 修复指令完全确定(`import <namespace>.R;`),唯一变换——**已在任务 4.1 提取为 `JavaImportNormalizer`,在预检前自动修复**,该规则现作为兜底保留(自动修复后不应再触发)。 |

结论:三条 REWRITE 规则中只有 R-import 一条满足"修复内容由规则自身唯一确定",已转为 normalizer(任务 4.1);另两条有语义歧义,按保守原则保持 REWRITE。无新增 normalizer。

---

# 阶段 5:云端审查员整顿

**文件**:`HermesReviewerPolicy.java` + 测试、`AgentService.java`(L1369-1401 审查区)、`OpenAiClient.java`(审查员 prompt)。

## 任务 5.1:结构性无效的审查不再发起

`shouldReviewOperations` 增加条件:`attempt >= maxAttempts` 时不审查(`shouldRetry` 已要求 `attempt < maxAttempts`,最后一轮的审查结果无路可走——日志中 22:09 那次 32 秒调用零效果)。同样,`preflightRewrites >= PREFLIGHT_REWRITE_BUDGET` 时(重写预算已耗尽)不审查。

## 任务 5.2:审查发现与确定性规则去重 + 陈旧发现免疫

审查员返回的 rewrite 若与确定性规则输出匹配(消息前缀匹配 "uses R.* but is missing R import" 等已知确定性消息集)→ 丢弃该 finding(确定性层已兜底,且阶段 4 已自动修复);若当前 attempt 的确定性预检刚返回 ok,审查员复读的同类 finding 视为陈旧(日志实锤:22:08 预检 ok,22:09 审查员仍要求加 R import,因为旧失败文本嵌在指令里)。实现:`HermesReviewerPolicy.isStaleOrDuplicate(finding, deterministicOkThisAttempt)` 单测覆盖。

## 任务 5.3:失败的审查调用不消耗预算

`cloudReviewsUsed` 仅在审查**成功返回且产生有效决定**时递增(现状:异常 FALLBACK 也消耗唯一名额,AgentService L1385-1387)。

## 任务 5.4:审查员禁止"内联字面量"类降级建议(种子消灭)

审查员 system prompt 增加硬规则:

```
Never advise replacing resource creation with inline literals or other quality-degrading
workarounds. If a referenced resource is missing, the correct fixes are: create the resource
in res/values within this task when allowed, or return blocked with prerequisiteWork naming it.
```

(日志实锤:667 的审查员建议内联字面量代替 strings.xml,是整条 62 分钟失败链的种子。)

**测试**:prompt 文案断言;5.1/5.2/5.3 的策略分支单测。

---

# 阶段 6:尝试循环漏洞修补

**文件**:`AgentService.java`、`DraftCorrectionPolicy.java` 视需要。

## 任务 6.1:单发解析异常接住

单发路径 `TaskOperationsParser.fromJson`/`canonicalizeAll` 抛出的普通 `IllegalArgumentException` 目前**逃出整个方法**(两个生成 catch 只接 `BatchGenerationException`/`StreamAbortException`,第二个 try 不包住生成调用)——一次解析失败直接跳过全部 5 次尝试且不存草稿(日志实锤:AI #501 生成 #2 "failed")。改动:单发生成 + 解析包进与批路径等价的 catch:`previousFailure`=消息、streak、保存草稿、`attempt==max` 时再抛。`TaskOperationErrorPolicy.shouldRequestRewrite` 已把这些消息归类为可重写,语义一致。

## 任务 6.2:exhaustion 不丢真因

循环耗尽时 `lastPolicyError` 可能为 null(末几轮全走守卫 rewrite continue)→ 抛出泛化的 "Task operation generation failed."。改为携带最后一次 `previousFailure` 文本,真实原因不被吞。

## 任务 6.3:二次 blocked 保存草稿

L1298 的 `throw new IllegalStateException(blockedSummary)` 之前补 `saveTaskDraftSafely`(该路径现在绕过所有草稿保存)。

**测试**:可抽的决策逻辑抽策略类;6.1 用解析失败注入(模拟 openAiClient 不可行,则把"生成+解析+异常分类"的可测部分抽出)。

---

# 阶段 7:可观测性(让下一次诊断不需要 6MB 日志)

**文件**:`AgentService.java`、新 `TaskAttemptJournal.java`(纯内存累积 + 渲染)+ 测试。

## 任务 7.1:每个 attempt 的终结原因必须落记录

法医调查证实:dispatch 2 的死因(守卫拒绝 App.ui)、dispatch 3 两次批失败原因、8/8 批完成后的失败——**全部没有对应日志记录**,只能从下一轮的请求上下文里反推。改动:尝试循环每条失败路径(含批失败、守卫拒绝、合并拒绝)写一行结构化记录(`recordCloudAiCall` 同级的本地记录或 `FileUtils.appendText(logs, ...)`,带 attempt 号、阶段、原因前 500 字)。

## 任务 7.2:任务结束执行摘要卡

任务终结(成功/失败/blocked)时产出一条时间线消息:`attempts=N, manifests=M, batches=K/T, rewrites=R, reviews=V, wallSeconds=S, 终结原因`(由 `TaskAttemptJournal` 累积渲染,纯函数可单测)。这是用户判断"是否流程又出问题"的第一入口。

---

# 执行顺序与提交

1 → 2 → 3 → 4 → 5 → 6 → 7,阶段内按任务号,每任务一提交(`feat:`/`fix:` 英文消息)。阶段 1 与阶段 2.2 可最先单独发布(纯止血)。每阶段完成跑全量测试;阶段 1 完成后额外用日志中提取的真实文件做守卫回归。

# 预期效果(对照本次 62 分钟)

| 环节 | 现状 | 大修后 |
|------|------|--------|
| 守卫误报烧尝试 | 10 次全灭 | 消灭(阶段 1) |
| 审查员种下缺资源祸根 | 整链失败源头 | 禁止该建议 + 缺资源确定性升级(5.4 + 3.2) |
| 批 1 失败 → 重新清单 | 5 份清单 | 清单只生成一次,断点续批(3.1) |
| 跨 dispatch | 全部从零 | 草稿复水 + 续批(2.1 + 3.1) |
| R-import | 多轮云端 | 零成本自动修复(4.1) |
| 最后一轮审查 | 32 秒零效果 | 不发起(5.1) |
| 失败可见性 | 需要 6MB 日志反推 | 摘要卡 + attempt 级记录(7) |

# 风险与边界

- 阶段 1 改守卫解析必须以真实误报样本驱动,修完跑全部守卫测试——宁可少排除一类误报,不可放过一类真问题。
- 阶段 2.3 移动草稿删除点时注意 Hermes 并行:多个任务各自的草稿独立按 taskId 删,不可误删他任务草稿。
- 阶段 3.1 的"只清单一次"以草稿文件为事实源:草稿被 2.2 的超限策略跳过保存时,允许退化为重新清单(写日志说明),不可死锁。
- 阶段 4.1 normalizer 只插 import,不做任何其他内容变换;插入位置错误会制造编译错误,测试必须覆盖"package 行带注释"“无 package 行"等怪形。
- 阶段 5.2 的"陈旧发现"判定要保守:仅当本 attempt 确定性预检明确 ok 且 finding 与确定性规则消息集匹配时丢弃,不可误丢审查员的独立发现。
- 本计划不动 `bootstrap-aarch64.zip` 与嵌入式运行时;不顺手重构无关代码。
