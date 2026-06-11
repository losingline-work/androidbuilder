# Hermes 多子 Agent 并行执行计划实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 Android Builder App 内集成“多子 Agent 同时执行计划”的能力，让一个工程计划可以被拆成有依赖、有文件边界、有守护审查的并行任务批次，并在失败、重试、修复和中断恢复时保持可控。

**架构：** 把当前 `AgentService.executePlan()` 的单任务串行循环升级为“任务图 -> 并行批次 -> 子 Agent 隔离执行 -> 合并守护 -> 状态/UI 更新”的流水线。子 Agent 是 App 内的逻辑 worker：每个 worker 拥有独立 prompt、独立 scratch source、独立 AI 日志和独立任务状态；只有 `HermesMergeCoordinator` 可以把结果写回 canonical source。高风险、共享文件、Gradle/Manifest/构建屏障任务保持串行，低风险且文件锁不重叠的任务并行。

**技术栈：** Java、Android Views/XML、SQLite、`ExecutorService`、`org.json`、JUnit 4、现有 `OpenAiClient`、`FileOperationsWriter`、`HermesTaskContract`、`ai_conversations` 日志表、Gradle `testDebugUnitTest`。

---

## 设计原则

- “子 Agent”不是外部 Codex 线程，也不是本地模型进程；它是 App 内一个独立执行上下文：一个 `HermesAgentWorker` + 一个 scratch source 目录 + 一组 AI 调用 + 一条 `hermes_agent_runs` 记录。
- 默认并发数为 2，可配置为 1、2、3；1 等价于当前串行安全模式。移动端上不默认超过 2，避免 API 限流、电量和内存问题。
- canonical source 只允许由合并器写入。子 Agent 不能直接写 `repository.sourceDir(projectId)`。
- 并行只发生在任务契约可判定安全时：依赖满足、文件锁不重叠、不是高风险屏障任务、没有 `buildRequiredAfter` 屏障。
- 守护分两层：子 Agent 本地 scratch 内执行现有契约预检、确定性预检、Hermes review；合并前再执行一次跨 Agent 冲突和契约守护。
- 修复流程第一版只并行“可按文件分片的构建错误”；无法分片时沿用单 Agent 修复，避免把 javac/API 连锁错误拆坏。
- 所有状态必须可恢复：App 被杀或进程中断后，运行中的 agent run 标记为 failed，canonical source 保持最近一次成功合并状态。

## 文件结构

- 创建：`app/src/main/java/com/androidbuilder/model/HermesExecutionRunRecord.java`  
  职责：描述一次计划执行运行，字段包含 `id`、`projectId`、`buildJobId`、`status`、`mode`、`maxParallel`、`baseSourceHash`、`createdAt`、`updatedAt`。
- 创建：`app/src/main/java/com/androidbuilder/model/HermesAgentRunRecord.java`  
  职责：描述一个子 Agent 执行记录，字段包含 `id`、`executionRunId`、`projectTaskId`、`batchIndex`、`agentIndex`、`status`、`workDir`、`baseSourceHash`、`mergedSourceHash`、`lockedPathsJson`、`summary`、`errorSummary`、`startedAt`、`completedAt`。
- 修改：`app/src/main/java/com/androidbuilder/data/DatabaseHelper.java`  
  职责：升级 DB_VERSION 到 6，创建 `hermes_execution_runs` 和 `hermes_agent_runs` 表。
- 修改：`app/src/main/java/com/androidbuilder/data/AppRepository.java`  
  职责：新增创建/更新/list execution run 与 agent run 的 DAO 方法；恢复中断工作时处理 running agent。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskGraph.java`  
  职责：从 `ProjectTaskRecord` + `HermesTaskContract` 构建任务图，解析 `dependsOn` 和 `produces`。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesParallelBatch.java`  
  职责：保存一个可并行执行批次，包含 `batchIndex`、`tasks`、`exclusiveReason`。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesFileLockPolicy.java`  
  职责：根据任务契约和任务文本推导文件锁；识别 Gradle、Manifest、values、layout、Java 包路径等锁粒度。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesParallelScheduler.java`  
  职责：根据任务状态、依赖、文件锁、风险和最大并发数输出下一批可运行任务。
- 创建：`app/src/main/java/com/androidbuilder/agent/SourceTreeHashPolicy.java`  
  职责：为 source tree 或指定路径集合计算稳定 hash，用于合并前基线校验。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesAgentResult.java`  
  职责：保存子 Agent scratch 执行结果，包含任务、agent run、`TaskOperations`、touched paths、summary、error。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesMergeCoordinator.java`  
  职责：把子 Agent 结果合并回 canonical source；检测路径冲突、基线变化、契约冲突，必要时 requeue 串行重试。
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesAgentWorker.java`  
  职责：在 scratch source 内执行单个任务，复用现有 AI 生成、预检、Hermes review 和 `FileOperationsWriter`。
- 创建：`app/src/main/java/com/androidbuilder/agent/TaskOperationExecutor.java`  
  职责：从 `AgentService.createAndApplyTaskOperations()` 抽出的可复用执行器；支持 canonical source 和 scratch source。
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`  
  职责：新增 `executePlanBatch()` 并接入并行运行、agent run 持久化、批次日志、合并、构建屏障。
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`  
  职责：任务拆分 prompt 明确输出可并行契约；task operation prompt 明确子 Agent 只负责自己的契约和锁定路径。
- 修改：`app/src/main/java/com/androidbuilder/backend/BuildBackendSettings.java`  
  职责：新增并行开关与最大并发配置：`KEY_PARALLEL_AGENT_LIMIT`。
- 修改：`app/src/main/java/com/androidbuilder/ui/SettingsActivity.java`  
  职责：增加“并行子 Agent”设置，选项为“关闭/2 个/3 个”。
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`  
  职责：执行计划按钮改为批次执行；任务卡展示并行批次、agent 状态和合并结果。
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectTaskListDisplayPolicy.java`  
  职责：在折叠列表中显示 running/merge_pending/failed agent 所属任务。
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesAgentRunDisplayPolicy.java`  
  职责：把 `HermesAgentRunRecord` 转成 UI 摘要文本、状态图标和批次标题。
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 创建测试：`HermesTaskGraphTest`、`HermesFileLockPolicyTest`、`HermesParallelSchedulerTest`、`SourceTreeHashPolicyTest`、`HermesMergeCoordinatorTest`、`HermesAgentRunDisplayPolicyTest`
- 修改测试：`AgentServiceRetryPolicyTest`、`OpenAiClientTest`、`ProjectTaskListDisplayPolicyTest`

