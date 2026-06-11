package com.androidbuilder.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PolicyRewriteInstruction {
    private static final Pattern MISSING_DRAWABLE = Pattern.compile("missing drawable resource:\\s*R\\.drawable\\.([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+([A-Za-z0-9_.$-]+\\.java)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MISSING_XML_RESOURCE = Pattern.compile("missing XML resource reference:\\s*@([a-z]+)/([A-Za-z_][A-Za-z0-9_.]*)\\s+in\\s+([A-Za-z0-9_.$-]+\\.xml)", Pattern.CASE_INSENSITIVE);

    private PolicyRewriteInstruction() {
    }

    static String create(String originalInstruction, String policyMessage, int attempt) {
        String message = policyMessage == null ? "" : policyMessage;
        StringBuilder instruction = new StringBuilder(originalInstruction == null ? "" : originalInstruction);
        instruction.append("\n\nPrevious output was rejected by local validation on attempt ")
                .append(attempt)
                .append(": ")
                .append(message)
                .append("\nRewrite this task with the smallest necessary file changes.");
        instruction.append("\nKeep Java + XML only, obey the active dependency mode, and do not add blocked dependencies, plugins, imports, Kotlin, DataBinding, ViewBinding, or Compose.");
        if (message.contains("Task operation list is empty")) {
            instruction.append("\nDo not return an empty operations array. Return at least one write or delete operation that advances this task.");
            instruction.append("\nIf the current source already contains part of the work, update the smallest relevant file with the remaining required change instead of returning no operations.");
        }
        if (message.contains("Task operation response did not contain a JSON object") || message.contains("Unsupported file operation action")) {
            instruction.append("\nReturn only a compact JSON object with summary and operations. Each operation action must be exactly write or delete.");
        }
        if (message.contains("Unsafe generated file path")) {
            instruction.append("\nUse only relative POSIX paths inside the Android project, such as app/src/main/java/... or app/src/main/res/layout/...");
        }
        if (message.contains("constructor argument mismatch")) {
            instruction.append("\nA class is constructed with arguments that do not match its declared constructor. Make the caller and the class consistent.");
            instruction.append("\nCheck the real constructor in the referenced class first, then either pass arguments whose types match an existing constructor, or change the class constructor to accept the types you actually pass.");
            instruction.append("\nIf a helper/dependency is required (for example a DBHelper), construct that object first and pass it; do not pass an Activity/Context where a different type is expected.");
        }
        if (message.contains("missing model field")) {
            instruction.append("\nThe code reads a field or getter that the referenced class does not declare. Keep the class and its callers consistent.");
            instruction.append("\nEither add the missing field (and its getter if used) to that class, or change the caller to use a field/method that already exists on it. Do not invent fields that are not declared.");
        }
        if (message.contains("missing class field")) {
            instruction.append("\nA class-qualified field/constant is referenced but not declared. For database code, keep DBHelper table/column constants and DAO callers synchronized.");
            instruction.append("\nIf the caller uses a constant such as DBHelper.COL_CATEGORY_ID, declare that exact table/column constant in DBHelper, or update the DAO to use an existing DBHelper constant.");
            instruction.append("\nDo not leave DAO SQL, ContentValues, Cursor reads, or where clauses pointing at undeclared constants.");
        }
        if (message.contains("missing method") || message.contains("method argument mismatch")) {
            instruction.append("\nA custom class method call has no matching declaration. Keep the method signature and every caller consistent.");
            instruction.append("\nFor DAO APIs such as RecordDao.listAll(), RecordDao.update(Record), delete(long), countByCategory(long), or queryByType(int), either add the exact DAO method with matching parameter types or change the Activity/Adapter/helper caller to an existing method.");
            instruction.append("\nIf JsonBackup.java calls RecordDao.listAll(), either implement RecordDao.listAll() with the return type JsonBackup expects, or update JsonBackup.java to use a DAO query method that already exists.");
            instruction.append("\nWhen changing database screens, update DBHelper, model, DAO, Activity, and Adapter together in the same response.");
        }
        if (message.contains("synthetic view access")) {
            instruction.append("\nThis project does not use Kotlin synthetics, DataBinding or ViewBinding, so a view cannot be referenced by its id name directly.");
            instruction.append("\nFor EVERY view you read or call methods on, first declare a local variable (or field) and assign it from findViewById before using it. Reuse the exact R.id name.");
            instruction.append("\nExample: replace radioIncome.isChecked() with RadioButton radioIncome = findViewById(R.id.radioIncome); ... radioIncome.isChecked();");
            instruction.append("\nInside an Activity use findViewById(R.id.xxx); inside a Fragment use rootView.findViewById(R.id.xxx) or requireView().findViewById(R.id.xxx).");
            instruction.append("\nFix every view accessed this way in the named file, not only the one mentioned, so no synthetic access remains.");
        }
        if (message.contains("Fragment findViewById usage")) {
            instruction.append("\nIn Fragments never call a bare findViewById(...). Resolve views from the inflated root view: View rootView = inflater.inflate(R.layout.xxx, container, false); TextView title = rootView.findViewById(R.id.title); and return rootView.");
            instruction.append("\nOutside onCreateView use requireView().findViewById(R.id.xxx).");
        }
        if (message.contains("DataBinding/ViewBinding")) {
            instruction.append("\nRemove all DataBinding/ViewBinding usage and the *Binding imports. Inflate layouts with LayoutInflater/setContentView and resolve views with findViewById(R.id.xxx) instead.");
        }
        if (message.contains("Java lambda syntax") || message.contains("->")) {
            instruction.append("\nRemove every Java lambda and every -> token from all generated Java files. Use anonymous inner classes for listeners and callbacks.");
            instruction.append("\nExamples: use new View.OnClickListener() { public void onClick(View view) { ... } } instead of v -> ...; use new AdapterView.OnItemClickListener() { public void onItemClick(...) { ... } } instead of (parent, view, position, id) -> ... .");
            instruction.append("\nDo not use -> anywhere in the returned file contents.");
        }
        if (message.contains("online Maven dependency") || message.contains("dynamic Maven version")) {
            instruction.append("\nFor online dependency mode, use the verified catalog coordinates when the feature needs a library: " + DependencyCatalog.coordinatesSummary() + ".");
            instruction.append("\nOther coordinates are only allowed from trusted groups (" + OnlineDependencyPolicy.trustedGroupsSummary() + ") with an exact pinned version such as 1.2.3.");
            instruction.append("\nRemove version ranges and + wildcards, and remove Compose, Room, Hilt, Dagger, any *-compiler annotation processor, and any Gradle plugin other than com.android.application. Prefer the Android SDK and plain Java/XML when a dependency is not essential.");
        }
        if (message.contains("missing XML resource reference")) {
            instruction.append("\nFor every XML @mipmap/@style/@drawable/@string/@color/@layout reference, either add a matching resource file or values entry, or change the XML to use an existing resource.");
        }
        if (message.contains("appbar_scrolling_view_behavior")) {
            instruction.append("\nDo not use @string/appbar_scrolling_view_behavior, CoordinatorLayout, AppBarLayout, CollapsingToolbarLayout, MaterialToolbar, or any Material/AndroidX behavior class unless the project explicitly declares and can resolve that dependency.");
            instruction.append("\nFor offline-safe generated apps, replace that layout with Android SDK widgets such as LinearLayout, FrameLayout, ScrollView, Toolbar, ListView, or RecyclerView alternatives already available in the project.");
            instruction.append("\nRemove app:layout_behavior and any appbar scrolling behavior attributes from the XML instead of adding a fake string resource that points to a missing class.");
        }
        if (message.contains("missing ") && message.contains("resource")) {
            instruction.append("\nFor every R.* or XML resource reference, make sure the referenced resource exists after your operations are applied.");
        }
        appendMissingXmlResourceHint(instruction, message);
        appendMissingDrawableHint(instruction, message);
        return instruction.toString();
    }

    private static void appendMissingXmlResourceHint(StringBuilder instruction, String message) {
        Matcher matcher = MISSING_XML_RESOURCE.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return;
        }
        String type = matcher.group(1);
        String name = matcher.group(2);
        String file = matcher.group(3);
        instruction.append("\n")
                .append(file)
                .append(" references @")
                .append(type)
                .append("/")
                .append(name)
                .append(", but that resource does not exist. ")
                .append(xmlResourceFixInstruction(type, name, file));
    }

    private static void appendMissingDrawableHint(StringBuilder instruction, String message) {
        Matcher matcher = MISSING_DRAWABLE.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return;
        }
        String name = matcher.group(1);
        String file = matcher.group(2);
        instruction.append("\n")
                .append(file)
                .append(" references R.drawable.")
                .append(name)
                .append(", but that drawable does not exist. In the next operations, either add app/src/main/res/drawable/")
                .append(name)
                .append(".xml as a valid vector/shape drawable resource, or change ")
                .append(file)
                .append(" to reference an existing drawable. If IconRes.java maps multiple built-in icon names, add every mapped drawable resource in the same response.");
    }

    private static String xmlResourceFixInstruction(String type, String name, String file) {
        if ("color".equals(type)) {
            return "In the next operations, either add app/src/main/res/values/colors.xml with <color name=\"" + name
                    + "\">...</color> (or add that entry to the existing colors.xml), or change " + file
                    + " to reference an existing @color resource.";
        }
        if ("string".equals(type)) {
            return "In the next operations, either add app/src/main/res/values/strings.xml with <string name=\"" + name
                    + "\">...</string> (or add that entry to the existing strings.xml), or change " + file
                    + " to reference an existing @string resource.";
        }
        if ("style".equals(type)) {
            return "In the next operations, either add app/src/main/res/values/styles.xml with <style name=\"" + name
                    + "\">...</style> (or add that entry to the existing styles.xml), or change " + file
                    + " to reference an existing @style resource.";
        }
        if ("layout".equals(type)) {
            return "In the next operations, either add app/src/main/res/layout/" + name + ".xml, or change "
                    + file + " to reference an existing @layout resource.";
        }
        if ("drawable".equals(type)) {
            return "In the next operations, either add app/src/main/res/drawable/" + name
                    + ".xml as a valid vector/shape drawable, or change " + file
                    + " to reference an existing @drawable resource.";
        }
        if ("mipmap".equals(type)) {
            return "In the next operations, either add app/src/main/res/mipmap/" + name + ".xml, or change "
                    + file + " to reference an existing @mipmap resource.";
        }
        return "In the next operations, add the missing @" + type + "/" + name + " resource, or change "
                + file + " to reference an existing resource.";
    }
}
