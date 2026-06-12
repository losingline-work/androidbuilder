package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;

import java.io.File;
import java.util.List;

/**
 * Streaming preflight only rejects errors that later chunks cannot repair. It intentionally does
 * not check resource existence or cross-Java API consistency because later operations in the same
 * response may still define those resources/classes; the full preflight and final source guard own
 * those checks after the complete response is available.
 */
final class TaskStreamPreflight {
    private TaskStreamPreflight() {
    }

    static String review(List<FileOperation> operations, HermesTaskContract contract) {
        if (operations == null || operations.isEmpty()) {
            return null;
        }
        if (operations.size() > TaskOperationsPreflight.MAX_OPERATIONS_PER_TASK) {
            return "Unusually many file operations for one task: " + operations.size() + ".";
        }
        for (FileOperation operation : operations) {
            FileOperation canonical;
            try {
                canonical = CanonicalPathPolicy.canonicalOperation(operation);
            } catch (IllegalArgumentException error) {
                return error.getMessage();
            }
            if (canonical.path.endsWith(".kt")) {
                return "Generated source policy blocked Kotlin source file: " + new File(canonical.path).getName() + ". Use Java source files (.java) only.";
            }
            if (!HermesTaskContractGuard.allowsPath(contract, canonical.path)) {
                return "Streaming preflight blocked operation outside allowedPaths: " + canonical.path + ". Keep this task inside its Hermes task contract.";
            }
            if (!"write".equals(canonical.action)) {
                continue;
            }
            if (canonical.path.endsWith(".xml")) {
                String error = TaskOperationsPreflight.xmlError(canonical.content);
                if (error != null) {
                    return "Malformed XML in " + canonical.path + ": " + error;
                }
            } else if (canonical.path.endsWith(".java")) {
                String error = javaStructuralError(canonical);
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private static String javaStructuralError(FileOperation operation) {
        String content = operation.content == null ? "" : operation.content;
        String scannable = JavaApiDigest.stripJavaCommentsAndStrings(content);
        String fileName = new File(operation.path).getName();
        if (scannable.contains("kotlinx.android.synthetic")) {
            return "Generated source policy blocked Kotlin synthetic view imports in " + fileName + ". Use findViewById on the inflated root/dialog view.";
        }
        if (scannable.matches("(?s).*import\\s+.*\\.databinding\\..*Binding.*")) {
            return "Generated source policy blocked DataBinding/ViewBinding imports in " + fileName + ". Use findViewById with plain XML ids.";
        }
        if (scannable.contains("->")) {
            return "Generated source policy blocked Java lambda syntax in " + fileName + ". Use anonymous listener classes instead of ->.";
        }
        return null;
    }
}
