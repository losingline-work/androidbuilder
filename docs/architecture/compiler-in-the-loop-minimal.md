# 最小档：把编译器搬进生成循环（compiler-in-the-loop, minimal）

> 本文是 `compile-driven-validation.md` 的直接延伸。compile-driven 已确立「真 javac = 类型权威」，
> 但编译器目前只在 **build 末尾整树跑一次**，且修复是 **手动按钮**。本设计在不推翻 Hermes/guard
> 编排的前提下，把编译反馈插进生成循环，让 agent「边写边编、读着真错误改」。
>
> 执行约定：final 策略类 + 静态方法 + 对应 `*Test`（JUnit4，见 `app/src/test/java/com/androidbuilder/`）；
> AgentService 本身不可单测，可测逻辑压进独立策略类。行号可能漂移，用 grep 定位。
> 全量测试：`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`

## 实现状态（2026-06-15）

阶段 1–4 全部已实现，`:app:testDebugUnitTest` + `:app:assembleDebug` 均通过。实现中对现状的修正（比本文初稿更贴合真实代码）：

- **块 A 部分早已存在**：`EditOperationPolicy` + `TaskOperationsMergePolicy` 已支持「同一草稿内 edit 一个刚 write 的文件」。真正的缺口是 **edit 磁盘上已存在的文件**——已在 `FileOperationsWriter.applyToDirectory` 接通（复用 `EditOperationPolicy`），并教进 `OpenAiClient` 的操作 schema。
- **块 B-1 改为「自动化 UI 里现有的 Build→Repair 闭环」**，而不是在 `AgentService` 里另跑 `compileOnly`/`TypeChecker`。原因：build/repair 的编排本就在 UI 层（`buildLatest` + `repairBuildJob`），现有 build 内部已有 `compileDebugJavaWithJavac` 编译门。复用它 = 零双重编译、不跨层。落点是新 `ui/AutoRepairLoopPolicy`（可测）+ `ProjectActivity` 薄驱动（仅嵌入式后端、6 轮上限）。`compileOnly`/`TypeChecker`/逐层 javac 的想法收敛进**阶段 4**。
- **块 C** 通过 `repairInstruction` 的 edit-first 指令 + 「逐字采用 javac 已打印的 required:/found: 真签名」+ 新 `ui/RepairLoopStallPolicy`（连续同诊断指纹 ⇒ 提前停）落地。真签名靠 javac 自带诊断，不再另造 SymbolTable 注入（冗余）。

- **块 B-2（阶段 4，直驱 javac 快速门）** 落地为 build 内、`abResolveDebugDeps` 之后、Gradle 编译门之前的一道**快速预检**（非「逐批次生成中编译」——那仍需生成期 classpath bootstrap，留作后续）：`abResolveDebugDeps` 现把 classpath 落盘（`androidbuilder-classpath.txt`）；新 `SyntheticAndroidSymbols` 从 `res/` 生成合成 R/BuildConfig（放行 R.* 引用）；后端用 `sh -c "javac @argfile"`（javac 是 ELF 二进制，不能像 gradle 脚本那样 `sh <file>`）直驱编译。**关键安全设计**：新 `TierCompileVerdict` 只在**高置信度跨文件错误**（cannot find symbol / 参数不匹配等，即 project-9 痛点类）时才 fast-fail；任何疑似 classpath/合成符号不完整的迹象（package does not exist / location: class R 等）一律**退回权威的 Gradle 编译门**。因此即便 on-device 的 javac 调用有细节偏差，最坏只是「白跑、退回」，**绝不会误判合法构建为失败**。

**诚实边界**：on-device 的 javac 直驱路径在本机开发环境无法单测（无 Termux runtime），仅靠真实构建做集成验证；纯逻辑（合成符号、verdict）已单测。tier 门仅在 online + 外部依赖（resolve 实际跑过）时生效，其他依赖模式自动跳过、走原 Gradle 编译门。

