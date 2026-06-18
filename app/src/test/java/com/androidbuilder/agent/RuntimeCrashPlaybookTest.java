package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeCrashPlaybookTest {
    @Test
    public void framesTheCrashAsTheAuthoritativeSignal() {
        String instruction = RuntimeCrashPlaybook.instruction(
                "java.lang.NullPointerException\n at com.app.X.onCreate(X.java:1)", false, false);
        assertTrue(instruction.contains("CRASHES ON LAUNCH"));
        assertTrue(instruction.contains("stack trace is the authority"));
        // The crash payload is carried into the instruction.
        assertTrue(instruction.contains("com.app.X.onCreate"));
    }

    @Test
    public void routesThemeCrashToTheThemeFix() {
        String instruction = RuntimeCrashPlaybook.instruction(
                "java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity",
                false, false);
        assertTrue(instruction.contains("Theme mismatch"));
        assertTrue(instruction.contains("Theme.AppCompat/Theme.Material3"));
        // Unrelated playbook hints are not emitted.
        assertFalse(instruction.contains("findViewById"));
        assertFalse(instruction.contains("ActivityNotFoundException"));
    }

    @Test
    public void routesComplexColorCrashToColorAttributeHint() {
        String instruction = RuntimeCrashPlaybook.instruction(
                "android.view.InflateException: ... Error inflating class androidx.cardview.widget.CardView\n"
                        + "Caused by: java.lang.UnsupportedOperationException: Can't convert to ComplexColor: type=0x1",
                false, false);
        assertTrue(instruction.contains("COLOR attribute points at a non-color"));
        assertTrue(instruction.contains("cardBackgroundColor"));
        assertTrue(instruction.contains("android:background for a drawable"));
    }

    @Test
    public void routesClassCastToFindViewByIdMismatchHint() {
        String instruction = RuntimeCrashPlaybook.instruction(
                "java.lang.ClassCastException: com.google.android.material.textview.MaterialTextView cannot be cast to android.widget.RadioButton\n"
                        + " at com.generated.app.ui.add.AddTransactionFragment.onCreateView(AddTransactionFragment.java:82)",
                false, false);
        assertTrue(instruction.contains("does not match the widget type"));
        assertTrue(instruction.contains("CompoundButton"));
        assertFalse(instruction.contains("Theme mismatch"));
    }

    @Test
    public void routesStubCrashToImplementHint() {
        String instruction = RuntimeCrashPlaybook.instruction(
                "java.lang.UnsupportedOperationException\n at Repo.load // ANDROIDBUILDER-STUB invoked stub", false, false);
        assertTrue(instruction.contains("stubbed method"));
        assertTrue(instruction.contains("ANDROIDBUILDER-STUB"));
    }

    @Test
    public void findViewByIdNpeNeedsBothNeedles() {
        String npeWithFindView = RuntimeCrashPlaybook.instruction(
                "java.lang.NullPointerException\n at X.bind findViewById returned null", false, false);
        assertTrue(npeWithFindView.contains("findViewById returned null"));
        // A plain NPE without findViewById does not fire the findViewById clause.
        String plainNpe = RuntimeCrashPlaybook.instruction("java.lang.NullPointerException at X.run", false, false);
        assertFalse(plainNpe.contains("findViewById returned null"));
    }

    @Test
    public void escalateAddsFullRewriteClauseAndChineseIsLocalized() {
        String escalated = RuntimeCrashPlaybook.instruction("java.lang.IllegalStateException Theme.AppCompat", false, true);
        assertTrue(escalated.contains("ESCALATION"));
        String chinese = RuntimeCrashPlaybook.instruction("java.lang.IllegalStateException Theme.AppCompat", true, false);
        assertTrue(chinese.contains("启动就崩溃"));
        assertTrue(chinese.contains("主题不兼容"));
    }
}
