package com.example.enib;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final long ANIM_LOGO_DURATION    = 750L;
    private static final long ANIM_TITLE_DURATION   = 500L;
    private static final long ANIM_DIVIDER_DURATION = 400L;
    private static final long ANIM_SUB_DURATION     = 400L;
    private static final long SPLASH_TOTAL          = 2800L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Plein écran, barre de statut de la couleur du fond
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0xFFFF6600);
        getWindow().setNavigationBarColor(0xFFB34700);

        setContentView(R.layout.activity_splash);

        FrameLayout logoContainer = findViewById(R.id.ivLogoContainer);
        TextView     tvTitle      = findViewById(R.id.tvSplashTitle);
        android.view.View vDivider = findViewById(R.id.vSplashDivider);
        TextView     tvSubtitle   = findViewById(R.id.tvSplashSubtitle);

        playAnimations(logoContainer, tvTitle, vDivider, tvSubtitle);
    }

    private void playAnimations(FrameLayout logoContainer,
                                TextView tvTitle,
                                android.view.View vDivider,
                                TextView tvSubtitle) {

        // ── Logo container : apparaît depuis 0 avec rebond ──────────────────
        logoContainer.setScaleX(0.15f);
        logoContainer.setScaleY(0.15f);
        logoContainer.setAlpha(0f);
        logoContainer.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(ANIM_LOGO_DURATION)
                .setInterpolator(new OvershootInterpolator(1.8f))
                .start();

        // ── Titre : glisse depuis le bas ─────────────────────────────────────
        tvTitle.setTranslationY(90f);
        tvTitle.setAlpha(0f);
        tvTitle.animate()
                .translationY(0f).alpha(1f)
                .setDuration(ANIM_TITLE_DURATION)
                .setStartDelay(450)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();

        // ── Séparateur : élargit depuis le centre ────────────────────────────
        vDivider.setScaleX(0f);
        vDivider.setAlpha(0f);
        vDivider.animate()
                .scaleX(1f).alpha(1f)
                .setDuration(ANIM_DIVIDER_DURATION)
                .setStartDelay(650)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // ── Sous-titre : fondu + translation ────────────────────────────────
        tvSubtitle.setTranslationY(50f);
        tvSubtitle.setAlpha(0f);
        tvSubtitle.animate()
                .translationY(0f).alpha(1f)
                .setDuration(ANIM_SUB_DURATION)
                .setStartDelay(750)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // ── Transition vers MainActivity ─────────────────────────────────────
        logoContainer.postDelayed(this::launchMain, SPLASH_TOTAL);
    }

    private void launchMain() {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
