package com.androidbuilder.model;

import java.util.Collections;
import java.util.List;

public class TaskOperations {
    public final String summary;
    public final List<FileOperation> operations;

    public TaskOperations(String summary, List<FileOperation> operations) {
        this.summary = summary;
        this.operations = Collections.unmodifiableList(operations);
    }
}
