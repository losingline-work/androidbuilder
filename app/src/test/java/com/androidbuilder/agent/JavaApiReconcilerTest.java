package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JavaApiReconcilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rewritesUniqueSingleCharMethodTypo() throws Exception {
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/CategoryDao.java",
                "package com.example;\nclass CategoryDao { public void findById(long id) {} }");
        File caller = write(root, "app/src/main/java/com/example/CategoryRepository.java",
                "package com.example;\nclass CategoryRepository { CategoryDao dao;\n void run(long id) { dao.findByid(id); } }");

        JavaApiReconciler.reconcile(root);

        String fixed = FileUtils.readText(caller);
        assertTrue(fixed.contains("dao.findById(id)"));
        assertFalse(fixed.contains("findByid"));
    }

    @Test
    public void rewritesBareAccessorToTheDeclaredGetter() throws Exception {
        // The model called category.isSystem() but Category declares getIsSystem() (field isSystem).
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/model/Category.java",
                "package com.example.model;\npublic class Category { private int isSystem;\n"
                        + " public int getIsSystem() { return isSystem; } }");
        File caller = write(root, "app/src/main/java/com/example/db/CategoryDao.java",
                "package com.example.db;\nimport com.example.model.Category;\n"
                        + "class CategoryDao { int f(Category category) { return category.isSystem(); } }");

        JavaApiReconciler.reconcile(root);

        String fixed = FileUtils.readText(caller);
        assertTrue(fixed.contains("category.getIsSystem()"));
        assertFalse(fixed.contains("category.isSystem()"));
    }

    @Test
    public void rewritesBareSetterCallToTheDeclaredSetter() throws Exception {
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/model/Account.java",
                "package com.example.model;\npublic class Account { private String name;\n"
                        + " public void setName(String n) { this.name = n; } }");
        File caller = write(root, "app/src/main/java/com/example/AccountUseCase.java",
                "package com.example;\nimport com.example.model.Account;\n"
                        + "class AccountUseCase { void f(Account account) { account.name(\"x\"); } }");

        JavaApiReconciler.reconcile(root);

        assertTrue(FileUtils.readText(caller).contains("account.setName(\"x\")"));
    }

    @Test
    public void leavesABareAccessorAloneWhenTheNameExistsSomewhere() throws Exception {
        // isSystem() IS declared on some class, so the call could be legitimate — never rewrite it.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/model/Flag.java",
                "package com.example.model;\npublic class Flag { public boolean isSystem() { return true; } }");
        write(root, "app/src/main/java/com/example/model/Category.java",
                "package com.example.model;\npublic class Category { public int getIsSystem() { return 0; } }");
        File caller = write(root, "app/src/main/java/com/example/db/CategoryDao.java",
                "package com.example.db;\nimport com.example.model.Category;\n"
                        + "class CategoryDao { int f(Category category) { return category.isSystem() == 1 ? 1 : 0; } }");

        JavaApiReconciler.reconcile(root);

        // isSystem exists (on Flag), so the conservative reconciler leaves the call for the guard.
        assertTrue(FileUtils.readText(caller).contains("category.isSystem()"));
    }

    @Test
    public void doesNotRewriteDenyListedTruncationSymptom() throws Exception {
        // getWritableDb absent (truncated DbHelper) must be LEFT for the guard, not silently
        // reconciled to the inherited getWritableDatabase.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/DbHelper.java",
                "package com.example;\nclass DbHelper { public void getWritableDatabase() {} }");
        File caller = write(root, "app/src/main/java/com/example/AccountDao.java",
                "package com.example;\nclass AccountDao { DbHelper helper;\n void run() { helper.getWritableDb(); } }");

        JavaApiReconciler.reconcile(root);

        assertTrue(FileUtils.readText(caller).contains("getWritableDb"));
    }

    @Test
    public void doesNotRewriteWhenMethodExistsSomewhere() throws Exception {
        // "save" exists on another class, so it is not a typo; leave it untouched.
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/AccountDao.java",
                "package com.example;\nclass AccountDao { public void store() {} }");
        write(root, "app/src/main/java/com/example/Other.java",
                "package com.example;\nclass Other { public void save() {} }");
        File caller = write(root, "app/src/main/java/com/example/Repo.java",
                "package com.example;\nclass Repo { AccountDao dao;\n void run() { dao.save(); } }");

        JavaApiReconciler.reconcile(root);

        assertTrue(FileUtils.readText(caller).contains("dao.save()"));
    }

    @Test
    public void doesNotRewriteAmbiguousNearMisses() throws Exception {
        File root = temporaryFolder.newFolder("src");
        write(root, "app/src/main/java/com/example/Box.java",
                "package com.example;\nclass Box { public void getA() {} public void getB() {} }");
        File caller = write(root, "app/src/main/java/com/example/User.java",
                "package com.example;\nclass User { Box box;\n void run() { box.getX(); } }");

        JavaApiReconciler.reconcile(root);

        assertTrue(FileUtils.readText(caller).contains("box.getX()"));
    }

    private static File write(File root, String path, String content) throws Exception {
        File file = new File(root, path);
        FileUtils.writeText(file, content);
        return file;
    }
}
