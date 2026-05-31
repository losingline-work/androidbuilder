# Structured Plan Execution Implementation Plan

> **For AI agents:** Required sub-skill: use test-driven-development for parser/writer behavior and verification-before-completion before reporting completion.

**Goal:** Make Execute Plan write a validated source file tree generated from the approved in-app engineering plan.

**Architecture:** Add a `GeneratedProject` model, JSON parser, and file writer. Keep direct prompt generation on the legacy `AppSpec` template, but route approved-plan execution through the new source file JSON contract.

**Technical stack:** Java, Android Gradle Plugin, JUnit 4 unit tests, existing `FileUtils`.

---

## Files

- Create: `app/src/main/java/com/androidbuilder/model/GeneratedProject.java`
- Create: `app/src/main/java/com/androidbuilder/model/GeneratedProjectFile.java`
- Create: `app/src/main/java/com/androidbuilder/agent/GeneratedProjectParser.java`
- Create: `app/src/main/java/com/androidbuilder/agent/GeneratedProjectFilesWriter.java`
- Create: `app/src/test/java/com/androidbuilder/agent/GeneratedProjectParserTest.java`
- Modify: `app/src/main/java/com/androidbuilder/agent/OpenAiClient.java`
- Modify: `app/src/main/java/com/androidbuilder/agent/AgentService.java`
- Modify: `app/build.gradle`

## Tasks

1. Add JUnit and failing parser tests for valid project JSON, fenced JSON, unsafe paths, duplicate paths, and missing required files.
2. Implement `GeneratedProjectFile`, `GeneratedProject`, and `GeneratedProjectParser` with strict validation.
3. Add `GeneratedProjectFilesWriter` that writes validated paths under the project source directory.
4. Add `OpenAiClient.createProjectFilesJson` and a source-generation system prompt that requests strict JSON file output.
5. Change `AgentService.executePlan` to parse generated project files and write them instead of using `AppSpecParser` and `GeneratedProjectWriter`.
6. Run focused unit tests, then run Gradle build or the closest available verification command.
