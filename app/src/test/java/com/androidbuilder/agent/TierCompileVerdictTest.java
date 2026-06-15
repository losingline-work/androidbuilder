package com.androidbuilder.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TierCompileVerdictTest {
    @Test
    public void crossFileMissingMethodIsActionable() {
        String log = "/work/app/src/main/java/com/x/Repo.java:12: error: cannot find symbol\n"
                + "        dao.listInRange(0, 1);\n"
                + "  symbol:   method listInRange(int,int)\n"
                + "  location: variable dao of type TransactionDao\n"
                + "1 error\n";
        assertTrue(TierCompileVerdict.isActionableCrossFileError(log));
    }

    @Test
    public void argumentMismatchIsActionable() {
        assertTrue(TierCompileVerdict.isActionableCrossFileError(
                "Foo.java:3: error: method bar in class Baz cannot be applied to given types\n"));
        assertTrue(TierCompileVerdict.isActionableCrossFileError(
                "Foo.java:3: error: incompatible types: int cannot be converted to String\n"));
    }

    @Test
    public void missingPackageDefersToAuthority() {
        // A wrong tier classpath shows up as "package ... does not exist"; never fast-fail on it.
        assertFalse(TierCompileVerdict.isActionableCrossFileError(
                "Foo.java:1: error: package com.google.android.material.button does not exist\n"));
    }

    @Test
    public void missingSyntheticResourceFieldDefersToAuthority() {
        // R is synthetic and may be incomplete; a missing R member is our gap, not a model error.
        String log = "Main.java:9: error: cannot find symbol\n"
                + "        setContentView(R.layout.missing_screen);\n"
                + "  symbol:   variable missing_screen\n"
                + "  location: class R\n";
        assertFalse(TierCompileVerdict.isActionableCrossFileError(log));
    }

    @Test
    public void cleanOrEmptyOutputIsNotActionable() {
        assertFalse(TierCompileVerdict.isActionableCrossFileError(""));
        assertFalse(TierCompileVerdict.isActionableCrossFileError(null));
        assertFalse(TierCompileVerdict.isActionableCrossFileError("BUILD SUCCESSFUL"));
    }
}
