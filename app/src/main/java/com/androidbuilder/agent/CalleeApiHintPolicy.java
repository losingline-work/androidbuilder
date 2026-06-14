package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a merge-time guard rejection into an authoritative-API hint. The guard says
 * "missing method: RecurringDao.findById(long)" but never tells the model what RecurringDao actually
 * declares - so the model keeps re-inventing the same call (project-84: findById on a DAO that has
 * none, {@code new BudgetCalculator()} when the only ctor is BudgetCalculator(TransactionDao,
 * DateUtils)). This lists, for every class named in the rejection, the methods and constructors it
 * REALLY declares (from the {@link SymbolTable} built off the current tree), so the model reconciles
 * against the truth instead of guessing from a digest it has demonstrably ignored.
 *
 * <p>Purely advisory context. The merge-time AndroidSourceGuard remains the sole authority; this only
 * helps the next attempt converge.
 */
final class CalleeApiHintPolicy {
    private static final Pattern MISSING_METHOD = Pattern.compile("missing method:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.[A-Za-z_][A-Za-z0-9_]*\\(");
    private static final Pattern ARG_MISMATCH = Pattern.compile("method argument mismatch:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.[A-Za-z_][A-Za-z0-9_]*\\(");
    private static final Pattern MISSING_FIELD = Pattern.compile("missing class field:\\s*([A-Za-z_][A-Za-z0-9_]*)\\.");
    private static final Pattern NEW_EXPR = Pattern.compile("\\bnew\\s+([A-Za-z_][A-Za-z0-9_]*)\\(");

    private CalleeApiHintPolicy() {
    }

    static String hint(String message, SymbolTable symbols) {
        if (message == null || message.trim().isEmpty() || symbols == null) {
            return "";
        }
        Set<String> classes = new LinkedHashSet<>();
        collect(MISSING_METHOD, message, classes);
        collect(ARG_MISMATCH, message, classes);
        collect(MISSING_FIELD, message, classes);
        collect(NEW_EXPR, message, classes);

        StringBuilder body = new StringBuilder();
        for (String className : classes) {
            // Only describe classes we can actually see (generated/in-draft). Platform/library types
            // are out of scope and giving advice on them would be wrong.
            if (!symbols.hasClass(className)) {
                continue;
            }
            List<String> methods = new ArrayList<>(symbols.declaredMethodNames(className));
            Collections.sort(methods);
            List<List<String>> ctors = symbols.declaredConstructors(className);

            StringBuilder line = new StringBuilder();
            line.append("- ").append(className).append(" declares methods: ")
                    .append(methods.isEmpty() ? "(none)" : join(methods));
            if (!ctors.isEmpty()) {
                line.append("; constructors: ");
                for (int i = 0; i < ctors.size(); i++) {
                    if (i > 0) {
                        line.append(", ");
                    }
                    line.append(className).append('(').append(join(ctors.get(i))).append(')');
                }
            }
            line.append('.');
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);
        }
        if (body.length() == 0) {
            return "";
        }
        return "Authoritative API of the classes involved (match these EXACTLY - do not invent members):\n"
                + body
                + "\nEvery call and `new` must resolve to a member listed above. If a caller genuinely needs "
                + "one that is missing, ADD it to that class in this same response with a matching signature; "
                + "otherwise call an existing one. Do not leave a call to a member that is not listed.";
    }

    private static void collect(Pattern pattern, String message, Set<String> classes) {
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }
}
