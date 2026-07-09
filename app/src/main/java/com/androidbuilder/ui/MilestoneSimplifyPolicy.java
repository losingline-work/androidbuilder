package com.androidbuilder.ui;

/**
 * A weak model often cannot deliver a full feature-slice, but CAN deliver a minimal version of it. So before a
 * march gives up on a milestone and rolls back+stops, try ONCE more with the slice reduced to its smallest
 * viable form (one screen, no extras). Only once per milestone (tracked by simplify_attempts), so a slice that
 * fails even when minimal still ends the march instead of looping.
 */
final class MilestoneSimplifyPolicy {
    static final int MAX_SIMPLIFY_ATTEMPTS = 1;
    /** Repair budget for the retry after simplifying — smaller than the first pass; a minimal slice should
     * need little repair, and we do not want the simplified retry to itself burn a long loop. */
    static final int SIMPLIFIED_REPAIR_ROUNDS = 2;

    private MilestoneSimplifyPolicy() {
    }

    /** True when this milestone may still be retried as a smallest-viable version. */
    static boolean shouldSimplify(int simplifyAttempts) {
        return simplifyAttempts < MAX_SIMPLIFY_ATTEMPTS;
    }

    /** Wrap a slice with an instruction to build only its smallest runnable form. */
    static String simplifiedSliceInstruction(String slice, boolean chinese) {
        String base = slice == null ? "" : slice.trim();
        if (chinese) {
            return base + "\n\n（最小化重试）只实现这个切片的最小可运行版本：单个屏幕、最核心的一条主路径，"
                    + "不要附加功能、设置、边角情况或装饰性 UI，可选项一律延后。宁可少而能跑，也不要多而编译不过。";
        }
        return base + "\n\n(Minimal retry) Build only the SMALLEST runnable version of this slice: a single screen "
                + "and the one core happy-path, with no extras, settings, edge cases, or decorative UI — defer everything "
                + "optional. Prefer less that builds over more that does not compile.";
    }
}
