package com.suraksha.ai.ui;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.suraksha.ai.R;
import com.suraksha.ai.databinding.ActivityFakeCallBinding;

import java.util.Locale;

/**
 * FakeCallActivity — full-screen fake incoming call with TTS voice.
 *
 * Audio routing:
 *   Default = EARPIECE (phone speaker, held to ear — looks most real)
 *   Toggle  = LOUDSPEAKER (hands-free, easier to hear)
 *
 * The 🔈/🔊 button switches between the two during the call.
 */
public class FakeCallActivity extends AppCompatActivity {

    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_LANGUAGE    = "language";

    private static final int    AUTO_DISMISS_MS = 45_000;
    private static final long[] VIBRATE_PATTERN = {0, 1000, 1000};

    private ActivityFakeCallBinding binding;
    private Vibrator                vibrator;
    private TextToSpeech            tts;
    private AudioManager            audioManager;
    private final Handler           handler      = new Handler(Looper.getMainLooper());
    private boolean                 callAnswered = false;
    private boolean                 ttsReady     = false;

    // Audio routing — false = earpiece (default), true = loudspeaker
    private boolean speakerOn = false;
    private int     savedAudioMode;

    // Auto-dismiss timer for the unanswered incoming call (cancelled on answer)
    private Runnable autoDismissRunnable;

    private String    callerName;
    private String    language;
    private boolean   isMale;
    private String[][] script;
    private int       scriptLine = 0;

    private int      secondsElapsed = 0;
    private Runnable timerRunnable;

    private android.animation.ValueAnimator pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityFakeCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        savedAudioMode = audioManager != null ? audioManager.getMode() : AudioManager.MODE_NORMAL;

        callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        language   = getIntent().getStringExtra(EXTRA_LANGUAGE);
        if (callerName == null || callerName.isEmpty()) callerName = "Mom";
        if (language   == null || language.isEmpty())   language   = FakeCallScript.LANG_ENGLISH;

        isMale = FakeCallScript.isMale(callerName);
        script = FakeCallScript.getScript(language, isMale, callerName);

        binding.tvAvatar.setText(isMale ? "👨" : "👩");
        binding.tvCallerName.setText(callerName);
        binding.tvCallStatus.setText("Incoming call...");

        setupButtons();
        startVibration();
        startPulseAnimation();
        initTts();

