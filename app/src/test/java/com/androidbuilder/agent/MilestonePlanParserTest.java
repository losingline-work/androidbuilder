package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MilestonePlanParserTest {
    @Test
    public void fromJson_acceptsOrderedMilestones() throws Exception {
        List<ProjectMilestoneRecord> milestones = MilestonePlanParser.fromJson("{\"milestones\":[" +
                "{\"order\":0,\"title\":\"Runnable skeleton\",\"description\":\"Gradle + launcher + empty home\",\"slice\":\"skeleton\"}," +
                "{\"order\":1,\"title\":\"Record a bill\",\"description\":\"Add-bill screen + DAO\",\"slice\":\"add bill flow\"}" +
                "]}");

        assertEquals(2, milestones.size());
        assertEquals("Runnable skeleton", milestones.get(0).title);
        assertEquals(0, milestones.get(0).orderIndex);
        assertEquals("add bill flow", milestones.get(1).slice);
        assertEquals(1, milestones.get(1).orderIndex);
        assertEquals(MilestoneStatus.PENDING, milestones.get(0).status);
    }

    @Test
    public void fromJson_sliceDefaultsToDescriptionWhenOmitted() throws Exception {
        List<ProjectMilestoneRecord> milestones = MilestonePlanParser.fromJson(
                "{\"milestones\":[{\"title\":\"Skeleton\",\"description\":\"the empty runnable app\"}]}");

        assertEquals("the empty runnable app", milestones.get(0).slice);
    }

    @Test
    public void fromJson_indexFromPositionNotModelOrderField() throws Exception {
        // The model numbered them out of order; positional index must win so the list is always contiguous.
        List<ProjectMilestoneRecord> milestones = MilestonePlanParser.fromJson("{\"milestones\":[" +
                "{\"order\":7,\"title\":\"A\",\"description\":\"first listed\"}," +
                "{\"order\":3,\"title\":\"B\",\"description\":\"second listed\"}" +
                "]}");

        assertEquals(0, milestones.get(0).orderIndex);
        assertEquals(1, milestones.get(1).orderIndex);
        assertEquals("A", milestones.get(0).title);
    }

    @Test
    public void fromJson_extractsJsonFromSurroundingProse() throws Exception {
        List<ProjectMilestoneRecord> milestones = MilestonePlanParser.fromJson(
                "Sure! Here is the plan:\n{\"milestones\":[{\"title\":\"S\",\"description\":\"d\"}]}\nHope that helps.");

        assertEquals(1, milestones.size());
        assertEquals("S", milestones.get(0).title);
    }

    @Test
    public void fromJson_repairsBareQuotesInsideStrings() throws Exception {
        String raw = "{\n"
                + "  \"milestones\": [\n"
                + "    {\n"
                + "      \"title\": \"骨架\",\n"
                + "      \"description\": \"设置 `applicationId \"com.generated.app\"` 的可运行骨架\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        List<ProjectMilestoneRecord> milestones = MilestonePlanParser.fromJson(raw);

        assertEquals(1, milestones.size());
        assertTrue(milestones.get(0).description.contains("com.generated.app"));
    }

    @Test
    public void fromJson_rejectsEmptyMilestoneList() {
        assertThrows(IllegalArgumentException.class,
                () -> MilestonePlanParser.fromJson("{\"milestones\":[]}"));
    }

    @Test
    public void fromJson_rejectsMissingTitleOrDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> MilestonePlanParser.fromJson("{\"milestones\":[{\"title\":\"only title\"}]}"));
        assertThrows(IllegalArgumentException.class,
                () -> MilestonePlanParser.fromJson("{\"milestones\":[{\"description\":\"only desc\"}]}"));
    }

    @Test
    public void fromJson_rejectsNonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> MilestonePlanParser.fromJson("no json here"));
    }
}
