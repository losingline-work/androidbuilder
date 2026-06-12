# 增量生成管线(Incremental Generation)

> 执行者注意:本文档自包含,不依赖任何对话上下文。按阶段顺序执行,每个任务完成后跑全量测试并单独提交。
> 全量测试命令:`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> 测试约定:final 策略类 + 静态方法 + 对应 `*Test`/`*PolicyTest`(JUnit4,见 `app/src/test/java/com/androidbuilder/`)。AgentService 本身不可单测——所有可测逻辑必须压进独立策略类。

## 背景

当前一个执行任务 = 一次云端调用,模型在单个 JSON 响应里输出该任务全部文件的完整内容。实际日志:一个 "Java source wiring" 任务的响应超过 100K 字符、流式生成 10 分钟以上。这个设计有四个结构性问题:

1. **全有或全无**:任何一处守卫违规(例如一个 Javadoc 里的 `->`)导致整批 15–30 个文件报废,白等 10 分钟才知道要重试。
2. **无中途熔断**:请求不设 max_tokens,客户端对累计字符数也无上限;复读机式失控生成只能靠模型自身输出上限兜底。第 9 分钟断网 = 草稿全丢(correction 模式只在拿到完整解析草稿后才生效)。
3. **一致性漂移**:模型写第 20 个文件时会忘记自己第 3 个文件里定下的字段名/方法签名,守卫在最后才发现。
4. **上下文前置**:模型只能依赖预先注入的快照,生成中途缺信息只能 blocked 或瞎猜。

## 现状锚点(执行前先确认这些位置,行号可能漂移,用 grep 定位)

- 生成调用:`AgentService` 的任务尝试循环内,`recordCloudAiCall(..., () -> openAiClient.createTaskOperations(...), taskId)`(约 L1204;`grep -n "createTaskOperations" AgentService.java`)。随后:`TaskOperationsParser.fromJson` → blocked 检查(`BlockedTaskPolicy`)→ `TaskOperationsMergePolicy.merge/stripDrops` → `HermesTaskContractGuard.review` → `TaskOperationsPreflight.review`(约 L1262)→ 通过后落盘。
- 最终守卫:`FileOperationsWriter`(L19 持有 `new AndroidSourceGuard()`)在 `apply(File sourceDir, TaskOperations)` 应用操作时验证整树。**本计划不改变这一最终时序——守卫仍是落盘前的最终权威。**
- 流式读取:`OpenAiClient.readChatContent(BufferedReader, ProgressListener, String callTag)`(约 L311),逐 SSE 块累积 `answer`,每 `PROGRESS_EMIT_CHARS` 通过 `ProgressListener.onProgress(callTag, answerChars, reasoningChars)`(L33)上报。无字符上限。另有 3 分钟无活动看门狗(180000ms inactivity)。
- 宽松解析:`TaskOperationsParser.operationObjectsFromMalformedArray(String)`(约 L105)+ `objectEnd(String, int)`(约 L133)已能从截断/畸形 JSON 中恢复**完整的**操作对象——增量解析直接复用这套逻辑。
- 操作上限:`TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK = 60`。
- 结构性错误分类:`DraftCorrectionPolicy.isStructuralError` / `errorSignature`(决定重试走全量还是修正)。
- 草稿持久化:`TaskDraftStore`(跨 dispatch);修正合并:`TaskOperationsMergePolicy`(path 键控)。
- 任务契约:`HermesTaskContractCodec.extractFromInstruction(instruction)` → `HermesTaskContract`(含 allowedPaths)。
- UI 进度:`AgentService.updateStreamPhase(String callTag, String phase, int attempt)`(约 L1071)。

## 不变量(执行中不得破坏)

- **守卫零旁路**:`AndroidSourceGuard`/`DependencyGuard` 仍是落盘前最终权威;本计划所有"提前检查"只允许比最终守卫**更早失败**,不允许放行最终守卫会拒绝的内容。
- 单违规守卫消息逐字兼容(现有 `assertEquals` 精确断言不得改)。
- `FileOperationsWriter.apply` 的对外语义不变(阶段 3 仅新增 action)。
- blocked / scope-expansion / correction / 草稿持久化 / SequentialFailureGate / dispatch 预算等现有机制全部保留并兼容。
- 现有公开方法签名保留(新增重载,不改旧签名)。

---

# 阶段 0:路径规范化与跨任务资源顺序(真实失败的直接修复,最优先)

> 依据:项目 6 任务 #30 的两连败日志。模型把全部资源写到 `res/...`(缺 `app/src/main/` 前缀),
> `FileOperationsWriter.applyToDirectory` 原样落盘(只查目录逃逸),而 `AndroidSourceGuard` 的资源
> 符号收集硬编码 `app/src/main/res`(validate 内 L42 附近)——错位文件被校验、其定义的资源却进不了
> 符号表,导致"模型自己写的资源自己引用不到"。另一次草稿同时写了 `res/values/colors.xml` 与
> `app/src/main/res/values/colors.xml` 两套路径(约 140 操作),被 60 上限裁剪后规范路径的副本丢失。

## 任务 0.1:操作路径规范化(CanonicalPathPolicy)

**文件**:新建 `CanonicalPathPolicy.java` + `CanonicalPathPolicyTest`;`AgentService.java`(解析后立即归一);`FileOperationsWriter.java`(最后防线)。

**改动**:

1. 新策略类(final + 静态):`static String canonicalize(String path)` 与 `static TaskOperations canonicalizeAll(TaskOperations ops)`。归一规则(按序匹配,均为前缀判断,大小写敏感):
   - 已是 `app/src/main/...`、`app/build.gradle`、根级 `build.gradle`/`settings.gradle`/`gradle.properties`/`gradle/...`/`proguard-rules.pro` → 原样保留;
   - `src/main/...` → `app/src/main/...`;
   - `app/res/...` → `app/src/main/res/...`;`app/java/...` → `app/src/main/java/...`;`app/AndroidManifest.xml` → `app/src/main/AndroidManifest.xml`;
   - `res/...` / `java/...` / `assets/...` / `AndroidManifest.xml` → 前面补 `app/src/main/`;
   - 其余路径原样保留(不过度猜测)。
2. `canonicalizeAll` 归一后**按 path 去重**:同 path 多个操作保留**最后一个**(模型后写的覆盖先写的),并在 summary 不变的前提下保持其余操作顺序。
3. 接线(两道防线):
   - `AgentService` 在 `TaskOperationsParser.fromJson` 返回后、blocked 检查之前调用 `canonicalizeAll`(这样合并、契约、预检、守卫全部只见规范路径);
   - `FileOperationsWriter.applyToDirectory` 写盘前对单个 path 调 `canonicalize`,若结果与传入不同则抛 `IllegalArgumentException("Operation path is not in canonical Android layout: <path>; use app/src/main/...")`——上游漏网时确定性失败而非写进死地。
4. 阶段 1 的 `TaskStreamPreflight` 与阶段 2 的清单校验(任务 2.1)各加一条:路径经 `canonicalize` 后改变 → 不致命(可自动修复),但**清单中归一后冲突重复**(两个不同原始路径归一为同一 path)→ 记入日志;流式阶段不为此中止。

**测试**:用本次日志的真实样本——`res/drawable/ic_add.xml`、`res/values/colors.xml`、`AndroidManifest.xml`、`res/mipmap-anydpi-v26/ic_launcher.xml`、`app/src/main/res/layout/activity_main.xml`(保持)、`app/build.gradle`(保持)、`settings.gradle`(保持);去重保留后者;writer 防线对未归一路径抛错。

## 任务 0.2:任务拆分的资源先行顺序

**文件**:`OpenAiClient.java`(任务拆分 prompt,`grep -n "Keep Gradle/build configuration" OpenAiClient.java` 定位,约 L736);`ImplementationTaskNormalizer.java`(若有模板逻辑则同步);测试更新对应 prompt 断言。

**改动**:拆分 prompt 追加硬规则:

```
If you split resources across tasks, the task that writes res/values (colors, strings, dimens,
styles) and AndroidManifest.xml and build.gradle dependencies MUST be the first implementation
task, and every later XML or Java task MUST declare dependsOn on it. A task that writes layout
or drawable XML may ALSO add missing res/values entries it references - never forbid a task
from satisfying its own resource references.
```

并检查现有任务指令模板:任何生成"Do not write values XML"类措辞的地方(若来自模型自由发挥则只能靠上面的规则约束;若来自我方模板则直接删除该禁令)。

**测试**:拆分 prompt 文案断言;若 `ImplementationTaskNormalizer` 有可单测的排序/依赖逻辑则补用例。

---

# 阶段 1:流式熔断 + 增量预检(止血,先行)

## 任务 1.1:流式检查回调与字符熔断

**文件**:`OpenAiClient.java`;新建 `StreamFusePolicy.java` + `StreamFusePolicyTest`。

**改动**:

1. `OpenAiClient` 新增回调接口与异常:

```java
public interface StreamInspector {
    /** 在累计内容增长时调用(节流后)。抛 StreamAbortException 则中止流。 */
    void onContent(String answerSoFar) throws StreamAbortException;
}
public static final class StreamAbortException extends Exception {
    public StreamAbortException(String message) { super(message); }
}
```

2. `readChatContent` 增加带 `StreamInspector` 的重载(旧签名委托传 null)。在现有 `listener.onProgress` 同一节流点(每 `PROGRESS_EMIT_CHARS` 增量)调用 `inspector.onContent(answer.toString())`;捕获 `StreamAbortException` 时关闭连接并把它原样抛出(不要包装丢失消息)。`createTaskOperations` 系列增加带 inspector 的新重载,层层透传。

3. 新 `StreamFusePolicy`(final + 静态):

```java
static final int MAX_STREAM_CHARS = 200_000;
static String fuseError(int chars);   // "Streaming response exceeded 200000 chars; generation aborted as runaway."
static boolean exceeds(int chars);
```

4. `DraftCorrectionPolicy.isStructuralError` 增加对 `"streaming response exceeded"` 前缀的识别(签名归一化后),使熔断走全量重试而非修正。

**决策记录(写进代码注释)**:刻意**不设** max_tokens——服务端截断无法附带我们的分类消息,且会把合法的长输出腰斩成畸形 JSON;客户端熔断可控、可分类、可测。

**测试**:`readChatContentForTest` 风格的 SSE 流中,inspector 在阈值处收到回调并中止;`StreamAbortException` 消息原样上抛;无 inspector 时行为与现状逐字节一致。

## 任务 1.2:增量操作提取与流式安全预检

**文件**:`TaskOperationsParser.java`;新建 `TaskStreamPreflight.java` + `TaskStreamPreflightTest`;`AgentService.java` 接线。

**改动**:

1. `TaskOperationsParser` 新增公开静态方法:

```java
/** 从可能未接收完的响应中提取所有"确定完整"的操作对象(复用 operationObjectsFromMalformedArray/objectEnd)。
 *  最后一个未闭合的对象绝不返回——半截 content 进预检会产生误报。 */
