package com.androidbuilder.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class FunnelPolicyTest {

    private static Map<String, Integer> outcomes() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("fenced_ok", 7);
        m.put("json_salvaged", 2);
        m.put("parse_failed", 1);
        m.put("success", 5); // a non-parse cloud status that must be ignored
        return m;
    }

    @Test
    public void reachedStagesAreTickedAndUnreachedAreNot() {
        String text = FunnelPolicy.summary(true, 4, 2, false, false, outcomes(), false);
        // plan created + M0 green + (2 of 4) milestones-not-all-green + apk not built + not installed
        assertTrue(text.contains("✓ Plan created"));
        assertTrue(text.contains("✓ Skeleton M0 green"));
        assertTrue(text.contains("○ All milestones green 2/4"));
        assertTrue(text.contains("○ APK built"));
        assertTrue(text.contains("○ Installed"));
    }

    @Test
    public void allGreenAndInstalledTicksEveryStage() {
        String text = FunnelPolicy.summary(true, 3, 3, true, true, outcomes(), false);
        assertTrue(text.contains("✓ All milestones green 3/3"));
        assertTrue(text.contains("✓ APK built"));
        assertTrue(text.contains("✓ Installed"));
    }

    @Test
    public void m0NotGreenWhenNoMilestoneCheckpointedYet() {
        String text = FunnelPolicy.summary(true, 5, 0, false, false, outcomes(), false);
        assertTrue(text.contains("○ Skeleton M0 green"));
    }

    @Test
    public void outcomeMixSharesSumOverParseOutcomesOnly() {
        // 7 + 2 + 1 = 10 parse outcomes (the "success" row is excluded), so fenced_ok is 70%.
        String mix = FunnelPolicy.outcomeMix(outcomes(), false);
        assertTrue(mix.contains("fenced_ok: 7 (70%)"));
        assertTrue(mix.contains("json_salvaged: 2 (20%)"));
        assertTrue(mix.contains("parse_failed: 1 (10%)"));
        assertFalse("non-parse statuses are excluded", mix.contains("success"));
    }

    @Test
    public void emptyOrIrrelevantOutcomesProduceNoMixSection() {
        assertTrue(FunnelPolicy.outcomeMix(new LinkedHashMap<>(), false).isEmpty());
        LinkedHashMap<String, Integer> onlyOther = new LinkedHashMap<>();
        onlyOther.put("success", 3);
        onlyOther.put("failed", 1);
        assertTrue(FunnelPolicy.outcomeMix(onlyOther, false).isEmpty());
    }

    @Test
    public void chineseLabelsRender() {
        String text = FunnelPolicy.summary(true, 2, 1, true, false, outcomes(), true);
        assertTrue(text.contains("计划已创建"));
        assertTrue(text.contains("截断救回"));
        assertTrue(text.contains("解析失败"));
    }
}
