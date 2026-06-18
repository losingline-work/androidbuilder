package com.androidbuilder.agent;

import com.androidbuilder.model.FileOperation;
import com.androidbuilder.model.HermesTaskContract;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BatchValidationPolicyTest {
    @Test
    public void rejectsEditOperationsInBatchResponse() {
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("edit", "app/src/main/res/layout/activity_main.xml", "", "a", "b")),
                Collections.singletonList("app/src/main/res/layout/activity_main.xml"),
                HermesTaskContract.empty());

        assertTrue(error.contains("must be full write or delete"));
        assertTrue(error.contains("got edit"));
    }

    @Test
    public void rejectsUnplannedNonResourceFile() {
        // A stray Java file that was not planned still sprawls scope and is rejected.
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/java/com/example/MainActivity.java", "class MainActivity {}"),
                        new FileOperation("write", "app/src/main/java/com/example/Stray.java", "class Stray {}")),
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"),
                HermesTaskContract.empty());

        assertTrue(error.contains("unplanned file app/src/main/java/com/example/Stray.java"));
    }

    @Test
    public void allowsUnplannedValuesSelfHeal() {
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/java/com/example/BillListFragment.java",
                                "package com.example;\nclass BillListFragment { int t() { return R.string.bill_summary_title; } }"),
                        new FileOperation("write", "app/src/main/res/values/strings.xml",
                                "<resources><string name=\"bill_summary_title\">Summary</string></resources>")),
                Collections.singletonList("app/src/main/java/com/example/BillListFragment.java"),
                HermesTaskContract.empty());

        assertNull(error);
    }

    @Test
    public void allowsUnplannedDrawableSelfHeal() {
        // The whack-a-mole case: a drawable self-heal (project-7's bg_welcome_dot) is now accepted,
        // not rejected as "unplanned" the way res/values used to be the only allowed type.
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/java/com/example/SplashActivity.java",
                                "package com.example;\nclass SplashActivity { int t() { return R.drawable.bg_welcome_dot; } }"),
                        new FileOperation("write", "app/src/main/res/drawable/bg_welcome_dot.xml",
                                "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"><solid android:color=\"#FF0000\"/></shape>")),
                Collections.singletonList("app/src/main/java/com/example/SplashActivity.java"),
                HermesTaskContract.empty());

        assertNull(error);
    }

    @Test
    public void allowsUnplannedMenuSelfHeal() {
        String error = BatchValidationPolicy.review(
                Arrays.asList(
                        new FileOperation("write", "app/src/main/java/com/example/MainActivity.java",
                                "package com.example;\nclass MainActivity { int t() { return R.menu.bottom_nav; } }"),
                        new FileOperation("write", "app/src/main/res/menu/bottom_nav.xml",
                                "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\"/>")),
                Collections.singletonList("app/src/main/java/com/example/MainActivity.java"),
                HermesTaskContract.empty());

        assertNull(error);
    }

    @Test
    public void doesNotRejectSiblingOwnedResourceReference() {
        // Core invariant: the batch path no longer checks resource existence. A Java file that
        // references a sibling-owned resource (no declaring file in this batch) is NOT rejected;
        // existence is validated only at merge time by AndroidSourceGuard on the full tree.
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/java/com/example/SplashActivity.java",
                        "package com.example;\nclass SplashActivity { int t() { return R.drawable.bg_welcome_dot; } }")),
                Collections.singletonList("app/src/main/java/com/example/SplashActivity.java"),
                HermesTaskContract.empty());

        assertNull(error);
    }

    @Test
    public void acceptsPartialBatchSoCarryForwardCanRegenerateTheRest() {
        // A batch that produced only one of two planned files is NOT rejected: the accepted partial is
        // kept and the still-missing file is regenerated by the caller's carry-forward rounds, instead of
        // all-or-nothing rejecting the whole batch to exhaustion.
        String error = BatchValidationPolicy.review(
                Collections.singletonList(new FileOperation("write", "app/src/main/res/layout/activity_main.xml", "<LinearLayout />")),
                Arrays.asList("app/src/main/res/layout/activity_main.xml", "app/src/main/res/values/strings.xml"),
                HermesTaskContract.empty());

        assertNull(error);
    }
}
