package com.androidbuilder.agent;

import java.util.Locale;

public final class BuildFailureClassifier {
    public enum Kind {
        KOTLIN_COMPILE,
        RESOURCE_LINKING,
        DEPENDENCY_POLICY,
        DEPENDENCY_NETWORK,
        RUNTIME_ENVIRONMENT,
        UNKNOWN
    }

    public static class Result {
        public final Kind kind;
        public final boolean repairableByModel;

        private Result(Kind kind, boolean repairableByModel) {
            this.kind = kind;
            this.repairableByModel = repairableByModel;
        }
    }

    private BuildFailureClassifier() {
    }

    public static Result classify(String phase, String errorText) {
        String text = ((phase == null ? "" : phase) + "\n" + (errorText == null ? "" : errorText)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "missing_tools", "toolchain is incomplete", "cannot run program", "permission denied", "no such file", "runtime_error", "termux", "jdkimagetransform", "core-for-system-modules.jar", "androidjdkimage")) {
            return new Result(Kind.RUNTIME_ENVIRONMENT, false);
        }
        if (containsAny(text, "dependency_network", "unknownhostexception", "dl.google.com", "network unavailable")) {
            return new Result(Kind.DEPENDENCY_NETWORK, false);
        }
        if (containsAny(text, "dependency policy blocked", "could not resolve all files", "failed to transform", "could not download")) {
            return new Result(Kind.DEPENDENCY_POLICY, true);
        }
        if (containsAny(text, "compiledebugkotlin", "unresolved reference", "kotlin compiler")) {
            return new Result(Kind.KOTLIN_COMPILE, true);
        }
        if (containsAny(text, "processdebugresources", "linkapplicationandroidresources", "resource linking failed", "androidmanifest.xml")) {
            return new Result(Kind.RESOURCE_LINKING, true);
        }
        return new Result(Kind.UNKNOWN, true);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
