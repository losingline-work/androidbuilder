package com.androidbuilder.model;

public class FileOperation {
    public final String action;
    public final String path;
    public final String content;
    public final String find;
    public final String replace;

    public FileOperation(String action, String path, String content) {
        this(action, path, content, "", "");
    }

    public FileOperation(String action, String path, String content, String find, String replace) {
        this.action = action;
        this.path = path;
        this.content = content;
        this.find = find == null ? "" : find;
        this.replace = replace == null ? "" : replace;
    }
}
