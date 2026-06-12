package com.androidbuilder.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WidgetDependencyPolicy {
    private static final Pattern CUSTOM_TAG = Pattern.compile("<\\s*([a-z][A-Za-z0-9_.]*\\.[A-Z][A-Za-z0-9_]*)\\b");
    private static final Pattern VIEW_CLASS = Pattern.compile("<\\s*view\\b[^>]*\\bclass\\s*=\\s*[\"']([^\"']+)[\"']");

    private WidgetDependencyPolicy() {
    }

    static String requiredCoordinate(String widgetFqcn) {
        if (widgetFqcn == null) {
            return null;
        }
        if (widgetFqcn.startsWith("androidx.gridlayout.")) {
            return "androidx.gridlayout:gridlayout";
        }
        if (widgetFqcn.startsWith("com.github.mikephil.charting.")) {
            return "com.github.PhilJay:MPAndroidChart";
        }
        if (widgetFqcn.startsWith("androidx.swiperefreshlayout.")) {
            return "androidx.swiperefreshlayout:swiperefreshlayout";
        }
        if (widgetFqcn.startsWith("androidx.constraintlayout.")) {
            return "androidx.constraintlayout:constraintlayout";
        }
        return null;
    }

    static List<String> missingWidgetDependencies(String xmlContent, String gradleText) {
        Set<String> missing = new LinkedHashSet<>();
        String gradle = gradleText == null ? "" : gradleText;
        for (String widget : widgets(xmlContent)) {
            String coordinate = requiredCoordinate(widget);
            if (coordinate != null && !gradle.contains(coordinate)) {
                missing.add(widget);
            }
        }
        return new ArrayList<>(missing);
    }

    private static Set<String> widgets(String xmlContent) {
        Set<String> widgets = new LinkedHashSet<>();
        String xml = xmlContent == null ? "" : xmlContent;
        Matcher tagMatcher = CUSTOM_TAG.matcher(xml);
        while (tagMatcher.find()) {
            widgets.add(tagMatcher.group(1));
        }
        Matcher viewMatcher = VIEW_CLASS.matcher(xml);
        while (viewMatcher.find()) {
            widgets.add(viewMatcher.group(1));
        }
        return widgets;
    }
}
