package com.androidbuilder.model;

public class AppSpec {
    public final String appName;
    public final String packageName;
    public final String description;
    public final String entityName;
    public final String primaryField;
    public final String secondaryField;
    public final String language;

    public AppSpec(String appName, String packageName, String description, String entityName, String primaryField, String secondaryField) {
        this(appName, packageName, description, entityName, primaryField, secondaryField, "en");
    }

    public AppSpec(String appName, String packageName, String description, String entityName, String primaryField, String secondaryField, String language) {
        this.appName = appName;
        this.packageName = packageName;
        this.description = description;
        this.entityName = entityName;
        this.primaryField = primaryField;
        this.secondaryField = secondaryField;
        this.language = language;
    }
}
