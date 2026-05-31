package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidSourceGuard {
    private static final Pattern XML_ID = Pattern.compile("android:id\\s*=\\s*[\"']@\\+?id/([A-Za-z_][A-Za-z0-9_]*)[\"']");
    private static final String APP_R_PREFIX = "(?<![A-Za-z0-9_.])R\\.";
    private static final Pattern R_ID = Pattern.compile(APP_R_PREFIX + "id\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_LAYOUT = Pattern.compile(APP_R_PREFIX + "layout\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_STRING = Pattern.compile(APP_R_PREFIX + "string\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_COLOR = Pattern.compile(APP_R_PREFIX + "color\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_DRAWABLE = Pattern.compile(APP_R_PREFIX + "drawable\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_MIPMAP = Pattern.compile(APP_R_PREFIX + "mipmap\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern R_STYLE = Pattern.compile(APP_R_PREFIX + "style\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern NAMED_VALUE_RESOURCE = Pattern.compile("<\\s*(string|color|style)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern FRAGMENT_CLASS = Pattern.compile("\\bclass\\s+\\w+[^\\n{]*(?:Fragment\\(|:\\s*Fragment\\b)");
    private static final Pattern NAKED_FIND_VIEW = Pattern.compile("(?<![A-Za-z0-9_.])findViewById\\s*(?:<|\\()");
    private static final Pattern DECLARED_VARIABLE = Pattern.compile("\\b(?:val|var)\\s+%s\\b|\\blateinit\\s+var\\s+%s\\b");

    public void validate(File sourceDir) throws Exception {
        ResourceSymbols symbols = new ResourceSymbols();
        collectXmlIds(sourceDir, symbols.ids);
        File resDir = new File(sourceDir, "app/src/main/res");
        collectNamedXmlFiles(resDir, "layout", symbols.layouts);
        collectNamedXmlFiles(resDir, "drawable", symbols.drawables);
        collectNamedXmlFiles(resDir, "mipmap", symbols.mipmaps);
        collectValueResources(resDir, symbols);
        validateFiles(sourceDir, symbols);
    }

    private void collectXmlIds(File file, Set<String> ids) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(".xml")) {
                Matcher matcher = XML_ID.matcher(FileUtils.readText(file));
                while (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectXmlIds(child, ids);
        }
    }

    private void collectNamedXmlFiles(File resDir, String directoryPrefix, Set<String> names) {
        if (resDir == null || !resDir.exists()) {
            return;
        }
        File[] resourceDirs = resDir.listFiles();
        if (resourceDirs == null) {
            return;
        }
        for (File resourceDir : resourceDirs) {
            if (resourceDir.isDirectory() && resourceDir.getName().startsWith(directoryPrefix)) {
                collectXmlFileNames(resourceDir, names);
            }
        }
    }

    private void collectXmlFileNames(File file, Set<String> names) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            String name = file.getName();
            if (name.endsWith(".xml")) {
                names.add(name.substring(0, name.length() - 4));
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectXmlFileNames(child, names);
        }
    }

    private void collectValueResources(File resDir, ResourceSymbols symbols) throws Exception {
        if (resDir == null || !resDir.exists()) {
            return;
        }
        File[] resourceDirs = resDir.listFiles();
        if (resourceDirs == null) {
            return;
        }
        for (File resourceDir : resourceDirs) {
            if (resourceDir.isDirectory() && resourceDir.getName().startsWith("values")) {
                collectValueResourcesFromDirectory(resourceDir, symbols);
            }
        }
    }

    private void collectValueResourcesFromDirectory(File file, ResourceSymbols symbols) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(".xml")) {
                Matcher matcher = NAMED_VALUE_RESOURCE.matcher(FileUtils.readText(file));
                while (matcher.find()) {
                    addValueResource(symbols, matcher.group(1), matcher.group(2));
                }
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectValueResourcesFromDirectory(child, symbols);
        }
    }

    private void addValueResource(ResourceSymbols symbols, String type, String name) {
        String javaName = name.replace('.', '_');
        if ("string".equals(type)) {
            symbols.strings.add(javaName);
        } else if ("color".equals(type)) {
            symbols.colors.add(javaName);
        } else if ("style".equals(type)) {
            symbols.styles.add(javaName);
        }
    }

    private void validateFiles(File file, ResourceSymbols symbols) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            String name = file.getName();
            if (name.endsWith(".kt")) {
                throw new IllegalArgumentException("Generated source policy blocked Kotlin source file: " + name + ". Use Java source files (.java) only.");
            }
            if (name.endsWith(".java")) {
                validateSourceFile(file, symbols);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            validateFiles(child, symbols);
        }
    }

    private void validateSourceFile(File file, ResourceSymbols symbols) throws Exception {
        String content = FileUtils.readText(file);
        if (content.contains("kotlinx.android.synthetic")) {
            throw new IllegalArgumentException("Generated source policy blocked Kotlin synthetic view imports in " + file.getName() + ". Use findViewById on the inflated root/dialog view.");
        }
        if (content.matches("(?s).*import\\s+.*\\.databinding\\..*Binding.*")) {
            throw new IllegalArgumentException("Generated source policy blocked DataBinding/ViewBinding imports in " + file.getName() + ". Use findViewById with plain XML ids.");
        }
        if (FRAGMENT_CLASS.matcher(content).find() && NAKED_FIND_VIEW.matcher(content).find()) {
            throw new IllegalArgumentException("Generated source policy blocked Fragment findViewById usage in " + file.getName() + ". Use rootView.findViewById or requireView().findViewById.");
        }
        rejectMissingResource(R_ID, symbols.ids, "Generated source policy blocked missing XML id: R.id.%s in %s.", content, file);
        rejectMissingResource(R_LAYOUT, symbols.layouts, "Generated source policy blocked missing layout resource: R.layout.%s in %s.", content, file);
        rejectMissingResource(R_STRING, symbols.strings, "Generated source policy blocked missing string resource: R.string.%s in %s.", content, file);
        rejectMissingResource(R_COLOR, symbols.colors, "Generated source policy blocked missing color resource: R.color.%s in %s.", content, file);
        rejectMissingResource(R_DRAWABLE, symbols.drawables, "Generated source policy blocked missing drawable resource: R.drawable.%s in %s.", content, file);
        rejectMissingResource(R_MIPMAP, symbols.mipmaps, "Generated source policy blocked missing mipmap resource: R.mipmap.%s in %s.", content, file);
        rejectMissingResource(R_STYLE, symbols.styles, "Generated source policy blocked missing style resource: R.style.%s in %s.", content, file);
        for (String id : symbols.ids) {
            if (isLikelySyntheticViewId(id) && usesLikelySyntheticView(content, id) && !declaresVariable(content, id)) {
                throw new IllegalArgumentException("Generated source policy blocked synthetic view access: " + id + " in " + file.getName() + ". Declare it with findViewById from the inflated root/dialog view.");
            }
        }
    }

    private void rejectMissingResource(Pattern pattern, Set<String> knownNames, String message, String content, File file) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!knownNames.contains(name)) {
                throw new IllegalArgumentException(String.format(message, name, file.getName()));
            }
        }
    }

    private boolean isLikelySyntheticViewId(String id) {
        return id.contains("_") ||
                hasUppercase(id) ||
                id.startsWith("btn") ||
                id.startsWith("tv") ||
                id.startsWith("et") ||
                id.startsWith("rv") ||
                id.startsWith("lv") ||
                id.startsWith("text") ||
                id.startsWith("img");
    }

    private boolean hasUppercase(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean usesLikelySyntheticView(String content, String id) {
        return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(id) + "\\s*(?:[.?!\\[])").matcher(content).find();
    }

    private boolean declaresVariable(String content, String id) {
        String quoted = Pattern.quote(id);
        if (Pattern.compile(String.format(DECLARED_VARIABLE.pattern(), quoted, quoted)).matcher(content).find()) {
            return true;
        }
        return Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_<>.?\\[\\]]*\\s+" + quoted + "\\b").matcher(content).find();
    }

    private static class ResourceSymbols {
        final Set<String> ids = new HashSet<>();
        final Set<String> layouts = new HashSet<>();
        final Set<String> strings = new HashSet<>();
        final Set<String> colors = new HashSet<>();
        final Set<String> drawables = new HashSet<>();
        final Set<String> mipmaps = new HashSet<>();
        final Set<String> styles = new HashSet<>();
    }
}
