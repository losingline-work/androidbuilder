package com.androidbuilder.agent;

import android.content.Context;

import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.AppSpec;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.util.FileUtils;
import com.androidbuilder.util.AppSettings;

import java.io.File;
import java.util.List;

public class AgentService {
    public interface Callback {
        void onComplete(BuildJobRecord job);
        void onError(Exception error);
    }

    private final Context context;
    private final AppRepository repository;
    private final OpenAiClient openAiClient;
    private final GeneratedProjectWriter writer = new GeneratedProjectWriter();

    public AgentService(Context context, AppRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.openAiClient = new OpenAiClient(context);
    }

    public void generateAsync(long projectId, String prompt, Callback callback) {
        new Thread(() -> {
            try {
                BuildJobRecord job = generate(projectId, prompt, true, true);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            }
        }, "agent-generate").start();
    }

    public void generateRepairAsync(long projectId, String prompt, Callback callback) {
        new Thread(() -> {
            try {
                BuildJobRecord job = generate(projectId, prompt, false, false);
                callback.onComplete(job);
            } catch (Exception error) {
                callback.onError(error);
            }
        }, "agent-generate").start();
    }

    public BuildJobRecord generate(long projectId, String prompt) throws Exception {
        return generate(projectId, prompt, true, true);
    }

    private BuildJobRecord generate(long projectId, String prompt, boolean recordUserMessage, boolean announceGenerated) throws Exception {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        BuildJobRecord job = repository.createBuildJob(projectId);
        if (recordUserMessage) {
            repository.addMessage(projectId, "user", prompt, job.id);
        }
        repository.updateBuildJob(job.id, "generating", "cloud_spec", null, null, null, 0);

        List<ChatMessage> history = repository.listMessages(projectId);
        boolean chinese = AppSettings.isChinese(context);
        String specJson = openAiClient.createSpecJson(history, prompt, chinese);
        AppSpec spec = AppSpecParser.fromJson(specJson, prompt, project.name, chinese);
        if (!isPackageName(spec.packageName)) {
            spec = new AppSpec(spec.appName, project.packageName, spec.description, spec.entityName, spec.primaryField, spec.secondaryField, spec.language);
        }

        repository.updateProjectMetadata(projectId, spec.packageName, spec.description);
        writer.write(repository.sourceDir(projectId), spec);

        File jobDir = repository.jobDir(projectId, job.id);
        File projectZip = new File(jobDir, "project.zip");
        FileUtils.zipDirectory(repository.sourceDir(projectId), projectZip);
        File logs = new File(jobDir, "build.log");
        FileUtils.writeText(logs, "Generated project for " + spec.appName + "\nWaiting for build.\n");

        if (announceGenerated) {
            repository.addMessage(projectId, "assistant", chinese ? "已生成项目源码：" + spec.appName + "。可以点击 Build 开始构建。" : "Generated source for " + spec.appName + ". Tap Build to start the build.", job.id);
        }
        repository.updateBuildJob(job.id, "generated", "ready_for_build", logs.getAbsolutePath(), null, null, 0);
        return repository.getBuildJob(job.id);
    }

    private boolean isPackageName(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }
}
