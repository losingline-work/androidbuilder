# Background Context Negotiation 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在错误重试和构建修复路径中加入后台上下文协商，让云端模型先确认需要哪些源码上下文，再生成最终文件操作，降低反复重新创建项目/文件的概率。

**架构：** 新增一个结构化协商结果模型、解析器和策略类。`OpenAiClient` 增加上下文协商调用和可测试 prompt builder；`AgentService` 在 failed task、policy retry、build repair 里自动协商、记录日志、重新聚焦源码快照，并把失败摘要和 patch intent 拼入最终 `TaskOperations` 生成 prompt。

**技术栈：** Java, Android app module, OpenAI-compatible Chat Completions, org.json, JUnit 4, Gradle `testDebugUnitTest`。

---

## 文件结构

- 创建：`app/src/main/java/com/androidbuilder/model/ContextNegotiation.java`  
  职责：不可变数据对象，保存 `ready`、`neededFiles`、`focusTerms`、`riskNotes`、`patchIntent`。
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java`  
  职责：从云端 JSON 响应解析并裁剪协商结果；忽略 unsafe path；对无 JSON 或空 patch intent 抛清晰错误。
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`  
  职责：决定是否需要协商、把协商结果转换成 focus text、生成最终 retry/repair prompt section、限制轮数。
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java`
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`  
  新增 `negotiateTaskContext(...)`；重构 task-operation user prompt builder；支持额外 retry context。
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`  
  在 failed task、policy retry、build repair 中调用协商，记录 `ai_conversation` 日志，传递失败摘要和 patch intent。
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`
- 可选修改：`docs/ai-context/02-业务流程.md`  
  若实现完成后行为变化明显，补充“后台上下文协商”流程说明。

## 任务 1：为协商 JSON 解析写失败测试

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java`

- [ ] **步骤 1：编写失败的解析器测试**

写入完整测试文件：

```java
package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContextNegotiationParserTest {
    @Test
    public void parsesValidNegotiationJson() throws Exception {
        ContextNegotiation result = ContextNegotiationParser.fromJson("{"
                + "\"ready\":false,"
                + "\"neededFiles\":[\"app/src/main/java/com/example/DBHelper.java\"],"
                + "\"focusTerms\":[\"DBHelper\",\"CategoryDao\"],"
                + "\"riskNotes\":[\"Keep DAO signatures synchronized.\"],"
                + "\"patchIntent\":\"Modify existing DAO and Activity only; do not recreate the project.\""
                + "}");

        assertFalse(result.ready);
        assertEquals("app/src/main/java/com/example/DBHelper.java", result.neededFiles.get(0));
        assertEquals("DBHelper", result.focusTerms.get(0));
        assertEquals("Keep DAO signatures synchronized.", result.riskNotes.get(0));
        assertTrue(result.patchIntent.contains("do not recreate"));
    }

    @Test
    public void ignoresUnsafeNeededFilePaths() throws Exception {
        ContextNegotiation result = ContextNegotiationParser.fromJson("{"
                + "\"ready\":true,"
                + "\"neededFiles\":[\"../secrets.txt\",\"app/src/main/res/layout/activity_main.xml\",\"/tmp/outside.java\"],"
                + "\"focusTerms\":[\"MainActivity\"],"
                + "\"riskNotes\":[],"
                + "\"patchIntent\":\"Patch the shown Activity only.\""
                + "}");

        assertEquals(1, result.neededFiles.size());
        assertEquals("app/src/main/res/layout/activity_main.xml", result.neededFiles.get(0));
    }

    @Test
    public void trimsAndCapsLists() throws Exception {
        StringBuilder json = new StringBuilder("{\"ready\":true,\"neededFiles\":[],\"focusTerms\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"Term").append(i).append("\"");
        }
        json.append("],\"riskNotes\":[\"").append(repeat("x", 500)).append("\"],");
        json.append("\"patchIntent\":\"").append(repeat("p", 2500)).append("\"}");

        ContextNegotiation result = ContextNegotiationParser.fromJson(json.toString());

        assertEquals(ContextNegotiationParser.MAX_ITEMS_FOR_TEST, result.focusTerms.size());
        assertEquals(ContextNegotiationParser.MAX_RISK_NOTE_CHARS_FOR_TEST, result.riskNotes.get(0).length());
        assertEquals(ContextNegotiationParser.MAX_PATCH_INTENT_CHARS_FOR_TEST, result.patchIntent.length());
    }

    @Test
    public void missingJsonThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ContextNegotiationParser.fromJson("not json"));

        assertEquals("Context negotiation response did not contain a JSON object.", error.getMessage());
    }

    @Test
    public void emptyPatchIntentThrowsClearError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ContextNegotiationParser.fromJson("{\"ready\":true,\"patchIntent\":\"   \"}"));

        assertEquals("Context negotiation patchIntent is empty.", error.getMessage());
    }

    private static String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationParserTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `ContextNegotiation` 或 `ContextNegotiationParser`。

