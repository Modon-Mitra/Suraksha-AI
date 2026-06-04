package com.suraksha.ai.agent.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * VoiceAuthManager — voice-print enrollment + verification using TFLite.
 *
 * Model: yamnet_embedding.tflite (place in app/src/main/assets/)
 * Download: https://www.kaggle.com/models/google/yamnet/tfLite
 *
 * Security: Raw audio is never written to disk. Only the 1024-float
 * embedding vector is persisted, encrypted with AES-256-GCM via
 * Android Keystore (EncryptedSharedPreferences).
 *
 * Thresholds:
 *   Normal : cosine similarity >= 0.85
 *   Panic  : cosine similarity >= 0.65 (screaming detected)
 */
public class VoiceAuthManager {

    private static final String TAG = "VoiceAuthManager";

    private static final float  NORMAL_THRESHOLD    = 0.85f;
    private static final float  PANIC_THRESHOLD     = 0.65f;
    private static final double PANIC_RMS_THRESHOLD = 18000.0;

    private static final int    SAMPLE_RATE          = 16000;
    private static final int    ENROLL_DURATION_MS   = 4000;
    @SuppressWarnings("unused")
    private static final int    VERIFY_DURATION_MS   = 2000;
    private static final int    YAMNET_INPUT_SAMPLES = 15600;

    private static final String PREFS_FILE     = "suraksha_voice_prefs";
    private static final String KEY_VOICEPRINT = "voiceprint_v2";
    private static final String KEY_ENROLLED   = "enrolled_v2";
    private static final String MODEL_ASSET    = "yamnet_embedding.tflite";

    private final Context     context;
    private       Interpreter tflite;

    // ── Callbacks ─────────────────────────────────────────────────────────

    public interface VoiceEnrollCallback {
        void onEnrolled();
        void onError(String message);
    }

    public interface VoiceAuthCallback {
        void onAuthenticated(boolean panicMode);
        void onFailed(String reason);
    }

    // ── Constructor ───────────────────────────────────────────────────────

    public VoiceAuthManager(Context context) {
        this.context = context.getApplicationContext();
        loadModel();
    }

    // ── Enrollment ────────────────────────────────────────────────────────

    public void enrollVoicePrint(VoiceEnrollCallback callback) {
        if (lacksAudioPermission()) {
            callback.onError("Microphone permission not granted");
            return;
        }
        if (tflite == null) {
            callback.onError("TFLite model not loaded. Check " + MODEL_ASSET + " is in assets/");
            return;
        }

        new Thread(() -> {
            try {
                short[] pcm       = recordPcm(ENROLL_DURATION_MS);
                float[] embedding = runInference(pcm);
                saveEmbedding(embedding);
                runOnMain(callback::onEnrolled);
            } catch (Exception e) {
                runOnMain(() -> callback.onError("Enrollment failed: " + e.getMessage()));
            }
        }, "VoiceEnroll").start();
    }

    // ── Verification ──────────────────────────────────────────────────────

    public void verify(VoiceAuthCallback callback) {
        if (lacksAudioPermission()) {
            callback.onFailed("Microphone permission not granted");
            return;
        }
        if (!isEnrolled()) {
            callback.onFailed("No voiceprint enrolled");
            return;
        }
        if (tflite == null) {
            callback.onFailed("TFLite model not loaded");
            return;
        }

        new Thread(() -> {
            try {
                short[] pcm      = recordPcm(ENROLL_DURATION_MS);
                boolean isPanic  = detectPanic(pcm);
                float[] incoming = runInference(pcm);
                float[] stored   = loadEmbedding();

                if (stored == null) {
                    runOnMain(() -> callback.onFailed("Stored voiceprint missing"));
                    return;
                }

                float score     = cosineSimilarity(incoming, stored);
                float threshold = isPanic ? PANIC_THRESHOLD : NORMAL_THRESHOLD;

                Log.d(TAG, String.format(Locale.US,
                        "score=%.3f threshold=%.2f panic=%b", score, threshold, isPanic));

                if (score >= threshold) {
                    runOnMain(() -> callback.onAuthenticated(isPanic));
                } else {
                    runOnMain(() -> callback.onFailed(
                            String.format(Locale.US,
                                    "Voice not recognised (score %.2f < %.2f)",
                                    score, threshold)));
                }
            } catch (Exception e) {
                Log.e(TAG, "Verification failed", e);
                runOnMain(() -> callback.onFailed("Verification error: " + e.getMessage()));
            }
        }, "VoiceVerify").start();
    }

