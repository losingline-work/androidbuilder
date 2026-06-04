package com.androidbuilder.agent;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PolicyRewriteInstructionTest {
    @Test
    public void lambdaPolicyErrorProducesExplicitRewriteHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked Java lambda syntax in TimelineFragment.java. Use anonymous listener classes instead of ->.",
                2);

        assertTrue(instruction.contains("Generated source policy blocked Java lambda syntax"));
        assertTrue(instruction.contains("Remove every Java lambda"));
        assertTrue(instruction.contains("anonymous inner classes"));
        assertTrue(instruction.contains("Do not use ->"));
        assertTrue(instruction.contains("attempt 2"));
    }

    @Test
    public void syntheticViewAccessProducesFindViewByIdHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked synthetic view access: radioIncome in AddTransactionActivity.java. Declare it with findViewById from the inflated root/dialog view.",
                2);

        assertTrue(instruction.contains("synthetic view access"));
        assertTrue(instruction.contains("findViewById(R.id.radioIncome)"));
        assertTrue(instruction.contains("RadioButton radioIncome = findViewById"));
        assertTrue(instruction.contains("not only the one mentioned"));
    }

    @Test
    public void constructorMismatchProducesConsistencyHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked constructor argument mismatch: new CategoryDao(Context) in CategoryManageActivity.java, but available constructors are CategoryDao(DBHelper). Update the constructor or caller consistently.",
                2);

        assertTrue(instruction.contains("constructor argument mismatch"));
        assertTrue(instruction.contains("match an existing constructor"));
        assertTrue(instruction.contains("DBHelper"));
    }

    @Test
    public void missingModelFieldProducesConsistencyHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked missing model field: CategorySum.categoryName in StatisticsAdapter.java. Add the field/getter or update the caller to use an existing API.",
                1);

        assertTrue(instruction.contains("missing model field"));
        assertTrue(instruction.contains("add the missing field"));
    }

    @Test
    public void missingClassFieldProducesDatabaseConstantHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Add category DAO",
                "Generated source policy blocked missing class field: DBHelper.COL_CATEGORY_ID in CategoryDao.java. Add the constant/field to DBHelper or update the caller to use an existing API.",
                2);

        assertTrue(instruction.contains("DBHelper.COL_CATEGORY_ID"));
        assertTrue(instruction.contains("table/column constants"));
        assertTrue(instruction.contains("DAO"));
    }

    @Test
    public void missingMethodProducesDaoApiHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Update record editing",
                "Generated source policy blocked missing method: RecordDao.update(Record) in AddRecordActivity.java. Add the method or update the caller to use an existing API.",
                2);

        assertTrue(instruction.contains("RecordDao.update(Record)"));
        assertTrue(instruction.contains("method signature"));
        assertTrue(instruction.contains("caller"));
    }

    @Test
    public void fragmentFindViewByIdProducesRootViewHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked Fragment findViewById usage in TimelineFragment.java. Use rootView.findViewById or requireView().findViewById.",
                1);

        assertTrue(instruction.contains("rootView.findViewById"));
        assertTrue(instruction.contains("requireView().findViewById"));
    }

    @Test
    public void missingResourcePolicyErrorProducesResourceHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Repair build failure",
                "Generated source policy blocked missing XML resource reference: @mipmap/ic_launcher in AndroidManifest.xml.",
                1);

        assertTrue(instruction.contains("@mipmap/@style/@drawable/@string/@color/@layout"));
        assertTrue(instruction.contains("matching resource"));
    }

    @Test
    public void appbarScrollingBehaviorResourceProducesPlainLayoutHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Add category management screen",
                "Generated source policy blocked missing XML resource reference: @string/appbar_scrolling_view_behavior in activity_category_manage.xml.",
                2);

        assertTrue(instruction.contains("appbar_scrolling_view_behavior"));
        assertTrue(instruction.contains("CoordinatorLayout"));
        assertTrue(instruction.contains("LinearLayout"));
        assertTrue(instruction.contains("Android SDK"));
    }

    @Test
    public void emptyOperationsProduceExplicitRewriteHint() {
        String instruction = PolicyRewriteInstruction.create(
                "Add the main screen",
                "Task operation list is empty.",
                2);

        assertTrue(instruction.contains("Task operation list is empty"));
        assertTrue(instruction.contains("operations array"));
        assertTrue(instruction.contains("at least one write or delete"));
    }
}
