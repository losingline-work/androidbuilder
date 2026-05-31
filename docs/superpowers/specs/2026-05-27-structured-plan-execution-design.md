# Structured Plan Execution Design

## Goal

Improve the in-app Execute Plan flow so it generates source code from the approved engineering plan instead of compressing the plan into the old fixed `AppSpec` CRUD template.

## Approved Approach

Use structured source generation. The model returns a strict JSON object with a `files` array. Each file has a relative `path` and UTF-8 text `content`. The app validates the file list, writes the generated Gradle/Kotlin/XML/resources into the project source directory, zips that source tree, and leaves the manual Build step unchanged.

## Architecture

- Planning stays unchanged: `planAsync` continues saving readable engineering plans.
- Execution changes from `plan -> AppSpec -> GeneratedProjectWriter` to `plan -> GeneratedProject -> GeneratedProjectFilesWriter`.
- A parser validates JSON shape, required Android project files, duplicate paths, and path traversal.
- The writer deletes and recreates only the project source directory, then writes validated files.
- The legacy `AppSpec` path remains for direct generate and repair flows.

## Data Contract

The model must return only compact JSON:

```json
{
  "appName": "Example",
  "packageName": "com.example.app",
  "description": "Short description",
  "files": [
    {
      "path": "settings.gradle",
      "content": "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ninclude ':app'\n"
    }
  ]
}
```

## Validation Rules

- `files` must be present and non-empty.
- Required files: `settings.gradle`, `build.gradle`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`.
- Paths must be relative, normalized, unique, and must not contain `..`, backslashes, control characters, or `.git` segments.
- Empty file content is allowed because some Android resources may be intentionally empty.
- Invalid model output fails the execute step with a clear error instead of silently falling back to the fixed template.

## Testing

Unit tests cover successful parsing, fenced JSON extraction, required-file validation, duplicate path rejection, and path traversal rejection. A Gradle compile/build check verifies integration.
