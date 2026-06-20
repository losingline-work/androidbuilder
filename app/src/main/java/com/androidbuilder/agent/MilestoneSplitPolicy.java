package com.androidbuilder.agent;

import com.androidbuilder.model.ProjectMilestoneRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministically splits a coarse model-produced milestone into finer ones, WITHOUT relying on a weak
 * model to have followed the "one unit per milestone" instruction. A milestone that bundles multiple build
 * units (e.g. "Category / Transaction 表", "BookFragment + RecordDetailActivity") is broken into one
 * milestone per unit, shrinking the per-step blast radius that weak models choke on.
 *
 * <p>Conservative by construction: it only splits on STRONG separators (/ 、 + &) — never on 与/和/and, which
 * usually join a unit with its seed data ("Account 表与预置账户") — and only when EVERY resulting part looks
 * like a real build unit (a CamelCase class name or a unit keyword like 表/页/Activity/chart). So a chart's
 * sub-aspects ("收支对比 / 累计结余") are left intact rather than mis-split. When in doubt it returns the
 * milestone unchanged.
 */
public final class MilestoneSplitPolicy {
    /** Never explode one milestone into more than this many parts — beyond it, trust the model's grouping. */
    public static final int MAX_SPLIT_PARTS = 4;


    private static final String[] UNIT_KEYWORDS = {
            "表", "页", "屏", "对话框", "管理", "列表", "图表",
            "键盘", "通知", "导出", "导入", "备份", "还原", "组件",
            "table", "activity", "fragment", "tab", "dialog", "dao", "screen", "adapter", "view", "widget", "chart"
    };

    private MilestoneSplitPolicy() {
    }

    /** Split one milestone into 1..MAX_SPLIT_PARTS finer ones; returns a singleton when no safe split applies. */
    public static List<ProjectMilestoneRecord> split(ProjectMilestoneRecord milestone, boolean chinese) {
        String title = milestone.title == null ? "" : milestone.title.trim();
        String prefix = "";
        int colon = indexOfColon(title);
        String detail;
        if (colon >= 0) {
            prefix = title.substring(0, colon).trim();
            detail = title.substring(colon + 1).trim();
        } else {
            detail = title;
        }
        List<String> parts = splitUnits(detail);
        if (parts.size() < 2 && !hasSeparator(detail)) {
            // The title has no unit list at all — the list may live in the slice instead. (When the title DID
            // have separators but split to one unit, e.g. "DBHelper + Account 表", that merge is authoritative
            // and must NOT be second-guessed by the slice.)
            parts = splitUnits(milestone.slice == null ? "" : milestone.slice.trim());
            prefix = title;
        }
        if (parts.size() < 2 || parts.size() > MAX_SPLIT_PARTS) {
            return Collections.singletonList(milestone);
        }
        List<ProjectMilestoneRecord> out = new ArrayList<>();
        for (String part : parts) {
            out.add(subMilestone(milestone, prefix, part, chinese));
        }
        return out;
    }

    private static ProjectMilestoneRecord subMilestone(ProjectMilestoneRecord origin, String prefix, String part, boolean chinese) {
        String subTitle = truncate(prefix.isEmpty() ? part : (prefix + " · " + part), 70);
        String description = chinese
                ? ("在里程碑「" + origin.title + "」内，本步只实现：" + part
                        + "。复用已有的类、资源与 DBHelper，保持 app 可编译运行。")
                : ("Within milestone \"" + origin.title + "\", implement ONLY: " + part + ". Reuse existing classes/resources and the DBHelper; keep the app compiling and runnable.");
        String slice = chinese
                ? (part + "（只做这一项，复用已有代码）")
                : (part + " (only this part; reuse existing code)");
        return new ProjectMilestoneRecord(0, 0, 0, subTitle, description, slice, MilestoneStatus.PENDING, "", 0, 0, 0, 0);
    }

    private static List<String> splitUnits(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        // Strong separators that denote distinct build units (regex character class). 与/和/and are
        // intentionally excluded — they usually join a unit with its seed data, not two independent units.
        for (String raw : text.split("[/／、＋+&]")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        // A bare helper ("DBHelper") is not its own unit — fold it into the first real unit.
        if (parts.size() >= 2 && isBareHelper(parts.get(0))) {
            String merged = parts.get(0) + " " + parts.get(1);
            parts.remove(0);
            parts.set(0, merged);
        }
        if (parts.size() < 2) {
            return Collections.emptyList();
        }
        // Only treat the split as real when every part names a build unit; otherwise the separator was
        // joining sub-aspects of ONE unit (e.g. a chart's two series), which must NOT be split.
        for (String part : parts) {
            if (!hasUnitSignal(part)) {
                return Collections.emptyList();
            }
        }
        return parts;
    }

    private static boolean hasSeparator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '／' || c == '、' || c == '＋' || c == '+' || c == '&') {
                return true;
            }
        }
        return false;
    }

    private static boolean isBareHelper(String part) {
        String p = part.trim().toLowerCase();
        return p.equals("dbhelper") || p.equals("databasehelper") || p.equals("database helper")
                || p.equals("db helper") || p.equals("数据库") || p.equals("数据库帮助类");
    }

    /** A part is a real build unit if it names a CamelCase class or contains a unit keyword. */
    private static boolean hasUnitSignal(String part) {
        if (containsCamelCaseIdentifier(part)) {
            return true;
        }
        String lower = part.toLowerCase();
        for (String keyword : UNIT_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** An ASCII uppercase letter followed by >=2 more ASCII letters/digits — e.g. Category, BookFragment. */
    private static boolean containsCamelCaseIdentifier(String part) {
        int run = 0;
        boolean sawUpper = false;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                if (Character.isUpperCase(c)) {
                    sawUpper = true;
                }
                run++;
                if (sawUpper && run >= 3) {
                    return true;
                }
            } else {
                run = 0;
                sawUpper = false;
            }
        }
        return false;
    }

    private static int indexOfColon(String value) {
        int a = value.indexOf('：');
        int b = value.indexOf(':');
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1).trim() + "…";
    }
}
