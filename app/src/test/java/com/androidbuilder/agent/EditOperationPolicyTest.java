package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class EditOperationPolicyTest {
    @Test
    public void appliesExactSingleReplacement() {
        String updated = EditOperationPolicy.apply(
                "class A {\n  int value = 1;\n}\n",
                "int value = 1;",
                "int value = 2;",
                "app/src/main/java/A.java");

        assertEquals("class A {\n  int value = 2;\n}\n", updated);
    }

    @Test
    public void rejectsEmptyFindText() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EditOperationPolicy.apply("abc", "", "x", "app/src/main/java/A.java"));

        assertEquals("edit operation has empty find text in app/src/main/java/A.java; resend the full file with action write",
                error.getMessage());
    }

    @Test
    public void rejectsMissingFindText() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EditOperationPolicy.apply("abc", "missing", "x", "app/src/main/java/A.java"));

        assertEquals("edit target not found in app/src/main/java/A.java (the file may have changed); resend the full file with action write",
                error.getMessage());
    }

    @Test
    public void rejectsAmbiguousFindText() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EditOperationPolicy.apply("abc abc", "abc", "x", "app/src/main/java/A.java"));

        assertEquals("edit target is ambiguous in app/src/main/java/A.java (2 matches); include more surrounding context in find, or resend the full file",
                error.getMessage());
    }
}
