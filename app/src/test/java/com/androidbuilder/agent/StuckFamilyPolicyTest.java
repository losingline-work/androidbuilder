package com.androidbuilder.agent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StuckFamilyPolicyTest {
    @Test
    public void detectsStuckDaoFamilyAfterTwoDistinctMethods() {
        List<FailureFingerprint> history = history(
                "TransactionRepository.java calls TransactionDao.listByMonth() but that DAO method is not declared",
                "TransactionRepository.java calls TransactionDao.listInRange() but that DAO method is not declared");

        StuckFamilyPolicy.Family family = StuckFamilyPolicy.detect(history, 2);

        assertNotNull("two distinct methods of one DAO should be a stuck family", family);
        assertEquals("TransactionDao", family.className);
        assertTrue(family.members.contains("listByMonth"));
        assertTrue(family.members.contains("listInRange"));
    }

    @Test
    public void doesNotFireOnASingleFlaggedMethod() {
        List<FailureFingerprint> history = history(
                "TransactionRepository.java calls TransactionDao.listInRange() but that DAO method is not declared",
                "TransactionRepository.java calls TransactionDao.listInRange() but that DAO method is not declared");

        // The same method twice is one member, not a mutating family - the ordinary same-error fuse owns this.
        assertNull(StuckFamilyPolicy.detect(history, 2));
    }

    @Test
    public void doesNotGroupTwoDifferentDaosEachBelowThreshold() {
        List<FailureFingerprint> history = history(
                "TransactionRepository.java calls TransactionDao.listInRange() but that DAO method is not declared",
                "AccountRepository.java calls AccountDao.listAll() but that DAO method is not declared");

        assertNull(StuckFamilyPolicy.detect(history, 2));
    }

    @Test
    public void ignoresUnrelatedErrors() {
        List<FailureFingerprint> history = history(
                "cannot find symbol variable foo",
                "Could not find com.example:lib:1.0",
                "Generated source policy blocked missing drawable resource: R.drawable.ic_add in IconCatalog.java");

        assertNull(StuckFamilyPolicy.detect(history, 2));
    }

    @Test
    public void directiveNamesEveryMemberAndTheClass() {
        List<FailureFingerprint> history = history(
                "TransactionRepository.java calls TransactionDao.listByMonth() but that DAO method is not declared",
                "BudgetCalculator.java calls TransactionDao.sumExpenseByCategory() but that DAO method is not declared");

        StuckFamilyPolicy.Family family = StuckFamilyPolicy.detect(history, 2);
        assertNotNull(family);
        String directive = StuckFamilyPolicy.reconcileDirective(family);

        assertTrue(directive.contains("TransactionDao.listByMonth()"));
        assertTrue(directive.contains("TransactionDao.sumExpenseByCategory()"));
        assertTrue(directive.contains("resend TransactionDao.java"));
        assertTrue(directive.contains("single pass"));
    }

    private static List<FailureFingerprint> history(String... messages) {
        List<FailureFingerprint> history = new ArrayList<>();
        for (String message : messages) {
            history.add(FailureFingerprintPolicy.fromPolicyError(message));
        }
        return history;
    }
}
