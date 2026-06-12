package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ResourceIndexDigest {
    private static final Pattern XML_ID = Pattern.compile("android:id\\s*=\\s*[\"']@\\+?id/([A-Za-z_][A-Za-z0-9_]*)[\"']");
    private static final Pattern NAMED_VALUE_RESOURCE = Pattern.compile("<\\s*(string|color|dimen|style)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final String[] TYPE_ORDER = {
            "id", "layout", "menu", "drawable", "mipmap", "color", "dimen", "string", "style"
    };

    private ResourceIndexDigest() {
    }

    static String digest(File sourceDir) throws Exception {
        return render(collectResources(sourceDir), 0);
    }

    static String digest(File sourceDir, int maxChars) throws Exception {
        return render(collectResources(sourceDir), maxChars);
    }

    private static Map<String, TreeSet<String>> collectResources(File sourceDir) throws Exception {
        Map<String, TreeSet<String>> resources = new TreeMap<>();
        for (String type : TYPE_ORDER) {
            resources.put(type, new TreeSet<String>());
        }
        File resDir = resolveResDir(sourceDir);
        collect(resDir, resources);
        return resources;
    }

    private static String render(Map<String, TreeSet<String>> resources, int maxChars) {
        List<String> sections = new ArrayList<>();
        for (String type : TYPE_ORDER) {
            TreeSet<String> names = resources.get(type);
            if (names == null || names.isEmpty()) {
                continue;
            }
            sections.add("R." + type + ": " + join(names));
        }
        if (sections.isEmpty()) {
            return "(no Android resources found)";
        }
        if (maxChars <= 0) {
            return joinSections(sections);
        }
        return joinBudgetedSections(sections, maxChars);
    }

    private static String joinBudgetedSections(List<String> sections, int maxChars) {
        List<String> ordered = new ArrayList<>();
        for (String section : sections) {
            if (isCriticalSection(section)) {
                ordered.add(section);
            }
        }
        for (String section : sections) {
            if (!isCriticalSection(section)) {
                ordered.add(section);
            }
        }
        StringBuilder builder = new StringBuilder();
        boolean truncated = false;
        for (String section : ordered) {
            boolean critical = isCriticalSection(section);
            int prefix = builder.length() == 0 ? 0 : 3;
            if (!critical && builder.length() + prefix + section.length() > maxChars) {
                truncated = true;
                break;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(section);
        }
        if (truncated) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append("...[truncated]");
        }
        return builder.toString();
    }

    private static boolean isCriticalSection(String section) {
        return section.startsWith("R.id: ") ||
                section.startsWith("R.layout: ") ||
                section.startsWith("R.string: ");
    }

    private static File resolveResDir(File sourceDir) {
        if (sourceDir == null) {
            return null;
        }
        File appRes = new File(sourceDir, "app/src/main/res");
        if (appRes.exists()) {
            return appRes;
        }
        return sourceDir;
    }

    private static void collect(File file, Map<String, TreeSet<String>> resources) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collect(child, resources);
            }
            return;
        }
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        String resourceName = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (fileName.endsWith(".xml")) {
            String text = FileUtils.readText(file);
            collectXmlIds(text, resources.get("id"));
            if (parent.startsWith("values")) {
                collectValueResources(text, resources);
            }
        }
        if (parent.startsWith("layout") && fileName.endsWith(".xml")) {
            resources.get("layout").add(resourceName);
        } else if (parent.startsWith("menu") && fileName.endsWith(".xml")) {
            resources.get("menu").add(resourceName);
        } else if (parent.startsWith("drawable")) {
            resources.get("drawable").add(resourceName);
        } else if (parent.startsWith("mipmap")) {
            resources.get("mipmap").add(resourceName);
        } else if (parent.startsWith("color")) {
            resources.get("color").add(resourceName);
        }
    }

    private static void collectXmlIds(String text, TreeSet<String> ids) {
        Matcher matcher = XML_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
    }

    private static void collectValueResources(String text, Map<String, TreeSet<String>> resources) {
        Matcher matcher = NAMED_VALUE_RESOURCE.matcher(text);
        while (matcher.find()) {
            TreeSet<String> names = resources.get(matcher.group(1));
            if (names != null) {
                names.add(javaResourceName(matcher.group(2)));
            }
        }
    }

    private static String javaResourceName(String name) {
        return name == null ? "" : name.replace('.', '_');
    }

    private static String join(TreeSet<String> names) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String name : names) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(name);
            index++;
        }
        return builder.toString();
    }

    private static String joinSections(List<String> sections) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(sections.get(i));
        }
        return builder.toString();
    }
}