public static List<FileOperation> completedOperations(String partialRaw)
```

实现要点:在 `extractJson` 提取的文本上扫描操作对象,仅收集 `objectEnd` 成功闭合的;任何异常返回已收集部分,绝不抛出。

2. 新 `TaskStreamPreflight`(final + 静态),输入为累计文本 + `HermesTaskContract`(可为 null),输出 `String fatalError`(null = 继续):

**流式安全(可立即致命)的检查——后续到达的操作救不了这些错**:
- 操作 path 以 `.kt` 结尾;
- 操作 path 在契约 allowedPaths 之外(复用 `HermesTaskContractGuard` 的路径匹配逻辑,抽公共方法,不复制粘贴);
- Java 操作 content 含 lambda 箭头 / `kotlinx.android.synthetic` / DataBinding import——**必须先 `JavaApiDigest.stripJavaCommentsAndStrings` 再扫**,与 `AndroidSourceGuard.validateSourceFile` 的现行做法一致;
- 完整 XML 操作 content 非良构(复用 `TaskOperationsPreflight` 现有 XML 检查逻辑,同样抽公共而非复制);
- 已提取操作数 > `TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK`。

**明确禁止在流式阶段检查(写进类注释)**:R.* 资源存在性、跨文件构造器/字段/方法一致性——同一响应里后续的 XML/Java 操作可能正是它们的定义;这些仍由批级(阶段 2)与最终守卫负责。

3. `AgentService` 在 `createTaskOperations` 调用处构造 inspector:

```java
String[] lastParsedLength = ...; // 节流:距上次解析增长 ≥ 8000 字符才重新提取
inspector = answerSoFar -> {
    if (StreamFusePolicy.exceeds(answerSoFar.length())) throw new StreamAbortException(StreamFusePolicy.fuseError(answerSoFar.length()));
    /* 节流后: */
    String fatal = TaskStreamPreflight.review(TaskOperationsParser.completedOperations(answerSoFar), contract);
    if (fatal != null) throw new StreamAbortException(fatal);
};
```

4. **中止后的草稿挽救**:捕获 `StreamAbortException` 时,把 `completedOperations(已收文本)` 包装成 `TaskOperations`(blocked=false,summary="partial draft salvaged from aborted stream")存入 `previousDraft` 与 `TaskDraftStore`,`previousFailure` = 中止消息,然后走现有重试路径(预检类错误天然命中 policy-rewrite/correction 流程)。这样 10 分钟的失败也能留下可修正的部分草稿。

**测试**:部分 JSON 提取只含完整对象;每类致命检查的命中与不命中;资源缺失类内容**不**触发中止;节流逻辑;草稿挽救的包装语义。

---

# 阶段 2:清单先行 + 分批生成(核心)

## 设计总览

首轮生成(无既有草稿时)从"一次生成全部文件"改为两步协议:

```
调用 A(清单,约几百字):模型列出本任务将写的文件(path + action + 一句意图)
调用 B×N(批次):按清单分批(默认 4 个文件/批,资源 XML 批先于 Java 批),
                  每批生成完整文件内容 → 内存预检 → 累积
