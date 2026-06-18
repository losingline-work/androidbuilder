package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a runtime crash — from a logcat dump OR a raw {@code Throwable.printStackTrace()} (e.g. a pasted
 * FATAL EXCEPTION, or one written by an injected handler) — into the same shape the build-repair loop
 * consumes. The runtime authority (the crash stack) plays the role javac/aapt diagnostics play for the
 * build: {@link #crashDiagnostics} is the focused repair payload and {@link #crashSignature} is the stable
 * fingerprint the stall policy compares across rounds. Pure, no I/O — fully unit-testable.
 */
final class LogcatCrashExtractor {
    private static final Pattern FATAL = Pattern.compile(".*\\bFATAL EXCEPTION\\b.*");
    private static final Pattern EXCEPTION_HEAD =
            Pattern.compile("^(?:Caused by:\\s*)?([A-Za-z_][\\w.$]*(?:Exception|Error))\\b.*");
    private static final Pattern FRAME =
            Pattern.compile("^at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^)]*)\\)\\s*$");
    private static final Pattern ELLIPSIS = Pattern.compile("^\\.\\.\\.\\s+\\d+\\s+more$");
    // Two common logcat line prefixes: threadtime ("MM-DD HH:MM:SS.mmm PID TID E AndroidRuntime: ") and
    // brief ("E/AndroidRuntime( 1234): ").
    private static final Pattern THREADTIME_PREFIX =
            Pattern.compile("^\\d{2}-\\d{2}\\s+[\\d:.]+\\s+\\d+\\s+\\d+\\s+[VDIWEFS]\\s+[^:]*:\\s?");
    private static final Pattern BRIEF_PREFIX = Pattern.compile("^[VDIWEFS]/[^(]*\\(\\s*\\d+\\):\\s?");
    private static final String[] NOISE_FRAME_PREFIXES = {
            "android.", "com.android.internal.", "java.lang.reflect.", "dalvik.", "libcore.", "sun.reflect."
    };

    private LogcatCrashExtractor() {
    }

    /** The contiguous crash block (exception heads + frames + Caused-by chain), prefixes stripped; "" if none. */
    static String fatalException(String dump, int maxChars) {
        List<String> block = crashBlock(dump);
        if (block.isEmpty()) {
            return "";
        }
        return trim(String.join("\n", block), maxChars);
    }

    /**
     * Stable fingerprint: {@code <ExceptionClass>@<firstOwnFrameClass.method>}, line numbers dropped (they
     * churn between rounds). Empty when there is no crash block — keeps the stall policy's empty-signature
     * short-circuit meaningful, exactly like the build extractor returns "" on success.
     */
    static String crashSignature(String dump, String appPackagePrefix) {
        List<String> block = crashBlock(dump);
        if (block.isEmpty()) {
            return "";
        }
        String exceptionClass = "";
        for (String line : block) {
            Matcher head = EXCEPTION_HEAD.matcher(line);
            if (head.matches()) {
                exceptionClass = head.group(1);
                break;
            }
        }
        String ownFrame = "";
        for (String line : block) {
            Matcher frame = FRAME.matcher(line);
            if (frame.matches() && isOwn(frame.group(1), appPackagePrefix)) {
                ownFrame = frame.group(1) + "." + frame.group(2);
                break;
            }
        }
        if (exceptionClass.isEmpty() && ownFrame.isEmpty()) {
            return "";
        }
        return exceptionClass + "@" + ownFrame;
    }

    /**
     * The focused repair payload: every exception head + Caused-by, the app's OWN frames, and the single
     * throw-site framework frame directly above the first own frame — dropping the rest of the framework
     * plumbing so the model sees its own wiring.
     */
    static String crashDiagnostics(String dump, String appPackagePrefix, int maxChars) {
        List<String> block = crashBlock(dump);
        if (block.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        boolean ownSeenSinceHead = false;
        String pendingFrameworkFrame = null;
        for (String line : block) {
            Matcher frame = FRAME.matcher(line);
            if (EXCEPTION_HEAD.matcher(line).matches() || ELLIPSIS.matcher(line).matches() || !frame.matches()) {
                kept.add(line);
                ownSeenSinceHead = false;
                pendingFrameworkFrame = null;
                continue;
            }
            String frameClass = frame.group(1);
            if (isOwn(frameClass, appPackagePrefix)) {
                if (!ownSeenSinceHead && pendingFrameworkFrame != null) {
                    kept.add(pendingFrameworkFrame); // the throw site, just above the first own frame
                }
                kept.add(line);
                ownSeenSinceHead = true;
                pendingFrameworkFrame = null;
            } else if (!ownSeenSinceHead && !isNoise(frameClass)) {
                pendingFrameworkFrame = line; // candidate throw-site frame (keep only if an own frame follows)
            }
        }
        return trim(String.join("\n", kept), maxChars);
    }

    private static List<String> crashBlock(String dump) {
        List<String> result = new ArrayList<>();
        if (dump == null || dump.trim().isEmpty()) {
            return result;
        }
        String[] raw = dump.split("\\R");
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            lines.add(stripPrefix(line).trim());
        }
        // Prefer the exception head AFTER a "FATAL EXCEPTION" marker; otherwise the first head (raw dumps
        // written by an injected handler carry no marker).
        int fatalAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (FATAL.matcher(lines.get(i)).matches()) {
                fatalAt = i;
                break;
            }
        }
        int start = -1;
        for (int i = Math.max(0, fatalAt + 1); i < lines.size(); i++) {
            if (EXCEPTION_HEAD.matcher(lines.get(i)).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return result;
        }
        boolean sawFrame = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                break; // a blank line ends the block
            }
            if (FRAME.matcher(line).matches()) {
                sawFrame = true;
                result.add(line);
            } else if (EXCEPTION_HEAD.matcher(line).matches() || ELLIPSIS.matcher(line).matches()) {
                result.add(line);
            } else {
                break; // first non-stack line ends the block
            }
        }
        // A real crash is a head PLUS at least one stack frame; a stray "FooException: ..." log line is not.
        return sawFrame ? result : new ArrayList<String>();
    }

    private static String stripPrefix(String line) {
        if (line == null) {
            return "";
        }
        String stripped = THREADTIME_PREFIX.matcher(line).replaceFirst("");
        if (stripped.equals(line)) {
            stripped = BRIEF_PREFIX.matcher(line).replaceFirst("");
        }
        return stripped;
    }

    private static boolean isOwn(String frameClass, String appPackagePrefix) {
        return appPackagePrefix != null && !appPackagePrefix.isEmpty()
                && (frameClass.equals(appPackagePrefix) || frameClass.startsWith(appPackagePrefix + "."));
    }

    private static boolean isNoise(String frameClass) {
        for (String prefix : NOISE_FRAME_PREFIXES) {
            if (frameClass.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        String marker = "\n...[truncated]...\n";
        int head = Math.max(0, (maxChars - marker.length()) / 2);
        int tail = Math.max(0, maxChars - marker.length() - head);
        return text.substring(0, head).trim() + marker + text.substring(text.length() - tail).trim();
    }
}
