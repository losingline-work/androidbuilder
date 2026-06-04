package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DatabaseContractNormalizer {
    private static final Pattern DB_HELPER_COLUMN_REFERENCE = Pattern.compile("\\bDBHelper\\.(COL_[A-Z0-9_]+)\\b");
    private static final Pattern DB_HELPER_CLASS = Pattern.compile("\\bclass\\s+DBHelper\\b[^\\{]*\\{");

    private DatabaseContractNormalizer() {
    }

    static void normalize(File sourceDir) throws IOException {
        Set<String> referencedColumns = new TreeSet<>();
        collectDbHelperColumnReferences(sourceDir, referencedColumns);
        if (referencedColumns.isEmpty()) {
            return;
        }
        File helper = findFileNamed(sourceDir, "DBHelper.java");
        if (helper == null || !helper.isFile()) {
            return;
        }
        String content = FileUtils.readText(helper);
        List<String> missing = missingColumns(content, referencedColumns);
        if (missing.isEmpty()) {
            return;
        }
        String normalized = insertColumnConstants(content, missing);
        if (!content.equals(normalized)) {
            FileUtils.writeText(helper, normalized);
        }
    }

    private static void collectDbHelperColumnReferences(File file, Set<String> out) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (!file.getName().endsWith(".java")) {
                return;
            }
            Matcher matcher = DB_HELPER_COLUMN_REFERENCE.matcher(FileUtils.readText(file));
            while (matcher.find()) {
                out.add(matcher.group(1));
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectDbHelperColumnReferences(child, out);
        }
    }

    private static File findFileNamed(File file, String name) {
        if (file == null || !file.exists()) {
            return null;
        }
        if (file.isFile()) {
            return name.equals(file.getName()) ? file : null;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findFileNamed(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> missingColumns(String content, Set<String> referencedColumns) {
        List<String> missing = new ArrayList<>();
        for (String column : referencedColumns) {
            if (!Pattern.compile("\\b" + Pattern.quote(column) + "\\b").matcher(content).find()) {
                missing.add(column);
            }
        }
        return missing;
    }

    private static String insertColumnConstants(String content, List<String> missing) {
        Matcher matcher = DB_HELPER_CLASS.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        StringBuilder constants = new StringBuilder();
        for (String column : missing) {
            constants.append("\n    public static final String ")
                    .append(column)
                    .append(" = \"")
                    .append(columnName(column))
                    .append("\";");
        }
        constants.append("\n");
        int insertAt = matcher.end();
        return content.substring(0, insertAt) + constants + content.substring(insertAt);
    }

    private static String columnName(String constantName) {
        String name = constantName.startsWith("COL_") ? constantName.substring("COL_".length()) : constantName;
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