## 目标与边界

**做**：用三块最小改动，把我（Claude Code）的工作循环的核心性质搬给 AgentService——
edit（精确局部改，不重发整文件）、closed-loop（编译器在环内当裁判）、diagnostic-driven（读 file:line 定向修）。

**不做**：不给模型开放 shell、不做自由 ReAct（那是「完整档」，需强模型）。本档只在**确定性编排**里
插入编译反馈，模型仍只填空。不引入 contract-first 的模型 seed 和重写 linter。

## 现状锚点（执行前先 grep 确认）

| 关注点 | 位置 | 现状 |
| --- | --- | --- |
| 文件操作模型 | `model/FileOperation.java:7-14` | **已带 `find`/`replace` 字段和构造** |
| 操作解析 | `TaskOperationsParser.java:135,139-140` | **已认 `edit`，解析出 find/replace** |
| 操作落盘 | `FileOperationsWriter.java:103-109` | 只处理 `write`/`delete`，`edit` 抛 `Unsupported file operation action` |
| 落盘流水线 | `FileOperationsWriter.java:46-88` | apply → normalize → StubReconciler → `validatePolicyOnly`（最终权威，零旁路） |
| 整树编译门 | `EmbeddedRuntimeBackend.java:146-170` | build 时跑一次 `compileDebugJavaWithJavac`，失败 → `java_compile_failed` |
| classpath 解析 | `EmbeddedRuntimeBackend.java:296-310` | `abResolveDebugDeps` 解析 `debugRuntimeClasspath`，**但不落盘** |
| 诊断提取 | `BuildLogContextExtractor.java:18,55` | `javaCompileDiagnostics` / `missingFieldHints` 已能裁 javac 输出 |
| 失败分类 | `BuildFailureClassifier.java:65-72` | `compiledebugjavawithjavac`/`.java:`/`cannot find symbol` → `JAVA_COMPILE, repairableByModel=true` |
| 生成循环 | `AgentService.java:1179` `createAndApplyTaskOperationsInternal`；分批 `:1283`/`:1754`；落盘 `apply` `:1534` | 一任务一云调用，整批生成后才校验 |
| 分批顺序 | `ManifestBatchPolicy.java`（`javaTier`，category 3） | **已有生产者-先于-消费者排序**（util/db→entity→dao→repo→domain→ui） |
| 修复 | `AgentService.java:510` `repairBuild`；触发 `repairBuildAsync :232` ← `ProjectActivity.repairLatest` | **纯手动**，无自动循环 |
| 真签名注入 | `StuckFamilyPolicy.reconcileDirective` + `SymbolTable` | 已落地（compile-driven Stage 5） |

## 不变量（不得破坏）

- **守卫零旁路**：`validatePolicyOnly`（merge 时）+ 真 javac（build/编译时）仍是唯一权威。本档所有「提前编译」只允许比最终编译门**更早失败**，不允许放行它会拒绝的东西。
- `FileOperationsWriter.apply` 对外语义不变；现有 `write`/`delete`/`drop`/blocked/草稿/预算机制全部保留。
- 现有公开方法签名保留，只加重载。
- edit 操作走**同一条** apply 流水线，落盘前照样过 normalizer + StubReconciler + `validatePolicyOnly`。

---

# 块 A：接通 `edit` 文件操作（最小、最先做）

模型层和解析器已就绪，缺的是落盘 + 教 prompt。

## A.1 落盘语义（`FileOperationsWriter.applyToDirectory`）

在 `:103-109` 的 write/delete 分支旁加 `edit`：

