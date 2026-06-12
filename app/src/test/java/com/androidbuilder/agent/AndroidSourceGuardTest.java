package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AndroidSourceGuardTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void blocksDataBindingImports() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/root\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nimport com.example.databinding.ActivityMainBinding;\nclass MainActivity {}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked DataBinding/ViewBinding imports in MainActivity.java. Use findViewById with plain XML ids.", error.getMessage());
    }

    @Test
    public void blocksBareCamelCaseViewIdAccess() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/fabAdd\" /></LinearLayout>");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind() { fabAdd.setOnClickListener(v -> { }); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked synthetic view access: fabAdd in MainActivity.java. Declare it with findViewById from the inflated root/dialog view.", error.getMessage());
    }

    @Test
    public void allowsDeclaredCamelCaseViewVariables() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/fabAdd\" /></LinearLayout>");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind() { View fabAdd = findViewById(R.id.fabAdd); fabAdd.setText(\"ok\"); } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void blocksJavaLambdaSyntax() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind(View button) { button.setOnClickListener(v -> open()); } void open() {} }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked Java lambda syntax in MainActivity.java. Use anonymous listener classes instead of ->.", error.getMessage());
    }

    @Test
    public void ignoresArrowAndResourceReferencesInsideCommentsAndStrings() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\n"
                        + "class MainActivity {\n"
                        + "  /** R.id.not_exist and value -> next are documentation only. */\n"
                        + "  void bind() {\n"
                        + "    // R.id.comment_only -> should not be scanned\n"
                        + "    String text = \"R.id.string_only -> still just text\";\n"
                        + "  }\n"
                        + "}\n");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void blocksRealMissingIdAfterIgnoringCommentReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\n"
                        + "class MainActivity {\n"
                        + "  // R.id.comment_only should not be the reported missing id\n"
                        + "  void bind() { findViewById(R.id.not_exist); }\n"
                        + "}\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing XML id: R.id.not_exist in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksKotlinSourceFiles() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/root\" />");
        write(root, "app/src/main/java/com/example/MainActivity.kt",
                "package com.example\nclass MainActivity");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked Kotlin source file: MainActivity.kt. Use Java source files (.java) only.", error.getMessage());
    }

    @Test
    public void blocksMissingLayoutReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind() { setContentView(R.layout.missing_screen); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing layout resource: R.layout.missing_screen in MainActivity.java.", error.getMessage());
    }

    @Test
    public void reportsMultipleGeneratedSourceViolationsTogether() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind(View button) { findViewById(R.id.toolbar); button.setOnClickListener(v -> open()); } void open() {} }");
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper { static final String TABLE_CATEGORY = \"categories\"; }");
        write(root, "app/src/main/java/com/example/CategoryDao.java",
                "package com.example;\nclass CategoryDao { String order() { return DBHelper.COL_CATEGORY_ID + \" ASC\"; } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertTrue(error.getMessage().startsWith("Generated source policy blocked"));
        assertTrue(error.getMessage().contains("Generated source policy blocked missing XML id: R.id.toolbar in MainActivity.java."));
        assertTrue(error.getMessage().contains("Generated source policy blocked Java lambda syntax in MainActivity.java. Use anonymous listener classes instead of ->."));
        assertTrue(error.getMessage().contains("Generated source policy blocked missing class field: DBHelper.COL_CATEGORY_ID in CategoryDao.java. Add the constant/field to DBHelper or update the caller to use an existing API."));
    }

    @Test
    public void blocksMissingStringReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/values/strings.xml",
                "<resources><string name=\"app_name\">Demo</string></resources>");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind() { getString(R.string.missing_label); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing string resource: R.string.missing_label in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksMissingDrawableReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/drawable/ic_add.xml",
                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { int icon = R.drawable.missing_icon; }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing drawable resource: R.drawable.missing_icon in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksMissingColorReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/values/colors.xml",
                "<resources><color name=\"primary\">#336699</color></resources>");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { int color = R.color.missing_color; }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing color resource: R.color.missing_color in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksMissingMipmapReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/mipmap/ic_launcher.xml",
                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { int icon = R.mipmap.missing_launcher; }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing mipmap resource: R.mipmap.missing_launcher in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksMissingManifestMipmapReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/AndroidManifest.xml",
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:icon=\"@mipmap/ic_launcher\" /></manifest>");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing XML resource reference: @mipmap/ic_launcher in AndroidManifest.xml.", error.getMessage());
    }

    @Test
    public void blocksMissingStyleReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/values/styles.xml",
                "<resources><style name=\"AppTheme\" /></resources>");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { int theme = R.style.MissingTheme; }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing style resource: R.style.MissingTheme in MainActivity.java.", error.getMessage());
    }

    @Test
    public void blocksMissingManifestStyleReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/values/styles.xml",
                "<resources><style name=\"AppTheme\" /></resources>");
        write(root, "app/src/main/AndroidManifest.xml",
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@style/Theme.LedgerApp\" /></manifest>");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing XML resource reference: @style/Theme.LedgerApp in AndroidManifest.xml.", error.getMessage());
    }

    @Test
    public void allowsExistingValueAndFileResources() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/root\" />");
        write(root, "app/src/main/res/values/strings.xml",
                "<resources><string name=\"app_name\">Demo</string></resources>");
        write(root, "app/src/main/res/values/colors.xml",
                "<resources><color name=\"primary\">#336699</color></resources>");
        write(root, "app/src/main/res/values/styles.xml",
                "<resources><style name=\"AppTheme\" /></resources>");
        write(root, "app/src/main/res/drawable/ic_add.xml",
                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\" />");
        write(root, "app/src/main/res/mipmap/ic_launcher.xml",
                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\" />");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind() { setContentView(R.layout.activity_main); int a = R.string.app_name; int b = R.color.primary; int c = R.style.AppTheme; int d = R.drawable.ic_add; int e = R.mipmap.ic_launcher; } }");
        write(root, "app/src/main/AndroidManifest.xml",
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@style/AppTheme\" android:icon=\"@mipmap/ic_launcher\" /></manifest>");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void blocksLayoutWidgetWhenRequiredDependencyIsMissing() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle",
                "plugins { id 'com.android.application' }\ndependencies { implementation 'androidx.appcompat:appcompat:1.7.0' }\n");
        write(root, "app/src/main/res/layout/activity_grid.xml",
                "<androidx.gridlayout.widget.GridLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertTrue(error.getMessage().contains("Generated source policy blocked layout widget androidx.gridlayout.widget.GridLayout in activity_grid.xml"));
        assertTrue(error.getMessage().contains("dependency androidx.gridlayout:gridlayout is not declared in app/build.gradle"));
        assertTrue(error.getMessage().contains("return blocked with prerequisiteWork naming the dependency"));
    }

    @Test
    public void allowsLayoutWidgetWhenRequiredDependencyIsDeclared() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/build.gradle",
                "plugins { id 'com.android.application' }\ndependencies { implementation 'androidx.gridlayout:gridlayout:1.0.0' }\n");
        write(root, "app/src/main/res/layout/activity_grid.xml",
                "<androidx.gridlayout.widget.GridLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void ignoresAndroidFrameworkResources() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { int ok = android.R.string.ok; }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void blocksMissingCustomModelFieldAccess() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/CategorySum.java",
                "package com.example;\nclass CategorySum { public final String name; CategorySum(String name) { this.name = name; } }");
        write(root, "app/src/main/java/com/example/StatisticsAdapter.java",
                "package com.example;\nclass StatisticsAdapter { void bind(CategorySum item) { String label = item.categoryName; } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing model field: CategorySum.categoryName in StatisticsAdapter.java. Add the field/getter or update the caller to use an existing API.", error.getMessage());
    }

    @Test
    public void blocksConstructorArgumentTypeMismatch() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/CategoryDAO.java",
                "package com.example;\nimport android.content.Context;\nclass CategoryDAO { CategoryDAO(Context context) {} }");
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper {}");
        write(root, "app/src/main/java/com/example/StatisticsActivity.java",
                "package com.example;\nclass StatisticsActivity { DBHelper dbHelper; void bind() { CategoryDAO dao = new CategoryDAO(dbHelper); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked constructor argument mismatch: new CategoryDAO(DBHelper) in StatisticsActivity.java, but available constructors are CategoryDAO(Context). Update the constructor or caller consistently.", error.getMessage());
    }

    @Test
    public void blocksMissingDbHelperColumnConstants() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper { static final String TABLE_CATEGORY = \"categories\"; }");
        write(root, "app/src/main/java/com/example/CategoryDao.java",
                "package com.example;\nclass CategoryDao { String order() { return DBHelper.COL_CATEGORY_ID + \" ASC\"; } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing class field: DBHelper.COL_CATEGORY_ID in CategoryDao.java. Add the constant/field to DBHelper or update the caller to use an existing API.", error.getMessage());
    }

    @Test
    public void allowsClassLiteralOnGeneratedApiClass() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper { String name() { synchronized (DBHelper.class) { return DBHelper.class.getName(); } } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void allowsSqliteOpenHelperInheritedDatabaseMethods() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\nclass DBHelper extends SQLiteOpenHelper { }");
        write(root, "app/src/main/java/com/example/CategoryDao.java",
                "package com.example;\nclass CategoryDao { DBHelper dbHelper; void save() { dbHelper.getWritableDatabase(); dbHelper.getReadableDatabase(); dbHelper.close(); } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void allowsAnonymousClassForNestedListenerInterface() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Record.java",
                "package com.example;\nclass Record { }");
        write(root, "app/src/main/java/com/example/TimelineAdapter.java",
                "package com.example;\nclass TimelineAdapter { interface OnRecordClickListener { void onRecordClick(Record record); } void setOnRecordClickListener(OnRecordClickListener listener) { } }");
        write(root, "app/src/main/java/com/example/TimelineFragment.java",
                "package com.example;\nclass TimelineFragment { TimelineAdapter adapter; void bind() { adapter.setOnRecordClickListener(new TimelineAdapter.OnRecordClickListener() { public void onRecordClick(Record record) { } }); } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void blocksMissingDaoMethodCalls() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Record.java",
                "package com.example;\nclass Record { }");
        write(root, "app/src/main/java/com/example/RecordDao.java",
                "package com.example;\nclass RecordDao { long insert(Record record) { return 1; } }");
        write(root, "app/src/main/java/com/example/AddRecordActivity.java",
                "package com.example;\nclass AddRecordActivity { RecordDao recordDao; void save(Record record) { recordDao.update(record); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));

        assertEquals("Generated source policy blocked missing method: RecordDao.update(Record) in AddRecordActivity.java. Add the method or update the caller to use an existing API.", error.getMessage());
    }

    @Test
    public void allowsExistingDaoMethodCalls() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Record.java",
                "package com.example;\nclass Record { }");
        write(root, "app/src/main/java/com/example/RecordDao.java",
                "package com.example;\nclass RecordDao { long insert(Record record) { return 1; } void update(Record record) { } }");
        write(root, "app/src/main/java/com/example/AddRecordActivity.java",
                "package com.example;\nclass AddRecordActivity { RecordDao recordDao; void save(Record record) { recordDao.update(record); } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void allowsFieldDeclaredOnSecondaryClassInSameFile() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Models.java",
                "package com.example;\nclass Outer { String label; }\nclass CategorySum { String total; }");
        write(root, "app/src/main/java/com/example/StatisticsAdapter.java",
                "package com.example;\nclass StatisticsAdapter { void bind(CategorySum item) { String label = item.total; } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void allowsClassQualifiedResourceIdConstantsInsideAdapter() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/TransactionAdapter.java",
                "package com.example;\nclass TransactionAdapter { static final int id = 1; static class ViewHolder {} int bind() { return TransactionAdapter.id; } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void doesNotTreatAdapterFieldAccessAsModelDtoPolicyFailure() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/TransactionAdapter.java",
                "package com.example;\nclass TransactionAdapter { int bind(TransactionAdapter adapter) { return adapter.id; } }");

        new AndroidSourceGuard().validate(root);
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
