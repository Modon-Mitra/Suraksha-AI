package com.suraksha.ai.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import com.suraksha.ai.R;

/**
 * SplashActivity — renders the branded intro animation via {@link SplashView}
 * (a pure-Canvas/ValueAnimator replica of the original video), then routes
 * based on Firebase auth state.
 *
 * Why replace the VideoView?
 *   • No codec dependency — works on every device / API level.
 *   • No res/raw asset needed — smaller APK.
 *   • Perfectly sharp on every DPI / aspect ratio.
 *   • Instant start — no buffering / prepare delay.
 */
public class SplashActivity extends AppCompatActivity {

    // Safety net timeout (slightly longer than the animation's 3 500 ms)
    private static final long MAX_SPLASH_MS = 4_500L;

    private SplashView splashView;
    private boolean    navigated = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // True full-screen (hides status bar + nav bar)
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_splash);

        splashView = findViewById(R.id.splash_view);

        // Hide system UI for an immersive feel
        splashView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // Start the animation; navigate when it finishes naturally
        splashView.startAnimation(this::goNext);

        // Absolute safety-net timeout
        handler.postDelayed(this::goNext, MAX_SPLASH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing to pause for a Canvas animator; the ValueAnimator keeps
        // ticking but the view won't be drawn — harmless.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (splashView != null) splashView.stopAnimation();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /** Called exactly once — either by the animation callback or the timeout. */
    private void goNext() {
        if (navigated) return;
        navigated = true;
        handler.removeCallbacksAndMessages(null);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        Intent next = (currentUser != null)
                ? new Intent(this, MainActivity.class)
                : new Intent(this, LoginActivity.class);

        startActivity(next);
        overridePendingTransition(0, 0);   // seamless hand-off
        finish();
    }
}
