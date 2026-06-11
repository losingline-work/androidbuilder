package com.androidbuilder.agent;

import com.androidbuilder.model.HermesReview;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FailureFingerprintPolicy {
    private static final Pattern PATH_PATTERN = Pattern.compile("(app/src/[^\\s:;,)）]+|app/build\\.gradle|settings\\.gradle|build\\.gradle)");
    private static final Pattern RESOURCE_SYMBOL_PATTERN = Pattern.compile("(@[A-Za-z0-9_]+/[A-Za-z0-9_]+|R\\.[A-Za-z0-9_]+\\.[A-Za-z0-9_]+|(?:resource\\s+)?(?:color|string|drawable|layout|mipmap|style|id)/[A-Za-z0-9_]+)");
    private static final Pattern CANNOT_FIND_METHOD_PATTERN = Pattern.compile("cannot find symbol\\s+(?:method|variable|class)?\\s*([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile("constructor\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("Could not find\\s+([^\\s.]+:[^\\s.]+:[^\\s.]+)");

    private FailureFingerprintPolicy() {
    }

    static FailureFingerprint fromPolicyError(String message) {
        return fromText("policy", message);
    }

    static FailureFingerprint fromHermesReview(String source, HermesReview review) {
        if (review == null) {
            return fromText(source, "");
        }
        return fromText(source, review.summary + "\n" + review.rewriteInstruction);
    }

    static FailureFingerprint fromLocalGuardResult(String source, LocalGuardResult result) {
        if (result == null) {
            return fromText(source, "");
        }
        return fromText(source, result.summary + "\n" + result.additionalInstruction);
    }

    static FailureFingerprint fromBuildLog(String buildLog) {
        return fromText("build", buildLog);
    }

    static boolean isRepeated(List<FailureFingerprint> history, FailureFingerprint current, int threshold) {
        if (current == null || history == null || threshold <= 0) {
            return false;
        }
        int count = 0;
        for (FailureFingerprint item : history) {
            if (current.sameIssue(item)) {
                count++;
            }
        }
        return count >= threshold;
    }

    static String repeatedRetryContext(List<FailureFingerprint> history, FailureFingerprint current, int threshold) {
        if (!isRepeated(history, current, threshold)) {
            return "";
        }
        return "Repeated failure detected: " + current.code
                + "\nPath: " + current.path
                + "\nSymbol: " + current.symbol
                + "\nSwitch strategy: modify only the narrowest file set named by this fingerprint. Do not broaden the patch.";
    }

    private static FailureFingerprint fromText(String source, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        String code = codeFor(lower);
        String path = pathFor(message);
        String symbol = symbolFor(code, message);
        return new FailureFingerprint(source, code, path, symbol, normalizeMessage(message));
    }

    private static String codeFor(String lower) {
        if (lower.contains("could not find") && lower.matches("(?s).*[^\\s]+:[^\\s]+:[^\\s]+.*")) {
            return "MISSING_DEPENDENCY";
        }
        if (lower.contains("missing xml resource reference")
                || lower.matches("(?s).*resource\\s+[a-z0-9_]+/[a-z0-9_]+\\s+not found.*")
                || lower.contains("resource not found")) {
            return "MISSING_RESOURCE";
        }
        if (lower.contains("constructor") && (lower.contains("cannot be applied") || lower.contains("required") && lower.contains("found"))) {
            return "API_SIGNATURE_MISMATCH";
        }
        if (lower.contains("cannot find symbol")) {
            return "MISSING_JAVA_SYMBOL";
        }
        if (lower.contains("lambda") || lower.contains("->")) {
            return "JAVA_LAMBDA";
        }
        return "UNKNOWN_BUILD_OR_POLICY_ERROR";
    }

    private static String pathFor(String message) {
        Matcher matcher = PATH_PATTERN.matcher(message);
        if (matcher.find()) {
            return trimTrailingPunctuation(matcher.group(1));
        }
        return "";
    }

    private static String symbolFor(String code, String message) {
        if ("MISSING_DEPENDENCY".equals(code)) {
            Matcher matcher = DEPENDENCY_PATTERN.matcher(message);
            return matcher.find() ? trimTrailingPunctuation(matcher.group(1)) : "";
        }
        if ("API_SIGNATURE_MISMATCH".equals(code)) {
            Matcher matcher = CONSTRUCTOR_PATTERN.matcher(message);
            return matcher.find() ? matcher.group(1) : "";
        }
        if ("MISSING_JAVA_SYMBOL".equals(code)) {
            Matcher matcher = CANNOT_FIND_METHOD_PATTERN.matcher(message);
            return matcher.find() ? matcher.group(1) : "";
        }
        Matcher resourceMatcher = RESOURCE_SYMBOL_PATTERN.matcher(message);
        if (resourceMatcher.find()) {
            String symbol = trimTrailingPunctuation(resourceMatcher.group(1));
            if (symbol.startsWith("resource ")) {
                symbol = symbol.substring("resource ".length()).trim();
            }
            if (!symbol.startsWith("@") && symbol.contains("/") && !symbol.startsWith("R.")) {
                symbol = "@" + symbol;
            }
            return symbol;
        }
        return "";
    }

    private static String normalizeMessage(String message) {
        return message.replaceAll("\\s+", " ").trim();
    }

    private static String trimTrailingPunctuation(String value) {
        String text = value == null ? "" : value.trim();
        while (!text.isEmpty()) {
            char last = text.charAt(text.length() - 1);
            if (last == '.' || last == ',' || last == ':' || last == ';' || last == ')' || last == '）') {
                text = text.substring(0, text.length() - 1);
            } else {
                break;
            }
        }
        return text;
    }
}
