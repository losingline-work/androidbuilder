package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JavaApiDigestTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void digestExtractsClassConstructorsMethodsAndConstants() throws Exception {
        File root = temporaryFolder.newFolder("source");
        File file = write(root, "app/src/main/java/com/example/DBHelper.java",
                "package com.example;\n"
                        + "import android.content.Context;\n"
                        + "import android.database.sqlite.SQLiteDatabase;\n"
                        + "import android.database.sqlite.SQLiteOpenHelper;\n"
                        + "class DBHelper extends SQLiteOpenHelper {\n"
                        + "  static final int DB_VERSION = 2;\n"
                        + "  DBHelper(Context context) { super(context, \"demo.db\", null, DB_VERSION); }\n"
                        + "  public void onCreate(SQLiteDatabase db) { }\n"
                        + "  public int version() { return DB_VERSION; }\n"
                        + "  private void hidden() { }\n"
                        + "}\n");

        assertEquals("class DBHelper extends SQLiteOpenHelper { DBHelper(Context); void onCreate(SQLiteDatabase); int version(); static final int DB_VERSION; }",
                JavaApiDigest.digest(file));
    }

    @Test
    public void digestIncludesNestedStaticContractConstants() {
        String digest = JavaApiDigest.digestSource("app/src/main/java/com/example/DBContract.java",
                "package com.example;\n"
                        + "public final class DBContract {\n"
                        + "  public static final class Account {\n"
                        + "    public static final String TABLE = \"account\";\n"
                        + "    public static final String COL_NAME = \"name\";\n"
                        + "  }\n"
                        + "}\n");

        assertTrue(digest.contains("class DBContract.Account { static final String COL_NAME; static final String TABLE; }"));
    }

    @Test
    public void digestTreeExcludesFullTextFocusFilesAndRespectsBudget() throws Exception {
        File root = temporaryFolder.newFolder("source");
        write(root, "app/src/main/java/com/example/Focus.java",
                "package com.example;\nclass Focus { public void fullText() {} }\n");
        write(root, "app/src/main/java/com/example/Other.java",
                "package com.example;\nclass Other { public void visible() {} public void anotherVisibleMethod() {} }\n");

        String digest = JavaApiDigest.digestTree(
                root,
                Collections.singleton("app/src/main/java/com/example/Focus.java"),
                90);

        assertFalse(digest.contains("Focus"));
        assertTrue(digest.contains("--- app/src/main/java/com/example/Other.java API ---"));
        assertTrue(digest.length() <= 90);
        assertTrue(digest.contains("...[truncated]"));
    }

    @Test
    public void malformedSourceReturnsFilenameInsteadOfThrowing() {
        String digest = JavaApiDigest.digestSource(
                "app/src/main/java/com/example/Broken.java",
                "class Broken { public void nope( ");

        assertTrue(digest.contains("Broken.java"));
    }

    private static File write(File root, String path, String content) throws Exception {
        File file = new File(root, path);
        FileUtils.writeText(file, content);
        return file;
    }
}
