package com.androidbuilder.agent;

import com.androidbuilder.model.AppSpec;
import com.androidbuilder.util.FileUtils;
import com.androidbuilder.util.NameUtils;

import java.io.File;
import java.io.IOException;

public class GeneratedProjectWriter {
    public void write(File sourceDir, AppSpec spec) throws IOException {
        FileUtils.deleteRecursively(sourceDir);
        String packagePath = spec.packageName.replace('.', '/');
        FileUtils.writeText(new File(sourceDir, "settings.gradle"), settings(spec));
        FileUtils.writeText(new File(sourceDir, "build.gradle"), rootBuild());
        FileUtils.writeText(new File(sourceDir, "gradle.properties"),
                "android.useAndroidX=false\n" +
                "org.gradle.daemon=false\n" +
                "org.gradle.workers.max=1\n");
        FileUtils.writeText(new File(sourceDir, "app/build.gradle"), appBuild(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/AndroidManifest.xml"), manifest(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/strings.xml"), strings(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/colors.xml"), colors());
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/styles.xml"), styles());
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/activity_main.xml"), mainLayout(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/activity_edit_item.xml"), editLayout(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/row_item.xml"), rowLayout());
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/ItemDbHelper.java"), dbHelper(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/MainActivity.java"), mainActivity(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/EditItemActivity.java"), editActivity(spec));
        FileUtils.writeText(new File(sourceDir, "README.md"), "# " + spec.appName + "\n\n" + spec.description + "\n");
    }

    private String settings(AppSpec spec) {
        return "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n" +
                "dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\n" +
                "rootProject.name = \"" + escape(spec.appName) + "\"\n" +
                "include ':app'\n";
    }

    private String rootBuild() {
        return "plugins {\n" +
                "    id 'com.android.application' version '8.7.3' apply false\n" +
                "}\n";
    }

    private String appBuild(AppSpec spec) {
        return "plugins {\n" +
                "    id 'com.android.application'\n" +
                "}\n\n" +
                "android { namespace '" + spec.packageName + "'; compileSdk 34\n" +
                "    defaultConfig { applicationId '" + spec.packageName + "'; minSdk 31; targetSdk 34; versionCode 1; versionName '1.0' }\n" +
                "    compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }\n" +
                "}\n";
    }

    private String manifest(AppSpec spec) {
        return "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <application android:allowBackup=\"true\" android:label=\"@string/app_name\" android:theme=\"@style/AppTheme\">\n" +
                "        <activity android:name=\".EditItemActivity\" />\n" +
                "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                "            <intent-filter>\n" +
                "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                "            </intent-filter>\n" +
                "        </activity>\n" +
                "    </application>\n" +
                "</manifest>\n";
    }

    private String strings(AppSpec spec) {
        return "<resources>\n" +
                "    <string name=\"app_name\">" + xml(spec.appName) + "</string>\n" +
                "    <string name=\"entity_name\">" + xml(spec.entityName) + "</string>\n" +
                "    <string name=\"primary_field\">" + xml(spec.primaryField) + "</string>\n" +
                "    <string name=\"secondary_field\">" + xml(spec.secondaryField) + "</string>\n" +
                "    <string name=\"no_records\">" + xml(text(spec, "No records yet", "暂无记录")) + "</string>\n" +
                "    <string name=\"add\">" + xml(text(spec, "Add", "新增")) + "</string>\n" +
                "    <string name=\"save\">" + xml(text(spec, "Save", "保存")) + "</string>\n" +
                "    <string name=\"delete\">" + xml(text(spec, "Delete", "删除")) + "</string>\n" +
                "    <string name=\"title_required\">" + xml(text(spec, "Title is required", "请填写标题")) + "</string>\n" +
                "</resources>\n";
    }

    private String colors() {
        return "<resources>\n" +
                "    <color name=\"primary\">#174EA6</color>\n" +
                "    <color name=\"surface\">#F7F8FA</color>\n" +
                "    <color name=\"ink\">#202124</color>\n" +
                "</resources>\n";
    }

    private String styles() {
        return "<resources>\n" +
                "    <style name=\"AppTheme\" parent=\"android:style/Theme.Material.Light.NoActionBar\">\n" +
                "        <item name=\"android:fontFamily\">sans</item>\n" +
                "        <item name=\"android:colorAccent\">@color/primary</item>\n" +
                "        <item name=\"android:windowLightStatusBar\">true</item>\n" +
                "        <item name=\"android:statusBarColor\">@color/surface</item>\n" +
                "    </style>\n" +
                "</resources>\n";
    }

    private String mainLayout(AppSpec spec) {
        return """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="@color/surface"
                    android:orientation="vertical"
                    android:padding="20dp">

                    <TextView
                        android:id="@+id/title"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/app_name"
                        android:textColor="@color/ink"
                        android:textSize="24sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/empty"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="14dp"
                        android:text="@string/no_records"
                        android:textColor="#5F6368"
                        android:visibility="gone" />

                    <ListView
                        android:id="@+id/list"
                        android:layout_width="match_parent"
                        android:layout_height="0dp"
                        android:layout_marginTop="12dp"
                        android:layout_weight="1"
                        android:divider="#DADCE0"
                        android:dividerHeight="1dp" />

                    <Button
                        android:id="@+id/addButton"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/add" />
                </LinearLayout>
                """;
    }

    private String editLayout(AppSpec spec) {
        return """
                <ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="@color/surface">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="20dp">

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/entity_name"
                            android:textColor="@color/ink"
                            android:textSize="24sp"
                            android:textStyle="bold" />

                        <EditText
                            android:id="@+id/titleInput"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="20dp"
                            android:hint="@string/primary_field"
                            android:inputType="textCapSentences" />

                        <EditText
                            android:id="@+id/notesInput"
                            android:layout_width="match_parent"
                            android:layout_height="160dp"
                            android:layout_marginTop="12dp"
                            android:gravity="top"
                            android:hint="@string/secondary_field"
                            android:inputType="textMultiLine|textCapSentences" />

                        <Button
                            android:id="@+id/saveButton"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="20dp"
                            android:text="@string/save" />

                        <Button
                            android:id="@+id/deleteButton"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/delete" />
                    </LinearLayout>
                </ScrollView>
                """;
    }

    private String rowLayout() {
        return """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="14dp">

                    <TextView
                        android:id="@+id/rowTitle"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:textColor="@color/ink"
                        android:textSize="17sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/rowNotes"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="4dp"
                        android:maxLines="2"
                        android:textColor="#5F6368" />
                </LinearLayout>
                """;
    }

    private String dbHelper(AppSpec spec) {
        return """
                package %s;

                import android.content.ContentValues;
                import android.content.Context;
                import android.database.Cursor;
                import android.database.sqlite.SQLiteDatabase;
                import android.database.sqlite.SQLiteOpenHelper;

                import java.util.ArrayList;
                import java.util.List;

                public class ItemDbHelper extends SQLiteOpenHelper {
                    public static class Item {
                        public final long id;
                        public final String title;
                        public final String notes;

                        public Item(long id, String title, String notes) {
                            this.id = id;
                            this.title = title;
                            this.notes = notes;
                        }
                    }

                    public ItemDbHelper(Context context) {
                        super(context, "items.db", null, 1);
                    }

                    @Override
                    public void onCreate(SQLiteDatabase db) {
                        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, notes TEXT NOT NULL)");
                    }

                    @Override
                    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                        db.execSQL("DROP TABLE IF EXISTS items");
                        onCreate(db);
                    }

                    public List<Item> all() {
                        List<Item> rows = new ArrayList<>();
                        try (Cursor cursor = getReadableDatabase().query("items", null, null, null, null, null, "id DESC")) {
                            while (cursor.moveToNext()) {
                                rows.add(new Item(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
                            }
                        }
                        return rows;
                    }

                    public Item get(long id) {
                        try (Cursor cursor = getReadableDatabase().query("items", null, "id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
                            return cursor.moveToFirst()
                                    ? new Item(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
                                    : null;
                        }
                    }

                    public long save(Long id, String title, String notes) {
                        ContentValues values = new ContentValues();
                        values.put("title", title);
                        values.put("notes", notes);
                        if (id == null) {
                            return getWritableDatabase().insertOrThrow("items", null, values);
                        } else {
                            getWritableDatabase().update("items", values, "id = ?", new String[]{String.valueOf(id)});
                            return id;
                        }
                    }

                    public void delete(long id) {
                        getWritableDatabase().delete("items", "id = ?", new String[]{String.valueOf(id)});
                    }
                }
                """.replace("%s", spec.packageName);
    }

    private String mainActivity(AppSpec spec) {
        return """
                package %s;

                import android.app.Activity;
                import android.content.Intent;
                import android.os.Bundle;
                import android.view.View;
                import android.view.ViewGroup;
                import android.widget.BaseAdapter;
                import android.widget.Button;
                import android.widget.ListView;
                import android.widget.TextView;

                import java.util.ArrayList;
                import java.util.List;

                public class MainActivity extends Activity {
                    private ItemDbHelper db;
                    private ItemAdapter adapter;
                    private List<ItemDbHelper.Item> items = new ArrayList<>();

                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                        db = new ItemDbHelper(this);
                        adapter = new ItemAdapter();
                        ListView list = findViewById(R.id.list);
                        list.setAdapter(adapter);
                        list.setOnItemClickListener((parent, view, position, id) ->
                                startActivity(new Intent(this, EditItemActivity.class).putExtra("id", items.get(position).id)));
                        Button addButton = findViewById(R.id.addButton);
                        addButton.setOnClickListener(v -> startActivity(new Intent(this, EditItemActivity.class)));
                    }

                    @Override
                    protected void onResume() {
                        super.onResume();
                        load();
                    }

                    private void load() {
                        items = db.all();
                        TextView empty = findViewById(R.id.empty);
                        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        adapter.notifyDataSetChanged();
                    }

                    private class ItemAdapter extends BaseAdapter {
                        @Override public int getCount() { return items.size(); }
                        @Override public Object getItem(int position) { return items.get(position); }
                        @Override public long getItemId(int position) { return items.get(position).id; }

                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            View view = convertView == null ? getLayoutInflater().inflate(R.layout.row_item, parent, false) : convertView;
                            ItemDbHelper.Item item = items.get(position);
                            TextView rowTitle = view.findViewById(R.id.rowTitle);
                            TextView rowNotes = view.findViewById(R.id.rowNotes);
                            rowTitle.setText(item.title);
                            rowNotes.setText(item.notes);
                            return view;
                        }
                    }
                }
                """.replace("%s", spec.packageName);
    }

    private String editActivity(AppSpec spec) {
        return """
                package %s;

                import android.app.Activity;
                import android.os.Bundle;
                import android.view.View;
                import android.widget.Button;
                import android.widget.EditText;
                import android.widget.Toast;

                public class EditItemActivity extends Activity {
                    private ItemDbHelper db;
                    private Long itemId;

                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_edit_item);
                        db = new ItemDbHelper(this);
                        EditText titleInput = findViewById(R.id.titleInput);
                        EditText notesInput = findViewById(R.id.notesInput);
                        if (getIntent().hasExtra("id")) {
                            long id = getIntent().getLongExtra("id", -1);
                            itemId = id > 0 ? id : null;
                        }
                        if (itemId != null) {
                            ItemDbHelper.Item item = db.get(itemId);
                            if (item != null) {
                                titleInput.setText(item.title);
                                notesInput.setText(item.notes);
                            }
                        }
                        Button deleteButton = findViewById(R.id.deleteButton);
                        deleteButton.setVisibility(itemId == null ? View.GONE : View.VISIBLE);
                        Button saveButton = findViewById(R.id.saveButton);
                        saveButton.setOnClickListener(v -> {
                            String title = titleInput.getText().toString().trim();
                            String notes = notesInput.getText().toString().trim();
                            if (title.isEmpty()) {
                                Toast.makeText(this, getString(R.string.title_required), Toast.LENGTH_SHORT).show();
                            } else {
                                db.save(itemId, title, notes);
                                finish();
                            }
                        });
                        deleteButton.setOnClickListener(v -> {
                            if (itemId != null) {
                                db.delete(itemId);
                            }
                            finish();
                        });
                    }
                }
                """.replace("%s", spec.packageName);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String xml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String text(AppSpec spec, String english, String chinese) {
        return "zh".equals(spec.language) ? chinese : english;
    }
}
