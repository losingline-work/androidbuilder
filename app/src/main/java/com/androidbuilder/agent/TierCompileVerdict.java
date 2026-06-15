package com.androidbuilder.agent;

import java.util.Locale;

/**
 * Decides whether a direct-javac tier type-check failure is trustworthy enough to fail the build fast,
 * or whether it should defer to the authoritative Gradle {@code compileDebugJavaWithJavac} gate.
 *
 * <p>The tier check trades completeness for speed (synthetic R/BuildConfig, a hand-built classpath, no
 * aapt), so a failure can be either a genuine cross-file Java type error (the high-value case worth
 * failing fast on) or an artifact of the tier setup (a wrong classpath entry, a resource field the
 * synthetic R did not model). This verdict fast-fails ONLY on the high-confidence cross-file signatures
 * and defers everything else — so a subtly-wrong tier invocation can never cause a false build
 * regression: the Gradle gate still runs and remains the authority.
 */
public final class TierCompileVerdict {

    // The cross-file type errors that motivated the tier check (the project-9 pain class).
    private static final String[] ACTIONABLE = {
            "cannot find symbol",
            "cannot be applied to given types",
            "incompatible types",
            "actual and formal argument lists differ",
            "has private access",
            "is not abstract and does not override"
    };

    // Markers of a tier-setup artifact (classpath/flags) rather than the model's source: defer.
    private static final String[] INFRA = {
            "does not exist",
            "cannot access",
            "bootclasspath",
            "invalid flag",
            "invalid source release",
            "invalid target release",
            "module not found",
            "error: package",
            "error reading",
            "bad class file",
            "cannot be resolved to a type"
    };

    // The synthetic R/BuildConfig are intentionally incomplete; a "missing" member there is OUR gap,
    // not a model error, so defer to the real build which generates the true R.
    private static final String[] SYNTHETIC_GAP = {
            "location: class r",
            "location: class buildconfig",
            "symbol:   class r",
            "symbol: class r",
            "symbol:   class buildconfig"
    };

    private TierCompileVerdict() {
    }

    public static boolean isActionableCrossFileError(String javacOutput) {
        if (javacOutput == null || javacOutput.trim().isEmpty()) {
            return false;
        }
        String text = javacOutput.toLowerCase(Locale.ROOT);
        if (containsAny(text, INFRA) || containsAny(text, SYNTHETIC_GAP)) {
            return false;
        }
        return containsAny(text, ACTIONABLE);
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
