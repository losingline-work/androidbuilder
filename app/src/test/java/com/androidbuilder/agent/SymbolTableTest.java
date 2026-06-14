package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SymbolTableTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void typedIngestMatchesOnDiskParseForMethodsSignaturesAndConstructors() throws Exception {
        // The SAME class, fed two ways, must answer every query identically - so a contract-built table
        // (Stage 4) and the guard's source-built table cannot disagree.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Money.java",
                "package com.example;\n"
                        + "class Money {\n"
                        + "    Money(int cents) {}\n"
                        + "    long toCents(long major) { return 0L; }\n"
                        + "    String text() { return \"\"; }\n"
                        + "}\n");
        SymbolTable onDisk = new AndroidSourceGuard().collectSymbolTableForTest(root);

        SymbolTable typed = new SymbolTable();
        typed.addClass("Money", "", null,
                Arrays.asList(
                        new SymbolTable.MethodFact("toCents", Arrays.asList("long")),
                        new SymbolTable.MethodFact("text", Collections.<String>emptyList())),
                Arrays.asList(Arrays.asList("int")));

        for (SymbolTable t : new SymbolTable[]{onDisk, typed}) {
            assertTrue(t.hasClass("Money"));
            assertTrue(t.hasMethod("Money", "toCents"));
            assertTrue(t.hasMethod("Money", "text"));
            assertFalse(t.hasMethod("Money", "missing"));
            // exact match
            assertTrue(t.hasMethodSignature("Money", "toCents", Arrays.asList("long")));
            // JLS widening: int arg assignable to long param
            assertTrue(t.hasMethodSignature("Money", "toCents", Arrays.asList("int")));
            // autoboxing: Long arg assignable to long param
            assertTrue(t.hasMethodSignature("Money", "toCents", Arrays.asList("Long")));
            // narrowing is NOT assignable: long arg to (a hypothetical) - here wrong arity
            assertFalse(t.hasMethodSignature("Money", "toCents", Collections.<String>emptyList()));
            // single declared constructor takes one arg
            assertTrue(hasArity(t.availableConstructors("Money"), 1));
            assertFalse(hasArity(t.availableConstructors("Money"), 0));
        }
    }

    @Test
    public void nestedClassResolvesBySimpleNameInBothPaths() throws Exception {
        // The text round-trip (render signature -> re-parse) broke on nested/qualified names; typed
        // ingest keys by simple name exactly like the guard's parser, so Outer.Entry is reachable as
        // "Entry" in both.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Outer.java",
                "package com.example;\n"
                        + "class Outer {\n"
                        + "    static final class Entry {\n"
                        + "        long total() { return 0L; }\n"
                        + "    }\n"
                        + "}\n");
        SymbolTable onDisk = new AndroidSourceGuard().collectSymbolTableForTest(root);

        SymbolTable typed = new SymbolTable();
        typed.addClass("Outer", "", null, Collections.<SymbolTable.MethodFact>emptyList(), null);
        typed.addClass("Entry", "", null,
                Arrays.asList(new SymbolTable.MethodFact("total", Collections.<String>emptyList())), null);

        for (SymbolTable t : new SymbolTable[]{onDisk, typed}) {
            assertTrue(t.hasClass("Outer"));
            assertTrue(t.hasClass("Entry"));
            assertTrue(t.hasMethod("Entry", "total"));
            assertFalse(t.hasMethod("Entry", "getKey"));
        }
    }

    @Test
    public void inheritedMethodsResolveThroughSuperChainInBothPaths() throws Exception {
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Base.java",
                "package com.example;\nclass Base { void shared() {} }\n");
        write(root, "app/src/main/java/com/example/Child.java",
                "package com.example;\nclass Child extends Base { void own() {} }\n");
        SymbolTable onDisk = new AndroidSourceGuard().collectSymbolTableForTest(root);

        SymbolTable typed = new SymbolTable();
        typed.addClass("Base", "", null,
                Arrays.asList(new SymbolTable.MethodFact("shared", Collections.<String>emptyList())), null);
        typed.addClass("Child", "Base", null,
                Arrays.asList(new SymbolTable.MethodFact("own", Collections.<String>emptyList())), null);

        for (SymbolTable t : new SymbolTable[]{onDisk, typed}) {
            assertTrue(t.hasMethod("Child", "own"));
            assertTrue(t.hasMethod("Child", "shared"));
        }
    }

    private static boolean hasArity(java.util.List<java.util.List<String>> ctors, int arity) {
        for (java.util.List<String> c : ctors) {
            if (c.size() == arity) {
                return true;
            }
        }
        return false;
    }

    private static void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
