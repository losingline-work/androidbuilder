package com.androidbuilder.agent;

import java.util.Locale;

final class DraftCorrectionPolicy {
    private DraftCorrectionPolicy() {
    }

    static boolean shouldCorrect(boolean hasPreviousDraft, String errorMessage, int sameErrorStreak) {
        if (!hasPreviousDraft || errorMessage == null || errorMessage.trim().isEmpty()) {
            return false;
        }
        if (sameErrorStreak >= 2) {
            return false;
        }
        return !isStructuralError(errorMessage);
    }

    static String errorSignature(String errorMessage) {
        if (errorMessage == null) {
            return "";
        }
        // Numeric counts in guard messages (for example operation totals) should not reset the
        // repeated-error fuse. If an identifier differs only by a digit, falling back to full
        // generation is the conservative path.
        return errorMessage.trim().replaceAll("\\s+", " ").replaceAll("\\d+", "#").toLowerCase(Locale.ROOT);
    }

    private static boolean isStructuralError(String errorMessage) {
        String signature = errorSignature(errorMessage);
        return signature.startsWith("task operation response did not contain a json object")
                || signature.startsWith("task operation response json could not be parsed")
                || signature.startsWith("task operation list is empty")
                || signature.startsWith("unsupported file operation action")
                || signature.startsWith("unsafe generated file path")
                || signature.startsWith("unterminated object")
                || signature.startsWith("streaming response exceeded")
                || signature.startsWith("edit target")
                || signature.startsWith("edit operation")
                || signature.startsWith("unusually many file operations");
    }
}
