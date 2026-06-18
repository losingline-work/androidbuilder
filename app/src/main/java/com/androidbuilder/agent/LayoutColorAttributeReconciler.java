package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes a guaranteed launch crash: a COLOR-only attribute pointing at a {@code @drawable/…}. A drawable is
 * never a valid color, so resolving e.g. {@code app:cardBackgroundColor="@drawable/bg_card"} at inflate time
 * throws {@code UnsupportedOperationException: Can't convert to ComplexColor} inside the view constructor
 * (project-135: CardView). aapt accepts it because the drawable EXISTS — which {@link CrossReferenceReconciler}
 * can even make true by seeding the missing drawable — so only runtime catches it.
 *
 * <p>Runs at the generation merge. The fix is to STRIP the offending attribute (the view falls back to its
 * default color), which is always safe: the attribute was certain to crash, and a color attribute never
 * legitimately takes a drawable. Only color-ONLY attributes are touched — never {@code android:background},
 * {@code android:src}, or {@code drawable*}, which legitimately take a drawable. Self-validating.
 */
final class LayoutColorAttributeReconciler {
    // Attributes whose value MUST be a color / ColorStateList (never a drawable).
    private static final String COLOR_ATTRS = String.join("|",
            "textColor", "textColorHint", "textColorLink",
            "tint", "backgroundTint", "foregroundTint", "drawableTint", "buttonTint",
            "thumbTint", "trackTint", "progressTint", "secondaryProgressTint", "indeterminateTint",
            "cardBackgroundColor", "cardForegroundColor", "strokeColor", "rippleColor",
            "tabTextColor", "tabIndicatorColor", "tabRippleColor", "itemTextColor", "itemIconTint",
            "boxStrokeColor", "hintTextColor", "chipBackgroundColor", "chipStrokeColor",
            "iconTint", "endIconTint", "startIconTint");
    private static final Pattern COLOR_ATTR_TO_DRAWABLE =
            Pattern.compile("\\s+(?:android|app):(?:" + COLOR_ATTRS + ")\\b\\s*=\\s*\"@drawable/[^\"]*\"");

    private LayoutColorAttributeReconciler() {
    }

    /** Returns greppable labels for each stripped attribute (empty when no color-as-drawable misuse exists). */
    static List<String> reconcile(File sourceDir) {
        List<String> fixed = new ArrayList<>();
        File resDir = new File(sourceDir, "app/src/main/res");
        for (File xml : layoutXmlFiles(resDir)) {
            String content = readText(xml);
            Matcher matcher = COLOR_ATTR_TO_DRAWABLE.matcher(content);
            if (!matcher.find()) {
                continue;
            }
            String updated = matcher.replaceAll("");
            if (updated.equals(content) || TaskOperationsPreflight.xmlError(updated) != null) {
                continue; // self-validate: never write malformed XML
            }
            try {
                FileUtils.writeText(xml, updated);
                fixed.add("stripped color-attr=@drawable in " + xml.getName());
            } catch (Exception ignored) {
            }
        }
        return fixed;
    }

    private static List<File> layoutXmlFiles(File resDir) {
        List<File> out = new ArrayList<>();
        File[] dirs = resDir == null ? null : resDir.listFiles();
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            if (dir.isDirectory() && dir.getName().startsWith("layout")) {
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".xml")) {
                        out.add(file);
                    }
                }
            }
        }
        return out;
    }

    private static String readText(File file) {
        try {
            return file != null && file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
