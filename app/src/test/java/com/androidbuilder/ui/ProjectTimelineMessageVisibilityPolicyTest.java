package com.androidbuilder.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProjectTimelineMessageVisibilityPolicyTest {
    @Test
    public void hidesBuildStartedChatter() {
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Embedded build started."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Termux build started."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "已启动内置构建。"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "已启动 Termux 构建。"));
    }

    @Test
    public void hidesBuildSuccessSummary() {
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Build complete: success. APK is ready."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "构建完成：成功，APK 已生成。"));
    }

    @Test
    public void hidesExecuteAndDoneAndSplitChatter() {
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Executing next step: Build login screen"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Executing next parallel batch: Build login screen, Wire settings"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "执行下一步：搭建登录页"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "并行执行下一批：搭建登录页、接入设置页"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Done: Build login screen. Continue with the next step."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "已完成：搭建登录页。可以继续执行下一步。"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "All plan tasks are done. Next, build the project."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "所有计划任务已完成。下一步可以构建。"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Split into implementation tasks:\n1. A\n2. B"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "已拆分为执行任务：\n1. A\n2. B"));
    }

    @Test
    public void keepsUserMessages() {
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("user", "Executing next step: anything the user typed"));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("user", "Build complete: success"));
    }

    @Test
    public void hidesRepairStatusChatter() {
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Build repair complete: rewired adapter binding"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "已完成构建修复：修正了适配器绑定"));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Repairing the current source from the build log."));
        assertTrue(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "正在根据构建日志修复当前源码。"));
    }

    @Test
    public void keepsRepairFailuresAndBuildFailures() {
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Repair failed: model returned no operations"));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "修复失败：模型没有返回操作"));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Build complete: failed. Gradle task assembleDebug FAILED"));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "构建完成：失败。Gradle 任务失败"));
    }

    @Test
    public void keepsSubstantiveContent() {
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Generated source for MyApp. Tap Build to start the build."));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "# Engineering Plan\n\n- screen A\n- screen B"));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", "Heads up: this app needs network access for dependencies."));
        assertFalse(ProjectTimelineMessageVisibilityPolicy.isChatter("assistant", null));
    }
}
