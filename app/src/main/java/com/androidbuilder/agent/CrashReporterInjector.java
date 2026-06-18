package com.androidbuilder.agent;

import com.androidbuilder.crash.CrashReportProvider;
import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects an auto crash-reporter into the generated app so a launch crash (闪退) is captured WITHOUT a
 * computer: the reporter is a {@link android.content.ContentProvider} whose {@code onCreate} (which runs
 * before any Activity) installs a {@code Thread.setDefaultUncaughtExceptionHandler} that posts the stack
 * trace to the control app's {@link CrashReportProvider} sink, then chains to the previous handler so the
 * app still crashes normally. A ContentProvider init is conflict-free (it never touches the app's
 * Application/android:name). Runs at the generation merge; additive + idempotent + self-validating.
 */
final class CrashReporterInjector {
    private static final Pattern NAMESPACE =
            Pattern.compile("namespace\\s*(?:=\\s*)?[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final String SINK_URI = "content://" + CrashReportProvider.AUTHORITY + "/crash";

    private CrashReporterInjector() {
    }

    static List<String> reconcile(File sourceDir) {
        List<String> changes = new ArrayList<>();
        try {
            File manifestFile = new File(sourceDir, "app/src/main/AndroidManifest.xml");
            String manifest = readText(manifestFile);
            if (manifest.isEmpty() || !manifest.contains("</application>") || !manifest.contains("</manifest>")) {
                return changes;
            }
            if (manifest.contains("AbCrashReporter") || manifest.contains(CrashReportProvider.AUTHORITY)) {
                return changes; // already injected
            }
            String namespace = appNamespace(sourceDir);
            if (namespace == null || namespace.isEmpty()) {
                return changes;
            }
            File reporter = new File(sourceDir, "app/src/main/java/" + namespace.replace('.', '/') + "/AbCrashReporter.java");
            String updated = manifest;
            // (1) the app's own reporter provider (unique authority, not exported — only its own app inits it)
            String providerXml = "        <provider android:name=\".AbCrashReporter\" android:authorities=\""
                    + namespace + ".abcrashreporter\" android:exported=\"false\" />\n";
            updated = insertBefore(updated, "</application>", providerXml);
            // (2) package-visibility for the control app's sink (Android 11+ blocks the insert otherwise)
            String queriesXml = "    <queries>\n        <provider android:authorities=\""
                    + CrashReportProvider.AUTHORITY + "\" />\n    </queries>\n";
            updated = insertBefore(updated, "</manifest>", queriesXml);

            if (updated.equals(manifest) || TaskOperationsPreflight.xmlError(updated) != null) {
                return changes;
            }
            FileUtils.writeText(reporter, reporterSource(namespace));
            FileUtils.writeText(manifestFile, updated);
            changes.add("injected crash reporter (" + namespace + ".AbCrashReporter)");
        } catch (Exception ignored) {
            // best-effort; never block the merge on instrumentation
        }
        return changes;
    }

    /** The injected ContentProvider source (no lambdas/Kotlin so it passes the generated-source guard). */
    static String reporterSource(String packageName) {
        return "package " + packageName + ";\n\n"
                + "import android.content.ContentProvider;\n"
                + "import android.content.ContentValues;\n"
                + "import android.content.Context;\n"
                + "import android.database.Cursor;\n"
                + "import android.net.Uri;\n\n"
                + "// Auto-injected by androidbuilder: reports uncaught exceptions to the control app's crash sink.\n"
                + "public class AbCrashReporter extends ContentProvider {\n"
                + "    @Override\n"
                + "    public boolean onCreate() {\n"
                + "        final Context ctx = getContext();\n"
                + "        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();\n"
                + "        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {\n"
                + "            @Override\n"
                + "            public void uncaughtException(Thread thread, Throwable error) {\n"
                + "                try {\n"
                + "                    java.io.StringWriter writer = new java.io.StringWriter();\n"
                + "                    error.printStackTrace(new java.io.PrintWriter(writer));\n"
                + "                    ContentValues values = new ContentValues();\n"
                + "                    values.put(\"package\", ctx.getPackageName());\n"
                + "                    values.put(\"stack\", writer.toString());\n"
                + "                    ctx.getContentResolver().insert(Uri.parse(\"" + SINK_URI + "\"), values);\n"
                + "                } catch (Throwable ignored) {\n"
                + "                }\n"
                + "                if (prev != null) {\n"
                + "                    prev.uncaughtException(thread, error);\n"
                + "                }\n"
                + "            }\n"
                + "        });\n"
                + "        return true;\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {\n"
                + "        return null;\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public String getType(Uri uri) {\n"
                + "        return null;\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public Uri insert(Uri uri, ContentValues values) {\n"
                + "        return null;\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public int delete(Uri uri, String selection, String[] selectionArgs) {\n"
                + "        return 0;\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {\n"
                + "        return 0;\n"
                + "    }\n"
                + "}\n";
    }

    private static String insertBefore(String content, String anchor, String snippet) {
        int index = content.lastIndexOf(anchor);
        return index < 0 ? content : content.substring(0, index) + snippet + content.substring(index);
    }

    private static String appNamespace(File sourceDir) {
        Matcher matcher = NAMESPACE.matcher(readText(new File(sourceDir, "app/build.gradle")));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String readText(File file) {
        try {
            return file != null && file.isFile() ? FileUtils.readText(file) : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
