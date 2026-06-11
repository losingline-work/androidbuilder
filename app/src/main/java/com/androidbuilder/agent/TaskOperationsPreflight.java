package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesReview;
import com.androidbuilder.model.TaskOperations;

import org.xml.sax.InputSource;

import java.io.StringReader;
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
    // Only flags an absurd batch; foundational tasks legitimately touch many files.
    private static final int MAX_OPERATIONS_PER_TASK = 30;
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
                    "Split this into smaller tasks: write drawables/selectors/icons in one task, layout XML in another, and Java wiring in a separate task.");
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
        String content = operation.content == null ? "" : operation.content;
        if (!BARE_R_USAGE.matcher(content).find()) {
            return ok();
        }
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        if (!packageMatcher.find()) {
            return ok();
        }
        String packageName = packageMatcher.group(1);
        if (!packageName.startsWith(namespace + ".")) {
            return ok();
        }
        String importLine = "import " + namespace + ".R;";
        if (content.contains(importLine)) {
            return ok();
        }
        return rewrite("Java file in subpackage " + packageName + " uses R.* but is missing R import.",
                "Add " + importLine + " to " + operation.path + " or use fully qualified " + namespace + ".R references.");
    }

    private static boolean isXmlWrite(FileOperation operation) {
        return operation != null && "write".equals(operation.action)
                && operation.path != null && operation.path.endsWith(".xml");
    }

    private static boolean isJavaWrite(FileOperation operation) {
        return operation != null && "write".equals(operation.action)
                && operation.path != null && operation.path.endsWith(".java");
    }

    private static String xmlError(String content) {
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
