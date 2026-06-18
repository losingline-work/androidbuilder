package com.androidbuilder.agent;

/**
 * The canned implementation-phase titles {@link ImplementationTaskNormalizer} normalizes every task into,
 * and a matcher that tolerates an appended " · &lt;feature&gt;" DISPLAY suffix. The phases are by file type
 * (Gradle / values+themes+menu / drawable+layout / Java) so each task touches one category and gets a tight
 * allowedPaths contract; the feature suffix is purely cosmetic so the user can tell otherwise-identical
 * phase titles apart (e.g. "Java source wiring · home" vs "Java source wiring · charts"). Every policy that
 * keys off a phase must match through {@link #is} so the suffix never breaks the contract.
 */
final class CanonicalTaskPhase {
    static final String GRADLE = "Gradle skeleton and dependencies";
    static final String RESOURCES = "resources: values, themes, and menu";
    static final String DRAWABLE_LAYOUT = "drawable and layout XML";
    static final String JAVA = "Java source wiring";
    static final String SEPARATOR = " · ";

    private CanonicalTaskPhase() {
    }

    /** True when {@code title} is the canonical {@code phase}, with or without an appended feature suffix. */
    static boolean is(String title, String phase) {
        if (title == null) {
            return false;
        }
        String trimmed = title.trim();
        return trimmed.equals(phase) || trimmed.startsWith(phase + SEPARATOR);
    }

    /** Appends a feature hint as a display suffix; returns the bare phase when the hint is empty. */
    static String withFeature(String phase, String featureHint) {
        String hint = featureHint == null ? "" : featureHint.trim();
        return hint.isEmpty() ? phase : phase + SEPARATOR + hint;
    }
}
