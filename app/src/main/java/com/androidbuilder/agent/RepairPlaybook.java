package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RepairPlaybook {
    final String id;
    final String hint;
    final List<String> focusTerms;

    RepairPlaybook(String id, String hint, List<String> focusTerms) {
        this.id = id == null ? "" : id.trim();
        this.hint = hint == null ? "" : hint.trim();
        this.focusTerms = immutableClean(focusTerms);
    }

    private static List<String> immutableClean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) {
                cleaned.add(text);
            }
        }
        return cleaned.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(cleaned);
    }
}
