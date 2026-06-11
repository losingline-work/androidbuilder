package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ImplementationTaskParserTest {
    @Test
    public void fromJson_acceptsTaskList() throws Exception {
        List<ProjectTaskRecord> tasks = ImplementationTaskParser.fromJson("{\"tasks\":[" +
                "{\"title\":\"Create skeleton\",\"instruction\":\"Write Gradle files\"}," +
                "{\"title\":\"Add main screen\",\"instruction\":\"Write activity and layout\"}" +
                "]}");

        assertEquals(2, tasks.size());
        assertEquals("Create skeleton", tasks.get(0).title);
        assertEquals("Write activity and layout", tasks.get(1).instruction);
        assertEquals(0, tasks.get(0).sortOrder);
    }

    @Test
    public void fromJson_repairsBareQuotesInsideInstructionStrings() throws Exception {
        String raw = "{\n"
                + "  \"tasks\": [\n"
                + "    {\n"
                + "      \"title\": \"初始化 Gradle 项目骨架与依赖\",\n"
                + "      \"instruction\": \"创建或更新 `app/build.gradle`：设置 `applicationId \"com.generated.app\"`、`versionName \"1.0.0\"`，并加入 `maven { url 'https://jitpack.io' }` 仓库。\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"title\": \"实现新增/编辑记录与分类选择\",\n"
                + "      \"instruction\": \"新建 `activity_record_edit.xml`：金额输入（`inputType=\"numberDecimal\"` 并限制两位小数）。\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        List<ProjectTaskRecord> tasks = ImplementationTaskParser.fromJson(raw);

        assertEquals(2, tasks.size());
        assertEquals("初始化 Gradle 项目骨架与依赖", tasks.get(0).title);
        assertEquals("创建或更新 `app/build.gradle`：设置 `applicationId \"com.generated.app\"`、`versionName \"1.0.0\"`，并加入 `maven { url 'https://jitpack.io' }` 仓库。", tasks.get(0).instruction);
        assertEquals("新建 `activity_record_edit.xml`：金额输入（`inputType=\"numberDecimal\"` 并限制两位小数）。", tasks.get(1).instruction);
    }

    @Test
    public void fromJson_preservesHermesTaskContractSignals() throws Exception {
        String raw = "{"
                + "\"tasks\":[{"
                + "\"title\":\"Write layout\","
                + "\"instruction\":\"Create activity_main.xml.\","
                + "\"allowedPaths\":[\"app/src/main/res/layout/activity_main.xml\"],"
                + "\"expectedFiles\":[\"app/src/main/res/layout/activity_main.xml\"],"
                + "\"acceptanceChecks\":[\"Layout references existing IDs\"],"
                + "\"riskLevel\":\"medium\","
                + "\"buildRequiredAfter\":true"
                + "}]}";

        List<ProjectTaskRecord> tasks = ImplementationTaskParser.fromJson(raw);
        HermesTaskContract contract = HermesTaskContractCodec.extractFromInstruction(tasks.get(0).instruction);

        assertTrue(tasks.get(0).instruction.contains(HermesTaskContractCodec.START));
        assertEquals("medium", contract.riskLevel);
        assertTrue(contract.buildRequiredAfter);
        assertEquals("app/src/main/res/layout/activity_main.xml", contract.allowedPaths.get(0));
        assertEquals("Layout references existing IDs", contract.acceptanceChecks.get(0));
    }

    @Test
    public void fromJson_rejectsEmptyTaskList() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ImplementationTaskParser.fromJson("{\"tasks\":[]}"));

        assertEquals("Implementation task list is empty.", error.getMessage());
    }
}
