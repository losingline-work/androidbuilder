package com.androidbuilder.agent;

import com.androidbuilder.agent.ThemeCompatibilityPolicy.Requirement;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThemeCompatibilityPolicyTest {
    @Test
    public void satisfiedByDistinguishesMaterialAppCompatAndFramework() {
        assertEquals(Requirement.MATERIAL, ThemeCompatibilityPolicy.satisfiedBy("Theme.Material3.DayNight"));
        assertEquals(Requirement.MATERIAL, ThemeCompatibilityPolicy.satisfiedBy("Theme.MaterialComponents.Light"));
        assertEquals(Requirement.APPCOMPAT, ThemeCompatibilityPolicy.satisfiedBy("Theme.AppCompat.Light.NoActionBar"));
        // The scaffold default is a FRAMEWORK theme -> provides nothing AppCompat needs.
        assertEquals(Requirement.NONE, ThemeCompatibilityPolicy.satisfiedBy("android:style/Theme.Material.Light.NoActionBar"));
        assertEquals(Requirement.NONE, ThemeCompatibilityPolicy.satisfiedBy("@android:style/Theme.Holo"));
        assertEquals(Requirement.NONE, ThemeCompatibilityPolicy.satisfiedBy("AppTheme"));
    }

    @Test
    public void materialSatisfiesAppCompatButNotViceVersa() {
        assertTrue(ThemeCompatibilityPolicy.satisfies(Requirement.MATERIAL, Requirement.APPCOMPAT));
        assertTrue(ThemeCompatibilityPolicy.satisfies(Requirement.MATERIAL, Requirement.MATERIAL));
        assertTrue(ThemeCompatibilityPolicy.satisfies(Requirement.APPCOMPAT, Requirement.APPCOMPAT));
        assertFalse(ThemeCompatibilityPolicy.satisfies(Requirement.APPCOMPAT, Requirement.MATERIAL));
        assertFalse(ThemeCompatibilityPolicy.satisfies(Requirement.NONE, Requirement.APPCOMPAT));
        assertTrue(ThemeCompatibilityPolicy.satisfies(Requirement.NONE, Requirement.NONE));
    }

    @Test
    public void requirementOfReadsSuperclassesAndWidgets() {
        assertEquals(Requirement.APPCOMPAT,
                ThemeCompatibilityPolicy.requirementOf(Arrays.asList("AppCompatActivity"), false));
        assertEquals(Requirement.MATERIAL,
                ThemeCompatibilityPolicy.requirementOf(Arrays.asList("Activity"), true));
        assertEquals(Requirement.NONE,
                ThemeCompatibilityPolicy.requirementOf(Arrays.asList("android.app.Activity"), false));
    }

    @Test
    public void resolveAppliedPassesWhenAnAncestorIsCompatible() {
        // The false-positive guard: a custom theme whose parent IS Theme.AppCompat must resolve compatible.
        Map<String, String> parents = new HashMap<>();
        parents.put("MyBrandTheme", "Theme.AppCompat.Light");
        assertEquals(Requirement.APPCOMPAT,
                ThemeCompatibilityPolicy.resolveApplied("@style/MyBrandTheme", parents));
    }

    @Test
    public void resolveAppliedWalksMultiLevelChainToMaterial() {
        Map<String, String> parents = new HashMap<>();
        parents.put("AppTheme", "AppTheme.Base");
        parents.put("AppTheme.Base", "Theme.Material3.DayNight.NoActionBar");
        assertEquals(Requirement.MATERIAL, ThemeCompatibilityPolicy.resolveApplied("AppTheme", parents));
    }

    @Test
    public void resolveAppliedReturnsNoneForFrameworkRootedChain() {
        Map<String, String> parents = new HashMap<>();
        parents.put("AppTheme", "android:style/Theme.Material.Light.NoActionBar");
        assertEquals(Requirement.NONE, ThemeCompatibilityPolicy.resolveApplied("@style/AppTheme", parents));
    }

    @Test
    public void resolveAppliedTerminatesOnCycle() {
        Map<String, String> parents = new HashMap<>();
        parents.put("A", "B");
        parents.put("B", "A");
        assertEquals(Requirement.NONE, ThemeCompatibilityPolicy.resolveApplied("A", parents));
    }

    @Test
    public void resolveAppliedHonorsImplicitDottedInheritance() {
        Map<String, String> parents = new HashMap<>();
        parents.put("AppTheme", "Theme.AppCompat.Light");
        parents.put("AppTheme.Card", ""); // no explicit parent -> inherits AppTheme
        assertEquals(Requirement.APPCOMPAT, ThemeCompatibilityPolicy.resolveApplied("AppTheme.Card", parents));
    }

    @Test
    public void resolveAppliedReturnsNoneForUnknownLeafWithNoParents() {
        assertEquals(Requirement.NONE,
                ThemeCompatibilityPolicy.resolveApplied("@style/AppTheme", Collections.emptyMap()));
    }
}
