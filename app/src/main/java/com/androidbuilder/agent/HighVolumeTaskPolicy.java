package com.androidbuilder.agent;

/**
 * Identifies the canned implementation phases that emit many files at once (every drawable + every
 * layout, the whole values/theme set, the whole Java wiring layer). A single {@code createTaskOperations}
 * response for these overflows the model's output budget and truncates mid-array ("Unterminated array"),
 * which the parser cannot recover, looping the task to retry-exhaustion. Such tasks must always go
 * through batched generation (a small file manifest, then weight-bounded batches) so no single response
 * is ever oversized — including on correction/retry passes, where the plain gate would otherwise fall
 * back to a single oversized request.
 *
 * <p>Titles are matched exactly, mirroring {@code ImplementationTaskNormalizer}'s canned phase titles.
 */
final class HighVolumeTaskPolicy {
    private HighVolumeTaskPolicy() {
    }

    static boolean isHighVolume(String taskTitle) {
        if (taskTitle == null) {
            return false;
        }
        String title = taskTitle.trim();
        return CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.DRAWABLE_LAYOUT)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.RESOURCES)
                || CanonicalTaskPhase.is(title, CanonicalTaskPhase.Phase.JAVA);
    }
}
