package com.androidbuilder.agent;

final class StreamFusePolicy {
    static final int MAX_STREAM_CHARS = 200_000;

    private StreamFusePolicy() {
    }

    static boolean exceeds(int chars) {
        return chars > MAX_STREAM_CHARS;
    }

    static String fuseError(int chars) {
        return "Streaming response exceeded " + MAX_STREAM_CHARS + " chars; generation aborted as runaway.";
    }
}
