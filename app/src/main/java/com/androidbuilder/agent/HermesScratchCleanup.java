package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;

final class HermesScratchCleanup {
    private HermesScratchCleanup() {
    }

    static void afterMerge(File agentsRoot, boolean fullSuccess) {
        if (agentsRoot == null || !agentsRoot.exists()) {
            return;
        }
        try {
            if (fullSuccess) {
                FileUtils.deleteRecursively(agentsRoot);
                return;
            }
            File[] children = agentsRoot.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (child != null && child.isDirectory()) {
                    FileUtils.deleteRecursively(new File(child, "source"));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
