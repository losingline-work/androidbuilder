package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.TaskOperations;

import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Cheap, deterministic, zero-false-positive structural checks on generated operations before they
 * are written. Resource existence and cross-file API consistency are intentionally NOT checked here:
 * those require the whole project tree and are owned by AndroidSourceGuard, which runs on the fully
 * written source. Checking them against a truncated snapshot only produces false rewrites.
 */
final class TaskOperationsPreflight {
    // Only flags an absurd batch; foundational tasks legitimately touch many files. Must admit
    // everything a valid task manifest can plan (TaskManifest.MAX_FILES), or batched generation
    // succeeds per batch and then dies here after paying for every batch.
    static final int MAX_OPERATIONS_PER_TASK = 120;
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("namespace\\s*(?:=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)*)\\s*;");
    private static final Pattern DOCTYPE_PATTERN = Pattern.compile("<!DOCTYPE\\b", Pattern.CASE_INSENSITIVE);
    // Bare project R reference: an "R." not preceded by an identifier char or dot, so android.R.* and
    // other.qualified.R.* (which do not need the local R import) are excluded.
    private static final Pattern BARE_R_USAGE = Pattern.compile("(?<![A-Za-z0-9_.])R\\.");

    private TaskOperationsPreflight() {
    }

    static HermesReview review(TaskOperations operations, String sourceSnapshot) {
        if (operations == null || operations.operations == null || operations.operations.isEmpty()) {
            return ok();
        }
        if (operations.operations.size() > MAX_OPERATIONS_PER_TASK) {
            return rewrite(
                    "Unusually many file operations for one task: " + operations.operations.size() + ".",
                    "Too many file operations: " + operations.operations.size() + " (cap " + MAX_OPERATIONS_PER_TASK + "). Trim the batch instead of splitting tasks: keep only files this task strictly needs, merge values resources into fewer files, drop duplicate or decorative drawables, and defer non-essential assets to a later task.");
        }
        for (FileOperation operation : operations.operations) {
            if (isXmlWrite(operation)) {
                String error = xmlError(operation.content);
                if (error != null) {
                    return rewrite("Malformed XML in " + operation.path + ": " + error,
                            "Return a complete, well-formed XML file for " + operation.path + " with all tags closed.");
                }
            }
        }
        String namespace = namespaceFor(operations, sourceSnapshot);
        if (!namespace.isEmpty()) {
            for (FileOperation operation : operations.operations) {
                if (isJavaWrite(operation)) {
                    HermesReview review = reviewJavaRImport(operation, namespace);
                    if (review.decision == HermesReview.Decision.REWRITE) {
                        return review;
                    }
                }
            }
        }
        return ok();
    }

    private static HermesReview reviewJavaRImport(FileOperation operation, String namespace) {
        String reason = rImportError(operation, namespace);
        if (reason == null) {
            return ok();
        }
        String importLine = "import " + namespace + ".R;";
        return rewrite(reason,
                "Add " + importLine + " to " + operation.path + " or use fully qualified " + namespace + ".R references.");
    }

    /** The missing-R-import reason for a Java write, or null when the file is fine. Shared by review + findings. */
    private static String rImportError(FileOperation operation, String namespace) {
        String content = operation.content == null ? "" : operation.content;
        if (!BARE_R_USAGE.matcher(content).find()) {
            return null;
        }
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        if (!packageMatcher.find()) {
            return null;
        }
        String packageName = packageMatcher.group(1);
        if (!packageName.startsWith(namespace + ".")) {
            return null;
        }
        if (content.contains("import " + namespace + ".R;")) {
            return null;
        }
        return "Java file in subpackage " + packageName + " uses R.* but is missing R import; add import " + namespace + ".R;";
    }

    /**
     * One structural defect per file (malformed XML or a missing R import) collected across the whole batch,
     * so a caller can micro-fix just the bad files instead of re-rolling the whole batch. Excludes the
     * &gt;{@link #MAX_OPERATIONS_PER_TASK} cap (that is a whole-batch decision, see {@link #review}).
     */
    static List<Finding> findings(TaskOperations operations, String sourceSnapshot) {
        List<Finding> out = new ArrayList<>();
        if (operations == null || operations.operations == null) {
            return out;
        }
        for (FileOperation operation : operations.operations) {
            if (isXmlWrite(operation)) {
                String error = xmlError(operation.content);
                if (error != null) {
                    out.add(new Finding(operation, "Malformed XML in " + operation.path + ": " + error));
                }
            }
        }
        String namespace = namespaceFor(operations, sourceSnapshot);
        if (!namespace.isEmpty()) {
            for (FileOperation operation : operations.operations) {
                if (isJavaWrite(operation)) {
                    String reason = rImportError(operation, namespace);
                    if (reason != null) {
                        out.add(new Finding(operation, reason));
                    }
                }
            }
        }
        return out;
    }

    /** Re-validate a single (micro-fixed) file's structure; null when it now passes. */
    static String validateSingle(FileOperation operation, String namespace) {
        if (isXmlWrite(operation)) {
            return xmlError(operation.content);
        }
        if (isJavaWrite(operation) && namespace != null && !namespace.isEmpty()) {
            return rImportError(operation, namespace);
        }
        return null;
    }

    /** The app's Gradle namespace, resolved from the operations or the snapshot (for validateSingle callers). */
    static String resolveNamespace(TaskOperations operations, String sourceSnapshot) {
        return namespaceFor(operations, sourceSnapshot);
    }

    /** A single structural defect: the offending operation and a human-readable reason for the fix prompt. */
    static final class Finding {
        final FileOperation op;
        final String reason;

        Finding(FileOperation op, String reason) {
            this.op = op;
            this.reason = reason;
        }
    }

    private static boolean isXmlWrite(FileOperation operation) {
        return operation != null && "write".equals(operation.action)
                && operation.path != null && operation.path.endsWith(".xml");
    }

    private static boolean isJavaWrite(FileOperation operation) {
        return operation != null && "write".equals(operation.action)
                && operation.path != null && operation.path.endsWith(".java");
    }

    static String xmlError(String content) {
        try {
            String xml = content == null ? "" : content;
            if (DOCTYPE_PATTERN.matcher(xml).find()) {
                return "DOCTYPE declarations are not allowed.";
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return null;
        } catch (Exception error) {
            return error.getMessage() == null ? error.toString() : error.getMessage();
        }
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (ParserConfigurationException | UnsupportedOperationException ignored) {
            // Android and desktop XML factories support different hardening flags. Unsupported
            // flags are not XML syntax errors; real DOCTYPE use is rejected before parsing.
        }
    }

    private static String namespaceFor(TaskOperations operations, String sourceSnapshot) {
        for (FileOperation operation : operations.operations) {
            if ("write".equals(operation.action) && "app/build.gradle".equals(operation.path)) {
                String namespace = namespaceFromText(operation.content);
                if (!namespace.isEmpty()) {
                    return namespace;
                }
            }
        }
        return namespaceFromText(sourceSnapshot);
    }

    private static String namespaceFromText(String text) {
        Matcher matcher = NAMESPACE_PATTERN.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static HermesReview ok() {
        return new HermesReview(HermesReview.Decision.OK, "Deterministic preflight passed.", "");
    }

    private static HermesReview rewrite(String summary, String instruction) {
        return new HermesReview(HermesReview.Decision.REWRITE, summary, instruction);
    }
}
