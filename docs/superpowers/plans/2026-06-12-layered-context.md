# 分层上下文：突破 18K 全文快照的规模墙

> **面向 AI 代理的工作者：** 本计划自包含，可零上下文执行。每任务先写失败测试、再实现、再全量测试、再独立提交。
>
> **状态：** 提案（未实现）。
> **全量测试命令：** `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testDebugUnitTest`
> **基线：** 开工前全量测试必须为绿。

---

## 背景：真机证据（job #27 / task 658「Java source wiring」）

项目长到 ~84 个文件后，`SOURCE_SNAPSHOT_LIMIT = 18000` 的全文快照窗口（`AgentService.java` L44-46）开始**颠簸**：

- 同一派发内，Scout 的快照含 6 个 Java 工具类、**省略 74 个文件**（含全部 fragment 布局与 Manifest）；Coder attempt 1 按报错重聚焦后，快照换成 Manifest + 4 个布局（模型需要的 id 全在），但**省略了 76 个文件**（工具类又被挤出）。每轮重聚焦=换页+挤出，模型每轮总能发现某些需要的文件"不可见"。
- blocked 机制（诚实拒绝盲写）工作正常，于是模型**诚实地死锁**：最终以 `blocked: Prerequisite files are not visible...` 失败，其中抱怨的 fragment_bill.xml 在 attempt 1 明明可见——窗口已转走。
- 模型还在向系统索要 `DBContract.java` 和全部 model 类——它们只存在于**模型自己上一轮被拒的草稿**里（草稿顾问只附清单+单个 offending 文件全文，模型看不到自己写过的 DBContract → 无法自洽 → blocked）。

**结论**：加大预算治标不治本。出路是**分层上下文**——只有"正在编辑的文件"给全文，其余一切以**确定性提取、永不截断的摘要**呈现。同时落地此前确诊但未实现的**守卫批量报告**（错误串行发现是收敛的另一半瓶颈）。

### 现状锚点（已核实，2026-06-12）

- 快照构造：`AgentService.sourceSnapshot(sourceDir, focusText)` + `appendFocusedSourceFiles` + 相关性排序 + 「omitted N files」context note；预算常量 L44-46。
- `AndroidSourceGuard.validate` 仍是**首错即抛**（L202/223/226/229/240 等多处 `throw`）。
- `TaskDraftContextPolicy`：草稿清单 + 错误点名文件全文（`DRAFT_SECTION_LIMIT = 12000`）。
- `LocalGuardPromptBuilder` 已随本地守卫子系统删除——本计划的摘要提取需新建（无现成可复用）。
- 守卫批量报告与资源 id 清单此前仅有提案，**均未实现**。

---

## 任务 1：守卫批量报告（错误收敛 O(N) → O(1)）

**现状**：`AndroidSourceGuard.validate(root)` 在第一个违规处抛 `IllegalArgumentException`。N 处跨文件不一致需要 N 次重试，预算只有 5；真机日志多次出现"修一个、冒一个"的串行死亡（tab_bill → RecordAdapter.Item → Account._ID）。

- [ ] **测试先行**（`AndroidSourceGuardTest`）：构造一个含 3 处不同违规（缺 id、缺字段、lambda）的源码树，断言抛出的消息**同时包含全部 3 条**（以 `\n` 分隔，每条保留现有文案格式），且以现有前缀 `Generated source policy blocked` 开头（保持 `isRewriteablePolicyError` / `PolicyRewriteInstruction` 的 contains 匹配兼容）。
- [ ] **实现**：`validate` 内部改为**收集模式**——各检查方法把违规追加进 `List<String> violations`（替换直接 throw；上限 **10** 条，够一次修正消化），遍历完成后非空则抛单个 `IllegalArgumentException`，message = 各条按行拼接。单条违规时输出与现状逐字一致（兼容全部既有测试与提示分支）。
- [ ] **下游确认**：`PolicyRewriteInstruction.create` 是 contains 匹配，多条消息会同时命中多个提示分支——正是期望行为，无需改动；`DraftCorrectionPolicy.errorSignature` 对多行消息照常归一。
- [ ] 既有 `AndroidSourceGuardTest` 单错误用例的 message 断言保持通过。

```bash
git add -A && git commit -m "feat: report all source guard violations in one pass"
```

## 任务 2：资源清单层（永不截断的 R.* 真值表）

**现状**：模型靠读布局全文获知 id；布局被省略/截断就臆造（上一案 `R.id.tab_bill`）。

- [ ] **测试先行**（新 `ResourceIndexDigestTest`，TemporaryFolder 造树）：
  - 提取全部 `android:id="@+id/..."`（layout/menu 通吃）、layout/menu/drawable/color 文件名、values 里的 string/color/dimen/style name；
  - 输出按类型分组、字母序、去重的紧凑清单：`R.id: fab_add, fragment_container, swipe_bill, ... | R.layout: activity_main, fragment_bill, ... | R.string: app_name, ...`；
  - 空树输出空串；畸形 XML 容错跳过。
- [ ] **实现**：新建 `app/src/main/java/com/androidbuilder/agent/ResourceIndexDigest.java`：`static String digest(File sourceDir)`。纯正则/字符串提取（不解析 DOM，容错优先），结果**不参与 18K 预算截断**。
- [ ] **接线**：`sourceSnapshot` 尾部（context note 之前）追加 `--- resource index (complete, authoritative) ---\n` + digest + 一句规则：`Every R.id/R.layout/R.string/... in Java MUST appear verbatim in this index. If a needed id is missing here, it does not exist - return blocked instead of inventing it.`
- [ ] **prompt 同步**：`taskOperationsSystemPromptText` 加一句"resource index 是资源存在性的唯一真值表"。

```bash
git add -A && git commit -m "feat: append a complete resource index to source snapshots"
```

