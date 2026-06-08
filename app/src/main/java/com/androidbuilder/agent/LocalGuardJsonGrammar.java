package com.androidbuilder.agent;

final class LocalGuardJsonGrammar {
    private static final String ROOT = "root";
    private static final String GRAMMAR =
            "root ::= ws \"{\" ws decision-kv \",\" ws summary-kv \",\" ws additional-instruction-kv ws \"}\" ws\n" +
            "decision-kv ::= \"\\\"decision\\\"\" ws \":\" ws decision\n" +
            "summary-kv ::= \"\\\"summary\\\"\" ws \":\" ws string\n" +
            "additional-instruction-kv ::= \"\\\"additionalInstruction\\\"\" ws \":\" ws string\n" +
            "decision ::= \"\\\"ok\\\"\" | \"\\\"rewrite\\\"\"\n" +
            "string ::= \"\\\"\" char* \"\\\"\" ws\n" +
            "char ::= [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F]{4})\n" +
            "ws ::= [ \\t\\n\\r]*\n";

    private LocalGuardJsonGrammar() {
    }

    static String root() {
        return ROOT;
    }

    static String grammar() {
        return GRAMMAR;
    }
}
