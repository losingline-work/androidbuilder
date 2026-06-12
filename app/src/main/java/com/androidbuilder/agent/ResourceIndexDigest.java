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

    private static ResourceIndex collectResources(File sourceDir) throws Exception {
        ResourceIndex index = new ResourceIndex();
        for (String type : TYPE_ORDER) {
            index.resources.put(type, new TreeSet<String>());
        }
        File resDir = resolveResDir(sourceDir);
        collect(resDir, index);
        return index;
    }

    private static String render(ResourceIndex index, int maxChars) {
        List<String> sections = new ArrayList<>();
        appendResourceSection(sections, index.resources, "id");
        appendResourceSection(sections, index.resources, "layout");
        appendResourceSection(sections, index.resources, "string");
        appendLayoutIdSection(sections, index.idsByLayout);
        appendResourceSection(sections, index.resources, "menu");
        appendResourceSection(sections, index.resources, "drawable");
        appendResourceSection(sections, index.resources, "mipmap");
        appendResourceSection(sections, index.resources, "color");
        appendResourceSection(sections, index.resources, "dimen");
        appendResourceSection(sections, index.resources, "style");
        if (sections.isEmpty()) {
            return "(no Android resources found)";
        }
        if (maxChars <= 0) {
            return joinSections(sections);
        }
        return joinBudgetedSections(sections, maxChars);
    }

    private static void appendResourceSection(List<String> sections, Map<String, TreeSet<String>> resources, String type) {
        TreeSet<String> names = resources.get(type);
        if (names != null && !names.isEmpty()) {
            sections.add("R." + type + ": " + join(names));
        }
    }

    private static void appendLayoutIdSection(List<String> sections, TreeMap<String, TreeSet<String>> idsByLayout) {
        if (idsByLayout.isEmpty()) {
            return;
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : idsByLayout.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                groups.add(entry.getKey() + "[" + join(entry.getValue()) + "]");
            }
        }
        if (!groups.isEmpty()) {
            sections.add("R.id by layout: " + joinList(groups, " | "));
        }
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

    private static void collect(File file, ResourceIndex index) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                collect(child, index);
            }
            return;
        }
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        String resourceName = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (fileName.endsWith(".xml")) {
            String text = FileUtils.readText(file);
            TreeSet<String> ids = collectXmlIds(text);
            index.resources.get("id").addAll(ids);
            if (parent.startsWith("values")) {
                collectValueResources(text, index.resources);
            }
            if (parent.startsWith("layout") && !ids.isEmpty()) {
                index.idsByLayout.put(resourceName, ids);
            }
        }
        if (parent.startsWith("layout") && fileName.endsWith(".xml")) {
            index.resources.get("layout").add(resourceName);
        } else if (parent.startsWith("menu") && fileName.endsWith(".xml")) {
            index.resources.get("menu").add(resourceName);
        } else if (parent.startsWith("drawable")) {
            index.resources.get("drawable").add(resourceName);
        } else if (parent.startsWith("mipmap")) {
            index.resources.get("mipmap").add(resourceName);
        } else if (parent.startsWith("color")) {
            index.resources.get("color").add(resourceName);
        }
    }

    private static TreeSet<String> collectXmlIds(String text) {
        TreeSet<String> ids = new TreeSet<>();
        Matcher matcher = XML_ID.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
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

    private static String joinList(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static final class ResourceIndex {
        final Map<String, TreeSet<String>> resources = new TreeMap<>();
        final TreeMap<String, TreeSet<String>> idsByLayout = new TreeMap<>();
    }
}
