package com.androidbuilder.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodeReviewParserTest {
    @Test
    public void parsesFindingsAndKeepsOnlyActionableOnes() {
        String response = "{\"findings\":["
                + "{\"severity\":\"blocker\",\"file\":\"app/src/main/res/layout/fragment_home.xml\",\"line\":25,\"issue\":\"cardBackgroundColor points at @drawable\",\"fix\":\"use @color/colorSurface\"},"
                + "{\"severity\":\"high\",\"file\":\"app/src/main/java/com/x/HomeFragment.java\",\"line\":40,\"issue\":\"findViewById on wrong layout\",\"fix\":\"use the inflated root\"},"
                + "{\"severity\":\"low\",\"file\":\"app/src/main/java/com/x/Util.java\",\"line\":3,\"issue\":\"rename method\",\"fix\":\"style only\"},"
                + "{\"severity\":\"blocker\",\"file\":\"\",\"line\":0,\"issue\":\"something vague\",\"fix\":\"\"}"
                + "]}";

        List<CodeReviewParser.Finding> all = CodeReviewParser.parse(response);
        List<CodeReviewParser.Finding> actionable = CodeReviewParser.actionable(all);

        assertEquals(4, all.size());
        // low severity dropped, and the blocker with no file dropped.
        assertEquals(2, actionable.size());
        assertEquals("blocker", actionable.get(0).severity);
        assertEquals(25, actionable.get(0).line);
    }

    @Test
    public void emptyFindingsAndMalformedResponseYieldNoActionable() {
        assertTrue(CodeReviewParser.parse("{\"findings\":[]}").isEmpty());
        assertTrue(CodeReviewParser.parse("not json at all").isEmpty());
        assertTrue(CodeReviewParser.parse("").isEmpty());
        assertTrue(CodeReviewParser.actionable(CodeReviewParser.parse("{\"findings\":[]}")).isEmpty());
    }

    @Test
    public void toleratesProseAroundTheJsonObject() {
        String response = "Sure, here is my review:\n{\"findings\":[{\"severity\":\"high\","
                + "\"file\":\"app/src/main/java/com/x/A.java\",\"line\":7,\"issue\":\"NPE risk\",\"fix\":\"null-check\"}]}\nDone.";
        List<CodeReviewParser.Finding> actionable = CodeReviewParser.actionable(CodeReviewParser.parse(response));
        assertEquals(1, actionable.size());
        assertEquals("app/src/main/java/com/x/A.java", actionable.get(0).file);
    }

    @Test
    public void buildsRepairInstructionFromActionableFindings() {
        String response = "{\"findings\":["
                + "{\"severity\":\"blocker\",\"file\":\"res/layout/a.xml\",\"line\":25,\"issue\":\"color attr is a drawable\",\"fix\":\"use @color\"},"
                + "{\"severity\":\"low\",\"file\":\"b.java\",\"line\":1,\"issue\":\"nit\",\"fix\":\"x\"}]}";
        String instruction = CodeReviewParser.toRepairInstruction(CodeReviewParser.parse(response), false);

        assertTrue(instruction.contains("Code review found"));
        assertTrue(instruction.contains("res/layout/a.xml:25"));
        assertTrue(instruction.contains("color attr is a drawable"));
        assertTrue(instruction.contains("use @color"));
        // The low-severity nit is not in the instruction.
        assertFalse(instruction.contains("nit"));
    }

    @Test
    public void noInstructionWhenNothingActionable() {
        assertEquals("", CodeReviewParser.toRepairInstruction(CodeReviewParser.parse("{\"findings\":[]}"), false));
        assertEquals("", CodeReviewParser.toRepairInstruction(
                CodeReviewParser.parse("{\"findings\":[{\"severity\":\"low\",\"file\":\"a\",\"line\":1,\"issue\":\"x\",\"fix\":\"y\"}]}"),
                true));
    }

    @Test
    public void promptNamesTheBuildInvisibleClassesAndStrictJson() {
        String en = CodeReviewPrompt.systemPrompt(false);
        assertTrue(en.contains("cannot see") || en.contains("CANNOT see"));
        assertTrue(en.contains("cardBackgroundColor"));
        assertTrue(en.contains("AppCompatActivity"));
        assertTrue(en.contains("\"findings\""));
        String zh = CodeReviewPrompt.systemPrompt(true);
        assertTrue(zh.contains("看不到"));
        assertTrue(zh.contains("findings"));
    }
}
