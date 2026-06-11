package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesTaskSchedulerTest {
    @Test
    public void highRiskContractRequiresContextScoutBeforeCoding() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Collections.singletonList("app/src/main/java/com/example/RecordDao.java"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList("DAO callers must stay synchronized."),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "high",
                false);

        HermesTaskDecision decision = HermesTaskScheduler.decide(contract, "", 0, false);

        assertEquals(HermesTaskDecision.Action.CODE, decision.action);
        assertTrue(decision.requiresContextScout);
        assertFalse(decision.requiresBuildAfter);
        assertEquals("normal", decision.retryMode);
    }

    @Test
    public void buildRequiredAfterContractRequestsBuildAfterTask() {
        HermesTaskContract contract = new HermesTaskContract(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "medium",
                true);

        HermesTaskDecision decision = HermesTaskScheduler.decide(contract, "", 0, false);

        assertEquals(HermesTaskDecision.Action.CODE, decision.action);
        assertFalse(decision.requiresContextScout);
        assertTrue(decision.requiresBuildAfter);
    }

    @Test
    public void repeatedTaskFailureNarrowsRetryScope() {
        HermesTaskDecision decision = HermesTaskScheduler.decide(
                HermesTaskContract.empty(),
                "Generated source policy blocked missing XML resource reference.",
                2,
                false);

        assertEquals("narrow_scope", decision.retryMode);
        assertTrue(decision.reason.contains("Repeated task failure"));
    }
}
