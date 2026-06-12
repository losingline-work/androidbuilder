package com.androidbuilder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.agent.PlanConstraintComposer;
import com.androidbuilder.agent.OpenAiClient;
import com.androidbuilder.backend.BuildBackendSettings;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.util.AppSettings;
import com.androidbuilder.util.NameUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_INITIAL_PROMPT = "initial_prompt";

    private AppRepository repository;
    private ProjectAdapter adapter;
    private final List<ProjectRecord> projects = new ArrayList<>();
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repository = ((AndroidBuilderApp) getApplication()).repository();
        adapter = new ProjectAdapter();
        ListView list = findViewById(R.id.projectList);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openProject(projects.get(position), null));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showProjectActions(projects.get(position));
            return true;
        });
        MaterialToolbar toolbar = findViewById(R.id.mainToolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        findViewById(R.id.newProjectButton).setOnClickListener(v -> showNewProjectDialog());
        EditText search = findViewById(R.id.searchInput);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                loadProjects();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProjects();
    }

    private void loadProjects() {
        projects.clear();
        projects.addAll(repository.listProjects(query));
        adapter.notifyDataSetChanged();
    }

    private void showNewProjectDialog() {
        if (!new OpenAiClient(this).isConfigured()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.api_required_title)
                    .setMessage(R.string.api_required_message)
                    .setPositiveButton(R.string.open_settings, (dialog, which) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_new_project, null, false);
        EditText projectNameInput = dialogView.findViewById(R.id.projectNameInput);
        EditText packageNameInput = dialogView.findViewById(R.id.packageNameInput);
        EditText initialRequirementInput = dialogView.findViewById(R.id.initialRequirementInput);
        TextView planDependencyModeSummaryText = dialogView.findViewById(R.id.planDependencyModeSummaryText);
        CheckBox confirmRiskyPlanChoicesInput = dialogView.findViewById(R.id.confirmRiskyPlanChoicesInput);
        TextInputLayout packageNameLayout = dialogView.findViewById(R.id.packageNameLayout);
        TextInputLayout initialRequirementLayout = dialogView.findViewById(R.id.initialRequirementLayout);
        String dependencyMode = BuildBackendSettings.dependencyMode(this);
        boolean offlineCacheAvailable = PlanConstraintComposer.offlineCacheAvailable(BuildBackendSettings.offlineMavenDir(this));
        planDependencyModeSummaryText.setText(PlanConstraintComposer.dependencyModeSummary(
                dependencyMode,
                offlineCacheAvailable,
                AppSettings.isChinese(this)));
        confirmRiskyPlanChoicesInput.setChecked(BuildBackendSettings.confirmRiskyPlanChoices(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setPositiveButton(R.string.create, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            packageNameLayout.setError(null);
            initialRequirementLayout.setError(null);
            String prompt = initialRequirementInput.getText().toString().trim();
            if (prompt.isEmpty()) {
                initialRequirementLayout.setError(getString(R.string.requirement_required));
                return;
            }
            String name = projectNameInput.getText().toString().trim();
            if (name.isEmpty()) {
                name = NameUtils.projectNameFromPrompt(prompt, AppSettings.isChinese(this));
            }
            String packageName = packageNameInput.getText().toString().trim();
            if (packageName.isEmpty()) {
                packageName = NameUtils.packageNameFromProject(name);
            } else if (!NameUtils.isPackageName(packageName)) {
                packageNameLayout.setError(getString(R.string.invalid_package_name));
                return;
            }
            BuildBackendSettings.setConfirmRiskyPlanChoices(this, confirmRiskyPlanChoicesInput.isChecked());
            ProjectRecord project = repository.createProject(name, packageName, prompt);
            dialog.dismiss();
            openProject(project, prompt);
        }));
        dialog.show();
    }

    private void showProjectActions(ProjectRecord project) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(project.name)
                .setItems(new CharSequence[]{getString(R.string.rename), getString(R.string.delete)}, (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog(project);
                    } else {
                        confirmDelete(project);
                    }
                })
                .show();
    }

    private void showRenameDialog(ProjectRecord project) {
        EditText input = new EditText(this);
        input.setText(project.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_project)
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        repository.renameProject(project.id, name);
                        loadProjects();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(ProjectRecord project) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_project)
                .setMessage(getString(R.string.delete_project_message, project.name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    repository.deleteProject(project.id);
                    loadProjects();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openProject(ProjectRecord project, String initialPrompt) {
        Intent intent = new Intent(this, ProjectActivity.class);
        intent.putExtra(EXTRA_PROJECT_ID, project.id);
        if (initialPrompt != null) {
            intent.putExtra(EXTRA_INITIAL_PROMPT, initialPrompt);
        }
        startActivity(intent);
    }

    private class ProjectAdapter extends BaseAdapter {
        @Override public int getCount() { return projects.size(); }
        @Override public Object getItem(int position) { return projects.get(position); }
        @Override public long getItemId(int position) { return projects.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_project, parent, false) : convertView;
            ProjectRecord project = projects.get(position);
            ((TextView) view.findViewById(R.id.projectName)).setText(project.name);
            ((TextView) view.findViewById(R.id.projectMeta)).setText(project.packageName + " · " + project.lastBuildStatus);
            return view;
        }
    }
}
