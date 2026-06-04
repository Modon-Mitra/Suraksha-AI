package com.suraksha.ai.agent.trigger;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.suraksha.ai.agent.auth.VoiceAuthManager;
import com.suraksha.ai.utils.PrefsManager;

import java.util.ArrayList;
import java.util.Locale;

/**
 * CodeWordDetector — listens continuously for the user's secret code word.
 *
 * ── HOW IT WORKS ─────────────────────────────────────────────────────────
 *  Uses Android's SpeechRecognizer in offline mode with partial results.
 *  When the code word is detected in any partial or final result, it
 *  broadcasts ACTION_CODEWORD_DETECTED so AutonomousTriggerService can
 *  activate all agents immediately.
 *
 * ── PANIC MODE ───────────────────────────────────────────────────────────
 *  If the spoken text contains the code word AND the confidence score is
 *  very high OR the text contains panic keywords ("help", "bachao", "chodo")
 *  → panicMode = true is included in the broadcast.
 *  Agents use this to bypass normal voice auth thresholds.
 *
 * ── USAGE ────────────────────────────────────────────────────────────────
 *  CodeWordDetector detector = new CodeWordDetector(context);
 *  detector.start();   // call when monitoring starts
 *  detector.stop();    // call when monitoring stops
 *
 *  Listen for broadcasts:
 *  IntentFilter filter = new IntentFilter(CodeWordDetector.ACTION_CODEWORD_DETECTED);
 *  LocalBroadcastManager.getInstance(ctx).registerReceiver(receiver, filter);
 *
 *  Extras in the broadcast Intent:
 *  - EXTRA_PANIC_MODE (boolean) : true if panic keywords detected
 *  - EXTRA_SPOKEN_TEXT (String) : full text that triggered detection
 */
public class CodeWordDetector {

    private static final String TAG = "CodeWordDetector";

    // ── Broadcast action ──────────────────────────────────────────────────
    public static final String ACTION_CODEWORD_DETECTED =
            "com.suraksha.ai.CODEWORD_DETECTED";
    public static final String EXTRA_PANIC_MODE  = "panic_mode";
    public static final String EXTRA_SPOKEN_TEXT = "spoken_text";

    // ── Panic keywords (Hindi + English) ─────────────────────────────────
    private static final String[] PANIC_KEYWORDS = {
            "help", "help me", "bachao", "chodo", "chordo",
            "leave me", "let me go", "save me", "police"
    };

    // ── Restart delay after each recognition session ends (ms) ───────────
    private static final int RESTART_DELAY_MS = 500;

    private final Context        context;
    private final PrefsManager   prefs;
    private final Handler        handler = new Handler(Looper.getMainLooper());
    private final AudioManager   audioManager;

    private SpeechRecognizer recognizer;
    private boolean          isListening = false;
    private boolean          isStopped   = false;   // set true on explicit stop()
    private boolean          verifyingVoice = false; // true while voice check runs

    private VoiceAuthManager voiceAuth;

    // ─────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────

    public CodeWordDetector(Context context) {
        this.context      = context.getApplicationContext();
        this.prefs        = new PrefsManager(this.context);
        this.audioManager = (AudioManager) this.context
                .getSystemService(Context.AUDIO_SERVICE);
        this.voiceAuth    = new VoiceAuthManager(this.context);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /** Start listening. Safe to call multiple times — won't double-start. */
    public void start() {
        if (isListening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device");
            return;
        }
        isStopped   = false;
        isListening = true;
        createAndStartRecognizer();
        Log.i(TAG, "CodeWordDetector started. Code word: [hidden]");
    }

    /** Stop listening and release all resources. */
    public void stop() {
        isStopped   = true;
        isListening = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        Log.i(TAG, "CodeWordDetector stopped.");
    }

    public boolean isListening() {
        return isListening;
    }

    // ─────────────────────────────────────────────────────────────────────
    // RECOGNIZER SETUP
    // ─────────────────────────────────────────────────────────────────────

    private void createAndStartRecognizer() {
        handler.post(() -> {
            destroyRecognizer();

            // ── Mute ding sound before starting recognition ────────────────
            muteRecognitionSounds(true);

            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(recognitionListener);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);
            // Suppress UI sounds from the recognizer
            intent.putExtra("android.speech.extra.DICTATION_MODE", true);

            recognizer.startListening(intent);

            // Unmute after short delay (after recognition starts, before it dings)
            handler.postDelayed(() -> muteRecognitionSounds(false), 300);
        });
    }

