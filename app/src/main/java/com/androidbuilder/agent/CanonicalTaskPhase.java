package com.androidbuilder.agent;

/**
 * The canned implementation phases {@link ImplementationTaskNormalizer} normalizes every task into. The
 * phases are by FILE TYPE (Gradle / values+themes+menu / drawable+layout / Java) so each task gets a tight
 * allowedPaths contract. Each phase has an English and a Chinese display title; the user sees the one for
 * their language, while {@link #is} matches EITHER so every phase-keyed policy (tiers, high-volume batching)
 * and re-normalization keeps working regardless of the title's language or an appended " · &lt;feature&gt;"
 * display suffix.
 */
final class CanonicalTaskPhase {
    enum Phase {
        GRADLE("Gradle skeleton and dependencies", "Gradle 配置与依赖"),
        RESOURCES("resources: values, themes, and menu", "资源：values / 主题 / 菜单"),
        DRAWABLE_LAYOUT("drawable and layout XML", "图形与布局 XML"),
        JAVA("Java source wiring", "Java 源码接线");

        private final String en;
        private final String zh;

        Phase(String en, String zh) {
            this.en = en;
            this.zh = zh;
        }

        String title(boolean chinese) {
            return chinese ? zh : en;
        }

        boolean matches(String title) {
            return prefixMatch(title, en) || prefixMatch(title, zh);
        }
    }

    static final String SEPARATOR = " · ";

    private CanonicalTaskPhase() {
    }

    /** True when {@code title} is this phase in either language, with or without an appended feature suffix. */
    static boolean is(String title, Phase phase) {
        return phase != null && phase.matches(title);
    }

    /** The phase's localized title, with an optional feature hint appended as a display suffix. */
    static String withFeature(Phase phase, String featureHint, boolean chinese) {
        String hint = featureHint == null ? "" : featureHint.trim();
        String base = phase.title(chinese);
        return hint.isEmpty() ? base : base + SEPARATOR + hint;
    }

    private static boolean prefixMatch(String title, String phaseTitle) {
        if (title == null) {
            return false;
        }
        String trimmed = title.trim();
        return trimmed.equals(phaseTitle) || trimmed.startsWith(phaseTitle + SEPARATOR);
    }
}
