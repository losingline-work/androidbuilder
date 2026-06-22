package com.androidbuilder.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CrashSummaryPolicyTest {
    @Test
    public void picksTheExceptionLineOverStackFrames() {
        String crash = "FATAL EXCEPTION: main\n"
                + "Process: com.generated.d, PID: 1234\n"
                + "java.lang.NullPointerException: Attempt to invoke virtual method 'void android.widget.TextView.setText'\n"
                + "\tat com.generated.d.MainActivity.onCreate(MainActivity.java:42)\n"
                + "\tat android.app.Activity.performCreate(Activity.java:8000)\n";
        String excerpt = CrashSummaryPolicy.excerpt(crash);
        assertTrue(excerpt.contains("NullPointerException"));
        assertTrue(excerpt.startsWith("java.lang.NullPointerException"));
        assertTrue(!excerpt.contains("\tat "));
    }

    @Test
    public void stripsFatalExceptionFramingWhenItIsTheOnlyHeadline() {
        // No standalone exception line; the FATAL EXCEPTION framing is stripped to its payload.
        String excerpt = CrashSummaryPolicy.excerpt("FATAL EXCEPTION: SomethingBroke\n\tat x.y.Z.a(Z.java:1)");
        assertEquals("SomethingBroke", excerpt);
    }

    @Test
    public void emptyForBlankInput() {
        assertEquals("", CrashSummaryPolicy.excerpt(""));
        assertEquals("", CrashSummaryPolicy.excerpt(null));
    }
}
