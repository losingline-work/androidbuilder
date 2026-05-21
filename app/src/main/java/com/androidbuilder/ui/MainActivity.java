package com.androidbuilder.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.androidbuilder.AndroidBuilderApp;
import com.androidbuilder.R;
import com.androidbuilder.data.AppRepository;
import com.androidbuilder.model.ProjectRecord;
import com.androidbuilder.util.NameUtils;

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
        applySystemBarPadding();
        repository = ((AndroidBuilderApp) getApplication()).repository();
        adapter = new ProjectAdapter();
        ListView list = findViewById(R.id.projectList);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openProject(projects.get(position), null));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showProjectActions(projects.get(position));
            return true;
        });
        findViewById(R.id.newProjectButton).setOnClickListener(v -> showNewProjectDialog());
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
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
        LinearInputs inputs = new LinearInputs(this, getString(R.string.project_name), getString(R.string.initial_requirement));
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_project)
                .setView(inputs.view)
                .setPositiveButton(R.string.create, (dialog, which) -> {
                    String prompt = inputs.second.getText().toString().trim();
                    if (prompt.isEmpty()) {
                        Toast.makeText(this, R.string.requirement_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = inputs.first.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = NameUtils.projectNameFromPrompt(prompt);
                    }
                    ProjectRecord project = repository.createProject(name, NameUtils.packageNameFromProject(name), prompt);
                    openProject(project, prompt);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showProjectActions(ProjectRecord project) {
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
