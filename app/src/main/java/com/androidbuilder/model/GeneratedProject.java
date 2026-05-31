package com.androidbuilder.model;

import java.util.Collections;
import java.util.List;

public class GeneratedProject {
    public final String appName;
    public final String packageName;
    public final String description;
    public final List<GeneratedProjectFile> files;

    public GeneratedProject(String appName, String packageName, String description, List<GeneratedProjectFile> files) {
        this.appName = appName;
        this.packageName = packageName;
        this.description = description;
        this.files = Collections.unmodifiableList(files);
    }
}