- [ ] **步骤 3：Commit 失败测试**

```bash
git add app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java
git commit -m "test: cover context negotiation parsing"
```

## 任务 2：实现协商模型和解析器

**文件：**
- 创建：`app/src/main/java/com/androidbuilder/model/ContextNegotiation.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java`
- 测试：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java`

- [ ] **步骤 1：创建协商结果模型**

写入 `app/src/main/java/com/androidbuilder/model/ContextNegotiation.java`：

```java
package com.androidbuilder.model;

import java.util.Collections;
import java.util.List;

public class ContextNegotiation {
    public final boolean ready;
    public final List<String> neededFiles;
    public final List<String> focusTerms;
    public final List<String> riskNotes;
    public final String patchIntent;

    public ContextNegotiation(boolean ready, List<String> neededFiles, List<String> focusTerms, List<String> riskNotes, String patchIntent) {
        this.ready = ready;
        this.neededFiles = Collections.unmodifiableList(neededFiles);
        this.focusTerms = Collections.unmodifiableList(focusTerms);
        this.riskNotes = Collections.unmodifiableList(riskNotes);
        this.patchIntent = patchIntent == null ? "" : patchIntent;
    }
}
```

- [ ] **步骤 2：实现解析器**

写入 `app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java`：

```java
package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ContextNegotiationParser {
    private static final int MAX_ITEMS = 12;
    private static final int MAX_TERM_CHARS = 80;
    private static final int MAX_RISK_NOTE_CHARS = 240;
    private static final int MAX_PATCH_INTENT_CHARS = 2000;

    static final int MAX_ITEMS_FOR_TEST = MAX_ITEMS;
    static final int MAX_RISK_NOTE_CHARS_FOR_TEST = MAX_RISK_NOTE_CHARS;
    static final int MAX_PATCH_INTENT_CHARS_FOR_TEST = MAX_PATCH_INTENT_CHARS;

    private ContextNegotiationParser() {
    }

    public static ContextNegotiation fromJson(String raw) throws Exception {
        JSONObject json = new JSONObject(extractJson(raw));
        String patchIntent = cap(json.optString("patchIntent", "").trim(), MAX_PATCH_INTENT_CHARS);
        if (patchIntent.isEmpty()) {
            throw new IllegalArgumentException("Context negotiation patchIntent is empty.");
        }
        return new ContextNegotiation(
                json.optBoolean("ready", false),
                sourcePaths(json.optJSONArray("neededFiles")),
                shortStrings(json.optJSONArray("focusTerms"), MAX_TERM_CHARS),
                shortStrings(json.optJSONArray("riskNotes"), MAX_RISK_NOTE_CHARS),
                patchIntent);
    }

    private static List<String> sourcePaths(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < MAX_ITEMS; i++) {
            String raw = array.optString(i, "").trim();
            if (raw.isEmpty()) {
                continue;
            }
            try {
                String path = PathValidator.normalizeGeneratedPath(raw);
                if (AgentService.isTextSourceFile(path)) {
                    values.add(path);
                }
            } catch (IllegalArgumentException ignored) {
                // Unsafe model-requested paths are ignored, not fatal.
            }
        }
        return values;
    }

    private static List<String> shortStrings(JSONArray array, int maxChars) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < MAX_ITEMS; i++) {
            String value = cap(array.optString(i, "").trim(), maxChars);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Context negotiation response did not contain a JSON object.");
        }
        return text.substring(start, end + 1);
    }

    private static String cap(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
```

- [ ] **步骤 3：运行解析器测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationParserTest
```

预期：PASS。

- [ ] **步骤 4：Commit 模型和解析器**

```bash
git add app/src/main/java/com/androidbuilder/model/ContextNegotiation.java app/src/main/java/com/androidbuilder/agent/ContextNegotiationParser.java app/src/test/java/com/androidbuilder/agent/ContextNegotiationParserTest.java
git commit -m "feat: parse context negotiation responses"
```

## 任务 3：为 OpenAiClient 协商 prompt 和 retry context 写失败测试

**文件：**
- 修改：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`

- [ ] **步骤 1：添加协商 prompt 测试**

在 `OpenAiClientTest` 中追加：

```java
@Test
public void contextNegotiationPromptRequestsStructuredJson() {
    String system = OpenAiClient.contextNegotiationSystemPromptForTest(false);
    String user = OpenAiClient.contextNegotiationUserPromptForTest(
            "# Engineering Plan\nUpdate records",
            "Fix DAO mismatch",
            "Make CategoryDao constructor match callers",
            "--- app/src/main/java/com/example/CategoryDao.java ---\nclass CategoryDao {}",
            "- Add CSV export",
            "Generated source policy blocked constructor argument mismatch.",
            false);

    assertTrue(system.contains("Return only compact JSON"));
    assertTrue(system.contains("neededFiles"));
    assertTrue(system.contains("patchIntent"));
    assertTrue(user.contains("Previous failure summary"));
    assertTrue(user.contains("Fix DAO mismatch"));
    assertTrue(user.contains("Current source snapshot"));
}
```

- [ ] **步骤 2：添加最终 task operations prompt 测试**

在同一个测试类中追加：

```java
@Test
public void taskOperationsUserPromptIncludesRetryContext() {
    String prompt = OpenAiClient.taskOperationsUserPromptForTest(
            "# Engineering Plan\nUpdate DAO",
            "Fix DAO",
            "Synchronize constructor",
            "--- app/src/main/java/com/example/CategoryDao.java ---\nclass CategoryDao {}",
            "- Keep export screen",
            "This is a retry or repair of an existing source tree.\nDo not recreate the project.");

    assertTrue(prompt.contains("Recent user requirements and clarifications"));
    assertTrue(prompt.contains("Additional retry/repair context"));
    assertTrue(prompt.contains("Do not recreate the project"));
    assertTrue(prompt.contains("Execute exactly this task"));
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：FAIL，包含 `cannot find symbol`，指向新增的 `OpenAiClient.*ForTest` 方法。

- [ ] **步骤 4：Commit 失败测试**

```bash
git add app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java
git commit -m "test: cover context negotiation prompts"
```

## 任务 4：实现 OpenAiClient 协商 API 和可注入 retry context

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`
- 测试：`app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java`

- [ ] **步骤 1：重构 task operations user prompt builder**

在 `OpenAiClient` 中新增可测试 builder，并让现有 `createTaskOperations(...)` 委托到新重载：

```java
public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, boolean chinese) throws Exception {
    return createTaskOperations(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, "", chinese);
}

public String createTaskOperations(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext, boolean chinese) throws Exception {
    return completeChat(
            taskOperationsSystemPrompt(chinese),
            java.util.Collections.emptyList(),
            taskOperationsUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext),
            0.2,
            chinese,
            CODING_READ_TIMEOUT_MS);
}

