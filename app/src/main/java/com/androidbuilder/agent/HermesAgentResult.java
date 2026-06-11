package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesAgentRunRecord;
import com.androidbuilder.model.HermesTaskContract;
import com.androidbuilder.model.ProjectTaskRecord;
import com.androidbuilder.model.TaskOperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HermesAgentResult {
    public final ProjectTaskRecord task;
    public final HermesAgentRunRecord run;
    public final HermesTaskContract contract;
    public final TaskOperations operations;
    public final List<String> touchedPaths;
    public final String summary;
    public final Exception error;

    public HermesAgentResult(ProjectTaskRecord task, HermesAgentRunRecord run, TaskOperations operations,
                             List<String> touchedPaths, String summary, Exception error) {
        this(task, run, HermesTaskContract.empty(), operations, touchedPaths, summary, error);
    }

    public HermesAgentResult(ProjectTaskRecord task, HermesAgentRunRecord run, HermesTaskContract contract,
                             TaskOperations operations, List<String> touchedPaths,
                             String summary, Exception error) {
        this.task = task;
        this.run = run;
        this.contract = contract == null ? HermesTaskContract.empty() : contract;
        this.operations = operations;
        this.touchedPaths = Collections.unmodifiableList(cleanTouchedPaths(touchedPaths, operations));
        this.summary = summary == null ? "" : summary.trim();
        this.error = error;
    }

    public boolean success() {
        return error == null && operations != null;
    }

    private static List<String> cleanTouchedPaths(List<String> touchedPaths, TaskOperations operations) {
        List<String> cleaned = new ArrayList<>();
        if (touchedPaths != null) {
            for (String path : touchedPaths) {
                addPath(cleaned, path);
            }
        }
        if (cleaned.isEmpty() && operations != null && operations.operations != null) {
            for (FileOperation operation : operations.operations) {
                if (operation != null) {
                    addPath(cleaned, operation.path);
                }
            }
        }
        return cleaned;
    }

    private static void addPath(List<String> paths, String path) {
        String cleaned = path == null ? "" : path.trim();
        if (!cleaned.isEmpty() && !paths.contains(cleaned)) {
            paths.add(cleaned);
        }
    }
}
