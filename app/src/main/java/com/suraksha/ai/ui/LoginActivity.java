package com.suraksha.ai.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import com.suraksha.ai.R;
import com.suraksha.ai.databinding.ActivityLoginBinding;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.PrefsManager;

/**
 * LoginActivity — Email/Password authentication only.
 * Phone OTP has been removed; signup still collects a phone number
 * (stored for live-tracking) but authentication is purely email-based.
 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth auth;
    private PrefsManager prefs;

    // State
    private boolean isSignup          = true;
    private boolean isPasswordVisible = false;

    // Animation state
    private ValueAnimator shieldPulseAnimator;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth  = FirebaseAuth.getInstance();
        prefs = new PrefsManager(this);

        prepareForEntryAnimation();

        setupModeToggle();
        setupButtons();
        setupPasswordToggle();
        setupForgotPassword();
        renderUI();

        binding.getRoot().post(this::runEntryAnimation);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startShieldPulse();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopShieldPulse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTRY ANIMATION
    // ─────────────────────────────────────────────────────────────────────

    private void prepareForEntryAnimation() {
        binding.tvShield.setAlpha(0f);
        binding.tvShield.setScaleX(0.2f);
        binding.tvShield.setScaleY(0.2f);

        binding.tvTitle.setAlpha(0f);
        binding.tvTitle.setTranslationY(dpToPx(30));

        binding.tvSubtitle.setAlpha(0f);
        binding.tvSubtitle.setTranslationY(dpToPx(30));

        binding.loginCard.setAlpha(0f);
        binding.loginCard.setTranslationY(dpToPx(50));
    }

    private void runEntryAnimation() {
        binding.tvShield.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(600).setStartDelay(100)
                .setInterpolator(new OvershootInterpolator(1.8f))
                .start();

        binding.tvTitle.animate()
                .alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(350)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();

        binding.tvSubtitle.animate()
                .alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(450)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();

        binding.loginCard.animate()
                .alpha(1f).translationY(0f)
                .setDuration(550).setStartDelay(550)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(this::startShieldPulse)
                .start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SHIELD PULSE
    // ─────────────────────────────────────────────────────────────────────

    private void startShieldPulse() {
        if (shieldPulseAnimator != null && shieldPulseAnimator.isRunning()) return;

        shieldPulseAnimator = ValueAnimator.ofFloat(1.0f, 1.10f, 1.0f);
        shieldPulseAnimator.setDuration(2200);
        shieldPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shieldPulseAnimator.setRepeatMode(ValueAnimator.RESTART);
        shieldPulseAnimator.setInterpolator(new FastOutSlowInInterpolator());
        shieldPulseAnimator.addUpdateListener(anim -> {
            float s = (float) anim.getAnimatedValue();
            binding.tvShield.setScaleX(s);
            binding.tvShield.setScaleY(s);
        });
        shieldPulseAnimator.start();
    }

    private void stopShieldPulse() {
        if (shieldPulseAnimator != null) {
            shieldPulseAnimator.cancel();
            shieldPulseAnimator = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIELD TRANSITION ANIMATIONS
    // ─────────────────────────────────────────────────────────────────────

    private void animateIn(View view) {
        if (view.getVisibility() == View.VISIBLE) return;
        view.setAlpha(0f);
        view.setTranslationY(dpToPx(16));
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f).translationY(0f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
    }

    private void animateOut(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        view.animate()
                .alpha(0f).translationY(-dpToPx(10))
                .setDuration(180)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                    view.setTranslationY(0f);
                })
                .start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SHAKE / BUTTON PRESS
    // ─────────────────────────────────────────────────────────────────────

    private void shake(View view) {
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.shake);
        view.startAnimation(anim);
    }

    private void animateButtonPress(View button, Runnable onComplete) {
        button.animate()
                .scaleX(0.95f).scaleY(0.95f)
                .setDuration(80)
                .withEndAction(() ->
                        button.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(120)
                                .setInterpolator(new OvershootInterpolator(2f))
                                .withEndAction(onComplete)
                                .start())
                .start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOGGLE: Login ↔ Signup
    // ─────────────────────────────────────────────────────────────────────

    private void setupModeToggle() {
        binding.btnSignup.setOnClickListener(v -> {
            if (isSignup) return;
            animateButtonPress(v, () -> {
                isSignup = true;
                renderUI();
                animateHeaderTextChange();
            });
        });

        binding.btnLogin.setOnClickListener(v -> {
            if (!isSignup) return;
            animateButtonPress(v, () -> {
                isSignup = false;
                renderUI();
                animateHeaderTextChange();
            });
        });
    }

    private void animateHeaderTextChange() {
        binding.tvTitle.animate()
                .alpha(0f).setDuration(120)
                .withEndAction(() -> {
                    binding.tvTitle.setText(isSignup ? "Create Account" : "Welcome Back");
                    binding.tvTitle.animate().alpha(1f).setDuration(200).start();
                }).start();

        binding.tvSubtitle.animate()
                .alpha(0f).setDuration(120)
                .withEndAction(() -> {
                    binding.tvSubtitle.setText(isSignup
                            ? "Sign up to stay safe with Suraksha AI"
                            : "Login to continue protecting yourself");
                    binding.tvSubtitle.animate().alpha(1f).setDuration(200).start();
                }).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PASSWORD EYE TOGGLE
    // ─────────────────────────────────────────────────────────────────────

    private void setupPasswordToggle() {
        binding.btnTogglePassword.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;
            v.animate().scaleX(0f).setDuration(100).withEndAction(() -> {
                if (isPasswordVisible) {
                    binding.etPassword.setTransformationMethod(
                            HideReturnsTransformationMethod.getInstance());
                    binding.btnTogglePassword.setAlpha(1.0f);
                } else {
                    binding.etPassword.setTransformationMethod(
                            PasswordTransformationMethod.getInstance());
                    binding.btnTogglePassword.setAlpha(0.4f);
                }
                v.animate().scaleX(1f).setDuration(100).start();
            }).start();
            binding.etPassword.setSelection(binding.etPassword.getText().length());
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // FORGOT PASSWORD
    // ─────────────────────────────────────────────────────────────────────

    private void setupForgotPassword() {
        binding.tvForgotPassword.setOnClickListener(v -> {
            String email = binding.etEmail.getText().toString().trim();
            if (TextUtils.isEmpty(email) || !email.contains("@")) {
                binding.etEmail.setError("Enter your email first");
                binding.etEmail.requestFocus();
                shake(binding.layoutEmail);
                return;
            }
            setLoading(true);
            auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener(unused -> {
                        setLoading(false);
                        Toast.makeText(this, "Password reset email sent to " + email,
                                Toast.LENGTH_LONG).show();
                    })
                    .addOnFailureListener(e -> {
                        setLoading(false);
                        Toast.makeText(this, "Failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // RENDER UI STATE
    // ─────────────────────────────────────────────────────────────────────

    private void renderUI() {

        // Toggle button background — swap the blue pill
        binding.btnSignup.setBackgroundResource(
                isSignup ? R.drawable.bg_toggle_selected : android.R.color.transparent);
        binding.btnLogin.setBackgroundResource(
                isSignup ? android.R.color.transparent : R.drawable.bg_toggle_selected);

        binding.btnSignup.setTextColor(isSignup ? 0xFFFFFFFF : 0x99FFFFFF);
        binding.btnLogin.setTextColor(isSignup  ? 0x99FFFFFF : 0xFFFFFFFF);

        // Header text
        binding.tvTitle.setText(isSignup ? "Create Account" : "Welcome Back");
        binding.tvSubtitle.setText(isSignup
                ? "Sign up to stay safe with Suraksha AI"
                : "Login to continue protecting yourself");

        // ── Show / hide fields ──────────────────────────────────────────

        // Name + Phone — signup only
        if (isSignup) {
            animateIn(binding.layoutName);
            animateIn(binding.layoutPhoneForEmail);
        } else {
            animateOut(binding.layoutName);
            animateOut(binding.layoutPhoneForEmail);
        }

        // Email + Password — always visible
        binding.layoutEmail.setVisibility(View.VISIBLE);
        binding.layoutPassword.setVisibility(View.VISIBLE);

        // Forgot password — login only
        if (!isSignup) animateIn(binding.tvForgotPassword);
        else           animateOut(binding.tvForgotPassword);

        // Action button
        binding.btnAction.setVisibility(View.VISIBLE);
        binding.btnAction.setText(isSignup ? "Sign Up" : "Login");

        // Reset password state
        isPasswordVisible = false;
        binding.etPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
        binding.btnTogglePassword.setAlpha(0.4f);
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUTTONS
    // ─────────────────────────────────────────────────────────────────────

    private void setupButtons() {
        binding.btnAction.setOnClickListener(v ->
                animateButtonPress(v, this::handleEmailAuth));
    }

    // ─────────────────────────────────────────────────────────────────────
    // EMAIL / PASSWORD FLOW
    // ─────────────────────────────────────────────────────────────────────

    private void handleEmailAuth() {
        String name     = binding.etName.getText().toString().trim();
        String phone    = binding.etPhoneForEmail.getText().toString().trim();
        String email    = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (isSignup && TextUtils.isEmpty(name)) {
            binding.etName.setError("Name required");
            shake(binding.layoutName);
            return;
        }
        if (isSignup && TextUtils.isEmpty(phone)) {
            binding.etPhoneForEmail.setError("Phone required for tracking");
            shake(binding.layoutPhoneForEmail);
            return;
        }
        if (TextUtils.isEmpty(email) || !email.contains("@")) {
            binding.etEmail.setError("Valid email required");
            shake(binding.layoutEmail);
            return;
        }
        if (password.length() < 6) {
            binding.etPassword.setError("Minimum 6 characters");
            shake(binding.layoutPassword);
            return;
        }

        setLoading(true);

        if (isSignup) {
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        if (user == null) { setLoading(false); return; }
                        user.updateProfile(new UserProfileChangeRequest.Builder()
                                .setDisplayName(name).build());
                        prefs.setMyName(name);
                        prefs.setMyPhone(phone);
                        FirebaseLocationHelper.registerPhone(user.getUid(), phone);
                        setLoading(false);
                        goToMain();
                    })
                    .addOnFailureListener(e -> {
                        setLoading(false);
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        } else {
            auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        setLoading(false);
                        goToMain();
                    })
                    .addOnFailureListener(e -> {
                        setLoading(false);
                        shake(binding.loginCard);
                        Toast.makeText(this, "Login failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnAction.setEnabled(!loading);
        binding.btnAction.setAlpha(loading ? 0.6f : 1.0f);
        binding.btnSignup.setEnabled(!loading);
        binding.btnLogin.setEnabled(!loading);
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
