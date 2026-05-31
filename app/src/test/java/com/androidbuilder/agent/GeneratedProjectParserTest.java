package com.androidbuilder.agent;

import com.androidbuilder.model.GeneratedProject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GeneratedProjectParserTest {
    @Test
    public void fromJson_acceptsValidGeneratedProject() throws Exception {
        GeneratedProject project = GeneratedProjectParser.fromJson(validProjectJson());

        assertEquals("Plan App", project.appName);
        assertEquals("com.example.planapp", project.packageName);
        assertEquals(4, project.files.size());
        assertEquals("settings.gradle", project.files.get(0).path);
    }

    @Test
    public void fromJson_extractsFencedJson() throws Exception {
        GeneratedProject project = GeneratedProjectParser.fromJson("```json\n" + validProjectJson() + "\n```");

        assertEquals("Plan App", project.appName);
        assertEquals("app/src/main/AndroidManifest.xml", project.files.get(3).path);
    }

    @Test
    public void fromJson_rejectsPathTraversal() {
        String raw = validProjectJson().replace("settings.gradle", "../settings.gradle");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GeneratedProjectParser.fromJson(raw));

        assertEquals("Unsafe generated file path: ../settings.gradle", error.getMessage());
    }

    @Test
    public void fromJson_rejectsDuplicatePaths() {
        String raw = validProjectJson().replace("app/src/main/AndroidManifest.xml", "settings.gradle");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GeneratedProjectParser.fromJson(raw));

        assertEquals("Duplicate generated file path: settings.gradle", error.getMessage());
    }

    @Test
    public void fromJson_requiresAndroidProjectFiles() {
        String raw = validProjectJson().replace("\"path\":\"app/build.gradle\"", "\"path\":\"app/other.gradle\"");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GeneratedProjectParser.fromJson(raw));

        assertEquals("Generated project is missing required file: app/build.gradle", error.getMessage());
    }

    @Test
    public void fromJson_requiresValidPackageName() {
        String raw = validProjectJson().replace("\"packageName\":\"com.example.planapp\"", "\"packageName\":\"bad package\"");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> GeneratedProjectParser.fromJson(raw));

        assertEquals("Generated project has invalid packageName: bad package", error.getMessage());
    }

    private String validProjectJson() {
        return "{" +
                "\"appName\":\"Plan App\"," +
                "\"packageName\":\"com.example.planapp\"," +
                "\"description\":\"Built from plan\"," +
                "\"files\":[" +
                "{\"path\":\"settings.gradle\",\"content\":\"pluginManagement {}\\ninclude ':app'\\n\"}," +
                "{\"path\":\"build.gradle\",\"content\":\"plugins {}\\n\"}," +
                "{\"path\":\"app/build.gradle\",\"content\":\"plugins { id 'com.android.application' }\\n\"}," +
                "{\"path\":\"app/src/main/AndroidManifest.xml\",\"content\":\"<manifest/>\\n\"}" +
                "]" +
                "}";
    }
}