static String taskOperationsUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext) {
    return taskOperationsUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, retryContext);
}

private static String taskOperationsUserPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String retryContext) {
    String requirementsSection = recentRequirements == null || recentRequirements.trim().isEmpty()
            ? ""
            : "\n\nRecent user requirements and clarifications (honor these even if the plan omits them):\n" + recentRequirements.trim();
    String retrySection = retryContext == null || retryContext.trim().isEmpty()
            ? ""
            : "\n\nAdditional retry/repair context:\n" + retryContext.trim();
    return "Approved engineering plan:\n\n" + plan
            + requirementsSection
            + retrySection
            + "\n\nCurrent source tree:\n" + sourceSnapshot
            + "\n\nExecute exactly this task:\nTitle: " + taskTitle
            + "\nInstruction: " + taskInstruction;
}
```

Remove the old inline prompt assembly inside the existing `createTaskOperations(...)` method so there is only one task-operation prompt path.

- [ ] **步骤 2：新增上下文协商 API**

在 `OpenAiClient` 中新增：

```java
public String negotiateTaskContext(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) throws Exception {
    return completeChat(
            contextNegotiationSystemPrompt(chinese),
            java.util.Collections.emptyList(),
            contextNegotiationUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, previousFailure, chinese),
            0.0,
            chinese,
            DEFAULT_READ_TIMEOUT_MS);
}

