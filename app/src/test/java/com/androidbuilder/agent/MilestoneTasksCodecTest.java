package com.androidbuilder.agent;

import com.androidbuilder.model.MilestoneTaskSnapshot;
import com.androidbuilder.model.ProjectTaskRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MilestoneTasksCodecTest {
    private ProjectTaskRecord task(String title, String status) {
        return new ProjectTaskRecord(0, 0, 0, title, "", status, "", 0, 0, 0, 0);
    }

    @Test
    public void encodeThenDecodeRoundTripsTitleAndStatus() {
        List<ProjectTaskRecord> tasks = Arrays.asList(task("数据层", "done"), task("记账页", "failed"));
        String json = MilestoneTasksCodec.encode(tasks);

        List<MilestoneTaskSnapshot> decoded = MilestoneTasksCodec.decode(json);
        assertEquals(2, decoded.size());
        assertEquals("数据层", decoded.get(0).title);
        assertEquals("done", decoded.get(0).status);
        assertEquals("记账页", decoded.get(1).title);
        assertEquals("failed", decoded.get(1).status);
    }

    @Test
    public void decodeHandlesEmptyAndMalformed() {
        assertTrue(MilestoneTasksCodec.decode(null).isEmpty());
        assertTrue(MilestoneTasksCodec.decode("").isEmpty());
        assertTrue(MilestoneTasksCodec.decode("not json").isEmpty());
        assertTrue(MilestoneTasksCodec.decode(MilestoneTasksCodec.encode(null)).isEmpty());
    }
}
