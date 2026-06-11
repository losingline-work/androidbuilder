package com.androidbuilder.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesRepairShardingPolicyTest {
    @Test
    public void splitsIndependentMissingResourceAndJavaFileFailures() {
        String log = "app/src/main/res/layout/activity_main.xml: error: resource color/primary not found\n"
                + "app/src/main/java/com/example/RecordDao.java:12: error: cannot find symbol\n";

        List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

        assertEquals(2, shards.size());
        assertEquals("app/src/main/res/layout/activity_main.xml", shards.get(0).focusPath);
        assertEquals("app/src/main/java/com/example/RecordDao.java", shards.get(1).focusPath);
        assertFalse(shards.get(0).exclusive);
        assertFalse(shards.get(1).exclusive);
    }

    @Test
    public void keepsConstructorApiMismatchAsSingleShard() {
        String log = "constructor RecordDao in class RecordDao cannot be applied to given types";

        List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

        assertEquals(1, shards.size());
        assertTrue(shards.get(0).exclusive);
    }

    @Test
    public void keepsGradleDependencyFailureAsSingleExclusiveShard() {
        String log = "Could not find com.example:missing:1.0.0.\nSearched in the following locations:";

        List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

        assertEquals(1, shards.size());
        assertTrue(shards.get(0).exclusive);
    }

    @Test
    public void keepsManifestMergeFailureAsSingleExclusiveShard() {
        String log = "Manifest merger failed : Attribute application@appComponentFactory value=(androidx.core.app.CoreComponentFactory)";

        List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

        assertEquals(1, shards.size());
        assertTrue(shards.get(0).exclusive);
    }

    @Test
    public void keepsUnparsedPathFailureAsSingleExclusiveShard() {
        String log = "error: resource color/primary not found";

        List<HermesRepairShard> shards = HermesRepairShardingPolicy.shards(log);

        assertEquals(1, shards.size());
        assertEquals("", shards.get(0).focusPath);
        assertTrue(shards.get(0).exclusive);
    }

    @Test
    public void shardCleansNullableTextFields() {
        HermesRepairShard shard = new HermesRepairShard(null, null, false);

        assertEquals("", shard.focusPath);
        assertEquals("", shard.logExcerpt);
        assertFalse(shard.exclusive);
    }
}
