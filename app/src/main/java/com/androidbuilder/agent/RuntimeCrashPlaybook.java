package com.androidbuilder.agent;

/**
 * Builds the repair instruction for a RUNTIME crash, the way {@code repairInstruction} does for a build
 * failure — the crash stack is the authoritative signal in place of the build log. Pure (no Android
 * {@code Context}), so the playbook routing is unit-testable. Keyed off the well-known launch-crash classes
 * a generated app hits; each clause names the smallest root-cause fix on the launch path.
 */
final class RuntimeCrashPlaybook {
    private RuntimeCrashPlaybook() {
    }

    /** {@code crashDiagnostics} is the focused payload from {@link LogcatCrashExtractor#crashDiagnostics}. */
    static String instruction(String crashDiagnostics, boolean chinese, boolean escalate) {
        String diagnostics = crashDiagnostics == null ? "" : crashDiagnostics;
        StringBuilder playbook = new StringBuilder();
        for (Clause clause : CLAUSES) {
            if (clause.matches(diagnostics)) {
                playbook.append(chinese ? clause.zh : clause.en).append('\n');
            }
        }
        String escalationClause = !escalate ? "" : (chinese
                ? "升级：之前几轮没有解决崩溃。请直接整文件 write 涉及的文件，做最小但完整的根因修复。\n\n"
                : "ESCALATION: prior rounds did not resolve the crash. Rewrite the involved files in full with the smallest complete root-cause fix.\n\n");
        if (chinese) {
            return escalationClause
                    + "生成的 app 能编译、能安装，但一启动就崩溃。下面是 FATAL EXCEPTION 与 app 自己的调用栈帧。"
                    + "请在启动路径上做最小改动修复根因（只改崩溃涉及的文件，不要重写整个项目）。"
                    + "javac/aapt 看不到运行期崩溃，崩溃栈才是权威，请据此定位。\n"
                    + (playbook.length() == 0 ? "" : "\n针对性提示：\n" + playbook)
                    + "\n崩溃信息：\n" + diagnostics;
        }
        return escalationClause
                + "The generated app COMPILES and INSTALLS but CRASHES ON LAUNCH. Below is the FATAL EXCEPTION "
                + "and the app's own stack frames. Fix the root cause on the launch path with the smallest "
                + "change (touch only the files in the crash, do not rewrite the project). javac/aapt cannot "
                + "see a runtime crash — the stack trace is the authority; use it to locate the fault.\n"
                + (playbook.length() == 0 ? "" : "\nTargeted hints:\n" + playbook)
                + "\nCrash:\n" + diagnostics;
    }

    private static final class Clause {
        final String[] needles;
        final String en;
        final String zh;

        Clause(String[] needles, String en, String zh) {
            this.needles = needles;
            this.en = en;
            this.zh = zh;
        }

        boolean matches(String diagnostics) {
            for (String needle : needles) {
                if (!diagnostics.contains(needle)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Clause[] CLAUSES = {
            new Clause(new String[]{"Theme.AppCompat"},
                    "- Theme mismatch: either change the crashing Activity to extend android.app.Activity, OR set the app theme in res/values/styles.xml to a Theme.AppCompat/Theme.Material3 descendant (and ensure the appcompat/material dependency is declared).",
                    "- 主题不兼容：要么把崩溃的 Activity 改成 extends android.app.Activity，要么把 res/values/styles.xml 的应用主题改为 Theme.AppCompat/Theme.Material3 的后代（并确认已声明 appcompat/material 依赖）。"),
            new Clause(new String[]{"Resources$NotFoundException"},
                    "- A resource referenced at runtime is missing or malformed: add the @layout/@drawable/@string/@id it names, or fix the reference.",
                    "- 运行期引用了缺失或损坏的资源：补上它指向的 @layout/@drawable/@string/@id，或修正该引用。"),
            new Clause(new String[]{"ComplexColor"},
                    "- A COLOR attribute points at a non-color resource: an attribute that needs a color (app:cardBackgroundColor, android:textColor, android:tint, *Tint, strokeColor, …) references a @drawable or a malformed res/color/*.xml selector. Point it at a real @color (or remove the attribute to use the default); never a @drawable. Use android:background for a drawable look.",
                    "- 颜色属性指向了非颜色资源：需要颜色的属性（app:cardBackgroundColor、android:textColor、android:tint、各种 *Tint、strokeColor 等）引用了 @drawable 或一个损坏的 res/color/*.xml 选择器。请改成真正的 @color（或删掉该属性用默认值），绝不能是 @drawable；想要图形背景请用 android:background。"),
            new Clause(new String[]{"InflateException"},
                    "- Layout inflation failed: the exception names the layout file, the line, and the failing view class — fix that tag's class name or its attributes.",
                    "- 布局 inflate 失败：异常里写明了布局文件、行号和失败的 view 类——修正该标签的类名或属性。"),
            new Clause(new String[]{"NullPointerException", "findViewById"},
                    "- findViewById returned null: the view id is not in THIS activity/fragment's inflated layout — use the correct id, or call findViewById after setContentView/inflate on the right root.",
                    "- findViewById 返回了 null：该 id 不在当前 activity/fragment 实际加载的布局里——改用正确的 id，或在 setContentView/inflate 之后、对正确的根视图调用。"),
            new Clause(new String[]{"UnsupportedOperationException", "stub"},
                    "- A stubbed method (// ANDROIDBUILDER-STUB) is on the launch path; implement it for real so it does the intended work.",
                    "- 启动路径上有一个自动桩方法（// ANDROIDBUILDER-STUB）被调用；请把它真正实现。"),
            new Clause(new String[]{"ActivityNotFoundException"},
                    "- An started Activity is not declared in AndroidManifest.xml; declare it, or navigate to an existing one.",
                    "- 跳转的 Activity 没在 AndroidManifest.xml 声明；补声明，或改跳到已存在的 Activity。"),
            new Clause(new String[]{"ClassCastException"},
                    "- A view/object was cast to the wrong type (e.g. (TextView) findViewById of a Button); cast to the type the id actually is.",
                    "- 把某个 view/对象转成了错误类型（例如把 Button 当 TextView 强转）；按该 id 实际的类型来转。"),
            new Clause(new String[]{"SQLiteException"},
                    "- A database error at launch: the SQL/schema is wrong (missing table/column or a bad query); align the schema and the query.",
                    "- 启动时数据库出错：SQL/表结构有误（缺表/缺列或查询语句错误）；让表结构和查询保持一致。"),
    };
}
