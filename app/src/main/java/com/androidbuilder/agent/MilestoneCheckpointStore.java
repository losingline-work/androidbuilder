package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * Persistent per-milestone snapshots of the generated app's source tree. After a milestone builds green its
 * source is copied to {@code projectRoot/checkpoints/m<order>}; if a later milestone cannot be made to build,
 * the live source is restored from the most recent green checkpoint so the project never regresses below the
 * last runnable app. Distinct from {@code FileOperationsWriter}'s transient {@code .backup-<ts>} dirs, which
 * exist only for the duration of a single apply.
 */
public final class MilestoneCheckpointStore {
    private static final String DIR = "checkpoints";

    private MilestoneCheckpointStore() {
    }

    /** {@code projectRoot/checkpoints/m<orderIndex>}. */
    public static File checkpointDir(File projectRoot, int orderIndex) {
        return new File(new File(projectRoot, DIR), "m" + orderIndex);
    }

    /** Snapshot the current source tree as this milestone's green checkpoint, replacing any prior snapshot. */
    public static void save(File sourceDir, File checkpointDir) throws IOException {
        FileUtils.deleteRecursively(checkpointDir);
        if (sourceDir != null && sourceDir.exists()) {
            FileUtils.copyRecursively(sourceDir, checkpointDir);
        } else if (!checkpointDir.mkdirs()) {
            throw new IOException("Cannot create checkpoint directory: " + checkpointDir);
        }
    }

    /** Restore a green checkpoint back into the live source tree (rollback of a failed later milestone). */
    public static void restore(File checkpointDir, File sourceDir) throws IOException {
        if (!exists(checkpointDir)) {
            throw new IOException("Checkpoint does not exist: " + checkpointDir);
        }
        FileUtils.deleteRecursively(sourceDir);
        FileUtils.copyRecursively(checkpointDir, sourceDir);
    }

    public static boolean exists(File checkpointDir) {
        return checkpointDir != null && checkpointDir.isDirectory();
    }
}
