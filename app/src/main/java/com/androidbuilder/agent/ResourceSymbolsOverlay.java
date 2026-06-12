package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

final class ResourceSymbolsOverlay {
    final Set<String> ids = new HashSet<>();
    final Set<String> layouts = new HashSet<>();
    final Set<String> strings = new HashSet<>();
    final Set<String> colors = new HashSet<>();
    final Set<String> drawables = new HashSet<>();
    final Set<String> mipmaps = new HashSet<>();
    final Set<String> styles = new HashSet<>();

    private ResourceSymbolsOverlay() {
    }

    static ResourceSymbolsOverlay empty() {
        return new ResourceSymbolsOverlay();
    }

    static ResourceSymbolsOverlay fromSourceDir(File sourceDir) {
        ResourceSymbolsOverlay symbols = empty();
        File resDir = sourceDir == null ? null : new File(sourceDir, "app/src/main/res");
        collectExisting(resDir, symbols);
        return symbols;
    }

    void absorb(List<FileOperation> operations) {
        if (operations == null) {
            return;
        }
        for (FileOperation operation : operations) {
            if (operation == null || !"write".equals(operation.action)) {
                continue;
            }
            String path = CanonicalPathPolicy.canonicalize(operation.path);
            if (!path.endsWith(".xml")) {
                continue;
            }
            absorbXml(path, operation.content == null ? "" : operation.content);
        }
    }

    void addAll(ResourceSymbolsOverlay other) {
        if (other == null) {
            return;
        }
        ids.addAll(other.ids);
        layouts.addAll(other.layouts);
        strings.addAll(other.strings);
        colors.addAll(other.colors);
        drawables.addAll(other.drawables);
        mipmaps.addAll(other.mipmaps);
        styles.addAll(other.styles);
    }

    private void absorbXml(String path, String content) {
        Matcher idMatcher = AndroidSourceGuard.XML_ID.matcher(content);
        while (idMatcher.find()) {
            ids.add(idMatcher.group(1));
        }
        if (path.startsWith("app/src/main/res/layout")) {
            layouts.add(fileStem(path));
        } else if (path.startsWith("app/src/main/res/drawable")) {
            drawables.add(fileStem(path));
        } else if (path.startsWith("app/src/main/res/mipmap")) {
            mipmaps.add(fileStem(path));
        } else if (path.startsWith("app/src/main/res/values")) {
            Matcher valueMatcher = AndroidSourceGuard.NAMED_VALUE_RESOURCE.matcher(content);
            while (valueMatcher.find()) {
                addValue(valueMatcher.group(1), valueMatcher.group(2));
            }
        }
    }

    private void addValue(String type, String name) {
        String javaName = name.replace('.', '_');
        if ("string".equals(type)) {
            strings.add(name);
            strings.add(javaName);
        } else if ("color".equals(type)) {
            colors.add(name);
            colors.add(javaName);
        } else if ("style".equals(type)) {
            styles.add(name);
            styles.add(javaName);
        }
    }

    private static void collectExisting(File file, ResourceSymbolsOverlay symbols) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            try {
                String path = canonicalExistingPath(file);
                symbols.absorbXml(path, FileUtils.readText(file));
            } catch (Exception ignored) {
                // Existing unreadable resources will still be handled by the final source guard.
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectExisting(child, symbols);
        }
    }

    private static String canonicalExistingPath(File file) {
        String path = file.getPath().replace(File.separatorChar, '/');
        int marker = path.indexOf("app/src/main/res/");
        return marker >= 0 ? path.substring(marker) : path;
    }

    private static String fileStem(String path) {
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
