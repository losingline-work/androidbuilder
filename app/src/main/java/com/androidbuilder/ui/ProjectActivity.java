package com.androidbuilder.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.agent.AgentService;
import com.androidbuilder.backend.BuildBackend;
import com.androidbuilder.backend.BuildBackendFactory;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.install.ApkInstaller;
import com.androidbuilder.model.BuildJobRecord;
import com.androidbuilder.model.ChatMessage;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.server.LocalBuildServer;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectActivity extends BaseActivity {
    private AppRepository repository;
    private AgentService agentService;
    private LocalBuildServer buildServer;
    private long projectId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private MessageAdapter adapter;
    private TextView title;
    private TextView status;
    private TextView logText;
    private EditText promptInput;
    private final Set<Long> autoRepairing = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project);
        applySystemBarPadding();
        repository = ((AndroidBuilderApp) getApplication()).repository();
        agentService = new AgentService(this, repository);
        projectId = getIntent().getLongExtra(MainActivity.EXTRA_PROJECT_ID, -1);
        title = findViewById(R.id.projectTitle);
        status = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        promptInput = findViewById(R.id.promptInput);
        adapter = new MessageAdapter();
        ((ListView) findViewById(R.id.messageList)).setAdapter(adapter);
        findViewById(R.id.sendButton).setOnClickListener(v -> generate());
        findViewById(R.id.buildButton).setOnClickListener(v -> buildLatest());
        findViewById(R.id.installButton).setOnClickListener(v -> installLatest());
        buildServer = new LocalBuildServer(repository, (p, j) -> runOnUiThread(() -> {
            refresh();
            maybeAutoRepair(j);
        }));
        try {
            buildServer.start();
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.local_server_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
        refresh();
        String initialPrompt = getIntent().getStringExtra(MainActivity.EXTRA_INITIAL_PROMPT);
        if (initialPrompt != null && repository.listMessages(projectId).isEmpty()) {
            promptInput.setText(initialPrompt);
            generate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (buildServer != null) {
            buildServer.stop();
        }
    }

    private void refresh() {
        ProjectRecord project = repository.getProject(projectId);
        if (project == null) {
            finish();
            return;
        }
        title.setText(project.name);
        BuildJobRecord job = repository.latestBuildJob(projectId);
        status.setText(project.packageName + " · " + project.lastBuildStatus + (job == null ? "" : " · job #" + job.id));
        messages.clear();
        messages.addAll(repository.listMessages(projectId));
        adapter.notifyDataSetChanged();
        if (job != null && job.logsPath != null) {
            try {
                String logs = FileUtils.readText(new File(job.logsPath));
                logText.setText(logs.length() > 5000 ? logs.substring(logs.length() - 5000) : logs);
            } catch (Exception ignored) {
                logText.setText("");
            }
        }
    }

    private void generate() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, R.string.write_requirement_first, Toast.LENGTH_SHORT).show();
            return;
        }
        promptInput.setText("");
        setBusy(true);
        status.setText("Generating...");
        agentService.generateAsync(projectId, prompt, new AgentService.Callback() {
            @Override
            public void onComplete(BuildJobRecord job) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refresh();
                    Toast.makeText(ProjectActivity.this, R.string.source_generated, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    repository.addMessage(projectId, "assistant", "生成失败：" + error.getMessage(), null);
                    refresh();
                    Toast.makeText(ProjectActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void buildLatest() {
        BuildJobRecord job = repository.latestBuildJob(projectId);
        if (job == null) {
            Toast.makeText(this, R.string.generate_source_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (buildServer == null) {
            Toast.makeText(this, R.string.local_server_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        BuildBackend backend = BuildBackendFactory.create(this, repository, buildServer);
        repository.updateBuildJob(job.id, "building", backend.id() + "_start", job.logsPath, job.apkPath, null, job.retryCount);
        backend.build(job, (p, j) -> runOnUiThread(() -> {
            refresh();
            maybeAutoRepair(j);
        }));
        refresh();
        Toast.makeText(this, BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started, Toast.LENGTH_SHORT).show();
    }

    private void installLatest() {
        BuildJobRecord job = repository.latestBuildJob(projectId);
        if (job == null || job.apkPath == null) {
            Toast.makeText(this, R.string.no_apk_yet, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            new ApkInstaller(this).install(new File(job.apkPath));
        } catch (Exception error) {
            Toast.makeText(this, "Install failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void maybeAutoRepair(long failedJobId) {
        BuildJobRecord failed = repository.getBuildJob(failedJobId);
        if (failed == null || !"failed".equals(failed.status) || failed.retryCount >= 3 || autoRepairing.contains(failedJobId)) {
            return;
        }
        if (!isRepairableFailure(failed)) {
            repository.addMessage(projectId, "assistant", "构建失败，但这类错误需要先修复本机运行环境或构建后端配置，不自动重试。", failedJobId);
            refresh();
            return;
        }
        autoRepairing.add(failedJobId);
        String logs = "";
        if (failed.logsPath != null) {
            try {
                logs = FileUtils.readText(new File(failed.logsPath));
                if (logs.length() > 7000) {
                    logs = logs.substring(logs.length() - 7000);
                }
            } catch (Exception ignored) {
            }
        }
        String prompt = "Fix the Android build failure and regenerate the project. Build log:\n" + logs;
        repository.addMessage(projectId, "assistant", "构建失败，开始自动修复第 " + (failed.retryCount + 1) + "/3 次。", failedJobId);
        agentService.generateRepairAsync(projectId, prompt, new AgentService.Callback() {
            @Override
            public void onComplete(BuildJobRecord job) {
                runOnUiThread(() -> {
                    repository.updateBuildJob(job.id, job.status, job.phase, job.logsPath, job.apkPath, job.errorSummary, failed.retryCount + 1);
                    BuildJobRecord retryJob = repository.getBuildJob(job.id);
                    refresh();
                    BuildBackend backend = BuildBackendFactory.create(ProjectActivity.this, repository, buildServer);
                    backend.build(retryJob == null ? job : retryJob, (p, j) -> runOnUiThread(() -> {
                        refresh();
                        maybeAutoRepair(j);
                    }));
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    repository.addMessage(projectId, "assistant", "自动修复失败：" + error.getMessage(), failedJobId);
                    refresh();
                });
            }
        });
    }

    private boolean isRepairableFailure(BuildJobRecord failed) {
        String phase = failed.phase == null ? "" : failed.phase;
        String error = failed.errorSummary == null ? "" : failed.errorSummary;
        if (phase.contains("missing_tools") || phase.contains("termux") || phase.contains("runtime_error")) {
            return false;
        }
        return !error.contains("toolchain is incomplete") &&
                !error.contains("No such file") &&
                !error.contains("Permission denied") &&
                !error.contains("Cannot run program");
    }

    private void setBusy(boolean busy) {
        findViewById(R.id.sendButton).setEnabled(!busy);
        findViewById(R.id.buildButton).setEnabled(!busy);
        findViewById(R.id.installButton).setEnabled(!busy);
    }

    private class MessageAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Object getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return messages.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_message, parent, false) : convertView;
            ChatMessage message = messages.get(position);
            ((TextView) view.findViewById(R.id.messageRole)).setText(message.role.toUpperCase());
            ((TextView) view.findViewById(R.id.messageContent)).setText(message.content);
            return view;
        }
    }
}
