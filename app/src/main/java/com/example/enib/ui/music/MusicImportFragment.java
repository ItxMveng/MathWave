package com.example.enib.ui.music;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.enib.R;
import com.example.enib.domain.AudioAnalyzer;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicImportFragment extends Fragment {

    private MaterialButton             btnImport;
    private CircularProgressIndicator  progressIndicator;
    private TextView                   tvDetectedFunction;
    private TextView                   tvDominantFreq;
    private LineChart                  waveformChart;
    private LineChart                  spectrumChart;
    private View                       cardResult;
    private View                       cardImport;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent>  audioPickerLauncher;
    private ActivityResultLauncher<String>  permissionLauncher;

    // ─── Cycle de vie ────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Résultat du sélecteur de fichiers
        audioPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) analyzeAudio(uri);
                    }
                });

        // Résultat de la demande de permission
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        launchFilePicker();
                    } else {
                        showPermissionDeniedMessage();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_music_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);
        setupCharts();
        animateEntrance();

        btnImport.setOnClickListener(v -> checkPermissionAndLaunchPicker());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }

    // ─── Bind views ──────────────────────────────────────────────────────────

    private void bindViews(View view) {
        btnImport          = view.findViewById(R.id.btnImportAudio);
        progressIndicator  = view.findViewById(R.id.progressIndicator);
        tvDetectedFunction = view.findViewById(R.id.tvDetectedFunction);
        tvDominantFreq     = view.findViewById(R.id.tvDominantFreq);
        waveformChart      = view.findViewById(R.id.waveformChart);
        spectrumChart      = view.findViewById(R.id.spectrumChart);
        cardResult         = view.findViewById(R.id.cardResult);
        cardImport         = view.findViewById(R.id.cardImport);
    }

    // ─── Animation d'entrée ──────────────────────────────────────────────────

    private void animateEntrance() {
        if (cardImport != null) {
            cardImport.setAlpha(0f);
            cardImport.setTranslationY(60f);
            cardImport.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(100).start();
        }
        if (cardResult != null) {
            cardResult.setVisibility(View.GONE);
        }
    }

    // ─── Gestion des permissions ─────────────────────────────────────────────

    private void checkPermissionAndLaunchPicker() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(requireContext(), permission)
                == PackageManager.PERMISSION_GRANTED) {
            // Permission déjà accordée
            launchFilePicker();

        } else if (shouldShowRequestPermissionRationale(permission)) {
            // L'utilisateur a déjà refusé une fois — expliquer pourquoi c'est nécessaire
            new AlertDialog.Builder(requireContext())
                    .setTitle("Accès aux fichiers audio")
                    .setMessage("MathWave a besoin d'accéder à vos fichiers audio pour analyser leur forme d'onde et générer la fonction mathématique correspondante.")
                    .setPositiveButton("Autoriser", (dialog, which) -> permissionLauncher.launch(permission))
                    .setNegativeButton("Annuler", null)
                    .show();

        } else {
            // Première demande — Android affiche le dialogue système
            permissionLauncher.launch(permission);
        }
    }

    private void showPermissionDeniedMessage() {
        View v = getView();
        if (v == null) return;
        Snackbar.make(v, "Permission refusée — impossible d'accéder aux fichiers audio.",
                        Snackbar.LENGTH_LONG)
                .setAction("Paramètres", view -> openAppSettings())
                .setActionTextColor(0xFFFF6600)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    // ─── Sélecteur de fichiers ───────────────────────────────────────────────

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        audioPickerLauncher.launch(
                Intent.createChooser(intent, "Choisir un fichier audio"));
    }

    // ─── Analyse audio ───────────────────────────────────────────────────────

    private void analyzeAudio(Uri audioUri) {
        Activity activity = getActivity();
        if (activity == null) return;

        android.content.Context appCtx = activity.getApplicationContext();

        btnImport.setEnabled(false);
        progressIndicator.setVisibility(View.VISIBLE);
        if (cardResult != null) cardResult.setVisibility(View.GONE);

        executor.execute(() -> {
            AudioAnalyzer.AnalysisResult result = AudioAnalyzer.analyze(appCtx, audioUri);

            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                progressIndicator.setVisibility(View.GONE);
                btnImport.setEnabled(true);

                if (result.isSuccess) {
                    displayResult(result);
                } else {
                    View v = getView();
                    if (v != null)
                        Snackbar.make(v, result.errorMessage, Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }

    // ─── Affichage du résultat ───────────────────────────────────────────────

    private void displayResult(AudioAnalyzer.AnalysisResult result) {
        if (cardResult != null) {
            cardResult.setVisibility(View.VISIBLE);
            cardResult.setAlpha(0f);
            cardResult.animate().alpha(1f).setDuration(500).start();
        }

        tvDetectedFunction.setText(result.approximateFunction);
        tvDominantFreq.setText(String.format(
                "Fréquence dominante : %.1f Hz", result.dominantFrequency));

        drawChart(waveformChart, result.waveformPoints, "Amplitude(t)");
        drawChart(spectrumChart, result.spectrumPoints, "Spectre");
    }

    private void drawChart(LineChart chart, List<float[]> points, String label) {
        if (points == null || points.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        for (float[] pt : points) entries.add(new Entry(pt[0], pt[1]));

        LineDataSet ds = new LineDataSet(entries, label);
        ds.setColor(0xFFFF6600);
        ds.setLineWidth(1.5f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setDrawFilled(true);
        ds.setFillColor(0xFFFF6600);
        ds.setFillAlpha(25);

        chart.setData(new LineData(ds));
        chart.animateX(500);
        chart.invalidate();
    }

    // ─── Config des graphiques ───────────────────────────────────────────────

    private void setupCharts() {
        configureChart(waveformChart, "Forme d'onde");
        configureChart(spectrumChart, "Spectre fréquentiel");
    }

    private void configureChart(LineChart chart, String noDataText) {
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(0xFFE0E0E0);
        xAxis.setTextColor(0xFF666666);

        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(0xFFE0E0E0);
        chart.getAxisLeft().setTextColor(0xFF666666);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setEnabled(true);
        chart.setNoDataText(noDataText);
        chart.setNoDataTextColor(0xFF9E9E9E);
    }
}
