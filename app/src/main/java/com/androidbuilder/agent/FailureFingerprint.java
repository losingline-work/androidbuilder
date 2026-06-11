package com.androidbuilder.agent;

final class FailureFingerprint {
    final String source;
    final String code;
    final String path;
    final String symbol;
    final String normalizedMessage;

    FailureFingerprint(String source, String code, String path, String symbol, String normalizedMessage) {
        this.source = clean(source);
        this.code = clean(code);
        this.path = clean(path);
        this.symbol = clean(symbol);
        this.normalizedMessage = clean(normalizedMessage);
    }

    boolean sameIssue(FailureFingerprint other) {
        if (other == null) {
            return false;
        }
        return code.equals(other.code)
                && path.equals(other.path)
                && symbol.equals(other.symbol);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
