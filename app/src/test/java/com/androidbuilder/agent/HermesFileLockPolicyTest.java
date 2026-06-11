package com.androidbuilder.agent;

import com.androidbuilder.model.HermesTaskContract;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HermesFileLockPolicyTest {
    @Test
    public void allowedPathsBecomeLocks() throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(
                "{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"]}"));

        List<String> locks = HermesFileLockPolicy.locksFor("Update DAO", "Do task.", contract);

        assertEquals(Collections.singletonList("app/src/main/java/com/example/RecordDao.java"), locks);
    }

    @Test
    public void allowedPathsTakePriorityOverExpectedFiles() throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(
                "{\"allowedPaths\":[\"app/src/main/java/com/example/RecordDao.java\"],"
                        + "\"expectedFiles\":[\"app/src/main/res/layout/activity_main.xml\"]}"));

        List<String> locks = HermesFileLockPolicy.locksFor("Update DAO", "Do task.", contract);

        assertEquals(Collections.singletonList("app/src/main/java/com/example/RecordDao.java"), locks);
    }

    @Test
    public void expectedFilesBecomeLocksWhenAllowedPathsAreAbsent() throws Exception {
        HermesTaskContract contract = HermesTaskContractCodec.fromJson(new JSONObject(
                "{\"expectedFiles\":[\"app/src/main/res/layout/activity_main.xml\"]}"));

        List<String> locks = HermesFileLockPolicy.locksFor("Update layout", "Do task.", contract);

        assertEquals(Collections.singletonList("app/src/main/res/layout/activity_main.xml"), locks);
    }

    @Test
    public void gradleTaskLocksBuildFilesExclusively() {
        List<String> locks = HermesFileLockPolicy.locksFor("Update Gradle", "Change app/build.gradle", HermesTaskContract.empty());

        assertTrue(locks.contains("settings.gradle"));
        assertTrue(locks.contains("build.gradle"));
        assertTrue(locks.contains("app/build.gradle"));
        assertTrue(HermesFileLockPolicy.isExclusiveBarrier("Update Gradle", "Change app/build.gradle", HermesTaskContract.empty()));
    }

    @Test
    public void manifestTaskLocksManifestExclusively() {
        List<String> locks = HermesFileLockPolicy.locksFor("Update manifest", "Change AndroidManifest.xml", HermesTaskContract.empty());

        assertEquals(Collections.singletonList("app/src/main/AndroidManifest.xml"), locks);
        assertTrue(HermesFileLockPolicy.isExclusiveBarrier("Update manifest", "Change AndroidManifest.xml", HermesTaskContract.empty()));
    }

    @Test
    public void taskWithoutKnownPathsUsesWildcardLock() {
        List<String> locks = HermesFileLockPolicy.locksFor("Update feature", "Implement the feature.", HermesTaskContract.empty());

        assertEquals(Collections.singletonList("*"), locks);
    }

    @Test
    public void wildcardAndOverlappingLocksConflict() {
        assertTrue(HermesFileLockPolicy.conflicts(
                Collections.singletonList("*"),
                Collections.singletonList("app/src/main/res/layout/activity_main.xml")));
        assertTrue(HermesFileLockPolicy.conflicts(
                Collections.singletonList("app/src/main/res/values/*"),
                Collections.singletonList("app/src/main/res/values/strings.xml")));
        assertTrue(HermesFileLockPolicy.conflicts(
                Collections.singletonList("app/src/main/res/values/strings.xml"),
                Collections.singletonList("app/src/main/res/values/strings.xml")));
        assertFalse(HermesFileLockPolicy.conflicts(
                Collections.singletonList("app/src/main/java/com/example/RecordDao.java"),
                Arrays.asList("app/src/main/res/layout/activity_main.xml")));
    }
}