    /** Mutes/unmutes the system sounds that SpeechRecognizer plays on start/stop. */
    private void muteRecognitionSounds(boolean mute) {
        try {
            if (mute) {
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_NOTIFICATION,
                        AudioManager.ADJUST_MUTE, 0);
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_RING,
                        AudioManager.ADJUST_MUTE, 0);
            } else {
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_NOTIFICATION,
                        AudioManager.ADJUST_UNMUTE, 0);
                audioManager.adjustStreamVolume(
                        AudioManager.STREAM_RING,
                        AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not mute recognition sounds: " + e.getMessage());
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.stopListening();
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    /** Restart after a short delay — keeps detection continuous. */
    private void scheduleRestart() {
        if (isStopped) return;
        handler.postDelayed(() -> {
            if (!isStopped) createAndStartRecognizer();
        }, RESTART_DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CODE WORD MATCHING
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Checks whether the recognised text contains the user's code word.
     * Matching is case-insensitive and tolerates partial matches
     * (e.g. code word "suraksha" matches "suraksha help me").
     */
    private void checkForCodeWord(String spokenText, boolean isFinalResult) {
        if (spokenText == null || spokenText.isEmpty()) return;

        String codeWord = prefs.getCodeWord();
        if (codeWord == null || codeWord.isEmpty()) return;

        String lowerSpoken   = spokenText.toLowerCase(Locale.getDefault()).trim();
        String lowerCodeWord = codeWord.toLowerCase(Locale.getDefault()).trim();

        if (lowerSpoken.contains(lowerCodeWord)) {
            boolean isPanic = detectPanic(lowerSpoken);
            Log.i(TAG, "CODE WORD DETECTED! text='" + spokenText
                    + "' panic=" + isPanic
                    + " final=" + isFinalResult);

            // ── Voice verification gate ──────────────────────────────────
            // Only verify when the user opted in AND a voiceprint is enrolled
            // AND it's NOT a panic situation. In genuine panic, getting help
            // fast matters more than authentication, so we bypass the check.
            boolean shouldVerify = prefs.isVoiceVerifyEnabled()
                    && voiceAuth.isEnrolled()
                    && !isPanic
                    && !verifyingVoice;

            if (shouldVerify) {
                verifyVoiceThenBroadcast(spokenText, isPanic);
            } else {
                broadcastDetected(spokenText, isPanic);
            }
        }
    }

    /**
     * Pauses the speech recognizer (to free the mic), runs a voiceprint
     * verification, and only broadcasts the trigger if the voice matches.
     * Restarts listening afterwards either way.
     */
    private void verifyVoiceThenBroadcast(String spokenText, boolean isPanic) {
        verifyingVoice = true;

        // Release the mic from SpeechRecognizer so VoiceAuthManager can record
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (Exception ignored) {}

        Log.i(TAG, "Verifying voice before triggering…");

        voiceAuth.verify(new VoiceAuthManager.VoiceAuthCallback() {
            @Override
            public void onAuthenticated(boolean panicMode) {
                Log.i(TAG, "Voice verified ✓ — triggering");
                broadcastDetected(spokenText, isPanic || panicMode);
                verifyingVoice = false;
                scheduleRestart();
            }

            @Override
            public void onFailed(String reason) {
                // Voice didn't match — likely someone else or a TV.
                // Do NOT trigger. Just resume listening.
                Log.w(TAG, "Voice verification failed: " + reason
                        + " — ignoring this trigger");
                verifyingVoice = false;
                scheduleRestart();
            }
        });
    }

    /**
     * Returns true if the spoken text contains any panic keyword,
     * indicating the user is in distress.
     */
    private boolean detectPanic(String lowerText) {
        for (String keyword : PANIC_KEYWORDS) {
            if (lowerText.contains(keyword)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // BROADCAST
    // ─────────────────────────────────────────────────────────────────────

    private void broadcastDetected(String spokenText, boolean panicMode) {
        Intent broadcast = new Intent(ACTION_CODEWORD_DETECTED);
        broadcast.putExtra(EXTRA_PANIC_MODE, panicMode);
        broadcast.putExtra(EXTRA_SPOKEN_TEXT, spokenText);
        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcast);
    }

    // ─────────────────────────────────────────────────────────────────────
    // RECOGNITION LISTENER
    // ─────────────────────────────────────────────────────────────────────

    private final RecognitionListener recognitionListener = new RecognitionListener() {

        @Override
        public void onPartialResults(Bundle partialResults) {
            // Check partial results immediately — don't wait for final
            ArrayList<String> partials = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (partials != null && !partials.isEmpty()) {
                checkForCodeWord(partials.get(0), false);
            }
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                checkForCodeWord(matches.get(0), true);
            }
            // Session ended — restart immediately for continuous listening
            scheduleRestart();
        }

        @Override
        public void onError(int error) {
            String reason = errorCodeToString(error);
            Log.d(TAG, "Recognition error: " + reason + " — restarting");
            // Restart on all errors — network errors, timeouts, etc.
            scheduleRestart();
        }

        @Override
        public void onEndOfSpeech() {
            // Silence detected — results will follow shortly
        }

        // ── Unused callbacks (required by interface) ──────────────────────

        @Override public void onReadyForSpeech(Bundle params)      {}
        @Override public void onBeginningOfSpeech()                {}
        @Override public void onRmsChanged(float rmsdB)            {}
        @Override public void onBufferReceived(byte[] buffer)      {}
        @Override public void onEvent(int type, Bundle params)     {}
    };

    // ─────────────────────────────────────────────────────────────────────
    // UTILITY
    // ─────────────────────────────────────────────────────────────────────

    private String errorCodeToString(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:              return "audio_error";
            case SpeechRecognizer.ERROR_CLIENT:             return "client_error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "no_permission";
            case SpeechRecognizer.ERROR_NETWORK:            return "network_error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:    return "network_timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:           return "no_match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:    return "recognizer_busy";
            case SpeechRecognizer.ERROR_SERVER:             return "server_error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:     return "speech_timeout";
            default:                                        return "unknown_" + error;
        }
    }
}
