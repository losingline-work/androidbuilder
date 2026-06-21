package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes two aapt-rejected attribute values the model writes that otherwise loop the repair forever
 * (project-27 burned dozens of build rounds on exactly these):
 * <ul>
 *   <li>{@code android:layout_width|layout_height="100%"} — a percentage is not a valid dimension; the only
 *       correct intent is {@code match_parent} ("'100%' is incompatible with attribute width").</li>
 *   <li>{@code @android:drawable/ic_menu_*} — the framework options-menu icon family is PRIVATE since API 1,
 *       so aapt fails linking ("…ic_menu_home is private"). Rewrite to a local {@code @drawable/fw_ic_menu_*}
 *       reference; {@link CrossReferenceReconciler} (which runs right after) seeds a valid placeholder for it.</li>
 * </ul>
 *
 * <p>Runs at the generation merge, BEFORE CrossReferenceReconciler so the rewritten local drawable gets
 * seeded. Edits in place, self-validating (a rewrite that would produce malformed XML is skipped), best-effort.
 */
final class LayoutValueReconciler {
    private static final Pattern PERCENT_SIZE =
            Pattern.compile("(android:layout_(?:width|height)\\s*=\\s*\")100%(\")");
    private static final Pattern PRIVATE_MENU_DRAWABLE =
            Pattern.compile("@android:drawable/(ic_menu_[A-Za-z0-9_]+)");

    private LayoutValueReconciler() {
    }

    /** Returns greppable labels for each fix (empty when nothing needed fixing). */
    static List<String> reconcile(File sourceDir) {
        List<String> fixed = new ArrayList<>();
        File resDir = new File(sourceDir, "app/src/main/res");
        for (File xml : resXmlFiles(resDir)) {
            String content = readText(xml);
            if (content.isEmpty()) {
                continue;
            }
            String updated = PERCENT_SIZE.matcher(content).replaceAll("$1match_parent$2");
            updated = PRIVATE_MENU_DRAWABLE.matcher(updated).replaceAll("@drawable/fw_$1");
            if (updated.equals(content) || TaskOperationsPreflight.xmlError(updated) != null) {
                continue; // no change, or self-validate: never write malformed XML
            }
            try {
                FileUtils.writeText(xml, updated);
                fixed.add("fixed aapt-rejected layout value in " + xml.getName());
            } catch (Exception ignored) {
            }
        }
        return fixed;
    }

    private static List<File> resXmlFiles(File resDir) {
        List<File> out = new ArrayList<>();
        collect(resDir, out);
        return out;
    }

    private static void collect(File file, List<File> out) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(".xml")) {
                out.add(file);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collect(child, out);
        }
    }

    private static String readText(File file) {
        try {
            return file != null && file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
