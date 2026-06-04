package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
