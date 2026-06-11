package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Curated capability libraries verified for this pipeline: consumable from plain Java (no Kotlin
 * plugin, no annotation processor), compatible with AGP 8.7.3 / compileSdk 34 / minSdk 28+, and
 * resolvable from the injected repositories (Aliyun mirrors + Google/Maven Central + JitPack).
 *
 * <p>This is the Tier-1 layer of the dependency policy: entries here are always approved in online
 * mode, advertised to the planner/coder prompts so plans are born compatible, and offered as
 * substitutes when an off-catalog dependency is rejected.
 */
public final class DependencyCatalog {
    public static final class Entry {
        public final String group;
        public final String artifact;
        public final String version;
        /** "central" or "jitpack" — JitPack entries need the injected jitpack.io repository. */
        public final String repo;
        /** Short use-case tag matched against rejected artifacts to suggest substitutes. */
        public final String useCase;
        /** One-line usage note for prompts (permissions, constraints). Empty when none. */
        public final String hint;

        Entry(String group, String artifact, String version, String repo, String useCase, String hint) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.repo = repo;
            this.useCase = useCase;
            this.hint = hint;
        }

        public String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    private static final List<Entry> ENTRIES = Arrays.asList(
            new Entry("com.github.PhilJay", "MPAndroidChart", "v3.1.0", "jitpack", "chart",
                    "line/bar/pie charts; pure Java"),
            new Entry("com.squareup.picasso", "picasso", "2.8", "central", "image",
                    "simple image loading; pure Java"),
            new Entry("com.github.bumptech.glide", "glide", "4.16.0", "central", "image",
                    "image loading; do NOT add the glide compiler/annotation processor"),
            new Entry("com.airbnb.android", "lottie", "6.4.0", "central", "animation",
                    "JSON animations via LottieAnimationView"),
            new Entry("com.squareup.okhttp3", "okhttp", "3.12.13", "central", "http",
                    "HTTP client; requires INTERNET permission in AndroidManifest.xml"),
            new Entry("com.squareup.retrofit2", "retrofit", "2.11.0", "central", "http",
                    "REST client; requires INTERNET permission; pair with converter-gson"),
            new Entry("com.squareup.retrofit2", "converter-gson", "2.11.0", "central", "http",
                    "Gson converter for Retrofit"),
            new Entry("io.reactivex.rxjava3", "rxjava", "3.1.9", "central", "reactive",
                    "reactive streams; pure Java"),
            new Entry("io.reactivex.rxjava3", "rxandroid", "3.0.2", "central", "reactive",
                    "Android main-thread scheduler for RxJava"),
            new Entry("org.greenrobot", "eventbus", "3.3.1", "central", "event",
                    "in-app event bus; pure Java"),
            new Entry("com.jakewharton.threetenabp", "threetenabp", "1.4.7", "central", "datetime",
                    "java.time backport; initialize AndroidThreeTen.init(this) in Application"),
            new Entry("com.google.zxing", "core", "3.5.3", "central", "qrcode",
                    "QR/barcode encode-decode; pure Java"),
            new Entry("com.squareup.moshi", "moshi", "1.15.1", "central", "json",
                    "JSON via reflection only; do NOT add moshi codegen/KSP")
    );

    private DependencyCatalog() {
    }

    public static List<Entry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    /** Exact coordinate match: cataloged libraries are always approved in online mode. */
    public static boolean isCataloged(String group, String name, String version) {
        Entry entry = find(group, name);
        return entry != null && entry.version.equals(version == null ? "" : version.trim());
    }

    /** The pinned, verified version for a cataloged group:artifact, or null when not cataloged. */
    public static String pinnedVersion(String group, String name) {
        Entry entry = find(group, name);
        return entry == null ? null : entry.version;
    }

    /**
     * Substitute advice for a rejected coordinate: a version correction when the library itself is
     * cataloged, a use-case match (e.g. anything chart-like maps to MPAndroidChart), or empty when
     * the catalog has nothing relevant.
     */
    public static String substituteAdvice(String group, String name) {
        Entry exact = find(group, name);
        if (exact != null) {
            return "Use the cataloged version instead: " + exact.coordinate() + ".";
        }
        String needle = ((group == null ? "" : group) + ":" + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        for (Entry entry : ENTRIES) {
            if (matchesUseCase(needle, entry.useCase)) {
                return "For " + entry.useCase + " functionality use the cataloged " + entry.coordinate()
                        + (entry.hint.isEmpty() ? "." : " (" + entry.hint + ").");
            }
        }
        return "";
    }

    private static boolean matchesUseCase(String coordinate, String useCase) {
        if (coordinate.contains(useCase)) {
            return true;
        }
        if ("image".equals(useCase)) {
            return coordinate.contains("picasso") || coordinate.contains("glide") || coordinate.contains("fresco") || coordinate.contains("coil");
        }
        if ("http".equals(useCase)) {
            return coordinate.contains("okhttp") || coordinate.contains("retrofit") || coordinate.contains("volley");
        }
        if ("qrcode".equals(useCase)) {
            return coordinate.contains("zxing") || coordinate.contains("barcode");
        }
        return false;
    }

    /** Prompt section advertising the catalog so plans and code are born compatible. */
    public static String promptSummary() {
        StringBuilder summary = new StringBuilder("Verified capability libraries (use these exact coordinates when the feature is needed): ");
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            if (i > 0) {
                summary.append("; ");
            }
            summary.append(entry.useCase).append(" -> ").append(entry.coordinate());
            if (!entry.hint.isEmpty()) {
                summary.append(" (").append(entry.hint).append(")");
            }
        }
        summary.append(". When a feature is not covered here or by the trusted groups, implement it with Android SDK/Java/XML instead of inventing dependencies.");
        return summary.toString();
    }

    /** Compact coordinate list for retry hints and repair instructions. */
    public static String coordinatesSummary() {
        StringBuilder summary = new StringBuilder();
        for (Entry entry : ENTRIES) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(entry.coordinate());
        }
        return summary.toString();
    }

    private static Entry find(String group, String name) {
        String g = group == null ? "" : group.trim();
        String n = name == null ? "" : name.trim();
        for (Entry entry : ENTRIES) {
            if (entry.group.equals(g) && entry.artifact.equals(n)) {
                return entry;
            }
        }
        return null;
    }
}
