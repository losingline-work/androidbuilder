package com.androidbuilder.agent;

final class PolicyRewriteInstruction {
    private PolicyRewriteInstruction() {
    }

    static String create(String originalInstruction, String policyMessage, int attempt) {
        String message = policyMessage == null ? "" : policyMessage;
        StringBuilder instruction = new StringBuilder(originalInstruction == null ? "" : originalInstruction);
        instruction.append("\n\nPrevious output was rejected by local validation on attempt ")
                .append(attempt)
                .append(": ")
                .append(message)
                .append("\nRewrite this task with the smallest necessary file changes.");
        instruction.append("\nKeep Java + XML only, obey the active dependency mode, and do not add blocked dependencies, plugins, imports, Kotlin, DataBinding, ViewBinding, or Compose.");
        if (message.contains("Java lambda syntax") || message.contains("->")) {
            instruction.append("\nRemove every Java lambda and every -> token from all generated Java files. Use anonymous inner classes for listeners and callbacks.");
            instruction.append("\nExamples: use new View.OnClickListener() { public void onClick(View view) { ... } } instead of v -> ...; use new AdapterView.OnItemClickListener() { public void onItemClick(...) { ... } } instead of (parent, view, position, id) -> ... .");
            instruction.append("\nDo not use -> anywhere in the returned file contents.");
        }
        if (message.contains("missing XML resource reference")) {
            instruction.append("\nFor every XML @mipmap/@style/@drawable/@string/@color/@layout reference, either add a matching resource file or values entry, or change the XML to use an existing resource.");
        }
        if (message.contains("missing ") && message.contains("resource")) {
            instruction.append("\nFor every R.* or XML resource reference, make sure the referenced resource exists after your operations are applied.");
        }
        return instruction.toString();
    }
}
