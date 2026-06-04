package com.suraksha.ai.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.suraksha.ai.agent.AgentOrchestrator;
import com.suraksha.ai.agent.auth.VoiceAuthManager;
import com.suraksha.ai.databinding.ActivityCodeWordSetupBinding;
import com.suraksha.ai.utils.PrefsManager;

import java.util.ArrayList;
import java.util.Locale;

/**
 * CodeWordSetupActivity — guides the user through enrolling their code word.
 *
 * IMPORTANT — MICROPHONE COORDINATION:
 *  The background scream-detection (ThreatDetectionAgent) holds the mic
 *  continuously. A phone has only one mic, so before this screen's
 *  SpeechRecognizer can listen, we must release the mic by pausing the
 *  passive audio monitoring. We resume it when leaving this screen.
 */
public class CodeWordSetupActivity extends AppCompatActivity {

    private static final String TAG = "CodeWordSetupActivity";
    private static final int REQUEST_RECORD_AUDIO = 301;
    private static final int TOTAL_STEPS          = 3;

    private ActivityCodeWordSetupBinding binding;
    private PrefsManager                  prefs;
    private VoiceAuthManager              voiceAuthManager;
    private SpeechRecognizer              recognizer;
    private final Handler                 handler = new Handler(Looper.getMainLooper());

    private int     currentStep    = 0;
    private String  capturedWord   = null;   // word from step 1
    private boolean isListening    = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCodeWordSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs            = new PrefsManager(this);
        voiceAuthManager = new VoiceAuthManager(this);

        // Release the mic from background scream detection so our
        // SpeechRecognizer can use it while this screen is open.
        try {
            AgentOrchestrator.getInstance(this).stopPassiveMonitoring();
            Log.i(TAG, "Paused passive audio monitoring to free the mic");
        } catch (Exception e) {
            Log.w(TAG, "Could not pause passive monitoring: " + e.getMessage());
        }

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnRecord.setOnClickListener(v -> onRecordTapped());
        binding.btnReset.setOnClickListener(v -> resetSetup());

