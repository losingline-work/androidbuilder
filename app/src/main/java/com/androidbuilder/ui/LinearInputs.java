package com.androidbuilder.ui;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

class LinearInputs {
    final LinearLayout view;
    final EditText first;
    final EditText second;

    LinearInputs(Context context, String firstHint, String secondHint) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(padding, 0, padding, 0);
        TextInputLayout firstLayout = new TextInputLayout(context);
        firstLayout.setHint(firstHint);
        firstLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        first = new TextInputEditText(context);
        first.setHint(firstHint);
        first.setSingleLine(true);
        firstLayout.addView(first);
        TextInputLayout secondLayout = new TextInputLayout(context);
        secondLayout.setHint(secondHint);
        secondLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        secondParams.topMargin = (int) (12 * context.getResources().getDisplayMetrics().density);
        secondLayout.setLayoutParams(secondParams);
        second = new TextInputEditText(context);
        second.setHint(secondHint);
        second.setMinLines(4);
        second.setImeOptions(EditorInfo.IME_ACTION_DONE);
        secondLayout.addView(second);
        view.addView(firstLayout);
        view.addView(secondLayout);
    }
}
