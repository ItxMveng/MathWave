package com.example.enib.ui.math;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MathKeyboardView extends LinearLayout {

    private TextInputEditText targetInput;
    private Runnable onConfirm;

    // Rows of the scientific keyboard
    private static final String[][] ROWS = {
        {"sin(",   "cos(",   "tan(",   "asin(",  "acos(" },
        {"atan(",  "log10(", "ln(",    "sqrt(",  "abs("  },
        {"π",      "e",      "x",      "^",      "%"    },
        {"7",      "8",      "9",      "(",      ")"    },
        {"4",      "5",      "6",      "×",      "÷"    },
        {"1",      "2",      "3",      "+",      "-"    },
        {"0",      ".",      "⌫",     "AC",     "✓"    }
    };

    public MathKeyboardView(Context context) {
        super(context);
        init();
    }

    public MathKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MathKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF1C1C1C);
        int pad = dp(8);
        setPadding(pad, dp(6), pad, dp(6));

        for (String[] row : ROWS) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(HORIZONTAL);
            LayoutParams rp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(4));
            rowLayout.setLayoutParams(rp);
            for (String key : row) rowLayout.addView(buildButton(key));
            addView(rowLayout);
        }
    }

    private MaterialButton buildButton(String key) {
        MaterialButton btn = new MaterialButton(getContext());
        btn.setText(key);

        // Scale text size based on label length
        int len = key.length();
        btn.setTextSize(len >= 6 ? 11 : (len >= 4 ? 13 : 15));

        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setTypeface(Typeface.MONOSPACE);
        btn.setCornerRadius(dp(8));

        LayoutParams p = new LayoutParams(0, dp(46), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        btn.setLayoutParams(p);

        int bg, fg;
        switch (key) {
            // Trigonometric
            case "sin(": case "cos(": case "tan(":
            case "asin(": case "acos(": case "atan(":
                bg = 0xFFBF3600; fg = Color.WHITE; break;
            // Other functions
            case "log10(": case "ln(": case "sqrt(": case "abs(":
                bg = 0xFF4E3000; fg = 0xFFFFB74D; break;
            // Constants and special
            case "π": case "e": case "x": case "^": case "%":
                bg = 0xFF2B2B2B; fg = 0xFFFF9800; break;
            // Parentheses
            case "(": case ")":
                bg = 0xFF2B2B2B; fg = Color.WHITE; break;
            // Arithmetic operators
            case "×": case "÷": case "+": case "-":
                bg = 0xFFCC5500; fg = Color.WHITE; break;
            // Delete
            case "⌫":
                bg = 0xFFC62828; fg = Color.WHITE; break;
            // All clear
            case "AC":
                bg = 0xFF8B0000; fg = Color.WHITE; break;
            // Confirm / close
            case "✓":
                bg = 0xFF2E7D32; fg = Color.WHITE; break;
            // Digits and decimal point
            default:
                bg = 0xFF383838; fg = Color.WHITE; break;
        }

        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        btn.setTextColor(fg);
        btn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            handleKey(key);
        });
        return btn;
    }

    public void setTarget(TextInputEditText input) {
        this.targetInput = input;
    }

    public void setOnConfirm(Runnable onConfirm) {
        this.onConfirm = onConfirm;
    }

    private void handleKey(String key) {
        if (targetInput == null) return;
        switch (key) {
            case "⌫":    deleteAtCursor(); break;
            case "AC":   targetInput.setText(""); break;
            case "✓":
                if (onConfirm != null) onConfirm.run();
                else setVisibility(GONE);
                break;
            case "×":    insert("*"); break;
            case "÷":    insert("/"); break;
            // π → exp4j built-in constant "pi"
            case "π":    insert("pi"); break;
            // ln( → custom function registered in MathFunctionParser
            default:     insert(key); break;
        }
    }

    private void insert(String text) {
        int start = Math.max(targetInput.getSelectionStart(), 0);
        int end   = Math.max(targetInput.getSelectionEnd(), 0);
        Editable e = targetInput.getText();
        if (e != null) e.replace(Math.min(start, end), Math.max(start, end), text);
    }

    private void deleteAtCursor() {
        int start = targetInput.getSelectionStart();
        int end   = targetInput.getSelectionEnd();
        Editable e = targetInput.getText();
        if (e == null) return;
        if (start != end) {
            e.delete(Math.min(start, end), Math.max(start, end));
        } else if (start > 0) {
            e.delete(start - 1, start);
        }
    }

    private int dp(int val) {
        return Math.round(val * getContext().getResources().getDisplayMetrics().density);
    }
}
