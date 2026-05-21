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
        FileUtils.writeText(new File(sourceDir, "gradle.properties"), "android.useAndroidX=false\norg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\n");
        FileUtils.writeText(new File(sourceDir, "app/build.gradle"), appBuild(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/AndroidManifest.xml"), manifest(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/strings.xml"), strings(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/colors.xml"), colors());
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/values/styles.xml"), styles());
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/activity_main.xml"), mainLayout(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/activity_edit_item.xml"), editLayout(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/res/layout/row_item.xml"), rowLayout());
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/ItemDbHelper.kt"), dbHelper(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/MainActivity.kt"), mainActivity(spec));
        FileUtils.writeText(new File(sourceDir, "app/src/main/java/" + packagePath + "/EditItemActivity.kt"), editActivity(spec));
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
                "    id 'org.jetbrains.kotlin.android' version '2.0.21' apply false\n" +
                "}\n";
    }

    private String appBuild(AppSpec spec) {
        return "plugins {\n" +
                "    id 'com.android.application'\n" +
                "    id 'org.jetbrains.kotlin.android'\n" +
                "}\n\n" +
                "android { namespace '" + spec.packageName + "'; compileSdk 34\n" +
                "    defaultConfig { applicationId '" + spec.packageName + "'; minSdk 31; targetSdk 34; versionCode 1; versionName '1.0' }\n" +
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
                package %s

                import android.content.ContentValues
                import android.content.Context
                import android.database.sqlite.SQLiteDatabase
                import android.database.sqlite.SQLiteOpenHelper

                data class Item(val id: Long, val title: String, val notes: String)

                class ItemDbHelper(context: Context) : SQLiteOpenHelper(context, "items.db", null, 1) {
                    override fun onCreate(db: SQLiteDatabase) {
                        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, notes TEXT NOT NULL)")
                    }

                    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        db.execSQL("DROP TABLE IF EXISTS items")
                        onCreate(db)
                    }

                    fun all(): List<Item> {
                        val rows = mutableListOf<Item>()
                        readableDatabase.query("items", null, null, null, null, null, "id DESC").use { cursor ->
                            while (cursor.moveToNext()) {
                                rows.add(Item(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                            }
                        }
                        return rows
                    }

                    fun get(id: Long): Item? {
                        readableDatabase.query("items", null, "id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
                            return if (cursor.moveToFirst()) Item(cursor.getLong(0), cursor.getString(1), cursor.getString(2)) else null
                        }
                    }

                    fun save(id: Long?, title: String, notes: String): Long {
                        val values = ContentValues().apply {
                            put("title", title)
                            put("notes", notes)
                        }
                        return if (id == null) {
                            writableDatabase.insertOrThrow("items", null, values)
                        } else {
                            writableDatabase.update("items", values, "id = ?", arrayOf(id.toString()))
                            id
                        }
                    }

                    fun delete(id: Long) {
                        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
                    }
                }
                """.replace("%s", spec.packageName);
    }

    private String mainActivity(AppSpec spec) {
        return """
                package %s

                import android.app.Activity
                import android.content.Intent
                import android.os.Bundle
                import android.view.View
                import android.view.ViewGroup
                import android.widget.BaseAdapter
                import android.widget.Button
                import android.widget.ListView
                import android.widget.TextView

                class MainActivity : Activity() {
                    private lateinit var db: ItemDbHelper
                    private lateinit var adapter: ItemAdapter
                    private var items: List<Item> = emptyList()

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                        db = ItemDbHelper(this)
                        adapter = ItemAdapter()
                        val list = findViewById<ListView>(R.id.list)
                        list.adapter = adapter
                        list.setOnItemClickListener { _, _, position, _ ->
                            startActivity(Intent(this, EditItemActivity::class.java).putExtra("id", items[position].id))
                        }
                        findViewById<Button>(R.id.addButton).setOnClickListener {
                            startActivity(Intent(this, EditItemActivity::class.java))
                        }
                    }

                    override fun onResume() {
                        super.onResume()
                        load()
                    }

                    private fun load() {
                        items = db.all()
                        findViewById<TextView>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                        adapter.notifyDataSetChanged()
                    }

                    inner class ItemAdapter : BaseAdapter() {
                        override fun getCount() = items.size
                        override fun getItem(position: Int) = items[position]
                        override fun getItemId(position: Int) = items[position].id

                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                            val view = convertView ?: layoutInflater.inflate(R.layout.row_item, parent, false)
                            val item = items[position]
                            view.findViewById<TextView>(R.id.rowTitle).text = item.title
                            view.findViewById<TextView>(R.id.rowNotes).text = item.notes
                            return view
                        }
                    }
                }
                """.replace("%s", spec.packageName);
    }

    private String editActivity(AppSpec spec) {
        return """
                package %s

                import android.app.Activity
                import android.os.Bundle
                import android.view.View
                import android.widget.Button
                import android.widget.EditText
                import android.widget.Toast

                class EditItemActivity : Activity() {
                    private lateinit var db: ItemDbHelper
                    private var itemId: Long? = null

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_edit_item)
                        db = ItemDbHelper(this)
                        val titleInput = findViewById<EditText>(R.id.titleInput)
                        val notesInput = findViewById<EditText>(R.id.notesInput)
                        itemId = intent.takeIf { it.hasExtra("id") }?.getLongExtra("id", -1)?.takeIf { it > 0 }
                        itemId?.let { id ->
                            db.get(id)?.let { item ->
                                titleInput.setText(item.title)
                                notesInput.setText(item.notes)
                            }
                        }
                        findViewById<Button>(R.id.deleteButton).visibility = if (itemId == null) View.GONE else View.VISIBLE
                        findViewById<Button>(R.id.saveButton).setOnClickListener {
                            val title = titleInput.text.toString().trim()
                            val notes = notesInput.text.toString().trim()
                            if (title.isEmpty()) {
                                Toast.makeText(this, getString(R.string.title_required), Toast.LENGTH_SHORT).show()
                            } else {
                                db.save(itemId, title, notes)
                                finish()
                            }
                        }
                        findViewById<Button>(R.id.deleteButton).setOnClickListener {
                            itemId?.let { db.delete(it) }
                            finish()
                        }
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
