package com.androidbuilder.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.agent.AgentService;
import com.androidbuilder.agent.OpenAiClient;
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
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProjectActivity extends BaseActivity {
    private AppRepository repository;
    private AgentService agentService;
    private LocalBuildServer buildServer;
    private long projectId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private MessageAdapter adapter;
    private ListView messageList;
    private TextView title;
    private TextView status;
    private EditText promptInput;
    private boolean buildLogVisible;
    private BuildJobRecord latestJob;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Set<Long> autoRepairing = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project);
        applySystemBarPadding();
        MaterialToolbar toolbar = findViewById(R.id.projectToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        repository = ((AndroidBuilderApp) getApplication()).repository();
        agentService = new AgentService(this, repository);
        projectId = getIntent().getLongExtra(MainActivity.EXTRA_PROJECT_ID, -1);
        title = findViewById(R.id.projectTitle);
        status = findViewById(R.id.statusText);
        promptInput = findViewById(R.id.promptInput);
        adapter = new MessageAdapter();
        messageList = findViewById(R.id.messageList);
        messageList.setAdapter(adapter);
        messageList.setOnItemLongClickListener((parent, view, position, id) -> {
            showMessageActions(position);
            return true;
        });
        findViewById(R.id.sendButton).setOnClickListener(v -> generate());
        findViewById(R.id.buildButton).setOnClickListener(v -> buildLatest());
        findViewById(R.id.installButton).setOnClickListener(v -> installLatest());
        findViewById(R.id.sourceFilesButton).setOnClickListener(v -> openSourceFiles());
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
        latestJob = repository.latestBuildJob(projectId);
        status.setText(project.packageName + " · " + project.lastBuildStatus + (latestJob == null ? "" : " · job #" + latestJob.id));
        messages.clear();
        messages.addAll(repository.listMessages(projectId));
        adapter.notifyDataSetChanged();
        if (!messages.isEmpty() || buildLogVisible) {
            messageList.post(() -> messageList.setSelection(Math.max(adapter.getCount() - 1, 0)));
        }
    }

    private void showMessageActions(int position) {
        if (position < messages.size()) {
            ChatMessage message = messages.get(position);
            new MaterialAlertDialogBuilder(this)
                    .setItems(new CharSequence[]{getString(R.string.copy_message), getString(R.string.delete_message)}, (dialog, which) -> {
                        if (which == 0) {
                            copyText(getString(R.string.message), message.content);
                        } else {
                            confirmDeleteMessage(message);
                        }
                    })
                    .show();
            return;
        }
        BuildJobRecord job = latestJob;
        if (job != null) {
            copyText(getString(R.string.build_log), readBuildLogTail(job));
        }
    }

    private void confirmDeleteMessage(ChatMessage message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_message_title)
                .setMessage(R.string.delete_message_message)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    repository.deleteMessage(message.id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
        Toast.makeText(this, getString(R.string.copied, label), Toast.LENGTH_SHORT).show();
    }

    private void generate() {
        if (!new OpenAiClient(this).isConfigured()) {
            Toast.makeText(this, R.string.api_required_short, Toast.LENGTH_LONG).show();
            startActivity(new android.content.Intent(this, SettingsActivity.class));
            return;
        }
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
        buildLogVisible = true;
        String logsPath = resetBuildLog(job);
        repository.updateBuildJob(job.id, "building", backend.id() + "_start", logsPath, null, null, job.retryCount);
        BuildJobRecord buildJob = repository.getBuildJob(job.id);
        backend.build(buildJob == null ? job : buildJob, (p, j) -> runOnUiThread(() -> {
            refresh();
            maybeAutoRepair(j);
        }));
        refresh();
        Toast.makeText(this, BuildBackendSettings.EXTERNAL_TERMUX.equals(backend.id()) ? R.string.termux_build_started : R.string.embedded_build_started, Toast.LENGTH_SHORT).show();
    }

    private String resetBuildLog(BuildJobRecord job) {
        File log = new File(repository.jobDir(job.projectId, job.id), "build.log");
        try {
            FileUtils.writeText(log, "");
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
        return log.getAbsolutePath();
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

    private void openSourceFiles() {
        File sourceDir = repository.sourceDir(projectId);
        if (!sourceDir.exists()) {
            Toast.makeText(this, R.string.no_source_files, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SourceFilesActivity.class);
        intent.putExtra(MainActivity.EXTRA_PROJECT_ID, projectId);
        startActivity(intent);
    }

    private void maybeAutoRepair(long failedJobId) {
        BuildJobRecord failed = repository.getBuildJob(failedJobId);
        if (failed == null || !"failed".equals(failed.status) || failed.retryCount >= 3 || autoRepairing.contains(failedJobId)) {
            return;
        }
        if (!isRepairableFailure(failed)) {
            autoRepairing.add(failedJobId);
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
        private static final int TYPE_MESSAGE = 0;
        private static final int TYPE_BUILD = 1;

        @Override public int getCount() { return messages.size() + (showBuildMessage() ? 1 : 0); }
        @Override public Object getItem(int position) { return position < messages.size() ? messages.get(position) : latestJob; }
        @Override public long getItemId(int position) { return position < messages.size() ? messages.get(position).id : -latestJob.id; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return position < messages.size() ? TYPE_MESSAGE : TYPE_BUILD; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (getItemViewType(position) == TYPE_BUILD) {
                View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_build_message, parent, false) : convertView;
                bindBuildMessage(view);
                return view;
            }
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_message, parent, false) : convertView;
            ChatMessage message = messages.get(position);
            ((TextView) view.findViewById(R.id.messageRole)).setText(message.role.toUpperCase());
            ((TextView) view.findViewById(R.id.messageTime)).setText(timeFormat.format(new Date(message.createdAt)));
            ((TextView) view.findViewById(R.id.messageContent)).setText(message.content);
            return view;
        }

        private boolean showBuildMessage() {
            return buildLogVisible && latestJob != null && latestJob.logsPath != null;
        }

        private void bindBuildMessage(View view) {
            boolean running = "building".equals(latestJob.status) || "queued".equals(latestJob.status);
            ((ProgressBar) view.findViewById(R.id.buildProgress)).setVisibility(running ? View.VISIBLE : View.GONE);
            ((TextView) view.findViewById(R.id.buildTime)).setText(timeFormat.format(new Date(latestJob.createdAt)));
            ((TextView) view.findViewById(R.id.buildContent)).setText(readBuildLogTail(latestJob));
        }
    }

    private String readBuildLogTail(BuildJobRecord job) {
        String logs = "";
        try {
            logs = FileUtils.readText(new File(job.logsPath));
        } catch (Exception ignored) {
        }
        if (logs.trim().isEmpty()) {
            return getString(R.string.build_waiting);
        }
        return logs.length() > 5000 ? logs.substring(logs.length() - 5000) : logs;
    }
}