        // Auto-dismiss only the UNANSWERED call (cancelled when answered)
        autoDismissRunnable = this::finishCall;
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS);
    }

    // ── Pulse ring animation ──────────────────────────────────────────────

    private void startPulseAnimation() {
        pulseAnimator = android.animation.ValueAnimator.ofFloat(1.0f, 1.5f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(android.animation.ValueAnimator.RESTART);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(anim -> {
            float scale = (float) anim.getAnimatedValue();
            float alpha = 1.0f - ((scale - 1.0f) / 0.5f);
            binding.pulseRing.setScaleX(scale);
            binding.pulseRing.setScaleY(scale);
            binding.pulseRing.setAlpha(alpha * 0.6f);
        });
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            binding.pulseRing.setVisibility(View.GONE);
            binding.glowBg.setVisibility(View.GONE);
        }
    }

    // ── Call timer ────────────────────────────────────────────────────────

    private void startCallTimer() {
        secondsElapsed = 0;
        binding.tvTimer.setVisibility(View.VISIBLE);
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                secondsElapsed++;
                int min = secondsElapsed / 60;
                int sec = secondsElapsed % 60;
                binding.tvTimer.setText(
                        String.format(Locale.getDefault(), "%02d:%02d", min, sec));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timerRunnable);
    }

    // ── TTS setup ─────────────────────────────────────────────────────────

    private void initTts() {
        Locale locale = FakeCallScript.getTtsLocale(language);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(locale);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("en", "IN"));
                }
                tts.setPitch(isMale ? 0.6f : 1.25f);
                tts.setSpeechRate(isMale ? 0.85f : 1.0f);
                ttsReady = true;
            } else {
                Toast.makeText(this, "Voice not available on this device",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Audio routing ─────────────────────────────────────────────────────

    /** Route audio to earpiece (phone speaker held to ear). */
    private void routeToEarpiece() {
        if (audioManager == null) return;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Modern API (Android 12+)
            setCommunicationDeviceByType(
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        } else {
            audioManager.setSpeakerphoneOn(false);
        }
        speakerOn = false;
        updateSpeakerIcon();
    }

    /** Route audio to loudspeaker (hands-free). */
    private void routeToLoudspeaker() {
        if (audioManager == null) return;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Modern API (Android 12+) — setSpeakerphoneOn is ignored here
            setCommunicationDeviceByType(
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        } else {
            audioManager.setSpeakerphoneOn(true);
        }
        speakerOn = true;
        updateSpeakerIcon();
    }

    /** Finds and activates a communication device of the given type (API 31+). */
    private void setCommunicationDeviceByType(int deviceType) {
        try {
            java.util.List<android.media.AudioDeviceInfo> devices =
                    audioManager.getAvailableCommunicationDevices();
            for (android.media.AudioDeviceInfo device : devices) {
                if (device.getType() == deviceType) {
                    audioManager.setCommunicationDevice(device);
                    return;
                }
            }
            // Fallback if exact type not found
            audioManager.setSpeakerphoneOn(
                    deviceType == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        } catch (Exception e) {
            audioManager.setSpeakerphoneOn(
                    deviceType == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        }
    }

    private void toggleSpeaker() {
        if (speakerOn) routeToEarpiece();
        else           routeToLoudspeaker();
    }

    private void updateSpeakerIcon() {
        // btnSpeaker is the icon TextView itself in the control grid
        binding.btnSpeaker.setText(speakerOn ? "🔊" : "🔈");
        binding.tvSpeakerLabel.setText(speakerOn ? "Speaker" : "Speaker");
        // Highlight the button when speaker is on (white bg) vs off (translucent)
        binding.btnSpeaker.setBackgroundResource(
                speakerOn ? R.drawable.bg_call_control_active
                        : R.drawable.bg_call_control);
    }

    /** Build TTS params that force playback through the voice-call stream. */
    private android.os.Bundle ttsVoiceCallParams() {
        android.os.Bundle params = new android.os.Bundle();
        // Route TTS through the voice-call stream so earpiece/speaker routing applies
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_VOICE_CALL);
        return params;
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private void setupButtons() {
        binding.btnAnswer.setOnClickListener(v -> {
            callAnswered = true;

            // Cancel the unanswered-call auto-dismiss so the script can finish
            if (autoDismissRunnable != null) {
                handler.removeCallbacks(autoDismissRunnable);
                autoDismissRunnable = null;
            }

            stopVibration();
            stopPulseAnimation();

            binding.tvCallStatus.setText("Connected");
            binding.btnAnswer.setVisibility(View.GONE);
            binding.btnDecline.setVisibility(View.GONE);
            binding.btnHangup.setVisibility(View.VISIBLE);
            binding.callControlsGrid.setVisibility(View.VISIBLE);

            // Default to EARPIECE (looks most real — phone held to ear)
            routeToEarpiece();

            startCallTimer();
            handler.postDelayed(this::speakNextLine, 800);
            // Safety timeout — end after 5 minutes (longer than any script)
            handler.postDelayed(this::finishCall, 5 * 60 * 1000L);
        });

        binding.btnDecline.setOnClickListener(v -> finishCall());
        binding.btnHangup.setOnClickListener(v -> finishCall());
        binding.btnSpeaker.setOnClickListener(v -> toggleSpeaker());
    }

    // ── TTS script playback ───────────────────────────────────────────────

    private void speakNextLine() {
        if (!callAnswered || !ttsReady || scriptLine >= script.length) return;

        String[] line    = script[scriptLine];
        long     delayMs = Long.parseLong(line[0]);
        String   text    = line[1];
        scriptLine++;

        handler.postDelayed(() -> {
            if (!callAnswered) return;

            runOnUiThread(() -> binding.tvCallStatus.setText("● ● ●"));

            // Speak through the voice-call stream so earpiece routing works
            String utteranceId = "line_" + scriptLine;
            tts.speak(text, TextToSpeech.QUEUE_ADD,
                    ttsVoiceCallParams(), utteranceId);

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}

                @Override
                public void onDone(String id) {
                    runOnUiThread(() -> {
                        binding.tvCallStatus.setText("Connected");
                        handler.postDelayed(
                                FakeCallActivity.this::speakNextLine, 3000);
                    });
                }

                @Override
                public void onError(String id) {
                    runOnUiThread(FakeCallActivity.this::speakNextLine);
                }
            });

        }, delayMs);
    }

    // ── Vibration ─────────────────────────────────────────────────────────

    private void startVibration() {
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VIBRATE_PATTERN, 0);
        }
    }

    private void stopVibration() {
        if (vibrator != null) vibrator.cancel();
    }

    // ── Finish ────────────────────────────────────────────────────────────

    private void finishCall() {
        handler.removeCallbacksAndMessages(null);
        stopVibration();
        stopPulseAnimation();

        // Restore audio mode
        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                }
                audioManager.setSpeakerphoneOn(false);
            } catch (Exception ignored) {}
            audioManager.setMode(savedAudioMode);
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        finishCall();
        super.onDestroy();
    }
}
