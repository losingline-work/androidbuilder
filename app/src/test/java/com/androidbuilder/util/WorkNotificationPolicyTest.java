package com.androidbuilder.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkNotificationPolicyTest {
    @Test
    public void suppressesNotificationWhileAppIsForeground() {
        assertFalse(WorkNotificationPolicy.shouldShowNotification(true));
    }

    @Test
    public void showsNotificationWhenAppIsBackground() {
        assertTrue(WorkNotificationPolicy.shouldShowNotification(false));
    }
}
