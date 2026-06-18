package com.androidbuilder.agent;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuildLogContextExtractorTest {
    @Test
    public void extractsMultipleJavacDiagnostics() {
        String log = "> Task :app:compileDebugJavaWithJavac FAILED\n" +
                "/data/source/app/src/main/java/com/example/StatisticsActivity.java:99: error: method getMonthlyTotal in class TransactionDAO cannot be applied to given types;\n" +
                "        long incomeTotal = transactionDAO.getMonthlyTotal(year, month, 1);\n" +
                "                                         ^\n" +
                "  required: int,int\n" +
                "  found:    int,int,int\n" +
                "  reason: actual and formal argument lists differ in length\n" +
                "/data/source/app/src/main/java/com/example/StatisticsActivity.java:102: error: percentage has private access in CategorySum\n" +
                "                item.percentage = 0.0f;\n" +
                "                    ^\n" +
                "/data/source/app/src/main/java/com/example/TransactionAdapter.java:55: error: cannot find symbol\n" +
                "            String name = categoryDAO.getCategoryNameById(item.categoryId);\n" +
                "                                     ^\n" +
                "  symbol:   method getCategoryNameById(long)\n" +
                "  location: variable categoryDAO of type CategoryDAO\n" +
                "27 errors\n";

        String diagnostics = BuildLogContextExtractor.javaCompileDiagnostics(log, 4000);

        assertTrue(diagnostics.contains("getMonthlyTotal"));
        assertTrue(diagnostics.contains("required: int,int"));
        assertTrue(diagnostics.contains("percentage has private access"));
        assertTrue(diagnostics.contains("getCategoryNameById"));
        assertTrue(diagnostics.contains("27 errors"));
    }

    @Test
    public void extractsReferencedTypesFromJavacLocations() {
        String log = "location: variable categoryDAO of type CategoryDAO\n" +
                "location: variable item of type CategorySum\n";

        Set<String> types = BuildLogContextExtractor.referencedJavaTypes(log);

        assertTrue(types.contains("CategoryDAO"));
        assertTrue(types.contains("CategorySum"));
    }

    @Test
    public void summarizesMissingFieldReferencesByType() {
        String log = "/data/source/app/src/main/java/com/example/StatisticsAdapter.java:31: error: cannot find symbol\n" +
                "        holder.textAmount.setText(String.format(\"¥%.2f\", item.total));\n" +
                "                                                             ^\n" +
                "  symbol:   variable total\n" +
                "  location: variable item of type CategorySum\n" +
                "/data/source/app/src/main/java/com/example/StatisticsActivity.java:87: error: cannot find symbol\n" +
                "            expenseSum += item.total;\n" +
                "                              ^\n" +
                "  symbol:   variable total\n" +
                "  location: variable item of type CategorySum\n";

        assertEquals("Missing field references:\n- CategorySum.total",
                BuildLogContextExtractor.missingFieldHints(log));
    }

    @Test
    public void resourceDiagnosticsExtractsSortedDeDupedMissingTokens() {
        String log = "> Task :app:processDebugResources FAILED\n"
                + "/data/work/13/92/source/app/src/main/res/layout/fragment_profile.xml:9: error: resource string/category_manage (aka com.generated.app:string/category_manage) not found.\n"
                + "/data/work/13/92/source/app/src/main/res/values/values.xml:245: error: resource color/colorSurface (aka com.generated.app:color/colorSurface) not found.\n"
                + "/other/path/menu_host.xml:3: error: resource menu/bottom_nav_menu (aka com.generated.app:menu/bottom_nav_menu) not found.\n"
                + "/dup/again.xml:7: error: resource color/colorSurface (aka com.generated.app:color/colorSurface) not found.\n"
                + "error: failed linking file resources.\n";

        String diagnostics = BuildLogContextExtractor.resourceDiagnostics(log, 4000);

        // Sorted, de-duplicated tokens; volatile work-dir paths and line numbers stripped.
        assertEquals("missing color/colorSurface\nmissing menu/bottom_nav_menu\nmissing string/category_manage",
                diagnostics);
    }

    @Test
    public void resourceDiagnosticsEmptyWhenNoAaptErrors() {
        assertEquals("", BuildLogContextExtractor.resourceDiagnostics("BUILD SUCCESSFUL in 2s", 4000));
        assertEquals("", BuildLogContextExtractor.resourceDiagnostics(
                "/work/app/src/main/java/com/x/Repo.java:12: error: cannot find symbol\n1 error\n", 4000));
    }
}
