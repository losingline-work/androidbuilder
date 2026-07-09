package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When a weak model is asked to "fix ALL these javac errors at once" across many files, it tends to fix a few
 * and break others, so the whole-log repair oscillates. Under degrade, narrow each repair round to the SINGLE
 * file with the most errors — a smaller, convergent step. The stall policy still bounds the loop; this just
 * makes each round make provable progress on one file.
 *
 * <p>Engages ONLY when: the march is already degrading (level ≥ 1), the model is not escalating (escalation
 * forces whole-file rewrites and must see the whole log), and more than two files have errors. It also
 * rotates off a file that did not shrink last round, so single-file focus can never eat the whole repair
 * budget on one stuck file.
 */
public final class RepairFocusPolicy {
    static final int MIN_FILES_TO_FOCUS = 3;

    private RepairFocusPolicy() {
    }

    static boolean shouldFocus(int degradeLevel, boolean escalate, int fileCount) {
        return degradeLevel >= 1 && !escalate && fileCount >= MIN_FILES_TO_FOCUS;
    }

    /**
     * The file to focus this round: the one with the most errors, but skipping a file that was focused last
     * round and did NOT shrink (its current error count &gt;= last round's), so a stuck file yields to the
     * next-worst. Returns null when there is nothing to focus.
     */
    static String pickFocusFile(LinkedHashMap<String, List<String>> clusters, String lastFocusFile, int lastFocusErrorCount) {
        if (clusters == null || clusters.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(clusters.entrySet());
        // Stable sort by error count desc; ties keep first-seen (build/producer order).
        Collections.sort(entries, new Comparator<Map.Entry<String, List<String>>>() {
            @Override
            public int compare(Map.Entry<String, List<String>> a, Map.Entry<String, List<String>> b) {
                return Integer.compare(b.getValue().size(), a.getValue().size());
            }
        });
        for (Map.Entry<String, List<String>> entry : entries) {
            boolean stuck = entry.getKey().equals(lastFocusFile) && entry.getValue().size() >= lastFocusErrorCount;
            if (!stuck) {
                return entry.getKey();
            }
        }
        // Every candidate is the stuck file (only one file has errors) — focus it anyway.
        return entries.get(0).getKey();
    }

    /** The instruction clause telling the model to fix only {@code file} this round, with its diagnostics. */
    static String focusClause(String file, List<String> errors, boolean chinese) {
        StringBuilder clause = new StringBuilder();
        if (chinese) {
            clause.append("本轮只修复文件 ").append(file)
                    .append(" 里的编译错误（下面列出）；不要改动其它文件，它们的错误会在后续轮次处理。\n");
        } else {
            clause.append("This round, fix ONLY the compile errors in ").append(file)
                    .append(" (listed below); do not touch other files — their errors are handled in later rounds.\n");
        }
        if (errors != null) {
            for (String error : errors) {
                clause.append("- ").append(error).append('\n');
            }
        }
        return clause.toString().trim();
    }
}
