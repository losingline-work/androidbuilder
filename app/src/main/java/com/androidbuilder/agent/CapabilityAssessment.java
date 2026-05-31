package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CapabilityAssessment {
    private final boolean blocksExecution;
    private final List<String> risks;
    private final List<String> suggestions;

    CapabilityAssessment(boolean blocksExecution, List<String> risks, List<String> suggestions) {
        this.blocksExecution = blocksExecution;
        this.risks = Collections.unmodifiableList(new ArrayList<>(risks));
        this.suggestions = Collections.unmodifiableList(new ArrayList<>(suggestions));
    }

    public boolean blocksExecution() {
        return blocksExecution;
    }

    public boolean hasRisks() {
        return !risks.isEmpty();
    }

    public String message(boolean chinese) {
        if (chinese) {
            return chineseMessage();
        }
        return englishMessage();
    }

    private String chineseMessage() {
        if (risks.isEmpty()) {
            return "能力评估：低风险。当前需求适合用 Kotlin + XML + Android SDK/SQLite 在本机生成和构建。";
        }
        StringBuilder message = new StringBuilder(blocksExecution
                ? "能力评估：当前依赖模式无法执行。"
                : "能力评估：存在构建风险。");
        for (String risk : risks) {
            message.append("\n- ").append(risk);
        }
        for (String suggestion : suggestions) {
            message.append("\n建议：").append(suggestion);
        }
        return message.toString();
    }

    private String englishMessage() {
        if (risks.isEmpty()) {
            return "Capability assessment: Low risk. This request fits Kotlin + XML + Android SDK/SQLite local builds.";
        }
        StringBuilder message = new StringBuilder(blocksExecution
                ? "Capability assessment: The current dependency mode cannot execute this plan."
                : "Capability assessment: Build risks detected.");
        for (String risk : risks) {
            message.append("\n- ").append(risk);
        }
        for (String suggestion : suggestions) {
            message.append("\nSuggestion: ").append(suggestion);
        }
        return message.toString();
    }
}
