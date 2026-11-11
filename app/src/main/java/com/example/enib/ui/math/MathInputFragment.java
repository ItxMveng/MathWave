package com.example.enib.ui.math;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.enib.R;
import com.example.enib.domain.MathSonifier;
import com.example.enib.domain.TimelineSignal;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.renderer.LineChartRenderer;
import com.github.mikephil.charting.utils.MPPointD;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MathInputFragment extends Fragment {
    private static final int TRACE_COLOR = 0xFF5DE2B8;
    private static final String[] EXAMPLES = {
            "sin(2*pi*x)",
            "sin(pi*x^2)",
            "sin(2*pi*x)+0.6*sin(2*pi*1.3*x)",
            "0.12*exp(-(((x%0.8)-0.12)/0.03)^2)"
                    + "-0.18*exp(-(((x%0.8)-0.21)/0.015)^2)"
                    + "+exp(-(((x%0.8)-0.24)/0.024)^2)"
                    + "-0.25*exp(-(((x%0.8)-0.275)/0.018)^2)"
                    + "+0.25*exp(-(((x%0.8)-0.43)/0.055)^2)",
            "x^2/4",
            "3*exp(-x/2)*sin(2*pi*x)"
    };
    private TextInputEditText input;
    private TextInputLayout inputLayout;
    private MaterialButton generateButton, playButton, stopButton;
    private MaterialButtonToggleGroup modes;
    private TextView status, readout, modeDescription;
    private ProgressBar progressBar;
    private LineChart chart;
    private BottomSheetDialog editor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MathSonifier sonifier = new MathSonifier();
    private final Runnable playbackFrame = this::updatePlayback;
    private ExecutorService worker;
    private Future<?> pending;
    private int revision;
    private boolean preparing;
    private boolean playing;
    private TimelineSignal signal;
    private short[] pcm;
    private double progress = -1;
    private double xMin = 0;
    private double xMax = 8;
    private double duration = 8;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_math_input, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        input = view.findViewById(R.id.etFunctionInput);
        inputLayout = view.findViewById(R.id.tilFunctionInput);
        chart = view.findViewById(R.id.lineChart);
        generateButton = view.findViewById(R.id.btnGenerate);
        playButton = view.findViewById(R.id.btnPlay);
        stopButton = view.findViewById(R.id.btnStop);
        modes = view.findViewById(R.id.soundModes);
        status = view.findViewById(R.id.playbackStatus);
        readout = view.findViewById(R.id.playbackValues);
        modeDescription = view.findViewById(R.id.modeDescription);
        progressBar = view.findViewById(R.id.playbackProgress);
        worker = Executors.newSingleThreadExecutor();
        setupChart();
        if (savedState != null) {
            xMin = savedState.getDouble("xMin", 0);
            xMax = savedState.getDouble("xMax", 8);
            duration = savedState.getDouble("duration", 8);
            modes.check(savedState.getBoolean("percussion") ? R.id.modeRhythm : R.id.modeCurve);
        }
        input.setText(savedState == null ? EXAMPLES[0] : savedState.getString("formula", EXAMPLES[0]));
        input.setShowSoftInputOnFocus(false);
        input.setFocusable(false);
        input.setOnClickListener(v -> openEditor());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                invalidateSignal();
                ((ChipGroup) view.findViewById(R.id.chipGroupSuggestions)).clearCheck();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        modes.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            boolean resume = playing;
            updateModeDescription();
            invalidateSignal();
            prepare(resume);
        });
        setupExamples(view);
        generateButton.setOnClickListener(v -> prepare(false));
        playButton.setOnClickListener(v -> {
            if (pcm == null) prepare(true);
            else startPlayback();
        });
        stopButton.setOnClickListener(v -> {
            cancelPreparation();
            stopPlayback();
            status.setText(R.string.studio_stopped);
            updateButtons();
        });
        view.findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        updateModeDescription();
        updateButtons();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle state) {
        super.onViewStateRestored(state);
        // Android restores EditText after onViewCreated, firing its text watcher.
        // Prepare only after that restoration so rotation keeps a generated graph.
        ChipGroup examples = requireView().findViewById(R.id.chipGroupSuggestions);
        examples.clearCheck();
        for (int i = 0; i < EXAMPLES.length; i++) {
            if (formula().equals(EXAMPLES[i])) examples.check(examples.getChildAt(i).getId());
        }
        prepare(false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("formula", formula());
        state.putDouble("xMin", xMin);
        state.putDouble("xMax", xMax);
        state.putDouble("duration", duration);
        state.putBoolean("percussion", isPercussion());
    }

    @Override
    public void onStop() {
        cancelPreparation();
        stopPlayback();
        updateButtons();
        status.setText(R.string.studio_stopped);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        cancelPreparation();
        stopPlayback();
        if (editor != null) editor.dismiss();
        worker.shutdownNow();
        signal = null;
        pcm = null;
        super.onDestroyView();
    }

    private boolean isPercussion() {
        return modes.getCheckedButtonId() == R.id.modeRhythm;
    }

    private String formula() {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void updateModeDescription() {
        modeDescription.setText(isPercussion() ? R.string.studio_rhythm_help : R.string.studio_curve_help);
    }

    private void setupExamples(View view) {
        ChipGroup group = view.findViewById(R.id.chipGroupSuggestions);
        String[] labels = getResources().getStringArray(R.array.studio_example_names);
        for (int i = 0; i < EXAMPLES.length; i++) {
            final int index = i;
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(labels[i]);
            chip.setCheckable(true);
            chip.setEnsureMinTouchTargetSize(true);
            chip.setContentDescription(getString(R.string.studio_example_description, labels[i]));
            chip.setOnClickListener(v -> {
                boolean resume = playing;
                input.setText(EXAMPLES[index]);
                group.check(chip.getId());
                prepare(resume);
            });
            group.addView(chip);
            chip.setChecked(formula().equals(EXAMPLES[i]));
        }
    }

    private void cancelPreparation() {
        revision++;
        if (pending != null) pending.cancel(true);
        pending = null;
        preparing = false;
    }

    private void invalidateSignal() {
        cancelPreparation();
        stopPlayback();
        signal = null;
        pcm = null;
        chart.clear();
        inputLayout.setError(null);
        status.setText(R.string.studio_changed);
        readout.setText(R.string.studio_values_idle);
        updateButtons();
    }

    private void prepare(boolean playAfter) {
        String expression = formula();
        cancelPreparation();
        stopPlayback();
        if (expression.isEmpty()) {
            inputLayout.setError(getString(R.string.error_invalid_function));
            updateButtons();
            return;
        }
        inputLayout.setError(null);
        preparing = true;
        status.setText(R.string.studio_preparing);
        updateButtons();
        final int request = revision;
        final View owner = getView();
        final double from = xMin, to = xMax, seconds = duration;
        final boolean percussion = isPercussion();
        pending = worker.submit(() -> {
            try {
                TimelineSignal nextSignal = TimelineSignal.sample(expression, from, to, seconds);
                short[] nextPcm = nextSignal.toPcm(percussion);
                mainHandler.post(() -> {
                    if (request != revision || getView() != owner || owner == null) return;
                    preparing = false;
                    pending = null;
                    signal = nextSignal;
                    pcm = nextPcm;
                    drawTimeline();
                    status.setText(R.string.studio_ready);
                    readout.setText(getString(R.string.studio_range, from, to, seconds));
                    updateButtons();
                    if (playAfter) startPlayback();
                });
            } catch (java.util.concurrent.CancellationException ignored) {
                // Replaced requests are deliberately discarded.
            } catch (RuntimeException error) {
                mainHandler.post(() -> {
                    if (request != revision || getView() != owner || owner == null) return;
                    preparing = false;
                    pending = null;
                    signal = null;
                    pcm = null;
                    chart.clear();
                    inputLayout.setError(error.getMessage());
                    status.setText(R.string.studio_check_function);
                    updateButtons();
                });
            }
        });
    }

    private void startPlayback() {
        if (pcm == null || signal == null) return;
        stopPlayback();
        try {
            sonifier.play(pcm);
            playing = true;
            progress = 0;
            chart.highlightValue(null);
            chart.setTouchEnabled(false);
            chart.fitScreen();
            status.setText(R.string.studio_playing);
            chart.postOnAnimation(playbackFrame);
            updateButtons();
        } catch (RuntimeException error) {
            stopPlayback();
            status.setText(R.string.error_audio_playback);
            Snackbar.make(requireView(), R.string.error_audio_playback, Snackbar.LENGTH_LONG).show();
            updateButtons();
        }
    }

    private void updatePlayback() {
        progress = sonifier.position();
        float value = signal.valueAtProgress(progress);
        String shownValue = Float.isFinite(value)
                ? String.format(Locale.getDefault(), "%.3f", value) : getString(R.string.studio_undefined);
        readout.setText(getString(R.string.studio_readout,
                progress * signal.duration(), signal.xAtProgress(progress), shownValue));
        if (progress >= 1) {
            stopPlayback();
            progressBar.setProgress(1000);
            status.setText(R.string.studio_finished);
            updateButtons();
            return;
        }
        progressBar.setProgress((int) (progress * 1000));
        chart.invalidate();
        chart.postOnAnimation(playbackFrame);
    }

    private void stopPlayback() {
        chart.removeCallbacks(playbackFrame);
        sonifier.stop();
        playing = false;
        progress = -1;
        chart.setTouchEnabled(true);
        chart.invalidate();
        progressBar.setProgress(0);
    }

    private void updateButtons() {
        boolean hasInput = !formula().isEmpty();
        generateButton.setEnabled(hasInput && !preparing);
        playButton.setEnabled(hasInput && !preparing && !playing);
        stopButton.setEnabled(preparing || playing);
        playButton.setText(preparing ? R.string.studio_loading : R.string.studio_play);
    }

    private void setupChart() {
        chart.setRenderer(new TimelineRenderer());
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setPinchZoom(true);
        chart.setNoDataText(getString(R.string.studio_chart_empty));
        chart.setNoDataTextColor(0xFFA6C1D3);
        chart.getAxisRight().setEnabled(false);
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(0xFFA6C1D3);
        xAxis.setGridColor(0xFF263C4B);
        xAxis.setAxisLineColor(0xFF426071);
        xAxis.setLabelCount(5);
        chart.getAxisLeft().setTextColor(0xFFA6C1D3);
        chart.getAxisLeft().setGridColor(0xFF263C4B);
        chart.getAxisLeft().setDrawAxisLine(false);
        chart.getAxisLeft().setLabelCount(4);
    }

    private void drawTimeline() {
        List<ILineDataSet> segments = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < signal.size(); i++) {
            float value = signal.valueAt(i);
            if (Float.isFinite(value)) {
                entries.add(new Entry(signal.xAt(i), value));
            } else if (!entries.isEmpty()) {
                segments.add(segment(entries));
                entries = new ArrayList<>();
            }
        }
        if (!entries.isEmpty()) segments.add(segment(entries));
        chart.getXAxis().setAxisMinimum(signal.xAt(0));
        chart.getXAxis().setAxisMaximum(signal.xAt(signal.size() - 1));
        chart.setData(new LineData(segments));
        chart.fitScreen();
        chart.invalidate();
    }

    private LineDataSet segment(List<Entry> entries) {
        LineDataSet data = new LineDataSet(entries, "");
        data.setColor(TRACE_COLOR);
        data.setLineWidth(2);
        data.setDrawValues(false);
        data.setDrawCircles(entries.size() == 1);
        data.setCircleColor(TRACE_COLOR);
        data.setHighLightColor(0xFFFFB878);
        return data;
    }

    private void openEditor() {
        if (editor != null && editor.isShowing()) return;
        editor = new BottomSheetDialog(requireContext());
        LinearLayout content = dialogColumn();
        TextInputLayout field = new TextInputLayout(requireContext());
        field.setHint(getString(R.string.studio_function));
        TextInputEditText expression = new TextInputEditText(field.getContext());
        expression.setText(formula());
        expression.setTextSize(17);
        expression.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        expression.setSingleLine(true);
        expression.setShowSoftInputOnFocus(false);
        field.addView(expression);
        content.addView(field);
        MathKeyboardView keyboard = new MathKeyboardView(requireContext());
        keyboard.setTarget(expression);
        content.addView(keyboard);
        Runnable accept = () -> {
            input.setText(expression.getText());
            editor.dismiss();
        };
        keyboard.setOnConfirm(() -> { accept.run(); prepare(false); });
        LinearLayout actions = new LinearLayout(requireContext());
        MaterialButton draw = new MaterialButton(requireContext());
        draw.setText(R.string.studio_generate);
        draw.setOnClickListener(v -> { accept.run(); prepare(false); });
        MaterialButton listen = new MaterialButton(requireContext());
        listen.setText(R.string.studio_play);
        listen.setOnClickListener(v -> { accept.run(); prepare(true); });
        actions.addView(draw, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(listen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(actions);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(content);
        editor.setContentView(scroll);
        editor.show();
        editor.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        expression.requestFocus();
        expression.setSelection(expression.length());
    }

    private LinearLayout dialogColumn() {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        return column;
    }

    private TextInputEditText numberField(LinearLayout parent, int label, double value) {
        TextInputLayout field = new TextInputLayout(requireContext());
        field.setHint(getString(label));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.ROOT, "%s", value));
        field.addView(input);
        parent.addView(field);
        return input;
    }

    private double number(TextInputEditText input) {
        return Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
    }

    private void openSettings() {
        LinearLayout content = dialogColumn();
        TextInputEditText from = numberField(content, R.string.studio_x_min, xMin);
        TextInputEditText to = numberField(content, R.string.studio_x_max, xMax);
        TextInputEditText seconds = numberField(content, R.string.studio_duration, duration);
        TextView help = new TextView(requireContext());
        help.setText(R.string.studio_settings_help);
        content.addView(help);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(content);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.studio_settings)
                .setView(scroll)
                .setNegativeButton(R.string.studio_cancel, null)
                .setPositiveButton(R.string.studio_apply, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                double nextMin = number(from), nextMax = number(to), nextDuration = number(seconds);
                if (!Double.isFinite(nextMin) || !Double.isFinite(nextMax) || nextMax <= nextMin
                        || Math.abs(nextMin) > 100000 || Math.abs(nextMax) > 100000
                        || !Double.isFinite(nextDuration) || nextDuration < 2 || nextDuration > 30) {
                    throw new IllegalArgumentException();
                }
                xMin = nextMin;
                xMax = nextMax;
                duration = nextDuration;
                invalidateSignal();
                prepare(false);
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) {
                help.setText(R.string.studio_settings_error);
                help.setTextColor(0xFFB3261E);
            }
        }));
        dialog.show();
    }

    private final class TimelineRenderer extends LineChartRenderer {
        private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
        TimelineRenderer() {
            super(chart, chart.getAnimator(), chart.getViewPortHandler());
            cursor.setColor(TRACE_COLOR);
            cursor.setStrokeWidth(getResources().getDisplayMetrics().density);
        }

        @Override
        public void drawData(Canvas canvas) {
            if (progress < 0 || signal == null) {
                super.drawData(canvas);
                return;
            }
            RectF bounds = chart.getViewPortHandler().getContentRect();
            int ghost = canvas.saveLayerAlpha(bounds, 45);
            super.drawData(canvas);
            canvas.restoreToCount(ghost);
            MPPointD point = chart.getTransformer(YAxis.AxisDependency.LEFT)
                    .getPixelForValues(signal.xAtProgress(progress), 0);
            int clip = canvas.save();
            canvas.clipRect(bounds.left, bounds.top, (float) point.x, bounds.bottom);
            super.drawData(canvas);
            canvas.restoreToCount(clip);
            MPPointD.recycleInstance(point);
        }

        @Override
        public void drawExtras(Canvas canvas) {
            if (progress < 0 || signal == null) {
                super.drawExtras(canvas);
                return;
            }
            float value = signal.valueAtProgress(progress);
            MPPointD point = chart.getTransformer(YAxis.AxisDependency.LEFT)
                    .getPixelForValues(signal.xAtProgress(progress), Float.isFinite(value) ? value : 0);
            RectF bounds = chart.getViewPortHandler().getContentRect();
            int clip = canvas.save();
            canvas.clipRect(bounds);
            float x = (float) point.x, y = (float) point.y;
            cursor.setAlpha(100);
            canvas.drawLine(x, bounds.top, x, bounds.bottom, cursor);
            if (Float.isFinite(value)) {
                float radius = (float) ((5 + signal.accentAtProgress(progress) * 10) * cursor.getStrokeWidth());
                cursor.setAlpha(35);
                canvas.drawCircle(x, y, radius * 2, cursor);
                cursor.setAlpha(255);
                canvas.drawCircle(x, y, radius / 2, cursor);
            }
            canvas.restoreToCount(clip);
            cursor.setAlpha(255);
            MPPointD.recycleInstance(point);
        }
    }
}