static String contextNegotiationSystemPromptForTest(boolean chinese) {
    return contextNegotiationSystemPromptText(chinese);
}

private String contextNegotiationSystemPrompt(boolean chinese) {
    return contextNegotiationSystemPromptText(chinese);
}

private static String contextNegotiationSystemPromptText(boolean chinese) {
    String language = chinese ? "Use Simplified Chinese for notes." : "Use English for notes.";
    return "You are the context negotiation step before Android code generation. "
            + "Do not write code and do not return file operations. "
            + "Decide whether the supplied source snapshot is enough for the next small patch. "
            + "Return only compact JSON with keys ready, neededFiles, focusTerms, riskNotes, and patchIntent. "
            + "neededFiles must contain relative POSIX paths only. patchIntent must be concise and must tell the coding model to modify existing files, not recreate the project. "
            + language;
}
```

- [ ] **步骤 3：新增协商 user prompt builder**

在 `OpenAiClient` 中新增：

```java
static String contextNegotiationUserPromptForTest(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) {
    return contextNegotiationUserPrompt(plan, taskTitle, taskInstruction, sourceSnapshot, recentRequirements, previousFailure, chinese);
}

private static String contextNegotiationUserPrompt(String plan, String taskTitle, String taskInstruction, String sourceSnapshot, String recentRequirements, String previousFailure, boolean chinese) {
    String requirements = recentRequirements == null || recentRequirements.trim().isEmpty()
            ? "(none)"
            : recentRequirements.trim();
    String failure = previousFailure == null || previousFailure.trim().isEmpty()
            ? "(none)"
            : truncatePrompt(previousFailure.trim(), 3000);
    String snapshot = truncatePrompt(sourceSnapshot, 12000);
    return "Approved engineering plan:\n" + plan
            + "\n\nTask title:\n" + taskTitle
            + "\n\nTask instruction:\n" + taskInstruction
            + "\n\nRecent user requirements:\n" + requirements
            + "\n\nPrevious failure summary:\n" + failure
            + "\n\nCurrent source snapshot:\n" + snapshot
            + "\n\nReturn JSON only. If more context is needed, name the exact source files or symbols. The patchIntent must say how to patch the existing source without recreating the project.";
}
```

- [ ] **步骤 4：运行 OpenAiClient 测试**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.OpenAiClientTest
```

预期：PASS。

- [ ] **步骤 5：Commit OpenAiClient 变更**

```bash
git add app/src/main/java/com/androidbuilder/agent/OpenAiClient.java app/src/test/java/com/androidbuilder/agent/OpenAiClientTest.java
git commit -m "feat: add context negotiation prompts"
```

## 任务 5：实现协商策略类

**文件：**
- 创建：`app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`
- 创建：`app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`

- [ ] **步骤 1：编写策略失败测试**

写入 `app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java`：

