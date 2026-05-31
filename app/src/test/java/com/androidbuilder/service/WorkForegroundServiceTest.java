package com.androidbuilder.service;

import com.androidbuilder.ui.MainActivity;
import com.androidbuilder.ui.ProjectActivity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WorkForegroundServiceTest {
    @Test
    public void validProjectNotificationReturnsToProjectPage() {
        assertEquals(ProjectActivity.class, WorkForegroundService.notificationTargetActivity(42));
    }

    @Test
    public void invalidProjectNotificationFallsBackToProjectList() {
        assertEquals(MainActivity.class, WorkForegroundService.notificationTargetActivity(-1));
    }
}
