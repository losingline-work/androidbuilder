package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void allowsMaterialLibraryStyleReferences() throws Exception {
        // Widget.Material3.* styles are provided by the Material dependency, not declared by the app.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/dialog_add_account.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "style=\"@style/Widget.Material3.Button.TextButton\" />");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void allowsColorSelectorFileReferences() throws Exception {
        // A res/color/*.xml ColorStateList selector is a valid @color/ target, just like a drawable file.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/color/bottom_nav_item_state.xml",
                "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">"
                        + "<item android:color=\"#FFEF5350\"/></selector>");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:textColor=\"@color/bottom_nav_item_state\" />");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void stillBlocksGenuinelyMissingColorReferences() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/layout/activity_main.xml",
                "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                        + "android:textColor=\"@color/not_declared_anywhere\" />");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AndroidSourceGuard().validate(root));

        assertTrue(error.getMessage().contains("missing XML resource reference: @color/not_declared_anywhere"));
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
    public void packageAndImportSegmentsAreNotFieldAccess() throws Exception {
        // Real false positive from project-51: BaseActivity has a field "app" of type App;
        // "package com.generated.app.ui.common;" and the imports were read as app.ui / app.App
        // / app.AppCompatActivity field accesses on class App.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/generated/app/App.java",
                "package com.generated.app;\npublic class App { private static App instance; public static App get() { return instance; } }");
        write(root, "app/src/main/java/com/generated/app/ui/common/BaseActivity.java",
                "package com.generated.app.ui.common;\n"
                        + "import com.generated.app.App;\n"
                        + "public class BaseActivity {\n"
                        + "    public App app;\n"
                        + "    void init() { app = App.get(); }\n"
                        + "}");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void nestedTypeReferenceIsNotFieldAccess() throws Exception {
        // Real false positive from project-51: BillAdapter.Row used as a type was read as a
        // missing field "Row" on class BillAdapter. ALL_CAPS constants stay checked.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/BillAdapter.java",
                "package com.example;\nclass BillAdapter { public static class Row { public long id; } }");
        write(root, "app/src/main/java/com/example/BillListFragment.java",
                "package com.example;\nclass BillListFragment { void bind() { BillAdapter.Row row = new BillAdapter.Row(); long id = row.id; } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void innerClassThisFieldsResolveToTheInnerClass() throws Exception {
        // Real false positive from project-51: this.date inside an inner class constructor was
        // attributed to the outer SectionHeaderDecoration class and rejected.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/SectionHeaderDecoration.java",
                "package com.example;\n"
                        + "public class SectionHeaderDecoration {\n"
                        + "    public int headerHeightPx;\n"
                        + "    public static class Header {\n"
                        + "        public CharSequence date;\n"
                        + "        public CharSequence income;\n"
                        + "        Header(CharSequence dateText, CharSequence incomeText) {\n"
                        + "            this.date = dateText;\n"
                        + "            this.income = incomeText;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void nestedViewHolderClassAndInheritedMembersAreNotFlagged() throws Exception {
        // project-81 false positives: VH is a nested class extending RecyclerView.ViewHolder.
        // BillRecordAdapter.VH (a type), VH.itemView and VH.getAdapterPosition() (inherited) must
        // not be reported as missing field/method.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/BillRecordAdapter.java",
                "package com.example;\n"
                        + "class BillRecordAdapter {\n"
                        + "    static class VH extends RecyclerView.ViewHolder {\n"
                        + "        VH(android.view.View v) { super(v); }\n"
                        + "        void bind() { int p = getAdapterPosition(); android.view.View v = itemView; }\n"
                        + "    }\n"
                        + "    BillRecordAdapter.VH make(android.view.View v) { return new BillRecordAdapter.VH(v); }\n"
                        + "}");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void missingMethodOnPureGeneratedClassStillBlocks() throws Exception {
        // Guardrail: the external-API deferral must NOT mask a real missing method on a class that
        // does not extend anything external.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Money.java",
                "package com.example;\nclass Money { public String text() { return \"\"; } }");
        write(root, "app/src/main/java/com/example/HomeFragmentHelper.java",
                "package com.example;\nclass HomeFragmentHelper { Money money;\n void run() { money.format(1L); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));
        assertTrue(error.getMessage().contains("missing method: Money.format"));
    }

    @Test
    public void mapEntryGetKeyIsNotFlaggedDespiteGeneratedEntryClass() throws Exception {
        // project-83 false positive: an adapter declares a nested class Entry; another file iterates a
        // java.util.Map and calls e.getKey(). simpleType("Map.Entry") collapses to "Entry", colliding
        // with the generated Entry, so getKey()/getValue() were wrongly reported missing. A Map.Entry
        // variable must be treated as external.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/BillSectionAdapter.java",
                "package com.example;\n"
                        + "class BillSectionAdapter {\n"
                        + "    static final class Entry { long total; }\n"
                        + "}");
        write(root, "app/src/main/java/com/example/StatsCalculator.java",
                "package com.example;\n"
                        + "import java.util.Map;\n"
                        + "class StatsCalculator {\n"
                        + "    void run(Map<Long, Long> totals) {\n"
                        + "        for (Map.Entry<Long, Long> e : totals.entrySet()) {\n"
                        + "            long k = e.getKey().longValue();\n"
                        + "            long v = e.getValue().longValue();\n"
                        + "        }\n"
                        + "    }\n"
                        + "}");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void policyOnlyValidationDefersTypeCheckingButKeepsPolicy() throws Exception {
        // Compile-driven mode: a real cross-file type error (a missing method) is NOT blocked at merge
        // (the compiler will catch it), but a POLICY violation (a Java lambda) still is.
        File typeError = temporaryFolder.newFolder("type");
        write(typeError, "app/src/main/java/com/example/Money.java", "package com.example;\nclass Money {}");
        write(typeError, "app/src/main/java/com/example/Repo.java",
                "package com.example;\nclass Repo { Money money; void run() { money.missingMethod(); } }");
        new AndroidSourceGuard().validatePolicyOnly(typeError); // type error deferred - does NOT throw

        IllegalArgumentException stillBlocked = assertThrows(IllegalArgumentException.class,
                () -> new AndroidSourceGuard().validate(typeError));
        assertTrue(stillBlocked.getMessage().contains("missing method"));

        File policyError = temporaryFolder.newFolder("policy");
        write(policyError, "app/src/main/java/com/example/MainActivity.java",
                "package com.example;\nclass MainActivity { void bind(View b) { b.setOnClickListener(v -> open()); } void open() {} }");
        IllegalArgumentException lambda = assertThrows(IllegalArgumentException.class,
                () -> new AndroidSourceGuard().validatePolicyOnly(policyError));
        assertTrue(lambda.getMessage().contains("Java lambda syntax"));
    }

    @Test
    public void argMismatchDefersWhenDeclaredParamIsLibrarySupertype() throws Exception {
        // project-9 false positive: ChartConfig.apply(Context, Chart) called with PieChart/BarChart.
        // PieChart IS-A Chart (MPAndroidChart), so it compiles; but the guard can't see library
        // hierarchies, so it must defer the arg check rather than assert a mismatch.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/ChartConfig.java",
                "package com.example;\nclass ChartConfig { void apply(Context c, Chart chart) {} }");
        write(root, "app/src/main/java/com/example/CategoryPieBuilder.java",
                "package com.example;\n"
                        + "class CategoryPieBuilder {\n"
                        + "    void build(Context c, PieChart pie) {\n"
                        + "        ChartConfig cfg = new ChartConfig();\n"
                        + "        cfg.apply(c, pie);\n"
                        + "    }\n"
                        + "}");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void argMismatchOnGeneratedParamTypeStillBlocks() throws Exception {
        // Guardrail: the library-type deferral must NOT mask a mismatch where the declared param is a
        // generated class the guard CAN resolve.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Money.java", "package com.example;\nclass Money {}");
        write(root, "app/src/main/java/com/example/Account.java", "package com.example;\nclass Account {}");
        write(root, "app/src/main/java/com/example/Calc.java",
                "package com.example;\nclass Calc { void apply(Account a) {} }");
        write(root, "app/src/main/java/com/example/Repo.java",
                "package com.example;\n"
                        + "class Repo {\n"
                        + "    Calc calc;\n"
                        + "    void run(Money m) { calc.apply(m); }\n"
                        + "}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));
        assertTrue(error.getMessage().contains("method argument mismatch: Calc.apply"));
    }

    @Test
    public void newExpressionsAreNotCountedAsDeclaredMethods() throws Exception {
        // project-9: the method parser counted `new ContentValues()`, `throw new IllegalArgumentException`,
        // `return new Record()` as RecordDao "methods", polluting the digest/hint injected into the model.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/RecordDao.java",
                "package com.example;\n"
                        + "class RecordDao {\n"
                        + "    long insert() { ContentValues v = new ContentValues(); return 0L; }\n"
                        + "    void bad() { throw new IllegalArgumentException(\"x\"); }\n"
                        + "    Record find() { return new Record(); }\n"
                        + "    long countByCategory() { return 0L; }\n"
                        + "}");
        SymbolTable table = new AndroidSourceGuard().collectSymbolTableForTest(root);

        assertTrue(table.hasMethod("RecordDao", "insert"));
        assertTrue(table.hasMethod("RecordDao", "countByCategory"));
        assertFalse(table.hasMethod("RecordDao", "ContentValues"));
        assertFalse(table.hasMethod("RecordDao", "IllegalArgumentException"));
        assertFalse(table.hasMethod("RecordDao", "Record"));
    }

    @Test
    public void realMissingMethodOnGeneratedEntryClassStillBlocks() throws Exception {
        // Guardrail: the Map.Entry deferral must NOT mask a real missing method on a generated class
        // genuinely named Entry when referenced as a plain (unqualified) Entry variable.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Entry.java",
                "package com.example;\nclass Entry { long total() { return 0L; } }");
        write(root, "app/src/main/java/com/example/StatsCalculator.java",
                "package com.example;\nclass StatsCalculator { void run() { Entry e = new Entry(); e.missingOne(); } }");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new AndroidSourceGuard().validate(root));
        assertTrue(error.getMessage().contains("missing method: Entry.missingOne"));
    }

    @Test
    public void recognizesIdsDeclaredViaValuesItemTag() throws Exception {
        // <item type="id" name="X"/> in a values file is a valid R.id source; a Java reference to
        // it must not be blocked as a missing id.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/res/values/ids.xml",
                "<resources><item type=\"id\" name=\"account_manage_title\"/></resources>");
        write(root, "app/src/main/java/com/example/AccountManageActivity.java",
                "package com.example;\nclass AccountManageActivity { int t() { return R.id.account_manage_title; } }");

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
    public void boxedAndWidenedNumericArgsAreNotMismatches() throws Exception {
        // project-8 false positives: findById(Long)/delete(Long) vs findById(long), and int->long
        // widening, are valid Java and must not be reported as argument mismatches.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/CategoryDao.java",
                "package com.example;\nclass CategoryDao { public void findById(long id) {} public void touch(long t) {} }");
        write(root, "app/src/main/java/com/example/CategoryRepository.java",
                "package com.example;\nclass CategoryRepository { CategoryDao dao;\n"
                        + " void a(Long boxed) { dao.findById(boxed); }\n"
                        + " void b(int small) { dao.touch(small); } }");

        new AndroidSourceGuard().validate(root);
    }

    @Test
    public void conflictingOverloadParamNamesDoNotProducePhantomMismatch() throws Exception {
        // The putLong(String,String) phantom: a file-global last-write-wins type map collapsed the
        // `value` param of putLong(String,long) to the `String value` of putString, producing a
        // false "putLong(String,String)" mismatch. A conflicted variable name is now treated unknown.
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/SettingDao.java",
                "package com.example;\nclass SettingDao {\n"
                        + " public void putString(String key, String value) {}\n"
                        + " public void putLong(String key, long value) {}\n"
                        + " public void putInt(String key, int value) {} }");
        write(root, "app/src/main/java/com/example/SettingRepository.java",
                "package com.example;\nclass SettingRepository { SettingDao dao;\n"
                        + " void s(String key, String value) { dao.putString(key, value); }\n"
                        + " void l(String key, long value) { dao.putLong(key, value); }\n"
                        + " void i(String key, int value) { dao.putInt(key, value); } }");

        new AndroidSourceGuard().validate(root);
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