```java
package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextNegotiationPolicyTest {
    @Test
    public void negotiatesForFailedTaskRepairAndPolicyRetry() {
        assertFalse(ContextNegotiationPolicy.shouldNegotiate(false, 1, "", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(true, 1, "previous task failed", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(true, 1, "build log javac failed", ""));
        assertTrue(ContextNegotiationPolicy.shouldNegotiate(false, 2, "", "Generated source policy blocked missing method."));
    }

    @Test
    public void focusTextIncludesNeededFilesTermsAndFailure() {
        ContextNegotiation result = new ContextNegotiation(
                false,
                Arrays.asList("app/src/main/java/com/example/DBHelper.java"),
                Arrays.asList("CategoryDao"),
                Collections.singletonList("Keep DAO synchronized."),
                "Patch existing DAO only.");

        String focus = ContextNegotiationPolicy.focusText(result, "constructor argument mismatch");

        assertTrue(focus.contains("app/src/main/java/com/example/DBHelper.java"));
        assertTrue(focus.contains("CategoryDao"));
        assertTrue(focus.contains("constructor argument mismatch"));
    }

    @Test
    public void retryContextIncludesNoRecreateInstructionFailureAndPatchIntent() {
        ContextNegotiation result = new ContextNegotiation(
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("Keep DBHelper constants synchronized."),
                "Modify existing DBHelper and RecordDao only.");

        String context = ContextNegotiationPolicy.retryContext("missing method RecordDao.update", result);

        assertTrue(context.contains("Do not recreate the project"));
        assertTrue(context.contains("missing method RecordDao.update"));
        assertTrue(context.contains("Modify existing DBHelper"));
        assertTrue(context.contains("Keep DBHelper constants synchronized"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `ContextNegotiationPolicy`。

- [ ] **步骤 3：实现策略类**

写入 `app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java`：

```java
package com.androidbuilder.agent;

import com.androidbuilder.model.ContextNegotiation;

final class ContextNegotiationPolicy {
    static final int MAX_NEGOTIATION_ROUNDS = 2;

    private ContextNegotiationPolicy() {
    }

    static boolean shouldNegotiate(boolean retryLikeFlow, int attempt, String previousFailure, String policyError) {
        return (retryLikeFlow && hasText(previousFailure)) || attempt > 1 || hasText(policyError);
    }

    static String focusText(ContextNegotiation result, String failureText) {
        StringBuilder builder = new StringBuilder();
        if (failureText != null && !failureText.trim().isEmpty()) {
            builder.append(failureText.trim()).append('\n');
        }
        if (result != null) {
            for (String path : result.neededFiles) {
                builder.append(path).append('\n');
            }
            for (String term : result.focusTerms) {
                builder.append(term).append('\n');
            }
        }
        return builder.toString().trim();
    }

