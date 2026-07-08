package com.androidbuilder.agent;

/**
 * Adapts generation parameters to a weak model that is visibly struggling THIS march. The system otherwise
 * runs one static configuration for every model; a model that keeps truncating, failing to parse, or dropping
 * planned files needs smaller batches and lower temperature, not the same oversized request retried.
 *
 * <p>Counters accumulate per project-march (in {@link AgentService}, reset when a new plan is created); this
 * class is the pure mapping counters → level → parameters, so every threshold is unit-tested. Level engages
 * only AFTER observed failures, so a healthy strong model is never slowed down.
 */
public final class GenerationDegradePolicy {
    // A weighted event score; hard failures (parse/abort/carry-forward exhaustion) count double a soft
    // salvage/preflight finding. Tuned conservatively — L1 after ~one hard failure, L2 after a few.
    static final int LEVEL1_THRESHOLD = 2;
    static final int LEVEL2_THRESHOLD = 5;

    private GenerationDegradePolicy() {
    }

    /** Mutable per-march failure tally. AgentService increments; {@link #level} reads. */
    public static final class Counters {
        int parseFailures;
        int salvaged;
        int streamAborts;
        int carryForwardExhaustions;
        int preflightFindings;

        int weightedScore() {
            return parseFailures * 2 + streamAborts * 2 + carryForwardExhaustions * 2
                    + salvaged + preflightFindings;
        }
    }

    /** 0 = healthy (no change), 1 = mild degrade, 2 = aggressive degrade. */
    static int level(Counters counters) {
        int score = counters == null ? 0 : counters.weightedScore();
        if (score >= LEVEL2_THRESHOLD) {
            return 2;
        }
        if (score >= LEVEL1_THRESHOLD) {
            return 1;
        }
        return 0;
    }

    /** Max weight per cloud call: L0 default 10, L1 → 6, L2 → 3 (near one heavy file per call). */
    static int maxBatchWeight(int level) {
        if (level >= 2) {
            return 3;
        }
        if (level == 1) {
            return 6;
        }
        return ManifestBatchPolicy.MAX_BATCH_WEIGHT;
    }

    /** Single-batch file-count threshold: shrinks to 1 at L2 so even a few files split. */
    static int singleBatchThreshold(int level) {
        if (level >= 2) {
            return 1;
        }
        return ManifestBatchPolicy.SINGLE_BATCH_THRESHOLD;
    }

    /** Drop sampling to greedy (0.0) once degrading; some providers ignore it (best-effort). */
    static double temperature(int level, double base) {
        return level >= 1 ? 0.0 : base;
    }

    /** Snapshot full-text budget: shrink to leave more output room (not wired yet — see AgentService). */
    static int fullTextLimit(int level, int base) {
        if (level >= 2) {
            return 8000;
        }
        if (level == 1) {
            return 10000;
        }
        return base;
    }
}
