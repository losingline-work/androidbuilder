package com.androidbuilder.ui;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

class LinearInputs {
    final LinearLayout view;
    final EditText first;
    final EditText second;

    LinearInputs(Context context, String firstHint, String secondHint) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(padding, 0, padding, 0);
        first = new EditText(context);
        first.setHint(firstHint);
        first.setSingleLine(true);
        second = new EditText(context);
        second.setHint(secondHint);
        second.setMinLines(4);
        second.setImeOptions(EditorInfo.IME_ACTION_DONE);
        view.addView(first);
        view.addView(second);
    }
}