    static String retryContext(String previousFailure, ContextNegotiation result) {
        StringBuilder builder = new StringBuilder();
        builder.append("This is a retry or repair of an existing source tree.\n");
        builder.append("Do not recreate the project.\n");
        builder.append("Modify only the files needed for the current task.\n");
        builder.append("Use the shown source as authoritative.\n");
        builder.append("If a file was omitted, do not invent its API; rely only on shown files and the negotiated focus context.\n");
        if (hasText(previousFailure)) {
            builder.append("\nPrevious failure summary:\n").append(previousFailure.trim()).append('\n');
        }
        if (result != null) {
            if (hasText(result.patchIntent)) {
                builder.append("\nNegotiated patch intent:\n").append(result.patchIntent.trim()).append('\n');
            }
            if (!result.riskNotes.isEmpty()) {
                builder.append("\nNegotiated risk notes:\n");
                for (String note : result.riskNotes) {
                    builder.append("- ").append(note).append('\n');
                }
            }
        }
        return builder.toString().trim();
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
```

- [ ] **步骤 4：运行策略测试验证通过**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.ContextNegotiationPolicyTest
```

预期：PASS。

- [ ] **步骤 5：Commit 策略类**

```bash
git add app/src/main/java/com/androidbuilder/agent/ContextNegotiationPolicy.java app/src/test/java/com/androidbuilder/agent/ContextNegotiationPolicyTest.java
git commit -m "feat: add context negotiation policy"
```

## 任务 6：把 failed task、policy retry、build repair 接入 AgentService

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java`

- [ ] **步骤 1：为 AgentService 暴露协商轮数测试**

在 `AgentServiceRetryPolicyTest` 中追加：

```java
@Test
public void contextNegotiationHasBoundedRoundLimit() {
    assertTrue(AgentService.contextNegotiationMaxRoundsForTest() <= 2);
}
```

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：FAIL，包含 `cannot find symbol`，指向 `contextNegotiationMaxRoundsForTest`。

- [ ] **步骤 2：在 AgentService 增加测试访问器和失败摘要传递**

在 `AgentService` 中添加：

```java
static int contextNegotiationMaxRoundsForTest() {
    return ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS;
}
```

修改 `executePlan(...)` 中调用 `createAndApplyTaskOperations(...)` 的位置：

```java
String previousFailure = "failed".equals(runningTask.status) ? runningTask.resultSummary : "";
TaskOperations operations = createAndApplyTaskOperations(
        projectId,
        job.id,
        sourceDir,
        plan.content,
        runningTask.title,
        runningTask.instruction,
        snapshot,
        logs,
        chinese,
        previousFailure,
        "failed".equals(runningTask.status));
```

修改 `repairBuild(...)` 中调用 `createAndApplyTaskOperations(...)` 的位置：

```java
TaskOperations operations = createAndApplyTaskOperations(
        projectId,
        job.id,
        sourceDir,
        planContent,
        chinese ? "修复构建失败" : "Repair build failure",
        instruction,
        snapshot,
        logs,
        chinese,
        buildLog,
        true);
```

修改方法签名：

```java
private TaskOperations createAndApplyTaskOperations(long projectId, Long linkedBuildJobId, File sourceDir, String planContent, String taskTitle, String taskInstruction, String snapshot, File logs, boolean chinese, String previousFailureSummary, boolean retryLikeFlow) throws Exception {
```

- [ ] **步骤 3：新增协商执行 helper**

在 `AgentService` 中新增私有方法：

```java
private ContextNegotiation negotiateTaskContext(long projectId, Long linkedBuildJobId, String planContent, String taskTitle, String instruction, String snapshot, String recentRequirements, String failureText, boolean chinese, int round) {
    String title = (chinese ? "云端 AI · 上下文协商" : "Cloud AI · context negotiation") + " #" + round;
    String request = "Task title:\n" + taskTitle
            + "\n\nTask instruction:\n" + truncateForInlineLog(instruction, 12000)
            + "\n\nPrevious failure summary:\n" + truncateForInlineLog(failureText, 12000)
            + "\n\nCurrent source tree:\n" + truncateForInlineLog(snapshot, 24000);
    try {
        String raw = recordCloudAiCall(
                projectId,
                linkedBuildJobId,
                title,
                request,
                () -> openAiClient.negotiateTaskContext(planContent, taskTitle, instruction, snapshot, recentRequirements, failureText, chinese));
        return ContextNegotiationParser.fromJson(raw);
    } catch (Exception error) {
        try {
            recordAiConversationSafely(
                    projectId,
                    "cloud",
                    title + (chinese ? " · 解析失败" : " · parse failed"),
                    request,
                    localGuardErrorMessage(error),
                    "failed",
                    cloudAiMetadata(),
                    linkedBuildJobId);
        } catch (Exception ignored) {
        }
        return null;
    }
}
```

Use `recordAiConversationSafely(...)` directly even though it is currently used by `recordCloudAiCall`; it is already private inside `AgentService`, so this helper can call it.

- [ ] **步骤 4：在操作生成循环中接入协商**

在 `createAndApplyTaskOperations(...)` 的 retry loop 内，调用 `openAiClient.createTaskOperations(...)` 前添加：

```java
String policyFailure = lastPolicyError == null ? "" : localGuardErrorMessage(lastPolicyError);
String failureText = joinFailureText(previousFailureSummary, policyFailure);
String attemptSnapshot = snapshot;
ContextNegotiation negotiation = null;
if (ContextNegotiationPolicy.shouldNegotiate(retryLikeFlow, attempt, previousFailureSummary, policyFailure)) {
    for (int round = 1; round <= ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS; round++) {
        negotiation = negotiateTaskContext(projectId, linkedBuildJobId, planContent, taskTitle, instruction, attemptSnapshot, recentRequirements, failureText, chinese, round);
        if (negotiation == null) {
            break;
        }
        String focusText = ContextNegotiationPolicy.focusText(negotiation, failureText);
        if (!focusText.isEmpty()) {
            attemptSnapshot = sourceSnapshot(sourceDir, focusText);
        }
        if (negotiation.ready || round == ContextNegotiationPolicy.MAX_NEGOTIATION_ROUNDS) {
            break;
        }
    }
}
String retryContext = ContextNegotiationPolicy.retryContext(failureText, negotiation);
```

Then change the cloud generation call to pass `attemptSnapshot` and `retryContext`:

```java
String operationsJson = recordCloudAiCall(
        projectId,
        linkedBuildJobId,
        (chinese ? "云端 AI · 文件操作生成" : "Cloud AI · task operations") + " #" + attempt,
        requestLog,
        () -> openAiClient.createTaskOperations(planContent, taskTitle, attemptInstruction, attemptSnapshot, recentRequirements, retryContext, chinese));
```

Also change `requestLog` to log the actual `attemptSnapshot` and retry context:

```java
String requestLog = taskOperationsRequestForAiLog(planContent, taskTitle, instruction, attemptSnapshot, attempt, retryContext);
```

- [ ] **步骤 5：添加失败文本拼接和日志重载**

在 `AgentService` 中新增：

```java
private String joinFailureText(String previousFailureSummary, String policyFailure) {
    StringBuilder builder = new StringBuilder();
    if (ContextNegotiationPolicy.hasText(previousFailureSummary)) {
        builder.append(previousFailureSummary.trim());
    }
    if (ContextNegotiationPolicy.hasText(policyFailure)) {
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(policyFailure.trim());
    }
    return builder.toString();
}
```

Change `taskOperationsRequestForAiLog(...)` signature:

```java
private String taskOperationsRequestForAiLog(String planContent, String taskTitle, String instruction, String snapshot, int attempt, String retryContext) {
    String retrySection = retryContext == null || retryContext.trim().isEmpty()
            ? ""
            : "\n\nRetry/repair context:\n" + truncateForInlineLog(retryContext, 8000);
    return "Attempt: " + attempt
            + "\n\nApproved engineering plan:\n" + truncateForInlineLog(planContent, 12000)
            + "\n\nTask title:\n" + taskTitle
            + "\n\nTask instruction:\n" + instruction
            + retrySection
            + "\n\nCurrent source tree:\n" + truncateForInlineLog(snapshot, 24000);
}
```

- [ ] **步骤 6：运行 AgentService retry 测试**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.AgentServiceRetryPolicyTest
```

预期：PASS。

- [ ] **步骤 7：Commit AgentService 接线**

```bash
git add app/src/main/java/com/androidbuilder/agent/AgentService.java app/src/test/java/com/androidbuilder/agent/AgentServiceRetryPolicyTest.java
git commit -m "feat: negotiate context before retry generation"
```

## 任务 7：补强源码聚焦行为和 prompt 覆盖

**文件：**
- 修改：`app/src/main/java/com/androidbuilder/agent/AgentService.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/SourceSnapshotRelevanceTest.java`
- 修改：`app/src/test/java/com/androidbuilder/agent/TaskOperationsPromptPolicyTest.java`

- [ ] **步骤 1：为通用 source path 聚焦添加测试**

在 `SourceSnapshotRelevanceTest` 中追加：

```java
@Test
public void textSourceFileAcceptsGradleAndPropertiesPaths() {
    assertTrue(AgentService.isTextSourceFile("settings.gradle"));
    assertTrue(AgentService.isTextSourceFile("app/build.gradle"));
    assertTrue(AgentService.isTextSourceFile("gradle.properties"));
}
```

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.SourceSnapshotRelevanceTest
```

预期：当前很可能 PASS；如果已经 PASS，这一步作为防回归测试保留。

- [ ] **步骤 2：让 focused source path 支持 Gradle/properties/json/pro 文件**

修改 `AgentService.appendFocusedSourceFiles(...)` 中 `pathMatcher` 的 pattern，从只匹配 `app/src/...` 扩展为安全相对文本源码路径：

```java
Matcher pathMatcher = Pattern.compile("\\b([A-Za-z0-9_./-]+\\.(?:kt|java|xml|gradle|kts|properties|json|pro))\\b").matcher(focusText);
while (pathMatcher.find() && snapshot.length() <= SOURCE_SNAPSHOT_LIMIT) {
    String rawPath = pathMatcher.group(1);
    try {
        String path = PathValidator.normalizeGeneratedPath(rawPath);
        File file = new File(root, path);
        appendSourceFile(root, file, snapshot, seen, true);
    } catch (IllegalArgumentException ignored) {
    }
}
```

Keep the existing bare filename/type matching below this block.

- [ ] **步骤 3：为 no-recreate prompt 添加断言**

在 `TaskOperationsPromptPolicyTest` 中追加：

```java
@Test
public void retryContextCanTellModelNotToRecreateProject() {
    String prompt = OpenAiClient.taskOperationsUserPromptForTest(
            "# Engineering Plan\nPatch existing app",
            "Fix build",
            "Repair missing resource",
            "--- app/src/main/res/values/colors.xml ---\n<resources/>",
            "",
            "This is a retry or repair of an existing source tree.\nDo not recreate the project.");

    assertTrue(prompt.contains("Do not recreate the project"));
    assertTrue(prompt.contains("Additional retry/repair context"));
}
```

- [ ] **步骤 4：运行聚焦测试**

运行：

```bash
./gradlew testDebugUnitTest --tests com.androidbuilder.agent.SourceSnapshotRelevanceTest --tests com.androidbuilder.agent.TaskOperationsPromptPolicyTest
```

预期：PASS。

- [ ] **步骤 5：Commit 聚焦和 prompt 覆盖**

```bash
git add app/src/main/java/com/androidbuilder/agent/AgentService.java app/src/test/java/com/androidbuilder/agent/SourceSnapshotRelevanceTest.java app/src/test/java/com/androidbuilder/agent/TaskOperationsPromptPolicyTest.java
git commit -m "test: cover retry context focus prompts"
```

## 任务 8：更新项目文档并运行最终验证

**文件：**
- 可选修改：`docs/ai-context/02-业务流程.md`
- 验证：Gradle unit tests

- [ ] **步骤 1：补充业务流程文档**

在 `docs/ai-context/02-业务流程.md` 的“文件操作应用和策略重写”小节中补充：

```markdown
### 4.1 后台上下文协商

在 failed task 重跑、构建修复、或本地策略拒绝后的重试中，`AgentService` 会先调用云端模型做一次后台上下文协商。协商只返回 JSON，不写源码，内容包括是否已具备足够上下文、需要聚焦的文件/符号、风险提示和 patch intent。

如果协商点名了文件或符号，App 会重新生成 focused `sourceSnapshot()`，再把失败摘要和 patch intent 拼入最终 `createTaskOperations()` prompt。协商最多两轮；协商失败不会阻塞代码生成，只会回退到原有生成路径。每轮协商都写入 AI conversation 日志。
```

- [ ] **步骤 2：运行全部相关 agent 测试**

运行：

```bash
./gradlew testDebugUnitTest --tests "com.androidbuilder.agent.*"
```

预期：PASS。

- [ ] **步骤 3：运行完整单元测试**

运行：

```bash
./gradlew testDebugUnitTest
```

预期：PASS。

- [ ] **步骤 4：运行构建验证**

运行：

```bash
./gradlew assembleDebug --stacktrace
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit 文档和最终验证点**

如果第 1 步修改了文档：

```bash
git add docs/ai-context/02-业务流程.md
git commit -m "docs: document background context negotiation"
```

如果第 1 步没有修改文档，只在最终回复中报告验证命令和结果，不创建空提交。

## 实施注意事项

- 开始每个任务前先运行 `git status --short`。当前工作区可能已有用户改动，禁止回滚未参与的文件。
- 如果 `AgentService.java` 或 `OpenAiClient.java` 已有未提交改动，先阅读相关 diff，再把本计划的变更叠加进去。
- 所有新增云端调用都必须走 `recordCloudAiCall(...)` 或 `recordAiConversationSafely(...)`，确保后台自动协商有日志。
- 协商失败必须 fallback，不能让用户卡在后台流程里。
- 不要改变 `FileOperationsWriter` 的最终校验权威地位。
