package com.androidbuilder.agent;

import com.androidbuilder.util.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidSourceGuard {
    private static final int MAX_REPORTED_VIOLATIONS = 10;
    static final Pattern XML_ID = Pattern.compile("android:id\\s*=\\s*[\"']@\\+?id/([A-Za-z_][A-Za-z0-9_]*)[\"']");
    // A values file may declare ids via <item type="id" name="X"/> (attributes in either order);
    // these are valid R.id sources but were invisible to the id collector and overlay.
    private static final Pattern VALUE_ITEM_TAG = Pattern.compile("<\\s*item\\b([^>]*?)/?>");
    private static final Pattern ITEM_TYPE_ID = Pattern.compile("\\btype\\s*=\\s*[\"']id[\"']");
    private static final Pattern ITEM_NAME = Pattern.compile("\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final String APP_R_PREFIX = "(?<![A-Za-z0-9_.])R\\.";
    static final Pattern R_ID = Pattern.compile(APP_R_PREFIX + "id\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_LAYOUT = Pattern.compile(APP_R_PREFIX + "layout\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_STRING = Pattern.compile(APP_R_PREFIX + "string\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_COLOR = Pattern.compile(APP_R_PREFIX + "color\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_DRAWABLE = Pattern.compile(APP_R_PREFIX + "drawable\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_MIPMAP = Pattern.compile(APP_R_PREFIX + "mipmap\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern R_STYLE = Pattern.compile(APP_R_PREFIX + "style\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    static final Pattern NAMED_VALUE_RESOURCE = Pattern.compile("<\\s*(string|color|style)\\b[^>]*\\bname\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.]*)[\"']");
    private static final Pattern XML_RESOURCE_REFERENCE = Pattern.compile("@(layout|string|color|drawable|mipmap|style)/([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern FRAGMENT_CLASS = Pattern.compile("\\bclass\\s+\\w+[^\\n{]*(?:Fragment\\(|:\\s*Fragment\\b)");
    private static final Pattern NAKED_FIND_VIEW = Pattern.compile("(?<![A-Za-z0-9_.])findViewById\\s*(?:<|\\()");
    private static final Pattern DECLARED_VARIABLE = Pattern.compile("\\b(?:val|var)\\s+%s\\b|\\blateinit\\s+var\\s+%s\\b");
    private static final Pattern JAVA_CLASS = Pattern.compile("\\bclass\\s+([A-Z][A-Za-z0-9_]*)\\b(?:\\s+extends\\s+([A-Za-z_][A-Za-z0-9_$.]*))?");
    private static final Pattern JAVA_FIELD_DECLARATION = Pattern.compile("\\b(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?([A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?=[=;,])");
    private static final Pattern JAVA_VARIABLE_DECLARATION = Pattern.compile("\\b(?:final\\s+)?((?:[A-Z][A-Za-z0-9_$.]*(?:\\s*<[^;(){}]+>)?|long|int|short|byte|char|boolean|float|double)(?:\\[\\])?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern JAVA_FIELD_ACCESS = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern JAVA_METHOD_DECLARATION = Pattern.compile("\\b(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?(?:[A-Za-z_][A-Za-z0-9_$.<>?\\[\\]]*\\s+)+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_.,\\s]+)?[\\{;]");
    private static final Pattern JAVA_METHOD_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^()]*)\\)");
    private static final Pattern JAVA_NEW_EXPRESSION = Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(([^()]*)\\)");

    public void validate(File sourceDir) throws Exception {
        ResourceSymbols symbols = new ResourceSymbols();
        collectXmlIds(sourceDir, symbols.ids);
        File resDir = new File(sourceDir, "app/src/main/res");
        collectNamedXmlFiles(resDir, "layout", symbols.layouts);
        collectResourceFileNames(resDir, "drawable", symbols.drawables);
        collectResourceFileNames(resDir, "mipmap", symbols.mipmaps);
        collectValueResources(resDir, symbols);
        SymbolTable javaSymbols = new SymbolTable();
        collectSymbolTable(sourceDir, javaSymbols);
        String gradleText = readTextIfExists(new File(sourceDir, "app/build.gradle"));
        List<String> violations = new ArrayList<>();
        validateFiles(sourceDir, symbols, javaSymbols, gradleText, violations);
        throwIfViolations(violations);
    }

    /** Test seam: the SymbolTable the guard builds from the on-disk .java tree. */
    SymbolTable collectSymbolTableForTest(File sourceDir) throws Exception {
        SymbolTable table = new SymbolTable();
        collectSymbolTable(sourceDir, table);
        return table;
    }

    private String readTextIfExists(File file) {
        try {
            if (file != null && file.exists()) {
                return FileUtils.readText(file);
            }
        } catch (Exception ignored) {
            // Missing build context is handled as missing dependency declarations.
        }
        return "";
    }

    private void collectXmlIds(File file, Set<String> ids) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(".xml")) {
                String content = FileUtils.readText(file);
                Matcher matcher = XML_ID.matcher(content);
                while (matcher.find()) {
                    ids.add(matcher.group(1));
                }
                collectValueItemIds(content, ids);
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

    static void collectValueItemIds(String content, Set<String> ids) {
        Matcher tag = VALUE_ITEM_TAG.matcher(content == null ? "" : content);
        while (tag.find()) {
            String attrs = tag.group(1);
            if (!ITEM_TYPE_ID.matcher(attrs).find()) {
                continue;
            }
            Matcher name = ITEM_NAME.matcher(attrs);
            if (name.find()) {
                ids.add(name.group(1));
            }
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

    private void collectResourceFileNames(File resDir, String directoryPrefix, Set<String> names) {
        if (resDir == null || !resDir.exists()) {
            return;
        }
        File[] resourceDirs = resDir.listFiles();
        if (resourceDirs == null) {
            return;
        }
        for (File resourceDir : resourceDirs) {
            if (resourceDir.isDirectory() && resourceDir.getName().startsWith(directoryPrefix)) {
                collectFileResourceNames(resourceDir, names);
            }
        }
    }

    private void collectFileResourceNames(File file, Set<String> names) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                names.add(name.substring(0, dot));
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFileResourceNames(child, names);
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
            symbols.strings.add(name);
            symbols.strings.add(javaName);
        } else if ("color".equals(type)) {
            symbols.colors.add(name);
            symbols.colors.add(javaName);
        } else if ("style".equals(type)) {
            symbols.styles.add(name);
            symbols.styles.add(javaName);
        }
    }

    private void validateFiles(File file, ResourceSymbols symbols, SymbolTable javaSymbols, String gradleText, List<String> violations) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            String name = file.getName();
            if (name.endsWith(".kt")) {
                addViolation(violations, "Generated source policy blocked Kotlin source file: " + name + ". Use Java source files (.java) only.");
            }
            if (name.endsWith(".java")) {
                validateSourceFile(file, symbols, javaSymbols, violations);
            } else if (name.endsWith(".xml")) {
                validateXmlFile(file, symbols, gradleText, violations);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            validateFiles(child, symbols, javaSymbols, gradleText, violations);
        }
    }

    private void validateSourceFile(File file, ResourceSymbols symbols, SymbolTable javaSymbols, List<String> violations) throws Exception {
        String content = FileUtils.readText(file);
        String scannable = JavaApiDigest.stripJavaCommentsAndStrings(content);
        if (scannable.contains("kotlinx.android.synthetic")) {
            addViolation(violations, "Generated source policy blocked Kotlin synthetic view imports in " + file.getName() + ". Use findViewById on the inflated root/dialog view.");
        }
        if (scannable.matches("(?s).*import\\s+.*\\.databinding\\..*Binding.*")) {
            addViolation(violations, "Generated source policy blocked DataBinding/ViewBinding imports in " + file.getName() + ". Use findViewById with plain XML ids.");
        }
        if (FRAGMENT_CLASS.matcher(scannable).find() && NAKED_FIND_VIEW.matcher(scannable).find()) {
            addViolation(violations, "Generated source policy blocked Fragment findViewById usage in " + file.getName() + ". Use rootView.findViewById or requireView().findViewById.");
        }
        rejectMissingResource(R_ID, symbols.ids, "Generated source policy blocked missing XML id: R.id.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_LAYOUT, symbols.layouts, "Generated source policy blocked missing layout resource: R.layout.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_STRING, symbols.strings, "Generated source policy blocked missing string resource: R.string.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_COLOR, symbols.colors, "Generated source policy blocked missing color resource: R.color.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_DRAWABLE, symbols.drawables, "Generated source policy blocked missing drawable resource: R.drawable.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_MIPMAP, symbols.mipmaps, "Generated source policy blocked missing mipmap resource: R.mipmap.%s in %s.", scannable, file, violations);
        rejectMissingResource(R_STYLE, symbols.styles, "Generated source policy blocked missing style resource: R.style.%s in %s.", scannable, file, violations);
        boolean syntheticViewViolation = false;
        for (String id : symbols.ids) {
            if (isLikelySyntheticViewId(id) && usesLikelySyntheticView(scannable, id) && !declaresVariable(scannable, id)) {
                addViolation(violations, "Generated source policy blocked synthetic view access: " + id + " in " + file.getName() + ". Declare it with findViewById from the inflated root/dialog view.");
                syntheticViewViolation = true;
            }
        }
        if (!syntheticViewViolation && scannable.contains("->")) {
            addViolation(violations, "Generated source policy blocked Java lambda syntax in " + file.getName() + ". Use anonymous listener classes instead of ->.");
        }
        String sanitized = stripJavaCommentsAndStrings(content);
        validateClassFieldAccess(file, sanitized, javaSymbols, violations);
        validateCustomFieldAccess(file, sanitized, javaSymbols, violations);
        validateConstructorCalls(file, sanitized, javaSymbols, violations);
        validateCustomMethodCalls(file, sanitized, javaSymbols, violations);
    }

    private void collectSymbolTable(File file, SymbolTable symbols) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            if (file.getName().endsWith(".java")) {
                collectSymbolTableFromFile(file, symbols);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectSymbolTable(child, symbols);
        }
    }

    /**
     * Production accessor: the SymbolTable for the on-disk tree plus in-flight draft .java contents
     * (path/content pairs not yet written to disk). Used by the convergence repair to cite a callee's
     * REAL declared methods. Best-effort: a parse failure on one source is skipped, never thrown.
     */
    SymbolTable symbolTableOf(File sourceDir, Iterable<String> extraJavaContents) {
        SymbolTable table = new SymbolTable();
        try {
            collectSymbolTable(sourceDir, table);
        } catch (Exception ignored) {
            // best-effort
        }
        if (extraJavaContents != null) {
            for (String content : extraJavaContents) {
                if (content == null || content.trim().isEmpty()) {
                    continue;
                }
                try {
                    collectSymbolTableFromContent(stripJavaCommentsAndStrings(content), table);
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
        return table;
    }

    private void collectSymbolTableFromFile(File file, SymbolTable symbols) throws Exception {
        collectSymbolTableFromContent(stripJavaCommentsAndStrings(FileUtils.readText(file)), symbols);
    }

    private void collectSymbolTableFromContent(String content, SymbolTable symbols) {
        List<ClassSpan> classSpans = new ArrayList<>();
        Matcher classMatcher = JAVA_CLASS.matcher(content);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            symbols.ensureClass(className);
            String superClass = simpleType(classMatcher.group(2));
            if (!superClass.isEmpty()) {
                symbols.superClassByClass.put(className, superClass);
            }
            int bodyStart = content.indexOf('{', classMatcher.end());
            int bodyEnd = bodyStart < 0 ? content.length() : matchingBrace(content, bodyStart);
            classSpans.add(new ClassSpan(className, bodyStart, bodyEnd));
        }
        if (classSpans.isEmpty()) {
            return;
        }
        Matcher fieldMatcher = JAVA_FIELD_DECLARATION.matcher(content);
        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group(2);
            if (isJavaKeyword(fieldName)) {
                continue;
            }
            String owner = enclosingClass(classSpans, fieldMatcher.start());
            if (owner != null) {
                symbols.fieldsByClass.get(owner).add(fieldName);
            }
        }
        for (ClassSpan span : classSpans) {
            Pattern constructorPattern = Pattern.compile("\\b(?:public|protected|private)?\\s*" + Pattern.quote(span.className) + "\\s*\\(([^)]*)\\)");
            Matcher constructorMatcher = constructorPattern.matcher(content);
            while (constructorMatcher.find()) {
                if (isNewExpressionReference(content, constructorMatcher.start())) {
                    continue;
                }
                symbols.constructorsByClass.get(span.className).add(parseParameterTypes(constructorMatcher.group(1)));
            }
        }
        Matcher methodMatcher = JAVA_METHOD_DECLARATION.matcher(content);
        while (methodMatcher.find()) {
            String owner = enclosingClass(classSpans, methodMatcher.start());
            if (owner == null) {
                continue;
            }
            String methodName = methodMatcher.group(1);
            if (owner.equals(methodName) || isJavaKeyword(methodName)) {
                continue;
            }
            // The method-declaration regex also matches `new Type(`, `throw new Type(`, `return new
            // Type(` - capturing the CONSTRUCTED type name as if it were a declared method (the
            // constructor loop guards this; the method loop forgot to). Skip new-expressions so a
            // class's method set is not polluted with type names like ContentValues / Record.
            if (isNewExpressionReference(content, methodMatcher.start(1))) {
                continue;
            }
            symbols.addMethod(owner, methodName, parseParameterTypes(methodMatcher.group(2)));
        }
    }

    private int matchingBrace(String content, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return content.length();
    }

    private String enclosingClass(List<ClassSpan> classSpans, int position) {
        String owner = null;
        int bestSpan = Integer.MAX_VALUE;
        for (ClassSpan span : classSpans) {
            if (span.bodyStart < 0 || position <= span.bodyStart || position >= span.bodyEnd) {
                continue;
            }
            int width = span.bodyEnd - span.bodyStart;
            if (width < bestSpan) {
                bestSpan = width;
                owner = span.className;
            }
        }
        return owner;
    }

    private boolean isNewExpressionReference(String content, int start) {
        int cursor = start - 1;
        while (cursor >= 0 && Character.isWhitespace(content.charAt(cursor))) {
            cursor--;
        }
        int end = cursor + 1;
        while (cursor >= 0 && Character.isJavaIdentifierPart(content.charAt(cursor))) {
            cursor--;
        }
        return "new".equals(content.substring(cursor + 1, end));
    }

    private void validateCustomFieldAccess(File file, String content, SymbolTable javaSymbols, List<String> violations) {
        Map<String, String> variableTypes = collectVariableTypes(content);
        List<ClassSpan> spans = classSpans(content);
        Matcher matcher = JAVA_FIELD_ACCESS.matcher(content);
        while (matcher.find()) {
            if (isQualifiedNameSegment(content, matcher.start()) || isMethodCall(content, matcher.end())) {
                continue;
            }
            String variableName = matcher.group(1);
            String fieldName = matcher.group(2);
            if (isLikelyNestedTypeReference(fieldName) || javaSymbols.hasClass(fieldName)) {
                continue;
            }
            // "this" must resolve to the smallest enclosing class: inner-class constructors
            // assigning this.x were misattributed to the outer class and rejected.
            String type = "this".equals(variableName)
                    ? enclosingClass(spans, matcher.start())
                    : variableTypes.get(variableName);
            if (type == null || !javaSymbols.hasClass(type) || !isLikelyModelType(type)) {
                continue;
            }
            if (inheritsExternalApi(type, javaSymbols)) {
                continue;
            }
            if (!javaSymbols.hasField(type, fieldName)) {
                addViolation(violations, "Generated source policy blocked missing model field: " + type + "." + fieldName + " in " + file.getName() + ". Add the field/getter or update the caller to use an existing API.");
            }
        }
    }

    private void validateClassFieldAccess(File file, String content, SymbolTable javaSymbols, List<String> violations) {
        Matcher matcher = JAVA_FIELD_ACCESS.matcher(content);
        while (matcher.find()) {
            if (isQualifiedNameSegment(content, matcher.start()) || isMethodCall(content, matcher.end())) {
                continue;
            }
            String className = matcher.group(1);
            String fieldName = matcher.group(2);
            if ("class".equals(fieldName)) {
                continue;
            }
            // A reference to a nested TYPE (Outer.Inner, e.g. BillRecordAdapter.VH) is not a field.
            if (isLikelyNestedTypeReference(fieldName) || javaSymbols.hasClass(fieldName)) {
                continue;
            }
            if (!javaSymbols.hasClass(className) || !isLikelyGeneratedApiClass(className)) {
                continue;
            }
            if (inheritsExternalApi(className, javaSymbols)) {
                continue;
            }
            if (!javaSymbols.hasField(className, fieldName)) {
                addViolation(violations, "Generated source policy blocked missing class field: " + className + "." + fieldName + " in " + file.getName() + ". Add the constant/field to " + className + " or update the caller to use an existing API.");
            }
        }
    }

    /**
     * A match whose previous character is '.' is the middle of a qualified name
     * (package/import lines, fully qualified types): the "app.ui" inside
     * "package com.generated.app.ui.common;" must not collide with a field named "app".
     */
    private boolean isQualifiedNameSegment(String content, int start) {
        return start > 0 && content.charAt(start - 1) == '.';
    }

    /**
     * PascalCase member access is a nested-type reference by Java convention
     * (e.g. BillAdapter.Row, RecyclerView.ViewHolder); real fields are camelCase
     * and constants are ALL_CAPS, both of which stay checked.
     */
    private boolean isLikelyNestedTypeReference(String fieldName) {
        if (fieldName.isEmpty() || !Character.isUpperCase(fieldName.charAt(0))) {
            return false;
        }
        for (int i = 0; i < fieldName.length(); i++) {
            if (Character.isLowerCase(fieldName.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private List<ClassSpan> classSpans(String content) {
        List<ClassSpan> spans = new ArrayList<>();
        Matcher classMatcher = JAVA_CLASS.matcher(content);
        while (classMatcher.find()) {
            int bodyStart = content.indexOf('{', classMatcher.end());
            int bodyEnd = bodyStart < 0 ? content.length() : matchingBrace(content, bodyStart);
            spans.add(new ClassSpan(classMatcher.group(1), bodyStart, bodyEnd));
        }
        return spans;
    }

    private boolean isMethodCall(String content, int end) {
        int cursor = end;
        while (cursor < content.length() && Character.isWhitespace(content.charAt(cursor))) {
            cursor++;
        }
        return cursor < content.length() && content.charAt(cursor) == '(';
    }

    private void validateConstructorCalls(File file, String content, SymbolTable javaSymbols, List<String> violations) {
        Map<String, String> variableTypes = collectVariableTypes(content);
        String currentClass = firstClassName(content);
        Matcher matcher = JAVA_NEW_EXPRESSION.matcher(content);
        while (matcher.find()) {
            String className = matcher.group(1);
            if (!javaSymbols.hasClass(className)) {
                continue;
            }
            List<String> argumentTypes = inferArgumentTypes(splitArguments(matcher.group(2)), variableTypes, currentClass);
            List<List<String>> constructors = javaSymbols.availableConstructors(className);
            boolean hasMatchingArity = hasMatchingArity(constructors, argumentTypes.size());
            if (!hasMatchingArity || (allKnown(argumentTypes) && !matchesAnyConstructor(argumentTypes, constructors, javaSymbols))) {
                addViolation(violations, "Generated source policy blocked constructor argument mismatch: new " + className + "(" + joinTypes(argumentTypes) + ") in " + file.getName() + ", but available constructors are " + describeConstructors(className, constructors) + ". Update the constructor or caller consistently.");
            }
        }
    }

    private void validateCustomMethodCalls(File file, String content, SymbolTable javaSymbols, List<String> violations) {
        Map<String, String> variableTypes = collectVariableTypes(content);
        String currentClass = firstClassName(content);
        if (!currentClass.isEmpty()) {
            variableTypes.put("this", currentClass);
        }
        Matcher matcher = JAVA_METHOD_CALL.matcher(content);
        while (matcher.find()) {
            if (isNewExpressionReference(content, matcher.start())) {
                continue;
            }
            String receiver = matcher.group(1);
            String methodName = matcher.group(2);
            String type = variableTypes.get(receiver);
            if (type == null && javaSymbols.hasClass(receiver)) {
                type = receiver;
            }
            if (type == null || !javaSymbols.hasClass(type) || !isLikelyGeneratedApiClass(type)) {
                continue;
            }
            List<String> argumentTypes = inferArgumentTypes(splitArguments(matcher.group(3)), variableTypes, currentClass);
            if (isKnownInheritedPlatformMethod(type, methodName, javaSymbols)) {
                continue;
            }
            // A type that extends an external/platform class inherits methods the guard cannot see
            // (getAdapterPosition, submitList, ...); defer its method checks to the compiler.
            if (inheritsExternalApi(type, javaSymbols)) {
                continue;
            }
            if (!javaSymbols.hasMethod(type, methodName)) {
                addViolation(violations, "Generated source policy blocked missing method: " + type + "." + methodName + "(" + joinTypes(argumentTypes) + ") in " + file.getName() + ". Add the method or update the caller to use an existing API.");
                continue;
            }
            if (allKnown(argumentTypes) && !javaSymbols.hasMethodSignature(type, methodName, argumentTypes)) {
                addViolation(violations, "Generated source policy blocked method argument mismatch: " + type + "." + methodName + "(" + joinTypes(argumentTypes) + ") in " + file.getName() + ". Update the method signature or caller consistently.");
            }
        }
    }

    private boolean isKnownInheritedPlatformMethod(String type, String methodName, SymbolTable javaSymbols) {
        if (isJavaObjectMethod(methodName)) {
            return true;
        }
        String parent = javaSymbols.superClassByClass.get(simpleType(type));
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            if ("SQLiteOpenHelper".equals(parent) && isSqliteOpenHelperMethod(methodName)) {
                return true;
            }
            parent = javaSymbols.superClassByClass.get(parent);
        }
        return false;
    }

    private boolean isJavaObjectMethod(String methodName) {
        return "toString".equals(methodName) ||
                "hashCode".equals(methodName) ||
                "equals".equals(methodName) ||
                "getClass".equals(methodName);
    }

    private boolean isSqliteOpenHelperMethod(String methodName) {
        return "getWritableDatabase".equals(methodName) ||
                "getReadableDatabase".equals(methodName) ||
                "close".equals(methodName) ||
                "getDatabaseName".equals(methodName) ||
                "setWriteAheadLoggingEnabled".equals(methodName);
    }

    private Map<String, String> collectVariableTypes(String content) {
        Map<String, String> variableTypes = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Matcher matcher = JAVA_VARIABLE_DECLARATION.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (isExternalQualifiedVariableType(matcher.group(1))) {
                // java.util.Map.Entry collapses under simpleType() to the simple name "Entry", which
                // can collide with an unrelated generated nested class also named Entry (e.g. an
                // adapter's row type). Treat the iteration entry as external so its getKey()/getValue()
                // calls are never validated against that generated class. Mark the name ambiguous so a
                // colliding capture elsewhere in the file is also discarded.
                ambiguous.add(name);
                continue;
            }
            String type = simpleType(matcher.group(1));
            if (type.isEmpty() || isJavaKeyword(name)) {
                continue;
            }
            String existing = variableTypes.get(name);
            if (existing != null && !existing.equals(type)) {
                // Same variable name declared with conflicting types in this file (e.g. a `String
                // value` param of putString and a `long value` param of putLong). A file-global map
                // cannot tell which method scope applies, so its type is unreliable: mark it unknown
                // so argument inference does not raise a phantom mismatch (javac is the real authority).
                ambiguous.add(name);
            }
            variableTypes.put(name, type);
        }
        for (String name : ambiguous) {
            variableTypes.remove(name);
        }
        return variableTypes;
    }

    private boolean isExternalQualifiedVariableType(String rawType) {
        String type = rawType == null ? "" : rawType.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic).trim();
        }
        return type.equals("Map.Entry") || type.equals("java.util.Map.Entry");
    }

    private String firstClassName(String content) {
        Matcher matcher = JAVA_CLASS.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private List<String> parseParameterTypes(String parameters) {
        List<String> types = new ArrayList<>();
        for (String parameter : splitArguments(parameters)) {
            String type = typeFromDeclaration(parameter);
            if (!type.isEmpty()) {
                types.add(type);
            }
        }
        return types;
    }

    private String typeFromDeclaration(String declaration) {
        String value = declaration == null ? "" : declaration.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = value.replaceAll("@[A-Za-z_][A-Za-z0-9_.]*(?:\\([^)]*\\))?\\s*", "");
        value = value.replaceAll("\\bfinal\\s+", "");
        int split = lastWhitespaceOutsideGenerics(value);
        if (split <= 0) {
            return "";
        }
        return simpleType(value.substring(0, split).replace("...", "[]"));
    }

    private int lastWhitespaceOutsideGenerics(String value) {
        int depth = 0;
        int split = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                split = i;
            }
        }
        return split;
    }

    private List<String> splitArguments(String arguments) {
        List<String> values = new ArrayList<>();
        String value = arguments == null ? "" : arguments.trim();
        if (value.isEmpty()) {
            return values;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(' || c == '[' || c == '<') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '>') && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                values.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        values.add(value.substring(start).trim());
        return values;
    }

    private List<String> inferArgumentTypes(List<String> arguments, Map<String, String> variableTypes, String currentClass) {
        List<String> types = new ArrayList<>();
        for (String argument : arguments) {
            types.add(inferArgumentType(argument, variableTypes, currentClass));
        }
        return types;
    }

    private String inferArgumentType(String argument, Map<String, String> variableTypes, String currentClass) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty()) {
            return "";
        }
        Matcher castMatcher = Pattern.compile("^\\(([^)]+)\\)").matcher(value);
        if (castMatcher.find()) {
            return simpleType(castMatcher.group(1));
        }
        if ("this".equals(value)) {
            return currentClass == null ? "" : currentClass;
        }
        if (value.endsWith(".this")) {
            return simpleType(value.substring(0, value.length() - 5));
        }
        Matcher newMatcher = Pattern.compile("^new\\s+([A-Z][A-Za-z0-9_]*)\\b").matcher(value);
        if (newMatcher.find()) {
            return newMatcher.group(1);
        }
        Matcher variableMatcher = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$").matcher(value);
        if (variableMatcher.find()) {
            String type = variableTypes.get(value);
            return type == null ? "" : type;
        }
        if (value.startsWith("\"")) {
            return "String";
        }
        if ("true".equals(value) || "false".equals(value)) {
            return "boolean";
        }
        if (value.matches("-?\\d+[lL]")) {
            return "long";
        }
        if (value.matches("-?\\d+")) {
            return "int";
        }
        if (value.matches("-?\\d+\\.\\d+[fF]?")) {
            return value.endsWith("f") || value.endsWith("F") ? "float" : "double";
        }
        return "";
    }

    private boolean hasMatchingArity(List<List<String>> constructors, int arity) {
        for (List<String> constructor : constructors) {
            if (constructor.size() == arity) {
                return true;
            }
        }
        return false;
    }

    private boolean allKnown(List<String> types) {
        for (String type : types) {
            if (type == null || type.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAnyConstructor(List<String> argumentTypes, List<List<String>> constructors, SymbolTable javaSymbols) {
        for (List<String> constructor : constructors) {
            if (constructor.size() != argumentTypes.size()) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < constructor.size(); i++) {
                if (!javaSymbols.isAssignable(argumentTypes.get(i), constructor.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyModelType(String type) {
        String value = simpleType(type);
        if (value.isEmpty()) {
            return false;
        }
        return !(value.endsWith("Activity") ||
                value.endsWith("Adapter") ||
                value.endsWith("DAO") ||
                value.endsWith("Dao") ||
                value.endsWith("Database") ||
                value.endsWith("Helper") ||
                value.endsWith("OpenHelper") ||
                value.endsWith("ViewHolder") ||
                value.endsWith("Holder") ||
                value.endsWith("Fragment") ||
                value.endsWith("Service") ||
                value.endsWith("Receiver") ||
                value.endsWith("Dialog") ||
                value.endsWith("View") ||
                value.endsWith("Button") ||
                value.endsWith("TextView") ||
                value.endsWith("ImageView"));
    }

    /**
     * A generated class whose superclass chain reaches a non-generated (platform/library) type -
     * e.g. a ViewHolder extends RecyclerView.ViewHolder, an Adapter extends RecyclerView.Adapter -
     * inherits an API the guard cannot see. Flagging "missing" members on such a type is a false
     * positive (itemView, getAdapterPosition, submitList, ...), so those checks are deferred to the
     * real compiler. Constructor checks are unaffected (they concern the class's own constructors).
     */
    private boolean inheritsExternalApi(String type, SymbolTable javaSymbols) {
        String current = javaSymbols.superClassByClass.get(simpleType(type));
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            if (!"Object".equals(current) && !javaSymbols.hasClass(current)) {
                return true;
            }
            current = javaSymbols.superClassByClass.get(current);
        }
        return false;
    }

    private boolean isLikelyGeneratedApiClass(String type) {
        String value = simpleType(type);
        if (value.isEmpty()) {
            return false;
        }
        return !(value.endsWith("Activity") ||
                value.endsWith("Fragment") ||
                value.endsWith("Service") ||
                value.endsWith("Receiver") ||
                value.endsWith("Dialog") ||
                value.endsWith("View") ||
                value.endsWith("Button") ||
                value.endsWith("TextView") ||
                value.endsWith("ImageView") ||
                value.endsWith("ViewHolder") ||
                value.endsWith("Holder"));
    }

    private String describeConstructors(String className, List<List<String>> constructors) {
        List<String> values = new ArrayList<>();
        for (List<String> constructor : constructors) {
            values.add(className + "(" + joinTypes(constructor) + ")");
        }
        return joinValues(values);
    }

    private String joinTypes(List<String> types) {
        List<String> values = new ArrayList<>();
        for (String type : types) {
            values.add(type == null || type.isEmpty() ? "unknown" : type);
        }
        return joinValues(values);
    }

    private String joinValues(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private String simpleType(String type) {
        // Single source of truth shared with the contract linter (Stage 2 SymbolTable).
        return SymbolTable.simpleType(type);
    }

    private boolean isJavaKeyword(String value) {
        return "class".equals(value) || "interface".equals(value) || "enum".equals(value) || "return".equals(value) || "new".equals(value);
    }

    private String stripJavaCommentsAndStrings(String content) {
        StringBuilder builder = new StringBuilder(content.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    builder.append(c);
                } else {
                    builder.append(' ');
                }
            } else if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (inString) {
                if (c == '\\' && next != '\0') {
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else if (c == '"') {
                    inString = false;
                    builder.append(' ');
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (inChar) {
                if (c == '\\' && next != '\0') {
                    builder.append(' ');
                    builder.append(' ');
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                    builder.append(' ');
                } else {
                    builder.append(c == '\n' ? c : ' ');
                }
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                builder.append(' ');
                builder.append(' ');
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                builder.append(' ');
                builder.append(' ');
                i++;
            } else if (c == '"') {
                inString = true;
                builder.append(' ');
            } else if (c == '\'') {
                inChar = true;
                builder.append(' ');
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private void validateXmlFile(File file, ResourceSymbols symbols, String gradleText, List<String> violations) throws Exception {
        String content = FileUtils.readText(file);
        Matcher matcher = XML_RESOURCE_REFERENCE.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!knownXmlResources(symbols, type).contains(name)) {
                addViolation(violations, "Generated source policy blocked missing XML resource reference: @" + type + "/" + name + " in " + file.getName() + ".");
            }
        }
        for (String widget : WidgetDependencyPolicy.missingWidgetDependencies(content, gradleText)) {
            String coordinate = WidgetDependencyPolicy.requiredCoordinate(widget);
            addViolation(violations, "Generated source policy blocked layout widget " + widget + " in " + file.getName() + ": dependency " + coordinate + " is not declared in app/build.gradle. Add the dependency, or use a built-in widget. If this task cannot edit app/build.gradle, return blocked with prerequisiteWork naming the dependency.");
        }
    }

    private Set<String> knownXmlResources(ResourceSymbols symbols, String type) {
        if ("layout".equals(type)) {
            return symbols.layouts;
        }
        if ("string".equals(type)) {
            return symbols.strings;
        }
        if ("color".equals(type)) {
            return symbols.colors;
        }
        if ("drawable".equals(type)) {
            return symbols.drawables;
        }
        if ("mipmap".equals(type)) {
            return symbols.mipmaps;
        }
        if ("style".equals(type)) {
            return symbols.styles;
        }
        return new HashSet<>();
    }

    private void rejectMissingResource(Pattern pattern, Set<String> knownNames, String message, String content, File file, List<String> violations) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!knownNames.contains(name)) {
                addViolation(violations, String.format(message, name, file.getName()));
            }
        }
    }

    private void addViolation(List<String> violations, String message) {
        if (violations.size() < MAX_REPORTED_VIOLATIONS) {
            violations.add(message);
        }
    }

    private void throwIfViolations(List<String> violations) {
        if (violations.isEmpty()) {
            return;
        }
        if (violations.size() == 1) {
            throw new IllegalArgumentException(violations.get(0));
        }
        throw new IllegalArgumentException(joinViolations(violations));
    }

    private String joinViolations(List<String> violations) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(violations.get(i));
        }
        return builder.toString();
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

    private static class ClassSpan {
        final String className;
        final int bodyStart;
        final int bodyEnd;

        ClassSpan(String className, int bodyStart, int bodyEnd) {
            this.className = className;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

}
