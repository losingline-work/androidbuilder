package com.androidbuilder.model;

public class GuardFinding {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public final String source;
    public final String code;
    public final Severity severity;
    public final String path;
    public final String symbol;
    public final String message;
    public final String suggestion;

    public GuardFinding(
            String source,
            String code,
            Severity severity,
            String path,
            String symbol,
            String message,
            String suggestion) {
        this.source = clean(source);
        this.code = clean(code);
        this.severity = severity == null ? Severity.INFO : severity;
        this.path = clean(path);
        this.symbol = clean(symbol);
        this.message = clean(message);
        this.suggestion = clean(suggestion);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
