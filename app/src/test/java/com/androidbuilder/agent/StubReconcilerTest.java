package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StubReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stubsMissingOverloadSoTheGuardPasses() throws Exception {
        // RecordRepository calls a 4-arg sumAmountByCategory; RecordDao only declares a 3-arg one.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/RecordDao.java",
                "package com.example;\n"
                        + "class RecordDao {\n"
                        + "    long sumAmountByCategory(String type, long start, long end) { return 0L; }\n"
                        + "}");
        write(root, "app/src/main/java/com/example/RecordRepository.java",
                "package com.example;\n"
                        + "class RecordRepository {\n"
                        + "    RecordDao recordDao;\n"
                        + "    long total(String type, long start, long end, long accountId) {\n"
                        + "        return recordDao.sumAmountByCategory(type, start, end, accountId);\n"
                        + "    }\n"
                        + "}");
        AndroidSourceGuard guard = new AndroidSourceGuard();

        List<String> stubs = StubReconciler.reconcile(root, guard);

        assertEquals(1, stubs.size());
        assertTrue(stubs.get(0).contains("RecordDao.sumAmountByCategory"));
        // the stub uses the existing overload's return type (long) and is tagged
        String dao = read(root, "app/src/main/java/com/example/RecordDao.java");
        assertTrue(dao.contains("long sumAmountByCategory(String a0, long a1, long a2, long a3)"));
        assertTrue(dao.contains(StubReconciler.STUB_TAG));
        // and the guard now passes the tree
        guard.validate(root);
    }

    @Test
    public void stubsMissingFieldWithInferredType() throws Exception {
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Summary.java",
                "package com.example;\nclass Summary { long totalCents; }");
        write(root, "app/src/main/java/com/example/StatisticsService.java",
                "package com.example;\n"
                        + "class StatisticsService {\n"
                        + "    void run(Summary summary) { String t = summary.type; }\n"
                        + "}");
        AndroidSourceGuard guard = new AndroidSourceGuard();

        List<String> stubs = StubReconciler.reconcile(root, guard);

        assertEquals(1, stubs.size());
        assertTrue(stubs.get(0).contains("Summary.type : String"));
        String summary = read(root, "app/src/main/java/com/example/Summary.java");
        assertTrue(summary.contains("public String type;"));
        guard.validate(root);
    }

    @Test
    public void doesNotStubWhenReturnTypeCannotBeInferred() throws Exception {
        // A genuinely-missing method whose result is never assigned/used in a typed way - we cannot
        // prove a compiling return type, so we must NOT stub (leave the guard to report it).
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Calc.java",
                "package com.example;\nclass Calc { }");
        write(root, "app/src/main/java/com/example/Repo.java",
                "package com.example;\n"
                        + "class Repo {\n"
                        + "    Calc calc;\n"
                        + "    Object run() { return calc; }\n"  // no call to a missing method here
                        + "}");
        // Reference a missing method only as an argument (no return-type signal):
        write(root, "app/src/main/java/com/example/Caller.java",
                "package com.example;\n"
                        + "class Caller {\n"
                        + "    Calc calc;\n"
                        + "    void go() { System.out.println(calc.mystery(1)); }\n"
                        + "}");
        AndroidSourceGuard guard = new AndroidSourceGuard();

        List<String> stubs = StubReconciler.reconcile(root, guard);

        assertFalse(stubs.toString().contains("mystery"));
    }

    private static void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }

    @Test
    public void softStubDefaultsAreCompileSafeByType() {
        assertEquals("false", StubReconciler.defaultValueExpr("boolean"));
        assertEquals("0", StubReconciler.defaultValueExpr("int"));
        assertEquals("0L", StubReconciler.defaultValueExpr("long"));
        assertEquals("0f", StubReconciler.defaultValueExpr("float"));
        assertEquals("0d", StubReconciler.defaultValueExpr("double"));
        assertEquals("'\\u0000'", StubReconciler.defaultValueExpr("char"));
        // Every reference / array / generic type returns null (assignable everywhere -> always compiles).
        assertEquals("null", StubReconciler.defaultValueExpr("String"));
        assertEquals("null", StubReconciler.defaultValueExpr("Integer"));
        assertEquals("null", StubReconciler.defaultValueExpr("java.util.List<Foo>"));
        assertEquals("null", StubReconciler.defaultValueExpr("int[]"));
        assertEquals("null", StubReconciler.defaultValueExpr("String[][]"));
    }

    @Test
    public void softStubReturnStatementIsEmptyForVoid() {
        assertEquals("", StubReconciler.returnStatement("void"));
        assertTrue(StubReconciler.returnStatement("String").contains("return null;"));
        // Void (boxed) is a reference type -> returns null, not a no-op.
        assertTrue(StubReconciler.returnStatement("Void").contains("return null;"));
    }

    @Test
    public void softStubBodyFailsSoftNotThrows() {
        String body = StubReconciler.safeStubBody("Repo", "load", "java.util.List<Item>");
        assertFalse("a stub on the launch path must not crash the app", body.contains("UnsupportedOperationException"));
        assertTrue(body.contains("return null;"));
        assertTrue(body.contains(StubReconciler.STUB_TAG));
        assertTrue("the debt should be visible at runtime", body.contains("Log.w"));
    }

    private static String read(File root, String path) throws Exception {
        return FileUtils.readText(new File(root, path));
    }
}