全部批完成 → 组合成一个 TaskOperations → 走现有合并/契约/预检/落盘/守卫路径(完全不变)
```

关键简化(必须遵守):
- **落盘与最终守卫的时序零改动**——批级一切检查都在内存中,`FileOperationsWriter.apply` 仍然只被调用一次。
- **分批只用于无草稿的首轮**。重试 dispatch 已有完整草稿时,走现有 correction 单发路径(草稿已含全部文件,修正目标明确,再分批反而浪费)。判定条件:`previousDraft == null && !correctionMode`。
- **资源先行批序**使 Java 批可以做 R.* 检查:验证 Java 批时,资源符号 = 现有树符号 + 本任务已接受 XML 批中新定义的符号。

## 任务 2.1:清单调用与解析

**文件**:`OpenAiClient.java`;新建 `TaskManifestParser.java` + 测试;新建 `app/src/main/java/com/androidbuilder/model/TaskManifest.java`。

**改动**:

1. `OpenAiClient.createTaskManifest(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, chinese, callTag)`:system prompt 要求只返回紧凑 JSON:

```json
{"summary": "...", "blocked": false, "blockedReason": "", "prerequisiteWork": "",
 "files": [{"path": "app/src/main/java/...", "action": "write", "intent": "one line"}]}
```

   - prompt 规则:列出**全部**要写/删的文件;intent 一句话(目的 + 关键 API 决策,如"DBHelper extends SQLiteOpenHelper, exposes getReadable/WritableDatabase");禁止在此返回文件内容;blocked 语义与现有任务生成一致(沿用资源索引/存在性语义那套措辞,从现有 taskOperations system prompt 复制共享段落时抽成私有方法复用)。
2. `TaskManifestParser.fromJson`:宽松解析(参考 `TaskOperationsParser` 容错风格);blocked 字段映射到与 `BlockedTaskPolicy` 兼容的结构(直接产出一个 blocked 的 `TaskOperations` 即可复用现有扩域路径)。
3. 清单校验(解析器内或新 `ManifestPreflight`):文件数 ∈ [1, 60];path 非空且相对路径;action ∈ write/delete;path 重复去重。违规 → 抛带消息的 `IllegalArgumentException`(走现有重试)。

**测试**:正常解析、blocked 透传、超限/空清单拒绝、畸形 JSON 容错。

## 任务 2.2:批次规划策略

**文件**:新建 `ManifestBatchPolicy.java` + `ManifestBatchPolicyTest`。

**改动**(final + 静态,纯函数):

```java
static final int BATCH_SIZE = 4;
static final int SINGLE_BATCH_THRESHOLD = 6;   // 清单 ≤6 个文件时一批发完
static List<List<TaskManifest.Entry>> batches(List<TaskManifest.Entry> files)
```

排序规则(批内保持清单原序,批间按类别):
1. `res/values/` XML(字符串/颜色/尺寸——一切的地基)
2. 其余 `res/` XML(drawable/menu/layout)
3. `AndroidManifest.xml`、`*.gradle`、其他配置
4. Java(`.java`)

delete 操作归入对应类别。**理由写进注释**:资源先行使后续 Java 批验证时新资源符号已可见(任务 2.3 的 overlay)。

**测试**:混合清单的类别排序与分批;≤6 单批;边界(恰好 4/5/6/7)。

## 任务 2.3:批级验证与资源符号叠加

**文件**:新建 `BatchValidationPolicy.java` + 测试;新建 `ResourceSymbolsOverlay.java` + 测试。

**改动**:

1. `ResourceSymbolsOverlay`:从已接受的 XML 操作 content 中提取新定义的资源名(android:id、layout 文件名由 path 推导、values 中的 string/color/dimen/style name——提取正则直接复用 `ResourceIndexDigest` 的 `XML_ID`/`NAMED_VALUE_RESOURCE`,把它们的可见性从 private 提为包内 static,不复制)。输出为各类型的 `Set<String>` 增量。
2. `BatchValidationPolicy.review(List<FileOperation> batchOps, List<String> manifestPathsForBatch, HermesTaskContract contract, ResourceSymbolsOverlay acceptedSoFar, File sourceDir)` 返回 `String error`(null = 通过):
   - 批响应中出现**不在本批清单内**的 path → 拒绝(消息:"batch contained unplanned file X; regenerate only the requested files")。模型"顺手"多写文件是已知行为,必须确定性拦截;
   - 本批清单中的 path 缺失 → 拒绝(列出缺失);
   - 复用阶段 1 的 `TaskStreamPreflight` 全部结构检查;
   - Java 批专属:R.id/R.layout/R.string/R.color/R.drawable/R.mipmap/R.style 引用必须存在于(现有树符号 ∪ overlay)——现有树符号提取直接复用 `AndroidSourceGuard` 的符号收集(把 `collectXmlIds`/values 收集抽成可复用的包内静态,或新建轻量 `ResourceSymbolsSnapshot.collect(File sourceDir)`;**不得复制粘贴守卫逻辑**,守卫消息格式也不要复用,批级用自己的消息前缀 "Batch validation: " 避免与守卫单违规消息混淆)。
3. 批级检查**宁缺毋滥**:跨文件 Java API 一致性(构造器/字段)不在批级做(模型可能在后续 Java 批补类),仍由最终守卫负责。注释写明。

**测试**:计划外文件拒绝;缺文件拒绝;Java 批引用前批 XML 新 id → 通过;引用任何地方都没有的 id → 拒绝;overlay 提取正确性。

## 任务 2.4:分批生成循环接线

**文件**:`OpenAiClient.java`(批生成调用)、`AgentService.java`(任务尝试循环改造)。

**改动**:

1. `OpenAiClient.createTaskOperationsBatch(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext, batchFileList, completedFilesContext, chinese, callTag)`:prompt = 现有 taskOperations prompt 的共享段落 + "Generate the COMPLETE content for exactly these files (path + intent): ..." + "Files you already wrote earlier in this task (authoritative, keep new code consistent with them): <completedFilesContext>"。响应格式与现有操作 JSON 相同(只是文件数少)。透传阶段 1 的 inspector(熔断与流式预检对每批同样生效;批响应小,200K 熔断几乎不会触发,保留作保底)。
2. `completedFilesContext` 构建规则(新 `CompletedBatchContextPolicy` + 测试):已接受文件按批序拼接 `--- path ---\n<content>`;总长 > 20000 时,最老的 Java 文件降级为 `JavaApiDigest.digestSource` 摘要,XML 降级为只列 path + overlay 符号;再超则只保留摘要。**本任务自己的产出永远优先于通用快照**——它们是一致性漂移的解药。
3. `AgentService` 任务尝试循环:在 `previousDraft == null && !correctionMode` 分支替换单发调用:

```
manifestJson = recordCloudAiCall(..., "任务文件清单", () -> openAiClient.createTaskManifest(...))
manifest 解析;blocked → 复用现有 BlockedTaskPolicy 分支(把 blocked TaskOperations 直接交给现有代码路径)
batches = ManifestBatchPolicy.batches(manifest.files)
acceptedOps = []; overlay = empty
for (batch : batches):
    batchAttempt 循环(每批预算 2 次):
        batchJson = recordCloudAiCall(..., "批次 i/N", () -> createTaskOperationsBatch(...))
        ops = TaskOperationsParser.fromJson(batchJson).operations
        error = BatchValidationPolicy.review(ops, batchPaths, contract, overlay, sourceDir)
        error == null → acceptedOps += ops; overlay.absorb(本批 XML); 进入下一批
        error != null → batchRetryContext = error;重试本批;两次失败 → 整任务失败,
                         previousFailure = error,acceptedOps 存 TaskDraftStore(部分草稿,下个 dispatch 走 correction)