        updateUI();
    }

    // ── Record button tapped ──────────────────────────────────────────────

    private void onRecordTapped() {
        if (isListening) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer NOT available on this device");
            setStatus("Speech recognition isn't available on this phone. "
                    + "Please install/enable Google app.", false);
            return;
        }

        // Make sure the mic is free (pause scream detection again, just in case)
        try {
            AgentOrchestrator.getInstance(this).stopPassiveMonitoring();
        } catch (Exception ignored) {}

        // Give the system a moment to release the mic, then start listening
        handler.postDelayed(this::startListening, 350);
    }

    // ── SpeechRecognizer ─────────────────────────────────────────────────

    private void startListening() {
        isListening = true;
        binding.btnRecord.setEnabled(false);
        binding.tvMicStatus.setText("Listening...");
        binding.tvHeard.setText("");
        startPulseAnimation();

        Log.i(TAG, "Starting SpeechRecognizer for code word capture");

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                stopPulseAnimation();
                binding.btnRecord.setEnabled(true);

                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches == null || matches.isEmpty()) {
                    setStatus("Couldn't hear that. Please try again.", false);
                    return;
                }

                String spoken = matches.get(0).toLowerCase(Locale.getDefault()).trim();
                Log.i(TAG, "Heard: " + spoken);
                onWordSpoken(spoken);
            }

            @Override
            public void onError(int error) {
                isListening = false;
                stopPulseAnimation();
                binding.btnRecord.setEnabled(true);
                binding.tvMicStatus.setText("Ready");

                String codeName = errorToString(error);
                Log.e(TAG, "SpeechRecognizer error: " + codeName + " (" + error + ")");

                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        msg = "Couldn't understand. Speak clearly and try again.";
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        msg = "Mic is busy. Wait a moment and tap again.";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        msg = "Microphone permission needed.";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        msg = "Microphone unavailable. Close other apps using it.";
                        break;
                    default:
                        msg = "Microphone error (" + codeName + "). Please try again.";
                }
                setStatus(msg, false);
            }

            @Override public void onReadyForSpeech(Bundle p)   { binding.tvMicStatus.setText("Say your code word..."); Log.i(TAG, "onReadyForSpeech"); }
            @Override public void onBeginningOfSpeech()         { binding.tvMicStatus.setText("Hearing you..."); }
            @Override public void onEndOfSpeech()               { binding.tvMicStatus.setText("Processing..."); }
            @Override public void onRmsChanged(float v)         {}
            @Override public void onBufferReceived(byte[] b)    {}
            @Override public void onPartialResults(Bundle b)    {}
            @Override public void onEvent(int t, Bundle b)      {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "startListening threw: " + e.getMessage());
            isListening = false;
            stopPulseAnimation();
            binding.btnRecord.setEnabled(true);
            setStatus("Could not start microphone. Please try again.", false);
        }
    }

    // ── Word processing logic ─────────────────────────────────────────────

    private void onWordSpoken(String spoken) {
        currentStep++;
        binding.tvHeard.setText("\" " + spoken + " \"");

        if (currentStep == 1) {
            capturedWord = spoken;
            setStatus("✓ Got it! Say the same word again to confirm.", true);
            updateStepIndicator();

        } else if (currentStep == 2) {
            if (wordsMatch(spoken, capturedWord)) {
                setStatus("✓ Confirmed! Say it one more time to train your voice.", true);
                updateStepIndicator();
            } else {
                setStatus("That didn't match. Try again from the beginning.", false);
                currentStep = 0;
                capturedWord = null;
                updateStepIndicator();
            }

        } else if (currentStep == 3) {
            if (wordsMatch(spoken, capturedWord)) {
                setStatus("✓ Perfect! Training your voice now...", true);
                updateStepIndicator();
                enrollVoiceAndSave();
            } else {
                setStatus("That didn't match. Try again from the beginning.", false);
                currentStep = 0;
                capturedWord = null;
                updateStepIndicator();
            }
        }

        binding.tvMicStatus.setText("Ready");
    }

    // ── Voice enrollment + save ───────────────────────────────────────────

    private void enrollVoiceAndSave() {
        binding.btnRecord.setEnabled(false);
        binding.btnRecord.setText("Saving...");

        voiceAuthManager.enrollVoicePrint(new VoiceAuthManager.VoiceEnrollCallback() {
            @Override
            public void onEnrolled() {
                prefs.setCodeWord(capturedWord);
                prefs.setCodeWordEnabled(true);

                runOnUiThread(() -> {
                    binding.tvTitle.setText("Code Word Set! ✓");
                    binding.tvSubtitle.setText(
                            "Your code word \"" + capturedWord + "\" is saved.\n"
                                    + "Suraksha will now recognise your voice automatically.");
                    binding.tvMicIcon.setText("✅");
                    binding.tvMicStatus.setText("Active");
                    binding.btnRecord.setText("Done");
                    binding.btnRecord.setEnabled(true);
                    binding.btnRecord.setBackgroundTintList(
                            getColorStateList(com.suraksha.ai.R.color.safe_green));
                    binding.btnRecord.setOnClickListener(v -> {
                        Toast.makeText(CodeWordSetupActivity.this,
                                "Code word activated! Say \"" + capturedWord
                                        + "\" anytime to activate Suraksha.",
                                Toast.LENGTH_LONG).show();
                        finish();
                    });
                    binding.btnReset.setVisibility(android.view.View.VISIBLE);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    prefs.setCodeWord(capturedWord);
                    prefs.setCodeWordEnabled(true);

                    binding.btnRecord.setEnabled(true);
                    binding.btnRecord.setText("Done");
                    setStatus("Code word saved! Voice training had an issue but "
                            + "text detection is active.", true);
                    binding.btnRecord.setOnClickListener(v -> finish());
                });
            }
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void updateUI() {
        switch (currentStep) {
            case 0:
                binding.tvTitle.setText("Your Secret Code Word");
                binding.tvSubtitle.setText(
                        "Say your code word clearly 3 times so Suraksha learns "
                                + "both the word and your voice.");
                binding.btnRecord.setText("🎤  Say Code Word");
                break;
            case 1:
                binding.btnRecord.setText("🎤  Say It Again");
                break;
            case 2:
                binding.btnRecord.setText("🎤  One More Time");
                break;
        }
        updateStepIndicator();
    }

    private void updateStepIndicator() {
        int activeColor   = getColor(com.suraksha.ai.R.color.brand_blue);
        int doneColor     = getColor(com.suraksha.ai.R.color.safe_green);
        int inactiveColor = getColor(com.suraksha.ai.R.color.bg_card);
        int darkText      = getColor(com.suraksha.ai.R.color.bg_dark);
        int lightText     = getColor(com.suraksha.ai.R.color.text_secondary);

        if (currentStep >= 1) {
            binding.tvStep1.setBackgroundColor(doneColor);
            binding.tvStep1.setTextColor(darkText);
        } else {
            binding.tvStep1.setBackgroundColor(activeColor);
            binding.tvStep1.setTextColor(darkText);
        }

        if (currentStep >= 2) {
            binding.tvStep2.setBackgroundColor(doneColor);
            binding.tvStep2.setTextColor(darkText);
        } else if (currentStep == 1) {
            binding.tvStep2.setBackgroundColor(activeColor);
            binding.tvStep2.setTextColor(darkText);
        } else {
            binding.tvStep2.setBackgroundColor(inactiveColor);
            binding.tvStep2.setTextColor(lightText);
        }

        if (currentStep >= 3) {
            binding.tvStep3.setBackgroundColor(doneColor);
            binding.tvStep3.setTextColor(darkText);
        } else if (currentStep == 2) {
            binding.tvStep3.setBackgroundColor(activeColor);
            binding.tvStep3.setTextColor(darkText);
        } else {
            binding.tvStep3.setBackgroundColor(inactiveColor);
            binding.tvStep3.setTextColor(lightText);
        }
    }

    private void setStatus(String message, boolean success) {
        binding.tvStatus.setText(message);
        binding.tvStatus.setTextColor(getColor(
                success ? com.suraksha.ai.R.color.safe_green
                        : com.suraksha.ai.R.color.warning_yellow));
    }

    private void resetSetup() {
        currentStep  = 0;
        capturedWord = null;
        binding.tvHeard.setText("");
        binding.tvMicIcon.setText("🎤");
        binding.btnRecord.setText("🎤  Say Code Word");
        binding.btnRecord.setEnabled(true);
        binding.btnRecord.setOnClickListener(v -> onRecordTapped());
        binding.btnReset.setVisibility(android.view.View.GONE);
        binding.btnRecord.setBackgroundTintList(
                getColorStateList(com.suraksha.ai.R.color.brand_blue));
        setStatus("Tap the button below and say your code word", false);
        updateStepIndicator();
    }

    // ── Pulse animation ───────────────────────────────────────────────────

    private void startPulseAnimation() {
        AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.3f);
        pulse.setDuration(600);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        binding.tvMicIcon.startAnimation(pulse);
    }

    private void stopPulseAnimation() {
        binding.tvMicIcon.clearAnimation();
    }

    // ── Word matching ─────────────────────────────────────────────────────

    private boolean wordsMatch(String a, String b) {
        if (a == null || b == null) return false;
        a = a.toLowerCase().trim();
        b = b.toLowerCase().trim();
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        String[] aWords = a.split("\\s+");
        String[] bWords = b.split("\\s+");
        if (aWords.length > 1 && bWords.length > 1) {
            int matches = 0;
            for (String wa : aWords) {
                for (String wb : bWords) {
                    if (wa.equals(wb)) matches++;
                }
            }
            return matches >= Math.min(aWords.length, bWords.length) - 1;
        }
        return false;
    }

    private String errorToString(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:                    return "audio";
            case SpeechRecognizer.ERROR_CLIENT:                   return "client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "no_permission";
            case SpeechRecognizer.ERROR_NETWORK:                  return "network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:          return "net_timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:                 return "no_match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:          return "busy";
            case SpeechRecognizer.ERROR_SERVER:                   return "server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:           return "speech_timeout";
            default:                                              return "unknown_" + error;
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            handler.postDelayed(this::startListening, 350);
        } else {
            Toast.makeText(this,
                    "Microphone permission needed to record code word",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        // Resume background scream detection now that we've released the mic
        // (only if code word detection isn't taking over the mic itself)
        try {
            if (!prefs.isCodeWordEnabled()) {
                AgentOrchestrator.getInstance(this).startPassiveMonitoring();
            }
        } catch (Exception ignored) {}
    }
}