## 任务 3：Java API 摘要层（看不到全文也能对齐 API）

**现状**：被省略的 Java 文件完全不可见，模型无法对齐 `DBContract.COL_*`、model getter 等（本案 blocked 的直接诉求）。

- [ ] **测试先行**（新 `JavaApiDigestTest`）：对一个含 类声明/公共方法/静态常量/字段 的 Java 文件，摘要输出 `class DBHelper extends SQLiteOpenHelper { DBHelper(Context); void onCreate(SQLiteDatabase); static final int DB_VERSION; }` 风格的签名行（无方法体）；嵌套 static class（DBContract.Account 模式）输出内层常量；畸形文件容错输出文件名。
- [ ] **实现**：新建 `JavaApiDigest.java`：`static String digest(File javaFile)` / `static String digestTree(File sourceDir, Set<String> excludePaths, int maxChars)`。正则提取 `class/interface/enum` 声明、`public|protected` 方法签名行、`static final` 常量声明行；按文件分节，节头为相对路径。
- [ ] **接线（分层组装）**：`sourceSnapshot` 改为三层（任务 4 统一调预算）：全文层（被编辑/报错聚焦文件）→ API 摘要层（**其余全部 Java 文件**，取代"整文件省略"）→ 资源清单层。context note 的"omitted"语义改为"以下文件以 API 摘要呈现/仅在索引中"。

```bash
git add -A && git commit -m "feat: add java api digests for files outside the full-text window"
```

## 任务 4：预算重分配（分层快照组装）

- [ ] 常量调整（`AgentService`）：总预算 `SOURCE_SNAPSHOT_LIMIT` 18000 → **24000**，内部划分：全文层 ≤ 14000（聚焦文件单文件上限沿用 `SOURCE_FOCUS_FILE_LIMIT=12000`）、API 摘要层 ≤ 6000、资源清单层 ≤ 3000（超限时摘要层按相关性截断并标注，清单层只截 drawable/dimen 等低危类型，**id/layout/string 永不截**）。
- [ ] 组装顺序与去重：全文层已含的文件不再出现在摘要层；三层之间用清晰分节头。
- [ ] **测试**：组装层逻辑抽成纯函数（输入各层字符串与预算，输出最终快照）+ 单测覆盖：去重、超限截断顺序、id 永不截。
- [ ] 真机回归说明：24K prefill 增量对耗时影响 <10%（结构化关思考已落地），换来的是消灭窗口颠簸。

```bash
git add -A && git commit -m "feat: assemble layered snapshots with protected digest budgets"
```

## 任务 5：草稿顾问升级（自我一致性死锁解除）

**现状**：`TaskDraftContextPolicy` 顾问段 = 清单 + 错误点名文件**全文**。模型看不到自己草稿里其它文件（如 DBContract）的 API，无法保持草稿内引用一致，于是向系统索要自己写过的文件（本案死锁）。

- [ ] **测试先行**（`TaskDraftContextPolicyTest` 扩展）：15 文件草稿、错误点名 DBHelper → 顾问段含 DBHelper 全文 + **其余 14 个草稿文件的 API 摘要**（复用任务 3 的 `JavaApiDigest`，对草稿内容字符串提取而非文件）；预算内点名全文优先、摘要其次。
- [ ] **实现**：`correctionSection` / `advisorySection` 在清单与点名全文之后追加 `Draft API digest (your own previous work - keep consistent with it):` 段；`JavaApiDigest` 增加 `static String digestSource(String path, String content)` 供草稿字符串复用。
- [ ] 顾问段预算 `DRAFT_SECTION_LIMIT` 12000 → 14000。

```bash
git add -A && git commit -m "feat: include draft api digests in retry advisories"
```

---

## 预期效果（对照本案死法）

| 死锁环节 | 对应解 |
| --- | --- |
| 布局被窗口挤出 → 臆造/索要 id | 任务 2：id 真值表常驻，永不截断 |
| 工具类/模型类被挤出 → 无法对齐 API | 任务 3+4：全部 Java 以 API 摘要常驻 |
| 看不到自己草稿的 DBContract → 自我死锁 | 任务 5：草稿 API 摘要 |
| 修一个错冒一个错 → 5 次预算耗尽 | 任务 1：一次报告全部违规 |
| blocked 诚实但无解 | 以上四项之后，"不可见"基本不再成立；仍 blocked 即真缺前置，走既有扩界/暂停语义 |

## 验收标准

1. 全量测试通过；新增 `ResourceIndexDigestTest` / `JavaApiDigestTest` / 守卫多违规用例 / 草稿顾问扩展用例全绿。
2. 含 3 处违规的树一次 `validate` 抛出的消息包含全部 3 条。
3. 80+ 文件项目的快照中：资源 id 清单完整（与 `grep -r 'android:id' res/` 数量一致）；非聚焦 Java 文件均有 API 摘要节；context note 不再出现"omitted"的 layout/values 关键文件。
4. 真机回归（无法本地完成则如实标注）：重跑本案"记账 App"，task 658 类 Java wiring 任务不再因"文件不可见"blocked，守卫拒绝一次后修正一次内通过。

## 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 正则提取摘要漏/错（泛型、注解、多行签名） | 摘要是辅助上下文非守卫依据，漏提仅降效不致错；测试覆盖主流形态 |
| prefill 增大拖慢调用 | 增量 ≤6K 且结构化调用已关思考；A1 耗时汇总可量化对比 |
| 多违规消息过长 | 上限 10 条；`PolicyRewriteInstruction` contains 匹配天然兼容 |
| 摘要与真实文件漂移 | 每次快照即时提取，无缓存 |

五个任务独立提交、可单独 revert。建议顺序：1（独立速效）→ 2 → 3 → 4 → 5。
