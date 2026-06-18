package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogcatCrashExtractorTest {
    private static final String PKG = "com.generated.app";

    private static final String LOGCAT_THEME_CRASH =
            "06-18 13:18:00.100  1234  1234 I ActivityManager: Start proc com.generated.app\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: Process: com.generated.app, PID: 1234\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: \tat androidx.appcompat.app.AppCompatDelegateImpl.createSubDecor(AppCompatDelegateImpl.java:837)\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: \tat com.generated.app.MainActivity.onCreate(MainActivity.java:42)\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: \tat android.app.Activity.performCreate(Activity.java:8000)\n"
                    + "06-18 13:18:00.123  1234  1234 E AndroidRuntime: \tat android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3500)\n"
                    + "06-18 13:18:00.200  1234  1234 I Process: Sending signal. PID: 1234 SIG: 9\n";

    private static final String RAW_NPE =
            "java.lang.NullPointerException: Attempt to read field 'id' on a null object reference\n"
                    + "\tat com.generated.app.fragment.HomeFragment.onViewCreated(HomeFragment.java:55)\n"
                    + "\tat androidx.fragment.app.Fragment.performViewCreated(Fragment.java:3120)\n"
                    + "\tat android.app.ActivityThread.main(ActivityThread.java:7000)\n";

    @Test
    public void parsesLogcatThemeCrash() {
        String diagnostics = LogcatCrashExtractor.crashDiagnostics(LOGCAT_THEME_CRASH, PKG, 4000);
        assertTrue(diagnostics.contains("IllegalStateException"));
        assertTrue(diagnostics.contains("Theme.AppCompat"));
        // app frame kept + the throw-site framework frame directly above it kept...
        assertTrue(diagnostics.contains("com.generated.app.MainActivity.onCreate"));
        assertTrue(diagnostics.contains("AppCompatDelegateImpl"));
        // ...but the android.* plumbing dropped.
        assertFalse(diagnostics.contains("ActivityThread"));
        assertFalse(diagnostics.contains("performCreate"));
    }

    @Test
    public void signatureIsStableAndStripsLineNumbers() {
        String sig = LogcatCrashExtractor.crashSignature(LOGCAT_THEME_CRASH, PKG);
        assertEquals("java.lang.IllegalStateException@com.generated.app.MainActivity.onCreate", sig);
        // Re-running with churned line numbers yields the SAME signature (so the stall policy sees no change).
        String churned = LOGCAT_THEME_CRASH.replace("MainActivity.java:42", "MainActivity.java:48");
        assertEquals(sig, LogcatCrashExtractor.crashSignature(churned, PKG));
    }

    @Test
    public void parsesRawPrintStackTraceWithoutLogcatPrefix() {
        String diagnostics = LogcatCrashExtractor.crashDiagnostics(RAW_NPE, PKG, 4000);
        assertTrue(diagnostics.contains("NullPointerException"));
        assertTrue(diagnostics.contains("com.generated.app.fragment.HomeFragment.onViewCreated"));
        assertEquals("java.lang.NullPointerException@com.generated.app.fragment.HomeFragment.onViewCreated",
                LogcatCrashExtractor.crashSignature(RAW_NPE, PKG));
    }

    @Test
    public void emptyWhenNoCrashBlock() {
        assertEquals("", LogcatCrashExtractor.crashSignature("06-18 13:18 I ActivityManager: all good\n", PKG));
        assertEquals("", LogcatCrashExtractor.crashDiagnostics("BUILD SUCCESSFUL", PKG, 4000));
        // A stray exception-shaped log line WITHOUT a stack frame is not a crash.
        assertEquals("", LogcatCrashExtractor.crashSignature("W/X: caught FooException: ignored and recovered\n", PKG));
    }

    @Test
    public void fatalExceptionIsolatesTheBlock() {
        String block = LogcatCrashExtractor.fatalException(LOGCAT_THEME_CRASH, 4000);
        assertTrue(block.startsWith("java.lang.IllegalStateException"));
        assertFalse(block.contains("ActivityManager"));
        assertFalse(block.contains("Sending signal"));
    }
}