---

## 任务 1：持久化并行运行与子 Agent 状态

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/data/DatabaseHelper.java`
- 修改：`app/src/main/java/com/androidbuilder/data/AppRepository.java`
- 创建：`app/src/main/java/com/androidbuilder/model/HermesExecutionRunRecord.java`
- 创建：`app/src/main/java/com/androidbuilder/model/HermesAgentRunRecord.java`
- 创建：`app/src/test/java/com/androidbuilder/model/HermesRunRecordTest.java`

- [ ] **步骤 1：编写失败的 record 测试**

在 `HermesRunRecordTest` 中先覆盖新增 record 的不可变构造和文本字段清洗。本项目当前主要使用 JVM 单测，SQLite DAO 的行为通过类型编译、完整单测和任务 8 的手动升级验收覆盖。

创建 record 清洗测试：

```java
@Test
public void agentRunRecordCleansNullableTextFields() {
    HermesAgentRunRecord record = new HermesAgentRunRecord(
            1, 2, 3, 4, 1, null, null, null, null, null, null, null, 0, 0);

    assertEquals("", record.status);
    assertEquals("", record.workDir);
    assertEquals("", record.lockedPathsJson);
    assertEquals("", record.errorSummary);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.model.HermesRunRecordTest
```

预期：FAIL，缺少 `HermesAgentRunRecord`。

- [ ] **步骤 3：创建两个 record 类**

`HermesExecutionRunRecord`：

```java
package com.androidbuilder.model;

public class HermesExecutionRunRecord {
    public final long id;
    public final long projectId;
    public final long buildJobId;
    public final String status;
    public final String mode;
    public final int maxParallel;
    public final String baseSourceHash;
    public final long createdAt;
    public final long updatedAt;

    public HermesExecutionRunRecord(long id, long projectId, long buildJobId, String status,
                                    String mode, int maxParallel, String baseSourceHash,
                                    long createdAt, long updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.buildJobId = buildJobId;
        this.status = clean(status);
        this.mode = clean(mode);
        this.maxParallel = maxParallel;
        this.baseSourceHash = clean(baseSourceHash);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
```

`HermesAgentRunRecord`：

```java
package com.androidbuilder.model;

public class HermesAgentRunRecord {
    public final long id;
    public final long executionRunId;
    public final long projectTaskId;
    public final int batchIndex;
    public final int agentIndex;
    public final String status;
    public final String workDir;
    public final String baseSourceHash;
    public final String mergedSourceHash;
    public final String lockedPathsJson;
    public final String summary;
    public final String errorSummary;
    public final long startedAt;
    public final long completedAt;

    public HermesAgentRunRecord(long id, long executionRunId, long projectTaskId, int batchIndex,
                                int agentIndex, String status, String workDir, String baseSourceHash,
                                String mergedSourceHash, String lockedPathsJson, String summary,
                                String errorSummary, long startedAt, long completedAt) {
        this.id = id;
        this.executionRunId = executionRunId;
        this.projectTaskId = projectTaskId;
        this.batchIndex = batchIndex;
        this.agentIndex = agentIndex;
        this.status = clean(status);
        this.workDir = clean(workDir);
        this.baseSourceHash = clean(baseSourceHash);
        this.mergedSourceHash = clean(mergedSourceHash);
        this.lockedPathsJson = clean(lockedPathsJson);
        this.summary = clean(summary);
        this.errorSummary = clean(errorSummary);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
```

- [ ] **步骤 4：升级数据库**

把 `DB_VERSION` 从 5 改成 6。

新增：

```java
static final String TABLE_HERMES_EXECUTION_RUNS = "hermes_execution_runs";
static final String TABLE_HERMES_AGENT_RUNS = "hermes_agent_runs";
```

在 `onCreate()` 调用：

```java
createHermesExecutionRunsTable(db);
createHermesAgentRunsTable(db);
```

在 `onUpgrade()` 添加：

```java
if (oldVersion < 6) {
    createHermesExecutionRunsTable(db);
    createHermesAgentRunsTable(db);
}
```

表结构：

```sql
CREATE TABLE IF NOT EXISTS hermes_execution_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL,
  build_job_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'running',
  mode TEXT NOT NULL DEFAULT 'parallel',
  max_parallel INTEGER NOT NULL DEFAULT 1,
  base_source_hash TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE,
  FOREIGN KEY(build_job_id) REFERENCES build_jobs(id) ON DELETE CASCADE
)
```

```sql
CREATE TABLE IF NOT EXISTS hermes_agent_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  execution_run_id INTEGER NOT NULL,
  project_task_id INTEGER NOT NULL,
  batch_index INTEGER NOT NULL,
  agent_index INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  work_dir TEXT NOT NULL DEFAULT '',
  base_source_hash TEXT NOT NULL DEFAULT '',
  merged_source_hash TEXT NOT NULL DEFAULT '',
  locked_paths_json TEXT NOT NULL DEFAULT '[]',
  summary TEXT NOT NULL DEFAULT '',
  error_summary TEXT NOT NULL DEFAULT '',
  started_at INTEGER NOT NULL DEFAULT 0,
  completed_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(execution_run_id) REFERENCES hermes_execution_runs(id) ON DELETE CASCADE,
  FOREIGN KEY(project_task_id) REFERENCES project_tasks(id) ON DELETE CASCADE
)
```

- [ ] **步骤 5：新增 Repository 方法**

在 `AppRepository` 添加：

```java
public synchronized HermesExecutionRunRecord createHermesExecutionRun(
        long projectId, long buildJobId, String mode, int maxParallel, String baseSourceHash)
```

```java
public synchronized void updateHermesExecutionRun(long id, String status, String baseSourceHash)
```

```java
public synchronized HermesAgentRunRecord createHermesAgentRun(
        long executionRunId, long projectTaskId, int batchIndex, int agentIndex,
        String status, String workDir, String baseSourceHash, String lockedPathsJson)
```

```java
public synchronized void updateHermesAgentRun(
        long id, String status, String mergedSourceHash, String summary, String errorSummary)
```

```java
public synchronized List<HermesAgentRunRecord> listHermesAgentRunsForProject(long projectId)
```

`readHermesExecutionRun(Cursor)` 和 `readHermesAgentRun(Cursor)` 私有方法与现有 `readProjectTask` 风格一致。

- [ ] **步骤 6：恢复中断运行**

在 `recoverInterruptedWork()` 中增加：

```java
for (HermesAgentRunRecord run : listRunningHermesAgentRuns(projectId)) {
    updateHermesAgentRun(run.id, "failed", "", "", summary);
    recovered = true;
}
for (HermesExecutionRunRecord run : listRunningHermesExecutionRuns(projectId)) {
    updateHermesExecutionRun(run.id, "failed", run.baseSourceHash);
    recovered = true;
}
```

- [ ] **步骤 7：验证**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.model.HermesRunRecordTest
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 8：Commit**

```bash
git add app/src/main/java/com/androidbuilder/data/DatabaseHelper.java \
        app/src/main/java/com/androidbuilder/data/AppRepository.java \
        app/src/main/java/com/androidbuilder/model/HermesExecutionRunRecord.java \
        app/src/main/java/com/androidbuilder/model/HermesAgentRunRecord.java \
        app/src/test/java/com/androidbuilder/model/HermesRunRecordTest.java
git commit -m "feat: persist Hermes parallel agent runs"
```

---

## 任务 2：任务图、依赖解析和文件锁策略

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesTaskGraph.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesParallelBatch.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesFileLockPolicy.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesParallelScheduler.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesTaskGraphTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesFileLockPolicyTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesParallelSchedulerTest.java`

- [ ] **步骤 1：编写任务图测试**

```java
@Test
public void taskGraphMarksTaskReadyWhenProducedDependenciesAreDone() {
    ProjectTaskRecord data = task(1, 0, "Data", contract("{\"produces\":[\"data\"]}"), "done");
    ProjectTaskRecord ui = task(2, 1, "UI", contract("{\"dependsOn\":[\"data\"],\"produces\":[\"ui\"]}"), "pending");

    HermesTaskGraph graph = HermesTaskGraph.fromTasks(Arrays.asList(data, ui));

    assertTrue(graph.isReady(ui));
    assertFalse(graph.isReady(data));
}
```

辅助方法放在测试文件底部：

```java
private static ProjectTaskRecord task(long id, int order, String title, String instruction, String status) {
    return new ProjectTaskRecord(id, 1, order, title, instruction, status, "", 0, 0, 0, 0);
}

private static String contract(String json) throws Exception {
    return HermesTaskContractCodec.appendToInstruction("Do task.", HermesTaskContractCodec.fromJson(new JSONObject(json)));
}
```

- [ ] **步骤 2：编写文件锁测试**

```java
@Test
public void allowedPathsBecomeLocks() throws Exception {
    HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(
            "{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"]}"));

    List<String> locks = HermesFileLockPolicy.locksFor("Update DAO", "Do task.", contract);

    assertEquals(Collections.singletonList("app/src/main/java/com/example/RecordDao.java"), locks);
}
```

```java
@Test
public void gradleTaskLocksBuildFilesExclusively() {
    List<String> locks = HermesFileLockPolicy.locksFor("Update Gradle", "Change app/build.gradle", HermesTaskContract.empty());

    assertTrue(locks.contains("app/build.gradle"));
    assertTrue(HermesFileLockPolicy.isExclusiveBarrier("Update Gradle", "Change app/build.gradle", HermesTaskContract.empty()));
}
```

- [ ] **步骤 3：编写并行调度测试**

```java
@Test
public void schedulerBatchesIndependentDisjointTasks() throws Exception {
    ProjectTaskRecord dao = task(1, 0, "DAO", contract("{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"],\"produces\":[\"data\"]}"), "pending");
    ProjectTaskRecord layout = task(2, 1, "Layout", contract("{\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],\"produces\":[\"ui\"]}"), "pending");

    HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(dao, layout), Collections.emptyList(), 2);

    assertEquals(2, batch.tasks.size());
    assertEquals("", batch.exclusiveReason);
}
```

再加冲突测试：

```java
@Test
public void schedulerDoesNotBatchOverlappingLocks() throws Exception {
    ProjectTaskRecord left = task(1, 0, "Strings A", contract("{\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"]}"), "pending");
    ProjectTaskRecord right = task(2, 1, "Strings B", contract("{\"allowedPaths\":[\"app/src/main/res/values/strings.xml\"]}"), "pending");

    HermesParallelBatch batch = HermesParallelScheduler.nextBatch(Arrays.asList(left, right), Collections.emptyList(), 2);

    assertEquals(1, batch.tasks.size());
}
```

- [ ] **步骤 4：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesTaskGraphTest \
                            --tests com.androidbuilder.agent.HermesFileLockPolicyTest \
                            --tests com.androidbuilder.agent.HermesParallelSchedulerTest
```

预期：FAIL，缺少新类。

- [ ] **步骤 5：实现 `HermesTaskGraph`**

关键 API：

```java
public final class HermesTaskGraph {
    public static HermesTaskGraph fromTasks(List<ProjectTaskRecord> tasks)
    public boolean isReady(ProjectTaskRecord task)
    public List<ProjectTaskRecord> readyTasks()
}
```

规则：
- `done` 任务不参与 ready。
- `failed` 任务优先 ready，但只返回该失败任务，不并行其它任务。
- `pending` 任务的 `dependsOn` 全部被任意 `done` 任务的 `produces` 覆盖时 ready。
- 如果任务没有 `dependsOn`，按 sort order 只要求前面未完成的“屏障任务”不存在。

- [ ] **步骤 6：实现 `HermesFileLockPolicy`**

关键 API：

```java
static List<String> locksFor(String title, String instruction, HermesTaskContract contract)
static boolean conflicts(List<String> left, List<String> right)
static boolean isExclusiveBarrier(String title, String instruction, HermesTaskContract contract)
```

锁推导：
- `allowedPaths` 非空：直接使用归一化后的 allowed paths。
- `expectedFiles` 非空且 `allowedPaths` 为空：使用 expected files。
- 文本包含 `gradle`、`settings.gradle`、`build.gradle`：锁 `settings.gradle`、`build.gradle`、`app/build.gradle`，并标记屏障。
- 文本包含 `manifest` 或 `AndroidManifest.xml`：锁 `app/src/main/AndroidManifest.xml`，并标记屏障。
- 文本包含 `values`、`strings.xml`、`colors.xml`、`themes.xml`：锁 `app/src/main/res/values/*`。
- 没有可推导锁：返回 `*`，表示只能串行。

- [ ] **步骤 7：实现 `HermesParallelScheduler`**

关键 API：

```java
public static HermesParallelBatch nextBatch(
        List<ProjectTaskRecord> tasks,
        List<HermesAgentRunRecord> activeRuns,
        int maxParallel)
```

策略：
- `maxParallel <= 1`：返回一个任务。
- 如果有 `failed` 任务：返回最早 failed 任务的单任务批次。
- 如果 ready 任务中第一个是屏障：返回该任务单任务批次。
- 依次加入 ready 任务，直到达到 maxParallel；加入前检查锁冲突。
- activeRuns 中状态为 `running` 或 `merge_pending` 的锁不能被新任务使用。

- [ ] **步骤 8：验证**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesTaskGraphTest \
                            --tests com.androidbuilder.agent.HermesFileLockPolicyTest \
                            --tests com.androidbuilder.agent.HermesParallelSchedulerTest
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/HermesTaskGraph.java \
        app/src/main/java/com/androidbuilder/agent/HermesParallelBatch.java \
        app/src/main/java/com/androidbuilder/agent/HermesFileLockPolicy.java \
        app/src/main/java/com/androidbuilder/agent/HermesParallelScheduler.java \
        app/src/test/java/com/androidbuilder/agent/HermesTaskGraphTest.java \
        app/src/test/java/com/androidbuilder/agent/HermesFileLockPolicyTest.java \
        app/src/test/java/com/androidbuilder/agent/HermesParallelSchedulerTest.java
git commit -m "feat: schedule Hermes tasks in safe parallel batches"
```

---

## 任务 3：抽出可复用任务执行器与 scratch source 执行

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/agent/TaskOperationExecutor.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesAgentWorker.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesAgentResult.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`

- [ ] **步骤 1：写构造器回归测试**

把现有 `agentServiceHasNoLocalModelInjectionConstructor` 改名为：

```java
@Test
public void agentServiceKeepsPublicRepositoryConstructor() {
    boolean found = false;
    for (Constructor<?> constructor : AgentService.class.getDeclaredConstructors()) {
        if (constructor.getParameterCount() == 2) {
            found = true;
        }
    }
    assertTrue(found);
}
```

新增测试：

```java
@Test
public void taskOperationExecutorClassExistsForParallelWorkers() {
    assertEquals("com.androidbuilder.agent.TaskOperationExecutor", TaskOperationExecutor.class.getName());
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：FAIL，缺少 `TaskOperationExecutor`。

- [ ] **步骤 3：创建 `TaskOperationExecutor` 骨架**

先把 `AgentService.createAndApplyTaskOperations()` 相关私有依赖以最小范围复制/移动进执行器，保持行为不变。构造函数：

```java
TaskOperationExecutor(
        Context context,
        AppRepository repository,
        OpenAiClient openAiClient,
        FileOperationsWriter operationsWriter)
```

公开方法：

```java
TaskOperations execute(
        long projectId,
        Long linkedBuildJobId,
        File sourceDir,
        String planContent,
        ProjectTaskRecord task,
        String snapshot,
        File logs,
        boolean chinese,
        String initialFailureContext,
        boolean repairFlow) throws Exception
```

迁移时保留：
- retry attempts
- context negotiation
- task contract preflight
- deterministic preflight
- local rule guard
- Hermes review
- policy rewrite
- failure fingerprint/playbook

`AgentService.createAndApplyTaskOperations()` 先改成委托：

```java
return taskOperationExecutor.execute(projectId, linkedBuildJobId, sourceDir, planContent,
        new ProjectTaskRecord(0, projectId, 0, taskTitle, taskInstruction, "running", "", 0, 0, 0, 0),
        snapshot, logs, chinese, initialFailureContext, repairFlow);
```

- [ ] **步骤 4：创建 `HermesAgentResult`**

```java
public class HermesAgentResult {
    public final ProjectTaskRecord task;
    public final HermesAgentRunRecord run;
    public final TaskOperations operations;
    public final List<String> touchedPaths;
    public final String summary;
    public final Exception error;

    public boolean success() {
        return error == null && operations != null;
    }
}
```

- [ ] **步骤 5：创建 `HermesAgentWorker`**

核心方法：

```java
HermesAgentResult runTask(
        long projectId,
        long linkedBuildJobId,
        long executionRunId,
        ProjectPlanRecord plan,
        ProjectTaskRecord task,
        int batchIndex,
        int agentIndex,
        File canonicalSource,
        File scratchRoot,
        boolean chinese)
```

行为：
- 复制 canonical source 到 `scratchRoot/agent-<taskId>/source`。
- 写 `scratchRoot/agent-<taskId>/agent.log`。
- 创建 `hermes_agent_runs`，状态 `running`。
- 调用 `TaskOperationExecutor.execute()`，sourceDir 使用 scratch source。
- 成功时 agent run 状态 `merge_pending`。
- 失败时 agent run 状态 `failed`，ProjectTask 状态 `failed`。
- 返回 `HermesAgentResult`，不修改 canonical source。

- [ ] **步骤 6：验证串行行为未变**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/TaskOperationExecutor.java \
        app/src/main/java/com/androidbuilder/agent/HermesAgentWorker.java \
        app/src/main/java/com/androidbuilder/agent/HermesAgentResult.java \
        app/src/main/java/com/androidbuilder/agent/AgentService.java \
        app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java
git commit -m "refactor: isolate Hermes task operation execution"
```

---

## 任务 4：合并协调器和跨 Agent 守护

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/agent/SourceTreeHashPolicy.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesMergeCoordinator.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/SourceTreeHashPolicyTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesMergeCoordinatorTest.java`

- [ ] **步骤 1：编写 source hash 测试**

```java
@Test
public void sourceHashChangesWhenTrackedFileChanges() throws Exception {
    File root = temporaryFolder.newFolder("source");
    FileUtils.writeText(new File(root, "app/src/main/java/MainActivity.java"), "class A {}\n");
    String before = SourceTreeHashPolicy.hash(root);

    FileUtils.writeText(new File(root, "app/src/main/java/MainActivity.java"), "class B {}\n");

    assertNotEquals(before, SourceTreeHashPolicy.hash(root));
}
```

- [ ] **步骤 2：编写合并冲突测试**

```java
@Test
public void mergeRejectsTwoResultsTouchingSamePath() {
    HermesAgentResult left = resultWithPath("app/src/main/res/values/strings.xml");
    HermesAgentResult right = resultWithPath("app/src/main/res/values/strings.xml");

    HermesMergeCoordinator.MergePlan plan = HermesMergeCoordinator.plan(Arrays.asList(left, right));

    assertFalse(plan.canMergeAll);
    assertEquals(1, plan.conflicts.size());
}
```

- [ ] **步骤 3：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.SourceTreeHashPolicyTest \
                            --tests com.androidbuilder.agent.HermesMergeCoordinatorTest
```

预期：FAIL，缺少新类。

- [ ] **步骤 4：实现 `SourceTreeHashPolicy`**

规则：
- 遍历 source tree 下所有普通文件。
- 排除 `.gradle/`、`build/`、`.DS_Store`。
- 按相对路径排序。
- hash 输入包含相对路径、文件长度和文件内容 SHA-256。

API：

```java
public static String hash(File root) throws IOException
public static String hash(File root, List<String> relativePaths) throws IOException
```

- [ ] **步骤 5：实现 `HermesMergeCoordinator`**

API：

```java
public MergePlan plan(List<HermesAgentResult> results)
public MergeResult merge(File canonicalSource, List<HermesAgentResult> results) throws Exception
```

合并流程：
1. 丢弃 failed result。
2. 检查 touched paths 是否两两冲突。
3. 对每个 result 重新跑 `HermesTaskContractGuard.review(contract, operations)`。
4. 对每个 result 重新跑 `TaskOperationsPreflight.review(operations, sourceSnapshot(canonicalSource))`。
5. 无冲突时按 task sort order 调用 `FileOperationsWriter.apply(canonicalSource, operations)`。
6. 合并成功后更新 agent run `done`，更新 project task `done`。
7. 合并失败时该任务状态改 `failed`，其它未合并任务保留 `pending`。

`MergePlan` 字段：

```java
public final boolean canMergeAll;
public final List<String> conflicts;
public final List<HermesAgentResult> mergeableResults;
```

- [ ] **步骤 6：验证**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.SourceTreeHashPolicyTest \
                            --tests com.androidbuilder.agent.HermesMergeCoordinatorTest
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/SourceTreeHashPolicy.java \
        app/src/main/java/com/androidbuilder/agent/HermesMergeCoordinator.java \
        app/src/test/java/com/androidbuilder/agent/SourceTreeHashPolicyTest.java \
        app/src/test/java/com/androidbuilder/agent/HermesMergeCoordinatorTest.java
git commit -m "feat: merge parallel Hermes agent results safely"
```

---

## 任务 5：AgentService 接入并行批次执行

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/backend/BuildBackendSettings.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`

- [ ] **步骤 1：编写 prompt 测试**

在 `OpenAiClientTest` 添加：

```java
@Test
public void taskSplitPromptRequestsParallelContracts() {
    String prompt = OpenAiClient.tasksSystemPromptForTest(false);

    assertTrue(prompt.contains("dependsOn"));
    assertTrue(prompt.contains("produces"));
    assertTrue(prompt.contains("allowedPaths"));
    assertTrue(prompt.contains("safe parallel"));
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：FAIL，prompt 尚未包含 `safe parallel`。

- [ ] **步骤 3：增强任务拆分 prompt**

在 `OpenAiClient.tasksSystemPromptText()` 中把契约说明扩展为：

```text
Use dependsOn and produces to expose a safe execution graph. Use allowedPaths and forbiddenPaths precisely so Hermes can decide safe parallel batches. Tasks may run in safe parallel only when their dependencies are satisfied and their allowed paths do not overlap; do not claim broad tasks are parallel-safe.
```

- [ ] **步骤 4：添加并行设置**

在 `BuildBackendSettings` 增加：

```java
public static final String KEY_PARALLEL_AGENT_LIMIT = "parallel_agent_limit";

public static int parallelAgentLimit(Context context) {
    int value = prefs(context).getInt(KEY_PARALLEL_AGENT_LIMIT, 2);
    if (value < 1) return 1;
    if (value > 3) return 3;
    return value;
}

public static void setParallelAgentLimit(Context context, int value) {
    prefs(context).edit().putInt(KEY_PARALLEL_AGENT_LIMIT, Math.max(1, Math.min(3, value))).apply();
}
```

- [ ] **步骤 5：新增 `executePlanBatch()`**

在 `AgentService.executePlan()` 中替换单任务选择：

```java
List<ProjectTaskRecord> tasks = repository.listProjectTasks(projectId);
int maxParallel = BuildBackendSettings.parallelAgentLimit(context);
HermesParallelBatch batch = HermesParallelScheduler.nextBatch(
        tasks,
        repository.listActiveHermesAgentRuns(projectId),
        maxParallel);
```

如果 `batch.tasks.isEmpty()`，沿用“所有任务已完成”逻辑。

新增私有方法：

```java
private List<HermesAgentResult> executeParallelBatch(
        long projectId,
        BuildJobRecord job,
        ProjectPlanRecord plan,
        HermesExecutionRunRecord executionRun,
        HermesParallelBatch batch,
        boolean chinese) throws Exception
```

实现：
- 如果 batch size 为 1，仍然通过 `HermesAgentWorker` 执行，保持同一执行路径。
- 如果 batch size > 1，创建固定大小 `ExecutorService`。
- 每个任务先更新 `project_tasks.status = running`。
- 每个 worker 使用独立 scratch dir：`jobs/<jobId>/agents/task-<taskId>`.
- `Future` 完成后收集 `HermesAgentResult`。
- 任一失败不取消已经完成的成功结果；合并器只合并成功且无冲突的结果。

- [ ] **步骤 6：合并结果并更新计划状态**

执行后：

```java
HermesMergeCoordinator.MergeResult mergeResult = mergeCoordinator.merge(sourceDir, results);
ProjectTaskRecord next = repository.nextPendingProjectTask(projectId);
repository.updateProjectPlanStatus(projectId, next == null ? "generated" : "planned", job.id);
```

如果批次中任何任务 `buildRequiredAfter=true`：
- 本批次必须单任务运行。
- 合并后消息提示“需要构建验证”。
- 不自动继续下一批，等待 UI 的构建动作或用户设置的自动构建策略。

- [ ] **步骤 7：记录批次 Hermes 事件**

使用现有 `recordHermesRunEvent()`，新增：

```java
recordHermesRunEvent(projectId, job.id, new HermesRunEvent(
        job.id + ":batch-" + batch.batchIndex,
        "parallel_batch",
        "orchestrator",
        "dispatch",
        batch.exclusiveReason.isEmpty() ? "Dispatch safe parallel batch." : batch.exclusiveReason,
        "tasks=" + taskTitles(batch.tasks),
        "maxParallel=" + maxParallel,
        1));
```

- [ ] **步骤 8：验证**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest \
                            --tests com.androidbuilder.agent.HermesParallelSchedulerTest \
                            --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 9：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/AgentService.java \
        app/src/main/java/com/androidbuilder/backend/BuildBackendSettings.java \
        app/src/main/java/com/androidbuilder/agent/OpenAiClient.java \
        app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java \
        app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java
git commit -m "feat: execute Hermes plan tasks in parallel batches"
```

---

## 任务 6：并行修复与守护策略

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesRepairShard.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/HermesRepairShardingPolicy.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesRepairShardingPolicyTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/BuildFailureClassifier.java`

- [ ] **步骤 1：编写修复分片测试**

```java
@Test
public void splitsIndependentMissingResourceAndJavaFileFailures() {
    String log = "app/src/main/res/layout/activity_main.xml: error: resource color/primary not found\n"
            + "app/src/main/java/com/example/RecordDao.java:12: error: cannot find symbol\n";

    List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

    assertEquals(2, shards.size());
    assertEquals("app/src/main/res/layout/activity_main.xml", shards.get(0).focusPath);
    assertEquals("app/src/main/java/com/example/RecordDao.java", shards.get(1).focusPath);
}
```

```java
@Test
public void keepsConstructorApiMismatchAsSingleShard() {
    String log = "constructor RecordDao in class RecordDao cannot be applied to given types";

    List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

    assertEquals(1, shards.size());
    assertTrue(shards.get(0).exclusive);
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesRepairShardingPolicyTest
```

预期：FAIL，缺少修复分片类。

- [ ] **步骤 3：实现修复分片**

`HermesRepairShard`：

```java
public class HermesRepairShard {
    public final String focusPath;
    public final String logExcerpt;
    public final boolean exclusive;
}
```

`HermesRepairShardingPolicy.shards(String buildLog)` 规则：
- AAPT missing resource 可以按资源或 XML path 分片。
- javac `cannot find symbol` 可按文件 path 分片。
- constructor/signature mismatch、Gradle dependency、Manifest merge error 标记 exclusive。
- 无法解析 path 时返回一个 exclusive shard。

- [ ] **步骤 4：接入 `repairBuild()`**

在 `repairBuild()` 中：
- 调用 `HermesRepairShardingPolicy.shards(buildLog)`。
- 如果 shard 数量为 1 或任一 shard exclusive，沿用当前单 repair。
- 如果 shard 数量 > 1 且路径锁不冲突，使用 `HermesAgentWorker` 并行执行 repair task。
- repair task title 格式：`Repair build failure: <focusPath>`。
- repair instruction 使用原 `repairInstruction(buildLog, chinese)` 加上 shard focus：

```text
Repair only diagnostics related to this focus path:
<focusPath>

Relevant log excerpt:
<logExcerpt>
```

- [ ] **步骤 5：合并前守护**

并行 repair 的结果必须经过 `HermesMergeCoordinator`。如果任一 repair result 和另一个 result 冲突：
- 合并已无冲突的结果。
- 冲突 shard 对应任务/agent run 标记 failed。
- 给用户消息：并行修复中有冲突，已保留未合并修复，建议单 Agent 修复剩余问题。

- [ ] **步骤 6：验证**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.HermesRepairShardingPolicyTest \
                            --tests com.androidbuilder.agent.HermesMergeCoordinatorTest
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/HermesRepairShard.java \
        app/src/main/java/com/androidbuilder/agent/HermesRepairShardingPolicy.java \
        app/src/main/java/com/androidbuilder/agent/AgentService.java \
        app/src/main/java/com/androidbuilder/agent/BuildFailureClassifier.java \
        app/src/test/java/com/androidbuilder/agent/HermesRepairShardingPolicyTest.java
git commit -m "feat: shard safe Hermes build repairs"
```

---

## 任务 7：UI 展示并行批次、子 Agent 和设置

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/ui/HermesAgentRunDisplayPolicy.java`
- 创建：`app/src/test/java/com/androidbuilder/ui/HermesAgentRunDisplayPolicyTest.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectActivity.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/ProjectTaskListDisplayPolicy.java`
- 修改：`app/src/main/java/com/androidbuilder/ui/SettingsActivity.java`
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`

- [ ] **步骤 1：编写显示策略测试**

```java
@Test
public void runningAgentRunShowsBatchAndAgentIndex() {
    HermesAgentRunRecord run = new HermesAgentRunRecord(
            1, 2, 3, 4, 2, "running", "", "", "", "[\"app/src/main/res/layout/activity_main.xml\"]",
            "", "", 0, 0);

    HermesAgentRunDisplayPolicy.Item item = HermesAgentRunDisplayPolicy.item(run, true);

    assertEquals("批次 5 · Agent 3", item.title);
    assertTrue(item.subtitle.contains("运行中"));
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.ui.HermesAgentRunDisplayPolicyTest
```

预期：FAIL，缺少显示策略。

- [ ] **步骤 3：实现 `HermesAgentRunDisplayPolicy`**

字段：

```java
public static final class Item {
    public final String title;
    public final String subtitle;
    public final String iconText;
}
```

状态映射：
- `pending` -> `·`
- `running` -> `...`
- `merge_pending` -> `⇄`
- `done` -> `✓`
- `failed` -> `!`

如果中文：
- 标题：`批次 <batchIndex + 1> · Agent <agentIndex + 1>`
- 副标题包含状态和 locked paths。

- [ ] **步骤 4：ProjectActivity 展示 agent run**

在 `refresh()` 或当前任务刷新逻辑中读取：

```java
agentRunItems = repository.listHermesAgentRunsForProject(projectId);
```

任务卡折叠态显示：
- running task
- failed task
- merge_pending agent run 所属 task
- 下一条 pending task

展开态在每个任务行下方展示最近 agent run：

```text
批次 2 · Agent 1 · 运行中
锁定：app/src/main/java/.../RecordDao.java
```

- [ ] **步骤 5：设置页增加并发数**

在 `SettingsActivity` 添加一个简单单选区域：
- 关闭（1，串行）
- 2 个子 Agent（推荐）
- 3 个子 Agent（更快但更容易触发 API 限流）

写入 `BuildBackendSettings.setParallelAgentLimit()`。

- [ ] **步骤 6：文案**

英文：

```xml
<string name="parallel_agents_title">Parallel sub-agents</string>
<string name="parallel_agents_serial">Off · serial execution</string>
<string name="parallel_agents_two">2 sub-agents · recommended</string>
<string name="parallel_agents_three">3 sub-agents · faster, higher API pressure</string>
<string name="parallel_batch_running">Running %1$d sub-agents in batch %2$d</string>
<string name="parallel_merge_conflict">Parallel merge conflict: %1$s</string>
```

中文：

```xml
<string name="parallel_agents_title">并行子 Agent</string>
<string name="parallel_agents_serial">关闭 · 串行执行</string>
<string name="parallel_agents_two">2 个子 Agent · 推荐</string>
<string name="parallel_agents_three">3 个子 Agent · 更快，但 API 压力更高</string>
<string name="parallel_batch_running">正在执行批次 %2$d：%1$d 个子 Agent 并行</string>
<string name="parallel_merge_conflict">并行合并冲突：%1$s</string>
```

- [ ] **步骤 7：验证**

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.ui.HermesAgentRunDisplayPolicyTest \
                            --tests com.androidbuilder.ui.ProjectTaskListDisplayPolicyTest
./gradlew testDebugUnitTest assembleDebug
```

预期：PASS，Debug APK 可构建。

- [ ] **步骤 8：Commit**

```bash
git add app/src/main/java/com/androidbuilder/ui/HermesAgentRunDisplayPolicy.java \
        app/src/main/java/com/androidbuilder/ui/ProjectActivity.java \
        app/src/main/java/com/androidbuilder/ui/ProjectTaskListDisplayPolicy.java \
        app/src/main/java/com/androidbuilder/ui/SettingsActivity.java \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/test/java/com/androidbuilder/ui/HermesAgentRunDisplayPolicyTest.java
git commit -m "feat: show Hermes parallel agent progress"
```

---

## 任务 8：端到端守护、限流和完成验证

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`
- 修改：`app/src/main/java/com/androidbuilder/util/ActiveWorkRegistry.java`
- 修改：`README.md`
- 修改：`docs/ai-context/00-项目总览.md`
- 创建：`app/src/test/java/com/androidbuilder/agent/HermesParallelExecutionPolicyTest.java`

- [ ] **步骤 1：编写并发降级策略测试**

```java
@Test
public void rateLimitErrorRecommendsSerialRetry() {
    String message = HermesParallelExecutionPolicy.userMessageForBatchFailure(
            "HTTP 429 rate limit exceeded", true);

    assertTrue(message.contains("serial"));
}
```

- [ ] **步骤 2：实现并发降级策略**

创建或放入 `HermesParallelExecutionPolicy`：

```java
static boolean shouldDowngradeToSerial(String errorMessage) {
    String text = errorMessage == null ? "" : errorMessage.toLowerCase(Locale.ROOT);
    return text.contains("429") || text.contains("rate limit") || text.contains("too many requests");
}
```

如果一个批次失败原因是限流：
- 停止继续派发本批剩余任务。
- 把未完成 running/pending agent run 标记 failed。
- 给用户消息：建议把并行子 Agent 调为关闭或 2。
- 保持已合并任务为 done，未合并任务 pending/failed。

- [ ] **步骤 3：ActiveWorkRegistry 文案**

并行批次执行时 foreground 文案：
- 中文：`Hermes 正在并行执行计划`
- 英文：`Hermes is running parallel agents`

不要新增 service 类型，只更新 begin 时的 message。

- [ ] **步骤 4：文档**

README 和 `docs/ai-context/00-项目总览.md` 增加：
- 并行子 Agent 是逻辑 worker，不是外部进程。
- 默认最大并行 2。
- canonical source 由 merge coordinator 写入。
- 高风险任务、构建屏障、冲突锁任务串行。
- 遇到 429 限流会建议降级。

- [ ] **步骤 5：完整验证**

运行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 6：手动验收清单**

在真机或模拟器上创建一个包含至少 4 个任务的项目，确认：
- 点击执行计划后，任务卡出现批次/Agent 状态。
- 两个 disjoint 任务同时进入 running。
- 任务成功后先进入 merge_pending，再变 done。
- 冲突任务不会同批执行。
- 设置并发为关闭后，只执行一个任务。
- API 限流或模拟失败后未完成任务不会错误标记 done。
- App 重启后 running agent 被恢复为 failed，计划仍可继续执行。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/androidbuilder/agent/AgentService.java \
        app/src/main/java/com/androidbuilder/agent/OpenAiClient.java \
        app/src/main/java/com/androidbuilder/util/ActiveWorkRegistry.java \
        app/src/test/java/com/androidbuilder/agent/HermesParallelExecutionPolicyTest.java \
        README.md \
        docs/ai-context/00-项目总览.md
git commit -m "chore: document and harden Hermes parallel execution"
```

---

## 执行顺序和并行开发建议

这个功能本身也适合多子 agent 开发，但必须按边界拆：

- Agent A：任务 1，数据库和 record。写集：`data/`、`model/Hermes*RunRecord.java`。
- Agent B：任务 2，纯调度策略。写集：`agent/HermesTaskGraph.java`、`HermesFileLockPolicy.java`、`HermesParallelScheduler.java` 和对应测试。
- Agent C：任务 4，合并与 hash。写集：`SourceTreeHashPolicy.java`、`HermesMergeCoordinator.java` 和对应测试。
- 主控/人工：任务 3 和任务 5，因为它们会改 `AgentService.java`，应串行整合。
- Agent D：任务 7 的 UI 显示策略可以在任务 1 record 稳定后并行做；真正改 `ProjectActivity.java` 前等待任务 5。
- Agent E：任务 6 修复分片可在任务 4 后并行做；接入 `repairBuild()` 前等待任务 5。

建议里程碑：
1. 先完成任务 1、2、4，得到可测试的“并行基础设施”。
2. 再完成任务 3、5，让计划执行真正并行。
3. 最后完成任务 6、7、8，把修复、UI、限流和文档补齐。

## 风险和回退

- API 限流：默认并行 2，出现 429 后提示降级；不自动无限重试。
- 文件冲突：合并器拒绝冲突结果，保留未合并任务，用户可以串行重试。
- 大文件重构风险：`AgentService.java` 已经很大，任务 3 只抽 `TaskOperationExecutor`，不顺手重构其它流程。
- 数据库升级风险：DB_VERSION 6 只新增表，不改旧表字段；旧项目可直接打开。
- Scratch 目录膨胀：每个 job 完成后可保留最近日志和失败 scratch；成功 scratch 可以交给单独的清理功能删除，本计划第一版不自动删，便于调试。

## 最终验证

完整实现后必须运行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

并记录：
- 单测通过。
- Debug APK 构建成功。
- 至少一次手动串行执行成功。
- 至少一次手动并行执行成功。
- 至少一次冲突任务被串行化或拒绝合并。
