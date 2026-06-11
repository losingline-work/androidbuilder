package com.androidbuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HermesTaskContract {
    public final List<String> allowedPaths;
    public final List<String> expectedFiles;
    public final List<String> forbiddenPaths;
    public final List<String> acceptanceChecks;
    public final List<String> riskNotes;
    public final List<String> dependsOn;
    public final List<String> produces;
    public final List<String> rollbackScope;
    public final String riskLevel;
    public final boolean buildRequiredAfter;

    public HermesTaskContract(
            List<String> allowedPaths,
            List<String> expectedFiles,
            List<String> forbiddenPaths,
            List<String> acceptanceChecks,
            List<String> riskNotes,
            List<String> dependsOn,
            List<String> produces,
            List<String> rollbackScope,
            String riskLevel,
            boolean buildRequiredAfter) {
        this.allowedPaths = immutableCleanList(allowedPaths);
        this.expectedFiles = immutableCleanList(expectedFiles);
        this.forbiddenPaths = immutableCleanList(forbiddenPaths);
        this.acceptanceChecks = immutableCleanList(acceptanceChecks);
        this.riskNotes = immutableCleanList(riskNotes);
        this.dependsOn = immutableCleanList(dependsOn);
        this.produces = immutableCleanList(produces);
        this.rollbackScope = immutableCleanList(rollbackScope);
        this.riskLevel = clean(riskLevel);
        this.buildRequiredAfter = buildRequiredAfter;
    }

    public static HermesTaskContract empty() {
        return new HermesTaskContract(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "",
                false);
    }

    public boolean hasSignals() {
        return !allowedPaths.isEmpty()
                || !expectedFiles.isEmpty()
                || !forbiddenPaths.isEmpty()
                || !acceptanceChecks.isEmpty()
                || !riskNotes.isEmpty()
                || !dependsOn.isEmpty()
                || !produces.isEmpty()
                || !rollbackScope.isEmpty()
                || !riskLevel.isEmpty()
                || buildRequiredAfter;
    }

    private static List<String> immutableCleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String text = clean(value);
            if (!text.isEmpty()) {
                cleaned.add(text);
            }
        }
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(cleaned);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
