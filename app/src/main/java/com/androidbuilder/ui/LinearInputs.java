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
    final EditText third;

    LinearInputs(Context context, String firstHint, String secondHint) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(padding, 0, padding, 0);
        first = singleLineInput(context, firstHint);
        second = multiLineInput(context, secondHint);
        third = null;
        view.addView(wrap(context, first, firstHint, 0));
        view.addView(wrap(context, second, secondHint, 12));
    }

    LinearInputs(Context context, String firstHint, String secondHint, String thirdHint) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(padding, 0, padding, 0);
        first = singleLineInput(context, firstHint);
        second = singleLineInput(context, secondHint);
        third = thirdHint == null ? null : multiLineInput(context, thirdHint);
        view.addView(wrap(context, first, firstHint, 0));
        view.addView(wrap(context, second, secondHint, 12));
        if (third != null) {
            view.addView(wrap(context, third, thirdHint, 12));
        }
    }

    private EditText singleLineInput(Context context, String hint) {
        TextInputEditText input = new TextInputEditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        return input;
    }

    private EditText multiLineInput(Context context, String hint) {
        TextInputEditText input = new TextInputEditText(context);
        input.setHint(hint);
        input.setMinLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        return input;
    }

    private TextInputLayout wrap(Context context, EditText input, String hint, int topMarginDp) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        if (topMarginDp > 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = (int) (topMarginDp * context.getResources().getDisplayMetrics().density);
            layout.setLayoutParams(params);
        }
        layout.addView(input);
        return layout;
    }
}
