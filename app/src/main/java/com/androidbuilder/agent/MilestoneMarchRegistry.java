package com.androidbuilder.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory marker of which projects are currently running an auto-march, so the build/repair message
 * emitters (the build server, the repair loop) can keep their per-round "build started / build failed /
 * repair complete" chatter OUT of the timeline while a march is driving — the milestone progress strip and
 * the transient operation status carry that state instead. Outside a march these messages are kept (manual
 * build/repair still narrates each step). UI-layer state, but read by backend emitters, hence a tiny shared
 * registry (mirrors ActiveWorkRegistry).
 */
public final class MilestoneMarchRegistry {
    private static final Set<Long> ACTIVE = ConcurrentHashMap.newKeySet();

    private MilestoneMarchRegistry() {
    }

    public static void setActive(long projectId, boolean active) {
        if (active) {
            ACTIVE.add(projectId);
        } else {
            ACTIVE.remove(projectId);
        }
    }

    public static boolean isActive(long projectId) {
        return ACTIVE.contains(projectId);
    }
}