    // ── Permission ────────────────────────────────────────────────────────

    private boolean lacksAudioPermission() {
        return ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ── Audio capture (PCM in memory — never written to disk) ─────────────

    private short[] recordPcm(int durationMs) throws IOException, SecurityException {
        int minBuf       = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int totalSamples = (SAMPLE_RATE * durationMs) / 1000;
        short[] pcm      = new short[totalSamples];

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, totalSamples * 2));

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IOException("AudioRecord failed to initialise — check RECORD_AUDIO permission");
        }

        try {
            recorder.startRecording();
            int offset = 0;
            while (offset < totalSamples) {
                int read = recorder.read(pcm, offset, totalSamples - offset);
                if (read < 0) break;
                offset += read;
            }
        } finally {
            recorder.stop();
            recorder.release();
        }

        return pcm;
    }

    // ── TFLite inference ──────────────────────────────────────────────────

    private float[] runInference(short[] pcm) {
        // Normalise PCM short[] → float[] in [-1, +1]
        float[] audio = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            audio[i] = pcm[i] / 32768.0f;
        }

        int     frameCount   = Math.max(1, audio.length / YAMNET_INPUT_SAMPLES);
        float[] sumEmbedding = new float[1024];

        for (int f = 0; f < frameCount; f++) {
            int     start  = f * YAMNET_INPUT_SAMPLES;
            int     end    = Math.min(start + YAMNET_INPUT_SAMPLES, audio.length);
            float[] frame  = new float[YAMNET_INPUT_SAMPLES];
            System.arraycopy(audio, start, frame, 0, end - start);

            // YAMNet expects 1-D float[] input, not float[][]
            float[][] output = new float[1][1024];
            tflite.run(frame, output);   // ← frame directly (1D) fixes PAD kernel crash

            for (int i = 0; i < 1024; i++) {
                sumEmbedding[i] += output[0][i];
            }
        }

        for (int i = 0; i < 1024; i++) {
            sumEmbedding[i] /= frameCount;
        }

        return sumEmbedding;
    }

    // ── Panic detection ───────────────────────────────────────────────────

    private boolean detectPanic(short[] pcm) {
        double sumSq = 0;
        for (short s : pcm) sumSq += (double) s * s;
        double rms = Math.sqrt(sumSq / pcm.length);
        Log.d(TAG, "RMS amplitude: " + rms);
        return rms > PANIC_RMS_THRESHOLD;
    }

    // ── Cosine similarity ─────────────────────────────────────────────────

    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0f : (float) (dot / denominator);
    }

    // ── Encrypted storage ─────────────────────────────────────────────────

    private void saveEmbedding(float[] embedding) throws Exception {
        getSecurePrefs().edit()
                .putString(KEY_VOICEPRINT, floatsToString(embedding))
                .putBoolean(KEY_ENROLLED, true)
                .apply();
    }

    private float[] loadEmbedding() {
        String raw = getSecurePrefs().getString(KEY_VOICEPRINT, null);
        return raw != null ? stringToFloats(raw) : null;
    }

    public boolean isEnrolled() {
        return getSecurePrefs().getBoolean(KEY_ENROLLED, false);
    }

    public void clearEnrollment() {
        getSecurePrefs().edit().clear().apply();
    }

    private SharedPreferences getSecurePrefs() {
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context, PREFS_FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPrefs unavailable, using plain prefs", e);
            return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────

    private void loadModel() {
        try {
            MappedByteBuffer modelBuffer = loadModelFile();
            Interpreter.Options options  = new Interpreter.Options();
            options.setNumThreads(2);
            tflite = new Interpreter(modelBuffer, options);
            Log.i(TAG, "TFLite model loaded: " + MODEL_ASSET);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite model '" + MODEL_ASSET
                    + "'. Place it in app/src/main/assets/", e);
            tflite = null;
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        // try-with-resources ensures both streams are closed properly
        android.content.res.AssetFileDescriptor afd =
                context.getAssets().openFd(MODEL_ASSET);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel channel = fis.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(), afd.getDeclaredLength());
        }
    }

    // ── Serialisation helpers ─────────────────────────────────────────────

    private String floatsToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private float[] stringToFloats(String s) {
        String[] parts = s.split(",");
        float[]  arr   = new float[parts.length];
        for (int i = 0; i < parts.length; i++) arr[i] = Float.parseFloat(parts[i]);
        return arr;
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private void runOnMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}