package com.example.enib.ui.math;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.enib.R;
import com.example.enib.domain.MathFunctionParser;
import com.example.enib.domain.MathSonifier;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public class MathInputFragment extends Fragment {

    private TextInputEditText etFunctionInput;
    private TextInputLayout   tilFunctionInput;
    private MaterialButton    btnGenerate, btnPlay, btnStop;
    private LineChart         lineChart;
    private MathKeyboardView  mathKeyboard;

    private final MathSonifier sonifier = new MathSonifier();
    private String currentExpression = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_math_input, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        setupChart();
        setupChips(view);
        setupButtons(view);
        setupMathKeyboard();
        animateEntrance(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        sonifier.stop();
    }

    // ─── Bind views ──────────────────────────────────────────────────────────

    private void bindViews(View view) {
        etFunctionInput = view.findViewById(R.id.etFunctionInput);
        tilFunctionInput = view.findViewById(R.id.tilFunctionInput);
        btnGenerate     = view.findViewById(R.id.btnGenerate);
        btnPlay         = view.findViewById(R.id.btnPlay);
        btnStop         = view.findViewById(R.id.btnStop);
        lineChart       = view.findViewById(R.id.lineChart);
        mathKeyboard    = view.findViewById(R.id.mathKeyboard);
    }

    // ─── Scientific keyboard setup ───────────────────────────────────────────

    private void setupMathKeyboard() {
        mathKeyboard.setTarget(etFunctionInput);

        // Suppress the system keyboard and show our math keyboard on focus
        etFunctionInput.setShowSoftInputOnFocus(false);

        etFunctionInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                hideSoftKeyboard();
                mathKeyboard.setVisibility(View.VISIBLE);
            }
        });

        // Also handle clicks when the field already has focus
        etFunctionInput.setOnClickListener(v -> {
            hideSoftKeyboard();
            mathKeyboard.setVisibility(View.VISIBLE);
        });
    }

    private void hideSoftKeyboard() {
        if (getActivity() == null) return;
        InputMethodManager imm = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = getActivity().getCurrentFocus();
        if (focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    // ─── Entrance animation ──────────────────────────────────────────────────

    private void animateEntrance(View view) {
        animateFadeSlide(view.findViewById(R.id.cardInput), 100);
        animateFadeSlide(view.findViewById(R.id.cardGraph), 200);
        animateFadeSlide(view.findViewById(R.id.layoutButtons), 300);
    }

    private void animateFadeSlide(View v, long delay) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(60f);
        v.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(delay).start();
    }

    // ─── Chart setup ─────────────────────────────────────────────────────────

    private void setupChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setPinchZoom(true);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(0xFFE0E0E0);
        xAxis.setTextColor(0xFF666666);

        lineChart.getAxisLeft().setDrawGridLines(true);
        lineChart.getAxisLeft().setGridColor(0xFFE0E0E0);
        lineChart.getAxisLeft().setTextColor(0xFF666666);
        lineChart.getAxisRight().setEnabled(false);
        lineChart.getLegend().setEnabled(true);
        lineChart.setNoDataText("Entrez une fonction et appuyez sur Générer");
        lineChart.setNoDataTextColor(0xFF9E9E9E);
    }

    // ─── Quick-insert chips ──────────────────────────────────────────────────

    private void setupChips(View view) {
        view.findViewById(R.id.chipSin).setOnClickListener(v  -> insertFunction("sin(x)"));
        view.findViewById(R.id.chipCos).setOnClickListener(v  -> insertFunction("cos(x)"));
        view.findViewById(R.id.chipTan).setOnClickListener(v  -> insertFunction("tan(x)"));
        view.findViewById(R.id.chipX2).setOnClickListener(v   -> insertFunction("x^2"));
        view.findViewById(R.id.chipSqrt).setOnClickListener(v -> insertFunction("sqrt(x)"));
    }

    private void insertFunction(String formula) {
        etFunctionInput.setText(formula);
        etFunctionInput.setSelection(formula.length());
    }

    // ─── Buttons ─────────────────────────────────────────────────────────────

    private void setupButtons(View view) {
        btnPlay.setEnabled(false);
        btnStop.setEnabled(false);
        btnGenerate.setOnClickListener(v -> {
            mathKeyboard.setVisibility(View.GONE);
            onGenerateClicked();
        });
        btnPlay.setOnClickListener(v -> onPlayClicked(view));
        btnStop.setOnClickListener(v -> onStopClicked());
    }

    private void onGenerateClicked() {
        String input = etFunctionInput.getText() != null
                ? etFunctionInput.getText().toString().trim() : "";

        if (input.isEmpty()) {
            tilFunctionInput.setError(getString(R.string.error_invalid_function));
            return;
        }
        tilFunctionInput.setError(null);

        MathFunctionParser.ParseResult result =
                MathFunctionParser.evaluate(input, -10.0, 10.0, 300);

        if (result.isSuccess) {
            currentExpression = input;
            drawChart(result.points, input);
            btnPlay.setEnabled(true);
        } else {
            tilFunctionInput.setError(result.errorMessage);
        }
    }

    private void onPlayClicked(View rootView) {
        if (currentExpression == null) return;

        sonifier.stop();
        btnPlay.setEnabled(false);
        btnStop.setEnabled(true);

        sonifier.play(currentExpression, new MathSonifier.Callback() {
            @Override
            public void onPlaybackComplete() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> resetAudioButtons());
                }
            }
            @Override
            public void onError(String message) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show();
                        resetAudioButtons();
                    });
                }
            }
        });
    }

    private void onStopClicked() {
        sonifier.stop();
        resetAudioButtons();
    }

    private void resetAudioButtons() {
        btnPlay.setEnabled(currentExpression != null);
        btnStop.setEnabled(false);
    }

    // ─── Chart drawing ───────────────────────────────────────────────────────

    private void drawChart(List<float[]> points, String label) {
        List<Entry> entries = new ArrayList<>();
        for (float[] pt : points) entries.add(new Entry(pt[0], pt[1]));

        LineDataSet ds = new LineDataSet(entries, "f(x) = " + label);
        ds.setColor(0xFFFF6600);
        ds.setLineWidth(2.5f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        ds.setDrawFilled(true);
        ds.setFillColor(0xFFFF6600);
        ds.setFillAlpha(35);

        lineChart.setData(new LineData(ds));
        lineChart.animateX(600);
        lineChart.invalidate();
    }
}
