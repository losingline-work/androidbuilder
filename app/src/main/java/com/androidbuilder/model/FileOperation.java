package com.androidbuilder.model;

public class FileOperation {
    public final String action;
    public final String path;
    public final String content;

    public FileOperation(String action, String path, String content) {
        this.action = action;
        this.path = path;
        this.content = content;
    }
}
