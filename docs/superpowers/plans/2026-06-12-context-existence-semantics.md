# 快照诚实性与文件存在性语义(Context Existence Semantics)

> 执行者注意:本文档自包含,不依赖任何对话上下文。按任务顺序执行,每个任务完成后跑全量测试并单独提交。
> 全量测试命令:`ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> 测试约定:final 策略类 + 静态方法 + `*PolicyTest`/同名 `*Test`(JUnit4,见 `app/src/test/java/com/androidbuilder/`)。

## 背景与证据

项目 5(记账 app)的执行日志暴露了一类死锁:云端模型在"Java source wiring"任务上连续两轮返回 blocked,理由都是"前置文件在快照中不可见"——但其中 `DBContract.java`、`domain/model/*.java`、六个 DAO **根本不存在**(之前的草稿被守卫拒绝,从未合并),创建它们正是该任务的工作。模型分不清"没给我看"和"根本不存在",而系统自己的三处缺陷加剧了这个混淆:

1. **context note 漏报**(实锤的代码 bug):`AgentService.buildSourceSnapshot` 把所有非 Java 文件入队时就加进 `fullTextPaths`(见 `appendSourceFile` 中的 `seen.add(path)`),而真正的预算裁剪发生在之后的 `SourceSnapshotComposer.appendFullTextSections` 里。`omittedFilesNote` 用入队集合计算遗漏清单,于是被组装器静默裁掉的 ~20 个文件(`AndroidManifest.xml`、`app/build.gradle`、`values/*.xml`、`menu_bottom_nav.xml`、多个 `item_*.xml`)完全没有出现在"未展示"清单里。日志中备注声称"只有 6 个 Java 文件未展示全文",模型却看不到 manifest 和 gradle——快照自相矛盾,模型对它失去信任。
2. **没有存在性语义**:提示词里全是禁止性措辞("If a file was omitted, do not invent its API"、"missing → return blocked"),但没有一句告诉模型"全文层 + API 摘要 + 遗漏清单合起来是穷尽的;三处都没有的文件就是不存在,创建它是任务的一部分"。
3. **侦察轮焊死死锁**:scout 在 `neededFiles` 里请求不存在的文件,`ContextNegotiationPolicy.focusText` 把这些路径拼进 focus 文本,`appendFocusedSourceFiles` 对不存在的文件静默跳过(`appendSourceFile` 的 `!file.exists()` 直接 return)——没有任何机制告诉模型"这个文件不存在,给不了"。更糟的是 scout 自己写的 patchIntent("暂不编写任何新的 Java 源文件")被原样注入为 "Negotiated patch intent",生成步骤把它当军规,最终 blocked 理由明确写着 "which the negotiated intent forbids"。

两个助推因素:

4. **守卫箭头检查误伤注释/字符串**:`AndroidSourceGuard.java:248` 用 `content.contains("->")` 在原始内容上扫描,Javadoc/注释/日志字符串里的 `->`(如"分 -> 元")也会触发 "blocked Java lambda syntax"。日志中的草稿修正记录("remove every arrow token from comments, Javadoc and log strings")证明这正是 DBHelper 草稿反复被拒、数据层至今没合并进树的起点。同理,注释里出现的 `R.id.xxx` 也会被资源引用检查误伤。
5. **GridLayout 依赖洞**:早前的 XML 任务写 `activity_pin_lock.xml` 时用了 `androidx.gridlayout.widget.GridLayout`,但没人在 `app/build.gradle` 声明 `androidx.gridlayout:gridlayout`。后续 Java 任务的 allowedPaths 不含 gradle、又看不到 gradle 全文,只能 blocked。布局里的自定义控件类引用没有任何确定性检查。

## 不变量(执行中不得破坏)

- 守卫零旁路:`AndroidSourceGuard` / `DependencyGuard` 仍是最终权威,只允许提升精度(减少误伤),不允许放宽拦截真问题的能力。
- 单违规消息逐字兼容:`AndroidSourceGuard.throwIfViolations` 在单违规时抛 `violations.get(0)` 原文,现有 `assertEquals` 精确断言测试不得改动断言文本。
- 快照预算不变量:总预算 `SOURCE_SNAPSHOT_LIMIT=24000`;资源索引的 id/layout/string 段永不截断(`ResourceIndexDigest.isCriticalSection` + `SourceSnapshotComposer` 尾层优先预算);本计划新增的 note 预留不得让资源索引重新暴露在尾部裁剪之下。
- 现有公共行为:`SourceSnapshotComposer.assemble(...)` 的现有签名与测试(`SourceSnapshotComposerTest`)保持通过;`sourceSnapshotForTest` 继续可用。

---

## 任务 1:快照覆盖清单 —— composer 报告真实纳入结果,note 改为组装后生成

**问题**:`omittedFilesNote` 基于入队集合而非实际输出,漏报被预算裁掉的文件。

**文件**:
- `app/src/main/java/com/androidbuilder/agent/SourceSnapshotComposer.java`
- `app/src/main/java/com/androidbuilder/agent/AgentService.java`(`buildSourceSnapshot`、`omittedFilesNote` 一带,约 L2049–L2170)
- 测试:`SourceSnapshotComposerTest`、`SourceSnapshotRelevanceTest`

**改动**:

1. `SourceSnapshotComposer` 新增组装结果类型与两段式 API(保留现有 `assemble` 委托实现以兼容旧测试):

```java
static final class Composition {
    final String text;                       // 不含 context note 的快照
    final List<String> fullyIncludedPaths;   // 全文完整纳入的 path,按输出顺序
    final String partiallyIncludedPath;      // 被截断在中途的那一个 path,无则 null
}

static Composition compose(List<TextSection> fullTextSections,
                           String javaApiDigest, String resourceIndex,
                           int fullTextLimit, int budget)   // budget = 总预算减去 note 预留

static String appendContextNote(String composedText, String contextNote, int totalLimit)
// 追加 "--- context note ---" 段;若超出 totalLimit,只裁剪 note 本身,绝不裁 composedText
```

`compose` 复用现有的尾层优先逻辑(先量 API 摘要 + 资源索引,全文层吃剩余预算)。`appendFullTextSections` 改为逐段记录:完整写入的 path 进 `fullyIncludedPaths`;某段被 `trimToLimit` 截断时记入 `partiallyIncludedPath` 并停止;之后的段一律视为丢弃(由调用方用"入队集合 − 已纳入"推导)。

2. `AgentService` 新增常量 `SOURCE_CONTEXT_NOTE_RESERVE = 2500`,`buildSourceSnapshot` 改为:

```java
SourceSnapshotComposer.Composition composition = SourceSnapshotComposer.compose(
        fullTextSections, javaApiDigest, resourceIndex,
        SOURCE_FULL_TEXT_LAYER_LIMIT,
        SOURCE_SNAPSHOT_LIMIT - SOURCE_CONTEXT_NOTE_RESERVE);
String note = snapshotCoverageNote(sourceDir, candidates, fullTextPaths, composition);
return SourceSnapshotComposer.appendContextNote(composition.text, note, SOURCE_SNAPSHOT_LIMIT);
```

3. 用 `snapshotCoverageNote` 取代 `omittedFilesNote`(可删除旧方法),输出四类清单(每类最多列 40 条,超出注明 "+N more"):
   - **Truncated mid-file**:`composition.partiallyIncludedPath`(若有),注明 "treat the unseen remainder as unknown"。
   - **Shown only in the Java API digest**:`.java` 候选文件中未完整纳入全文的(即现有 omitted 逻辑覆盖的那部分)。
   - **Not shown at all (budget)**:入队的非 Java 文件(`fullTextPaths` 中)减去 `fullyIncludedPaths`、减去 partial——这是本次修复的核心新增类别。
   - 收尾加存在性语义句(英文,与现有提示词一致):

```
This inventory is COMPLETE. Every existing project file appears above in exactly one
category (full text, truncated, API digest, or not-shown), and every existing XML
resource name appears in the resource index. A file path that appears in NONE of
these lists does not exist in the project yet - if your task requires it, CREATE it;
that is expected work, not invention.
```

**测试**:
- `SourceSnapshotComposerTest`:`compose` 正确报告 fullyIncluded / partial;`appendContextNote` 超预算时只裁 note,composedText 完整保留;现有三个 `assemble` 测试不动、继续通过。
- `SourceSnapshotRelevanceTest` 新增:构造超出全文预算的项目(若干 Java 聚焦文件 + 大量 XML),断言 note 中 "Not shown at all" 列出了被裁的 XML 文件名、"This inventory is COMPLETE" 句存在、且总长 ≤ 24000。

---

## 任务 2:存在性语义写进所有提示词,patchIntent 降级为建议

**文件**:
- `app/src/main/java/com/androidbuilder/agent/SourceSnapshotComposer.java`(`RESOURCE_INDEX_RULE`)
- `app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`(`retryContext`)
- `app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`(文件操作生成的 system prompt,现有真值表规则在 ~L803;侦察 `contextNegotiationSystemPrompt`)
- 测试:`OpenAiClientTest`、`ContextNegotiationPolicyTest`(如无则新建)

**改动**:

1. `RESOURCE_INDEX_RULE` 末尾追加正向条款(资源索引不只用来拦,也用来放行):

```
 Conversely, every name listed here EXISTS - you may reference it from Java
 without seeing the XML body.
```

2. `ContextNegotiationPolicy.retryContext` 的固定段落改写。将第五行
   `"If a file was omitted, do not invent its API; rely only on shown files and the negotiated focus context.\n"`
   替换为:

```
If a file is listed as omitted or digest-only, do not invent its API beyond what the digest shows.
If a file appears in NO part of the snapshot inventory, it does not exist yet; creating it is part
of the task when the task requires it - never return blocked because a nonexistent file is "missing".
```

3. 同函数中,旧失败摘要标题改为(防止上一轮的可见性论断锚定本轮判断):

```
Previous failure summary (the snapshot has changed since this failure; re-verify every
visibility claim against the current snapshot and resource index before acting on it):
```

4. patchIntent 标题降级:

```
Negotiated patch intent (advisory; it cannot forbid creating files that do not exist
or work the task instruction requires):
```

5. `OpenAiClient` 文件操作生成 system prompt(真值表规则同一处)追加一句:

```
The snapshot inventory (full text + API digest + coverage note) is complete: a Java file
absent from all of them does not exist yet, and creating it is part of your task when needed.
```

**测试**:断言 `retryContext` 输出包含新句式、advisory 标题;`OpenAiClientTest` 中对应 prompt 文本断言更新(找到现有对 prompt 文案做 contains 断言的测试,保持同样模式)。

---

## 任务 3:neededFiles 存在性裁决

**问题**:scout 请求不存在的文件时被静默忽略,模型永远等不到也永远不知道等不到。

**文件**:
- `app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`
- `app/src/main/java/com/androidbuilder/agent/AgentService.java`(调用 `retryContext` 的位置,用 `grep -n "retryContext" ` 定位)
- `app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`(`contextNegotiationSystemPrompt`)
- 测试:`ContextNegotiationPolicyTest`

**改动**:

1. `retryContext` 增加三参重载 `retryContext(String previousFailure, ContextNegotiation result, List<String> missingNeededFiles)`(二参重载委托传 null,保兼容)。当 missing 列表非空时,在 patch intent 段之前追加:

```
File existence verdict for the files you requested:
- <path>: does NOT exist in the project. It cannot be shown; create it yourself if the task requires it.
```

2. `AgentService` 在侦察轮结束后计算 `missing`:对 `result.neededFiles` 中每个 path 检查 `new File(sourceDir, path).exists()`,不存在的收集传入三参 `retryContext`。

3. scout 系统提示(`contextNegotiationSystemPrompt`)追加规则:

```
Request a file in neededFiles only if it plausibly already exists. The snapshot inventory is
complete - a Java file absent from full text and the API digest does not exist. Never set
ready=false solely because files that do not exist yet are missing; state in patchIntent that
you will create them.
```

**测试**:三参 `retryContext` 在有 missing 时输出裁决段、为空时不输出;二参重载行为不变。

---

## 任务 4:守卫剥离注释/字符串后再扫描

**问题**:`AndroidSourceGuard.validateSourceFile` 在原始内容上做 `->`、`R.*`、synthetic 等文本扫描,注释/Javadoc/字符串字面量里的内容被误伤(实际案例:DBHelper 的 Javadoc 含 `->` 被反复拒绝)。

**文件**:
- `app/src/main/java/com/androidbuilder/agent/JavaApiDigest.java`(`stripJavaCommentsAndStrings` 从 `private` 改为包内可见 `static`)
- `app/src/main/java/com/androidbuilder/agent/AndroidSourceGuard.java`(`validateSourceFile`,约 L223–L250)
- 测试:`AndroidSourceGuardTest`

**改动**:`validateSourceFile` 开头计算一次 `String scannable = JavaApiDigest.stripJavaCommentsAndStrings(content);`(该方法保留换行结构,行级逻辑不受影响),以下检查全部改用 `scannable`:

- `kotlinx.android.synthetic` import 检查
- DataBinding/ViewBinding import 正则
- Fragment findViewById 检查
- 全部 7 个 `rejectMissingResource`(R.id/layout/string/color/drawable/mipmap/style)
- `->` lambda 检查(L248)
- synthetic view access 扫描

**不要动**:类结构解析(`JavaApiSymbols`/`ClassSpan` 字段、构造器、方法一致性检查)维持在原始内容上,本任务不碰。

**测试**(新增,沿用现有测试的项目构造方式):
- Javadoc / 行注释 / 字符串字面量中含 `->` → 通过(不再误伤);
- 真实 lambda(`v -> doSomething()`)→ 仍被拦,消息逐字不变;
- 行注释中含 `R.id.not_exist` → 通过;真实代码引用缺失 id → 仍被拦,消息逐字不变。

---

## 任务 5:AndroidManifest.xml 与 app/build.gradle 钉死在全文层

**问题**:两个体积小、影响全局的文件按相关性排序(manifest=2、gradle=4)排在所有 Java/layout 之后,大项目里必然被预算裁掉,模型无法核对组件注册与依赖声明。

**文件**:`AgentService.buildSourceSnapshot`;测试 `SourceSnapshotRelevanceTest`。

**改动**:在 `appendFocusedSourceFiles(...)` 之后、相关性遍历之前,无条件全文入队(`full=true` 跳过预览截断;`seen` 去重保证不会重复):

```java
appendSourceFile(sourceDir, new File(sourceDir, "app/src/main/AndroidManifest.xml"), fullTextSections, fullTextPaths, true);
appendSourceFile(sourceDir, new File(sourceDir, "app/build.gradle"), fullTextSections, fullTextPaths, true);
```

**测试**:构造含大量 layout 的项目使预算紧张,断言快照中 manifest 与 app/build.gradle 全文存在(出现在被裁的 layout 之前)。

---

## 任务 6:布局自定义控件的依赖核对(堵 GridLayout 类的洞)

**问题**:XML 任务可以写出引用 `androidx.gridlayout.widget.GridLayout` 的布局而无人核对 gradle 依赖,洞会延迟到构建期甚至永远卡死后续任务。

**文件**:
- 新建 `app/src/main/java/com/androidbuilder/agent/WidgetDependencyPolicy.java` + `WidgetDependencyPolicyTest`
- `app/src/main/java/com/androidbuilder/agent/AndroidSourceGuard.java`(`validate` 读 gradle、`validateXmlFile` 接线)
- `AndroidSourceGuardTest`

**改动**:

1. 新策略类(final + 私有构造 + 静态方法):

```java
final class WidgetDependencyPolicy {
    // 只收录"绝不会由 appcompat/material 传递引入"的坐标,避免误报:
    // "androidx.gridlayout."            -> "androidx.gridlayout:gridlayout"
    // "com.github.mikephil.charting."   -> "com.github.PhilJay:MPAndroidChart"
    // "androidx.swiperefreshlayout."    -> "androidx.swiperefreshlayout:swiperefreshlayout"
    // "androidx.constraintlayout."      -> "androidx.constraintlayout:constraintlayout"
    static String requiredCoordinate(String widgetFqcn);          // 无匹配返回 null
    static List<String> missingWidgetDependencies(String xmlContent, String gradleText);
}
```

   - 控件提取:正则匹配 XML 开标签 `<\s*([a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*)` 以及 `<view ... class="fqcn"`。
   - 依赖判定:`gradleText.contains(coordinate)`(group:artifact 前缀匹配,忽略版本号);gradleText 为空(无 build.gradle)视为全部缺失。

2. `AndroidSourceGuard.validate` 在遍历前读一次 `app/build.gradle` 文本(不存在则空串),传入 `validateXmlFile`;对每个布局文件调用 `missingWidgetDependencies`,缺失则 `addViolation`:

```
Generated source policy blocked layout widget <fqcn> in <file>: dependency <coordinate>
is not declared in app/build.gradle. Add the dependency, or use a built-in widget. If this
task cannot edit app/build.gradle, return blocked with prerequisiteWork naming the dependency.
```

   消息中最后一句把无权改 gradle 的任务显式导向 blocked → scope expansion 路径(`BlockedTaskPolicy` 已实现),避免制造新死锁。

**测试**:
- `WidgetDependencyPolicyTest`:gridlayout 控件 + 无声明 → 报缺;已声明(带版本号写法)→ 不报;`com.google.android.material.*` / `androidx.recyclerview.*` 控件 → 永不报(不在映射内);`<view class=...>` 形式被识别。
- `AndroidSourceGuardTest`:布局含 GridLayout 且 build.gradle 未声明 → 守卫违规,消息含坐标;声明后通过。

---

## 任务 7(增强):ResourceIndexDigest 按布局分组列 id

**动机**:让模型不看 `item_*.xml` 原文也能写 Adapter——平铺的 R.id 列表不回答"哪个 id 在哪个布局里"。

**文件**:`ResourceIndexDigest.java` + `ResourceIndexDigestTest`。

**改动**:收集阶段对 `layout*/` 目录下的文件额外记录"布局名 → 该文件内 id 集合";渲染阶段在三个关键段(id/layout/string)之后、其余非关键段之前,输出**非关键**段(可被 3000 预算截断,关键段优先级不受影响):

```
R.id by layout: activity_main[bottom_nav, fab_add, fragment_container, toolbar] | item_record[icon_category, text_amount, ...]
```

布局名与 id 均按字典序。`isCriticalSection` 不收录此段(它是冗余便利层,平铺 id 段才是真值表)。

**测试**:多布局项目分组正确;预算极小时关键段完整、分组段被裁;空 layout 目录不输出该段。

---

## 任务 8:全量验证

1. `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest` 全绿(当前基线 477 个测试,本计划会新增若干)。
2. 自查清单:
   - 大项目快照:note 四类清单与实际输出一致,无文件凭空消失;总长 ≤ 24000;资源索引关键段完整。
   - 单违规守卫消息与改造前逐字一致(抽查现有 `assertEquals` 测试未被修改)。
   - `assemble` 旧签名测试全部未改动且通过。

## 提交粒度

每个任务一个提交,信息格式沿用仓库惯例(`feat:`/`fix:` 前缀,英文,简洁列点)。任务 1–3 是本计划核心(快照诚实 + 存在性语义 + 侦察裁决),任务 4 是连锁失败的起点修复,5–7 是确定性补强。

## 风险与边界

- 任务 4 只迁移文本扫描类检查,结构一致性检查不动;若剥离后某现有测试失败,优先怀疑该测试依赖了"注释中的引用也被拦"的旧行为——这正是要修掉的误伤,更新测试时保持单违规消息文本不变。
- 任务 6 的映射表故意保守:宁可漏报(交给构建期分类器兜底)也不误报(误报会卡住所有后续任务)。不得把 material/appcompat 传递引入的坐标加进映射。
- 任务 1 的 `SOURCE_CONTEXT_NOTE_RESERVE=2500` 若实测偏小(40 条路径 + 语义句超出),裁剪发生在 note 内部清单上,语义句在 note 开头不受影响——保持这个顺序:先写语义句,再写清单。
