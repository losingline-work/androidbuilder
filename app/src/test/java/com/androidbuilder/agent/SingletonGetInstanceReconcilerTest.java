package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SingletonGetInstanceReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void addsStaticContextSingletonForSqliteHelperShape() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/x/db/DatabaseHelper.java",
                "package com.x.db;\nimport android.content.Context;\n"
                        + "public class DatabaseHelper extends android.database.sqlite.SQLiteOpenHelper {\n"
                        + "  public DatabaseHelper(Context context) { super(context, \"db\", null, 1); }\n"
                        + "  public void onCreate(android.database.sqlite.SQLiteDatabase d) {}\n"
                        + "  public void onUpgrade(android.database.sqlite.SQLiteDatabase d, int a, int b) {}\n}\n");
        write(root, "app/src/main/java/com/x/ui/TransactionAdapter.java",
                "package com.x.ui;\nimport android.content.Context;\nimport com.x.db.DatabaseHelper;\n"
                        + "class TransactionAdapter { void f(Context context) {\n"
                        + "  Object db = DatabaseHelper.getInstance(context).getWritableDatabase();\n} }\n");

        List<String> added = SingletonGetInstanceReconciler.reconcile(root);

        String helper = FileUtils.readText(new File(root, "app/src/main/java/com/x/db/DatabaseHelper.java"));
        assertTrue(added.contains("DatabaseHelper.getInstance (singleton)"));
        assertTrue(helper.contains("public static synchronized DatabaseHelper getInstance(android.content.Context a0)"));
        assertTrue(helper.contains("new DatabaseHelper(a0.getApplicationContext())"));
        assertTrue(helper.contains("private static DatabaseHelper sInstance;"));
    }

    @Test
    public void doesNotTouchAClassThatAlreadyDeclaresGetInstance() throws Exception {
        File root = temporaryFolder.newFolder("source");
        String original = "package com.x;\nimport android.content.Context;\n"
                + "public class Repo {\n  public Repo(Context c) {}\n"
                + "  private static Repo i;\n  public static Repo getInstance(Context c) { return i; }\n}\n";
        write(root, "app/src/main/java/com/x/Repo.java", original);
        write(root, "app/src/main/java/com/x/Caller.java",
                "package com.x;\nimport android.content.Context;\nclass Caller { void f(Context c){ Repo.getInstance(c); } }\n");

        List<String> added = SingletonGetInstanceReconciler.reconcile(root);

        assertTrue(added.isEmpty());
        assertTrue(original.equals(FileUtils.readText(new File(root, "app/src/main/java/com/x/Repo.java"))));
    }

    @Test
    public void skipsWhenNoConstructorMatchesTheCallArity() throws Exception {
        File root = temporaryFolder.newFolder("source");
        // Repo only has a 2-arg ctor; a getInstance(context) singleton cannot be built safely → leave it.
        write(root, "app/src/main/java/com/x/Repo.java",
                "package com.x;\nimport android.content.Context;\n"
                        + "public class Repo {\n  public Repo(Context c, String name) {}\n}\n");
        write(root, "app/src/main/java/com/x/Caller.java",
                "package com.x;\nimport android.content.Context;\nclass Caller { void f(Context c){ Repo.getInstance(c); } }\n");

        List<String> added = SingletonGetInstanceReconciler.reconcile(root);

        assertTrue(added.isEmpty());
        assertFalse(FileUtils.readText(new File(root, "app/src/main/java/com/x/Repo.java")).contains("getInstance"));
    }

    private void write(File root, String path, String content) throws Exception {
        FileUtils.writeText(new File(root, path), content);
    }
}