operations = TaskOperations(acceptedOps, summary=manifest.summary)
→ 从这里起完全回到现有路径:TaskOperationsMergePolicy.stripDrops → 契约守卫 → TaskOperationsPreflight → 落盘守卫
```

4. 遥测与 UI:每批一条 AI 调用记录(`recordCloudAiCall` 天然支持);`updateStreamPhase(callTag, "coding", attempt)` 的 callTag 拼上 `批次 i/N`,让现有流式进度卡显示批次进度;日志行 "任务清单:N 个文件,分 M 批"。
5. 批失败计数与 `FailureStreak`/`DraftCorrectionPolicy.errorSignature` 打通:批级错误消息进入 `draftFailureStreak.remember`,防止同错误无限循环(现有保险丝直接生效)。

**测试**:循环逻辑在 AgentService 内不可单测——把批预算判定、批重试上下文构造、"部分草稿入库时机"等决策抽进 `ManifestBatchPolicy`/`BatchValidationPolicy` 的静态方法并单测;接线代码保持薄。

## 任务 2.5:开关与回退

**文件**:`AgentService.java`(读取 SharedPreferences)、设置界面对应项(找到现有"思考模式开关"的实现位置,同样式添加)。

**改动**:`cloud_api` prefs 新增 `batched_generation`(默认 `true`)。关闭时完全走原单发路径(代码路径保留,不删除)。设置界面加开关,文案:"分批生成(清单先行,降低大任务整批报废风险)"。

**测试**:无(UI + prefs 读取);确认默认值 true 的读取逻辑有单测可测的策略时再说,这里允许直接读。

---

# 阶段 3:差量修正(correction 走 edit)

## 任务 3.1:edit 操作类型

**文件**:`app/src/main/java/com/androidbuilder/model/FileOperation.java`(或操作模型所在处,以 `grep -rn "class FileOperation" app/src/main/java` 定位)、`TaskOperationsParser.java`;新建 `EditOperationPolicy.java` + 测试。

**改动**:

1. `FileOperation` 增加可空字段 `find`/`replace`;action 取值新增 `"edit"`。解析器读取这两个字段;`action=edit` 时 `content` 允许为空。
2. `EditOperationPolicy.apply(String existingContent, String find, String replace)` 返回新内容或抛 `IllegalArgumentException`,消息必须可指导模型:
   - find 为空 → "edit operation has empty find text in <path>; resend the full file with action write"
   - 匹配 0 次 → "edit target not found in <path> (the file may have changed); resend the full file with action write"
   - 匹配 >1 次 → "edit target is ambiguous in <path> (N matches); include more surrounding context in find, or resend the full file"

精确字符串匹配(不做正则、不做空白归一化)——确定性优先,匹配不上就降级全量,绝不模糊应用。

**测试**:三种失败消息、成功替换、多行 find、find 含特殊字符不被正则化。

## 任务 3.2:correction 合并时物化 edit

**文件**:`TaskOperationsMergePolicy.java` + 测试。

**改动**:`merge(previousDraft, corrections)` 遇到 `action=edit` 的修正操作时:在 previousDraft 中找同 path 的 write 内容 → `EditOperationPolicy.apply` → 物化为该 path 的新 **write** 操作进入合并结果。物化失败(找不到前稿该文件 / apply 抛错)→ 整次修正按现有"结构性错误"路径处理:把 apply 的异常消息作为该次合并的失败抛出,走全量重试(`DraftCorrectionPolicy.isStructuralError` 增加 "edit target" / "edit operation" 前缀识别)。

**关键不变量**:草稿(`previousDraft`/`TaskDraftStore`)中**永远只存物化后的全量 write**,绝不存 edit——后续合并、预检、守卫、落盘全部只见 write,下游零改动。

**测试**:edit 物化成功进草稿;目标文件不在前稿 → 失败走结构性;edit + write 混合修正。

## 任务 3.3:correction prompt 更新

**文件**:`TaskDraftContextPolicy.java`(correction 段措辞)+ 测试、`OpenAiClient.java`(若 correction 指令在其 prompt 中,grep `"Mode: correction"` 与现有修正指令文案定位)。

**改动**:correction 指令追加:

```
Prefer minimal edits: for small fixes return {"action":"edit","path":...,"find":"<exact existing snippet>","replace":"<new snippet>"} instead of rewriting the whole file. The find text must match the file exactly once; include enough surrounding lines to be unique. Rewrite the full file (action write) only when changes are extensive.
```

**测试**:prompt 文案断言更新。

---

# 阶段 4:生成中途按需取文件(实验性,默认关闭)

> 此阶段独立于 1–3,可延后。所有改动必须在开关后面,默认 off;M3/DeepSeek 对 tools + 流式 + 结构化输出的组合行为未实测,风险自担。

## 任务 4.1:tool-call 循环

**文件**:`OpenAiClient.java`;新建 `ToolCallLoopPolicy.java` + 测试。

**改动**:

1. 请求体可选注入 `tools=[{type:"function", function:{name:"read_file", parameters:{path:string}}}]`。
2. `readChatContent` 解析 SSE 中的 `tool_calls` delta(id/name/arguments 分片拼装——新 `ToolCallAssembler` 静态类 + 测试,纯字符串逻辑可完全单测)。
3. 响应以 tool_calls 结束时:本地执行(见 4.2)→ 把 assistant(tool_calls) + tool(result) 消息追加进对话 → 再次请求,循环。`ToolCallLoopPolicy`:每次生成最多 6 次工具调用,超出后在 tool result 中返回 "tool budget exhausted; produce your answer with what you have"。
4. 工具执行:`read_file(path)` 读 scratch 树;存在 → 全文(上限 12000 字,超出截断并注明);不存在 → `"<path> does not exist in the project. If your task requires it, create it."`(与存在性语义一致);路径越界(`..` 或绝对路径)→ 拒绝消息。

**测试**:delta 拼装(分片 arguments)、预算耗尽、不存在/越界应答、截断。

## 任务 4.2:开关与侦察轮让位

**文件**:`AgentService.java`、设置界面。

**改动**:prefs `tool_file_access`(默认 `false`)。开启时:任务生成与批生成调用带 tools;`ContextNegotiationPolicy.shouldNegotiate` 在开关开启时返回 false(侦察轮的职责被即时取文件取代);遥测记录 toolCallCount 进 AI 调用日志。

**测试**:shouldNegotiate 的开关分支。

---

# 执行顺序与提交

0 → 1 → 2 → 3 → 4,阶段内按任务号。阶段 0 独立可发布(不依赖其余阶段),修的是已确认的线上失败。**每个任务一个提交**(`feat:`/`fix:` 前缀英文消息);阶段 1、2 完成后各跑一次全量测试并确认绿,再进入下一阶段。阶段 4 默认关闭,合入不影响线上行为。

# 验证

- 全量:`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`(基线 494 个,本计划预计 +40 以上)。
- 手工冒烟(阶段 2 后):真机/模拟器跑一个多文件任务,确认:UI 显示 "批次 i/N";一批内故意诱导违规(如清单外文件)只重试该批;最终落盘仍走一次守卫;关闭开关后回退单发路径正常。

# 风险与边界

- **增量解析的半截对象**:`completedOperations` 必须只返回 `objectEnd` 闭合成功的对象;此处出错会把半个 content 当完整文件触发误熔断——测试必须覆盖"截断在 content 字符串中间"的用例。
- **批级检查与守卫的关系**:批级是"提前失败",不是"提前放行"。任何拿不准的检查(跨文件一致性、删除操作对符号的影响)一律留给最终守卫,批级宁可漏报。overlay 只增不减(delete 不从 overlay 移除符号)——可能放过引用已删资源的 Java,由最终守卫兜底,可接受。
- **批序与依赖**:Java 批之间可能互相引用(Repository 用 DAO)。批序按清单原序保持 Java 内部顺序,且 completedFilesContext 把前批 Java 全文喂给后批;若模型前向引用了后批的类,批级不拦(见上条),守卫最终裁决。
- **修正模式优先级**:有草稿必走 correction 单发,绝不对修正分批——两套机制叠加会让失败归因变得不可调试。
- **阶段 4 的协议兼容性**:thinking 模式与 tools 同时开启在 MiniMax 上行为未知;现有 `effectiveThinking`(结构化输出强制关思考)逻辑对带 tools 的调用同样适用,保持关闭思考。
- 不要动 `bootstrap-aarch64.zip`(约 430MB)及嵌入式运行时;不要在本计划中"顺手"重构无关代码。