```java
} else if ("edit".equals(operation.action)) {
    if (!target.isFile()) {
        throw new IllegalArgumentException(
            "edit operation targets a non-existent file: " + operation.path + ". Use write to create it.");
    }
    String before = FileUtils.readText(target);
    int first = before.indexOf(operation.find);
    if (operation.find.isEmpty() || first < 0) {
        throw new IllegalArgumentException(
            "edit find-text not found in " + operation.path + ". Quote the exact existing text.");
    }
    if (before.indexOf(operation.find, first + 1) >= 0) {
        throw new IllegalArgumentException(
            "edit find-text is ambiguous (>1 match) in " + operation.path + ". Include more surrounding context.");
    }
    FileUtils.writeText(target, before.substring(0, first)
            + operation.replace + before.substring(first + operation.find.length()));
}
```

**语义对齐我自己的 Edit 工具**：`find` 必须**唯一命中**，否则报错（不静默改错位置）。报错走 `IllegalArgumentException` →
现有 `TaskOperationErrorPolicy.shouldRequestRewrite` 会触发更严格重写（见 `02-业务流程` §4），无需新机制。

把判定逻辑抽成 `EditOperationPolicy.locate(String content, String find)` 返回 `int`（-1 未命中 / -2 多义 / >=0 位置），
让 applyToDirectory 只做 IO，策略可单测。

## A.2 教 prompt（`OpenAiClient` 的 system prompt + 任务/修复 instruction）

在操作 schema 处补：
- `{action:"edit", path, find, replace}`：`find` 必须是文件中**逐字唯一**存在的片段；改局部时**优先用 edit，不要重发整个文件**。
- 修复场景强约束：见块 C。

## A.3 测试

- `FileOperationsWriterEditTest`：唯一命中→替换成功；0 命中→报错；多义→报错；目标不存在→报错；edit 后整树仍过 `validatePolicyOnly`。
- `EditOperationPolicyTest`：locate 的三态。
- 解析回归：`edit` 缺 find 字段时的行为（`TaskOperationsParser` 已 `optString("find","")`，断言空 find 落到 A.1 的报错）。

**守卫安全**：edit 产物和 write 产物在落盘后无差别，统一过最终守卫。

---

# 块 B：编译器进环

分两个粒度。**先做 B-1（最小），用数据决定要不要做 B-2。**

## B-1：自动循环「现有的整树编译门」（推荐先落地）

这就是 compile-driven 文档 Stage A 第 4 点「auto-loop the existing repair on JAVA_COMPILE」——规划了但没做。
不引入任何新编译器，只是把已有的 `compileDebugJavaWithJavac` 变得能从 AgentService 调、且失败自动修。

### B-1.1 让编译可被 AgentService 调用

从 `EmbeddedRuntimeBackend.runBuild`（`:120-170`）抽出 resolve + compile 段为：

```java
public TypeCheckResult compileOnly(File sourceWorkDir, BuildJobRecord job, Listener listener)
// 跑 abResolveDebugDeps + compileDebugJavaWithJavac（不跑 assembleDebug），
// 返回 { boolean ok; String log; String diagnostics }（diagnostics 用 BuildLogContextExtractor 裁好）
```

为保持 AgentService 可测、不硬依赖 backend，定义接口：

```java
public interface TypeChecker { TypeCheckResult compileOnly(File sourceDir); }
```

`EmbeddedRuntimeBackend` 实现它；AgentService 持有 `TypeChecker`（注入），单测里用假实现。

### B-1.2 在生成收尾处插入「编译 → 自动修」循环

`executePlan`（`AgentService.java:331`）里，所有批次 merge 完、把 job 标 `generated/ready_for_build` **之前**：

```
for (round = 0; round < MAX_AUTO_COMPILE_ROUNDS /*≈6*/; round++) {
    r = typeChecker.compileOnly(canonicalSource)
    if (r.ok) break
    kind = BuildFailureClassifier.classify(r.log)
    if (kind != JAVA_COMPILE || !kind.repairableByModel) break   // 环境/依赖类不在此循环里硬磕
    repairBuild(projectId, job, r.diagnostics)                    // 复用现有修复，喂精确诊断
}
// 仍不过 → job 照常进 ready/failed，手动 Repair 按钮依旧可用（不回归）
```

