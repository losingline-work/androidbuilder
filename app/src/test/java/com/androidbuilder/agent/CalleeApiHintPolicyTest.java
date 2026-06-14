package com.androidbuilder.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CalleeApiHintPolicyTest {
    private static SymbolTable projectSymbols() {
        SymbolTable table = new SymbolTable();
        table.addClass("RecurringDao", "", null,
                Arrays.asList(
                        new SymbolTable.MethodFact("insert", Arrays.asList("String", "int")),
                        new SymbolTable.MethodFact("listAll", Collections.<String>emptyList()),
                        new SymbolTable.MethodFact("listDue", Arrays.asList("long"))),
                Arrays.asList(Arrays.asList("Context"), Arrays.asList("DbHelper")));
        table.addClass("BudgetCalculator", "", null,
                Arrays.asList(new SymbolTable.MethodFact("remainingFor", Arrays.asList("long", "long"))),
                Arrays.asList(Arrays.asList("TransactionDao", "DateUtils")));
        return table;
    }

    @Test
    public void listsRealMethodsForAMissingMethodRejection() {
        String message = "Generated source policy blocked missing method: RecurringDao.findById(long) in RecurringRepository.java.";

        String hint = CalleeApiHintPolicy.hint(message, projectSymbols());

        assertTrue(hint.contains("RecurringDao declares methods: insert, listAll, listDue"));
        assertTrue(hint.contains("RecurringDao(Context)"));
        assertTrue(hint.contains("RecurringDao(DbHelper)"));
        assertFalse(hint.contains("findById"));
        assertTrue(hint.contains("match these EXACTLY"));
    }

    @Test
    public void listsRealConstructorForANewExpressionMismatch() {
        String message = "Generated source policy blocked constructor argument mismatch: new BudgetCalculator() in BudgetRepository.java, but available constructors are BudgetCalculator(TransactionDao, DateUtils).";

        String hint = CalleeApiHintPolicy.hint(message, projectSymbols());

        assertTrue(hint.contains("BudgetCalculator(TransactionDao, DateUtils)"));
        assertTrue(hint.contains("BudgetCalculator declares methods: remainingFor"));
    }

    @Test
    public void coversAllClassesNamedInAMultiViolationRejection() {
        String message = "missing method: RecurringDao.findById(long) in RecurringRepository.java\n"
                + "method argument mismatch: BudgetCalculator.remainingFor(long) in BudgetRepository.java";

        String hint = CalleeApiHintPolicy.hint(message, projectSymbols());

        assertTrue(hint.contains("RecurringDao declares"));
        assertTrue(hint.contains("BudgetCalculator declares"));
    }

    @Test
    public void skipsClassesNotInTheSymbolTable() {
        // A platform/library type (e.g. Map.Entry) must not get fabricated advice.
        String message = "missing method: Entry.getKey() in StatsCalculator.java";

        assertEquals("", CalleeApiHintPolicy.hint(message, projectSymbols()));
    }

    @Test
    public void emptyForBlankOrNull() {
        assertEquals("", CalleeApiHintPolicy.hint("", projectSymbols()));
        assertEquals("", CalleeApiHintPolicy.hint(null, projectSymbols()));
        assertEquals("", CalleeApiHintPolicy.hint("missing method: RecurringDao.x()", null));
    }
}
