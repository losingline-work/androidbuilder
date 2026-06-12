package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BatchValidationPolicy {
    private BatchValidationPolicy() {
    }

    static String review(List<FileOperation> batchOps,
                         List<String> manifestPathsForBatch,
                         HermesTaskContract contract,
                         ResourceSymbolsOverlay acceptedSoFar,
                         File sourceDir) {
        List<FileOperation> canonicalOps = canonicalOps(batchOps);
        for (FileOperation operation : canonicalOps) {
            if (!"write".equals(operation.action) && !"delete".equals(operation.action)) {
                return "Batch validation: batch operations must be full write or delete; got " + operation.action + " for " + operation.path + ". Resend the complete file content.";
            }
        }
        Set<String> plannedPaths = canonicalPaths(manifestPathsForBatch);
        Set<String> actualPaths = operationPaths(canonicalOps);
        for (String actualPath : actualPaths) {
            if (!plannedPaths.contains(actualPath)) {
                return "Batch validation: batch contained unplanned file " + actualPath + "; regenerate only the requested files.";
            }
        }
        for (String plannedPath : plannedPaths) {
            if (!actualPaths.contains(plannedPath)) {
                return "Batch validation: missing planned file " + plannedPath + ".";
            }
        }
        String streamError = TaskStreamPreflight.review(canonicalOps, contract);
        if (streamError != null) {
            return streamError;
        }
        ResourceSymbolsOverlay symbols = ResourceSymbolsOverlay.fromSourceDir(sourceDir);
        symbols.addAll(acceptedSoFar);
        for (FileOperation operation : canonicalOps) {
            if ("write".equals(operation.action) && operation.path.endsWith(".java")) {
                String error = validateJavaResources(operation, symbols);
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private static List<FileOperation> canonicalOps(List<FileOperation> operations) {
        List<FileOperation> canonical = new ArrayList<>();
        if (operations == null) {
            return canonical;
        }
        for (FileOperation operation : operations) {
            canonical.add(CanonicalPathPolicy.canonicalOperation(operation));
        }
        return canonical;
    }

    private static Set<String> canonicalPaths(List<String> paths) {
        Set<String> canonical = new LinkedHashSet<>();
        if (paths == null) {
            return canonical;
        }
        for (String path : paths) {
            canonical.add(CanonicalPathPolicy.canonicalize(path));
        }
        return canonical;
    }

    private static Set<String> operationPaths(List<FileOperation> operations) {
        Set<String> paths = new LinkedHashSet<>();
        for (FileOperation operation : operations) {
            paths.add(operation.path);
        }
        return paths;
    }

    private static String validateJavaResources(FileOperation operation, ResourceSymbolsOverlay symbols) {
        String scannable = JavaApiDigest.stripJavaCommentsAndStrings(operation.content == null ? "" : operation.content);
        String fileName = new File(operation.path).getName();
        String error = missing(AndroidSourceGuard.R_ID, symbols.ids, "XML id", "R.id", scannable, fileName);
        if (error != null) {
            return error;
        }
        error = missing(AndroidSourceGuard.R_LAYOUT, symbols.layouts, "layout resource", "R.layout", scannable, fileName);
        if (error != null) {
            return error;
        }
        error = missing(AndroidSourceGuard.R_STRING, symbols.strings, "string resource", "R.string", scannable, fileName);
        if (error != null) {
            return error;
        }
        error = missing(AndroidSourceGuard.R_COLOR, symbols.colors, "color resource", "R.color", scannable, fileName);
        if (error != null) {
            return error;
        }
        error = missing(AndroidSourceGuard.R_DRAWABLE, symbols.drawables, "drawable resource", "R.drawable", scannable, fileName);
        if (error != null) {
            return error;
        }
        error = missing(AndroidSourceGuard.R_MIPMAP, symbols.mipmaps, "mipmap resource", "R.mipmap", scannable, fileName);
        if (error != null) {
            return error;
        }
        return missing(AndroidSourceGuard.R_STYLE, symbols.styles, "style resource", "R.style", scannable, fileName);
    }

    private static String missing(Pattern pattern, Set<String> known, String label, String prefix, String content, String fileName) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!known.contains(name)) {
                return "Batch validation: missing " + label + " " + prefix + "." + name + " in " + fileName + ".";
            }
        }
        return null;
    }
}
