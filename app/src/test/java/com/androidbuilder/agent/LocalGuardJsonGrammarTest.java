package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalGuardJsonGrammarTest {
    @Test
    public void grammarConstrainsLocalGuardResultShape() {
        String grammar = LocalGuardJsonGrammar.grammar();

        assertEquals("root", LocalGuardJsonGrammar.root());
        assertTrue(grammar.contains("root ::= ws \"{\" ws decision-kv \",\" ws summary-kv \",\" ws additional-instruction-kv ws \"}\" ws"));
        assertTrue(grammar.contains("decision ::= \"\\\"ok\\\"\" | \"\\\"rewrite\\\"\""));
        assertTrue(grammar.contains("decision-kv ::= \"\\\"decision\\\"\""));
        assertTrue(grammar.contains("summary-kv ::= \"\\\"summary\\\"\""));
        assertTrue(grammar.contains("additional-instruction-kv ::= \"\\\"additionalInstruction\\\"\""));
    }
}
