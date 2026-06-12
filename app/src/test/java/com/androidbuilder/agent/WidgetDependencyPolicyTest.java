package com.androidbuilder.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WidgetDependencyPolicyTest {
    @Test
    public void reportsGridLayoutWhenDependencyIsMissing() {
        String xml = "<androidx.gridlayout.widget.GridLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />";

        List<String> missing = WidgetDependencyPolicy.missingWidgetDependencies(xml, "dependencies {}");

        assertEquals(1, missing.size());
        assertEquals("androidx.gridlayout.widget.GridLayout", missing.get(0));
        assertEquals("androidx.gridlayout:gridlayout",
                WidgetDependencyPolicy.requiredCoordinate("androidx.gridlayout.widget.GridLayout"));
    }

    @Test
    public void ignoresGridLayoutWhenDependencyIsDeclaredWithVersion() {
        String xml = "<androidx.gridlayout.widget.GridLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" />";
        String gradle = "dependencies { implementation 'androidx.gridlayout:gridlayout:1.0.0' }";

        assertTrue(WidgetDependencyPolicy.missingWidgetDependencies(xml, gradle).isEmpty());
    }

    @Test
    public void ignoresWidgetsOutsideConservativeMapping() {
        String xml = "<LinearLayout>"
                + "<com.google.android.material.button.MaterialButton />"
                + "<androidx.recyclerview.widget.RecyclerView />"
                + "</LinearLayout>";

        assertTrue(WidgetDependencyPolicy.missingWidgetDependencies(xml, "").isEmpty());
    }

    @Test
    public void recognizesViewClassAttribute() {
        String xml = "<view class=\"androidx.swiperefreshlayout.widget.SwipeRefreshLayout\" />";

        List<String> missing = WidgetDependencyPolicy.missingWidgetDependencies(xml, "");

        assertEquals(1, missing.size());
        assertEquals("androidx.swiperefreshlayout.widget.SwipeRefreshLayout", missing.get(0));
    }
}