`repairBuild`（`:510`）已存在，只是改成可传入「已裁好的诊断字符串」而非自己去读 build.log。

### B-1.3 测试

- `AutoCompileLoopPolicyTest`：把「是否继续循环」抽成 `static boolean shouldRetry(round, max, classifierKind, repairable)` 单测（AgentService 不可测的部分压进这里）。
- 假 `TypeChecker`：先返回 fail（带可解析诊断）再返回 ok，断言恰好修一轮、第二轮编过即停；返回 `DEPENDENCY_NETWORK` 时**不**进循环。

**守卫安全**：编译门是只读校验，修复产物仍过 merge 守卫；循环上限防止打转；失败回退到现有手动路径，零回归。

## B-2：逐层 javac（可选，B-1 数据证明整树循环太粗再做）

整树循环的缺点：一次编 24 文件、一墙红、爆炸半径大、且每轮要等 Gradle。B-2 把编译挪到**每批次之后**、绕开 Gradle 直接 javac。

### B-2.1 一次性把 classpath 落盘

扩 `abResolveDebugDeps` 的 init script（`EmbeddedRuntimeBackend.java:298-308`），`doLast` 里把解析结果写出：

```groovy
def cp = configuration.resolvedConfiguration.resolvedArtifacts.collect { it.file.absolutePath }
new File(project.buildDir, 'androidbuilder-classpath.txt').text = cp.join('\n')
// 另把 android.jar（bootClasspath）一并写出
```

生成开始时（Gradle 脚手架任务产出 `app/build.gradle` 后）解析一次，整轮复用。

### B-2.2 `TierCompiler`（直驱 javac，不走 Gradle）

```java
TierCompiler.check(File sourceDir, File classpathFile, ResourceSymbols res) -> List<Diagnostic>
// 1) 从 res 生成「合成 R.java」：把已知资源符号写成 int 常量，让 R.* 引用不挡类型检查
//    （资源真实性仍由 policy 守卫 + build 时 aapt 负责，此处只为放行 R 引用）
// 2) javac -cp <classpath> -d <tmp> <sourceDir 下全部 .java + 合成 R.java>
// 3) 解析 javac 诊断为 {file,line,message,可能的真签名}
```

runtime bootstrap 已带 openjdk-21 → javac 现成；classpath 已解析好 → 秒级，无 Gradle 启动税。

### B-2.3 接到批次循环

`ManifestBatchPolicy` 已按 `javaTier` 生产者-先于-消费者排序——天然就是「层」。每批 merge 后 `TierCompiler.check`
累计源码；该批引入的 file:line 错误 → 作为**块 C 的定向修**喂回**这一批**，编过再进下一批。

### B-2.4 诚实的限制

- 合成 R.java 意味着**资源类型正确性**仍延后到 build 时 aapt（与现状一致，不退步）。
- 泛型在 simpleType 下会塌（`List<Foo>` vs `List<Bar>` 不可见）——和最终守卫同精度，只是更早。
- 首次 javac 要 Gradle 脚手架任务先产出 `app/build.gradle` 才能解析 classpath。

### B-2.5 测试

- `TierCompilerTest`：合成 R.java 让 `R.id.x` 引用编过；跨文件缺方法→报对 file:line；纯 Java 类型错被捕获。
- `SyntheticRPolicyTest`：从资源符号集生成的 R.java 可编、字段齐全。

---

# 块 C：诊断驱动的定向修复（把 A 和 B 黏起来）

编译门给出 file:line，修复就该**在那一行下 edit 刀**，而不是重发整文件。

## C.1 修复 prompt 改成「edit-first」

修复 instruction（`repairBuild` 喂给模型的那段）强约束：
- 你会拿到 `file:line: error: ...` 诊断；**只用 `edit` 操作**精确改命中行，禁止 `write` 整文件，除非该文件需整体重写。
- 把 `BuildLogContextExtractor.javaCompileDiagnostics` 的输出逐字带上。

抽 `RepairInstructionPolicy.editFirst(diagnostics, realSignatures)` 生成这段，可单测。

## C.2 注入被调方的真实签名

复用 `StuckFamilyPolicy.reconcileDirective` + `SymbolTable`（已落地）：F1/F2 类错误把**被调方真实声明签名**塞进修复
directive，让模型「拿着答案改」，而不是再猜一遍。`BuildLogContextExtractor.missingFieldHints` 已能抽缺失字段，接上即可。

## C.3 不盲目重试

`DraftCorrectionPolicy.isStructuralError` 已区分结构错 vs 内容错。保证每轮修复输入随**真实诊断**变化；连续两轮诊断
签名相同（同一处没修动）则提前停，回退手动，避免 token 空烧。

## C.4 测试

- `RepairInstructionPolicyTest`：诊断 → edit-first 指令；带真签名时签名出现在指令里。
- `RepairLoopStallPolicyTest`：连续两轮同诊断 → 停。

---

# 分阶段落地

| 阶段 | 内容 | 风险 | 触及文件 |
| --- | --- | --- | --- |
| **1** | 块 A：接通 `edit` | low | `FileOperationsWriter`、新 `EditOperationPolicy`、`OpenAiClient` prompt、测试 |
| **2** | 块 B-1：抽 `compileOnly` + 自动编译-修复循环 | medium | `EmbeddedRuntimeBackend`（抽 `compileOnly`+`TypeChecker`）、`AgentService.executePlan`、新 `AutoCompileLoopPolicy`、`repairBuild` 改签名、测试 |
| **3** | 块 C：edit-first 修复 + 真签名注入 | medium | 新 `RepairInstructionPolicy`/`RepairLoopStallPolicy`、`repairBuild`、prompt、测试 |
| **4（可选）** | 块 B-2：逐层 javac + classpath 落盘 + 合成 R | high | init script、新 `TierCompiler`/`SyntheticRPolicy`、批次循环、测试 |

**推进纪律**：1→2→3 先做，**量收敛数据**（典型 app 几轮编过、消耗几次云调用），如果整树循环仍嫌粗再上阶段 4。
每阶段独立可交付、独立提交、跑全量测试。

# 风险与缓解

- **R1 AgentService 新增编译依赖**：用 `TypeChecker` 接口注入，AgentService 不硬依赖 backend，可假实现单测。
- **R2 自动循环放大构建耗时**：上限 `MAX_AUTO_COMPILE_ROUNDS≈6`；只对 `JAVA_COMPILE && repairableByModel` 进循环，环境/网络/依赖类直接回退手动（`BuildFailureClassifier` 已能分类）。
- **R3 edit 改错位置**：强制 `find` 唯一命中，多义即报错（对齐我自己的 Edit 工具语义）；edit 产物仍过最终守卫。
- **R4 合成 R.java 掩盖资源真错（仅阶段 4）**：资源真实性继续由 policy 守卫 + build 时 aapt 兜底，B-2 只放行类型检查，不改资源权威。
- **R5 修复打转**：`RepairLoopStallPolicy` 连续同诊断即停，回退手动。

# 验证

- **单元**：每阶段的 `*PolicyTest` / `*Test`（见各块）。AgentService 逻辑全部下沉到可测策略类。
- **集成/回放**：复用 compile-driven 文档提议的 record/replay——用 project-9（或 82/83）的真实模型输出做 fixture，跑
  plan→生成→编译→自动修，断言：(1) 自动循环把首次整树 javac 错误数收敛到 0 所需轮数；(2) 收敛消耗的云调用数；
  (3) 块 A 上线后修复产物里 `edit` 占比上升、整文件 `write` 下降。
- **成功判据**：典型 app 在 `MAX_AUTO_COMPILE_ROUNDS` 内自动编过，用户不再需要反复手点 Build→Repair；
  最终 `compileDebugJavaWithJavac`（不变的权威）在产物上通过。
