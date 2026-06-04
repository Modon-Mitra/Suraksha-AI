package com.suraksha.ai.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.suraksha.ai.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EvidenceCollectionAgent — records audio in 30-second chunks and uploads
 * each chunk to Supabase Storage (free tier, no billing required).
 *
 * ── STORAGE ───────────────────────────────────────────────────────────────
 *  Files  → Supabase Storage  (bucket: evidence)
 *  URLs   → Firestore collection "evidence" (just metadata, no billing issue)
 *
 * ── SETUP (one-time) ──────────────────────────────────────────────────────
 *  1. In local.properties add:
 *       SUPABASE_ANON_KEY=eyJ...your key from Supabase → Settings → API
 *  2. In Supabase Dashboard → Storage → create bucket "evidence" → Public ON
 *  3. build.gradle already has the buildConfigField for SUPABASE_ANON_KEY
 */
public class EvidenceCollectionAgent extends BaseAgent {

    private static final String TAG = "EvidenceCollectionAgent";

    private static final long   CHUNK_INTERVAL_MS = 30_000;
    private static final String SUPABASE_URL      = "https://bhdgcwmdjmgtqwyfhlxa.supabase.co";
    private static final String BUCKET            = "evidence";

    // Background thread — all MediaRecorder + upload operations run here
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler  = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private File          currentFile;
    private volatile boolean isRecording  = false;
    private int              chunkIndex   = 0;
    private String           sessionId;

    private final Handler  chunkHandler  = new Handler(Looper.getMainLooper());
    private       Runnable chunkRunnable;

    private String latestChunkUrl = null;

    public EvidenceCollectionAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Evidence Collection Agent"; }
    @Override public String getEmoji() { return "📹"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("start") || lower.contains("record")) {
            if (isRecording) {
                callback.onAgentMessage(getName(), getEmoji(),
                        "🔴 Already recording.\n"
                                + chunkIndex + " chunk(s) uploaded so far.\n"
                                + "Evidence is being preserved securely.");
            } else {
                startRecording();
                callback.onAgentMessage(getName(), getEmoji(),
                        "🔴 Recording started.\n"
                                + "Audio is uploaded to secure storage every 30 seconds.\n"
                                + "Evidence is safe even if your phone is taken.\n\n"
                                + "Say 'stop recording' to end.");
            }

        } else if (lower.contains("stop")) {
            if (!isRecording) {
                callback.onAgentMessage(getName(), getEmoji(),
                        "No recording is currently active.");
            } else {
                stopRecording();
                callback.onAgentMessage(getName(), getEmoji(),
                        "⏹️ Recording stopped.\n"
                                + chunkIndex + " chunk(s) saved securely.\n"
                                + (latestChunkUrl != null
                                ? "Latest: " + latestChunkUrl : ""));
            }

        } else if (lower.contains("status")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Recording: " + (isRecording ? "🔴 ACTIVE" : "⬛ STOPPED") + "\n"
                            + "Session: " + (sessionId != null ? sessionId : "none") + "\n"
                            + "Chunks uploaded: " + chunkIndex + "\n"
                            + (latestChunkUrl != null
                            ? "Latest URL: " + latestChunkUrl
                            : "No uploads yet."));

        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I silently record audio and upload it to secure storage so evidence\n"
                            + "is preserved even if your phone is taken.\n\n"
                            + "• 'start recording'  — begin evidence collection\n"
                            + "• 'stop recording'   — end and save all chunks\n"
                            + "• 'recording status' — check current state");
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        if (ctx.threatLevel == ThreatContext.ThreatLevel.HIGH && !isRecording) {
            startRecording();
            postToChatAsAgent(
                    "🔴 Emergency recording started automatically.\n"
                            + "Audio is uploading to secure storage every 30 seconds.\n"
                            + "Evidence is protected even if your phone is taken.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void startRecording() {
        if (isRecording) return;

        // Check RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted");
            postToChatAsAgent("⚠️ Microphone permission not granted.\n"
                    + "Please allow microphone access in app settings.");
            return;
        }

        sessionId   = new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date());
        chunkIndex  = 0;
        isRecording = true;

        workerThread.execute(this::startNewChunk);
        scheduleNextChunk();

        Log.i(TAG, "Recording started. Session: " + sessionId);
    }

    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        chunkHandler.removeCallbacks(chunkRunnable);
        workerThread.execute(this::finalizeAndUpload);
        Log.i(TAG, "Recording stopped after " + chunkIndex + " chunk(s)");
    }

    public boolean isRecording()       { return isRecording; }
    public String  getLatestChunkUrl() { return latestChunkUrl; }

    // ── Chunk lifecycle (runs on workerThread) ────────────────────────────

    private void startNewChunk() {
        currentFile = new File(context.getCacheDir(),
                "ev_" + sessionId + "_" + chunkIndex + ".mp4");
        try {
            // Use MediaRecorder(context) on API 31+ to avoid deprecation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder = new MediaRecorder(context);
            } else {
                recorder = new MediaRecorder();
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            Log.d(TAG, "Chunk " + chunkIndex + " started");
        } catch (IOException | SecurityException | IllegalStateException e) {
            Log.e(TAG, "Failed to start chunk " + chunkIndex, e);
            isRecording = false;
            mainHandler.post(() -> postToChatAsAgent(
                    "⚠️ Recording error: " + e.getMessage()));
        }
    }

    private void scheduleNextChunk() {
        chunkRunnable = () -> {
            if (!isRecording) return;
            // Run on workerThread: finalize current → increment → start new
            workerThread.execute(() -> {
                finalizeAndUpload();
                chunkIndex++;
                if (isRecording) {
                    startNewChunk();
                    mainHandler.post(this::scheduleNextChunk);
                }
            });
        };
        chunkHandler.postDelayed(chunkRunnable, CHUNK_INTERVAL_MS);
    }

    private void finalizeAndUpload() {
        if (recorder == null) return;

        // Capture references before releasing
        final File   file  = currentFile;
        final int    index = chunkIndex;

        try { recorder.stop(); }
        catch (RuntimeException e) {
            // Too short to have valid audio — skip upload
            Log.w(TAG, "Chunk too short, skipping: " + e.getMessage());
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            if (file != null) file.delete();
            return;
        }
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;

        if (file == null || !file.exists() || file.length() == 0) {
            Log.w(TAG, "Chunk " + index + " file empty — skipping");
            return;
        }

        uploadToSupabase(file, index);
    }

    // ── Supabase Storage upload via HttpURLConnection ─────────────────────

    private void uploadToSupabase(File file, int index) {
        String userId = getUserId();
        String path   = userId + "/" + sessionId + "/chunk_" + index + ".mp4";
        String apiKey = BuildConfig.SUPABASE_ANON_KEY;

        // Supabase Storage upload endpoint
        String uploadUrl = SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + path;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type",  "audio/mp4");
            conn.setRequestProperty("x-upsert",      "true");
            conn.setRequestProperty("Content-Length", String.valueOf(file.length()));
            conn.setFixedLengthStreamingMode(file.length());
            conn.connect();

            // Stream file to Supabase — memory efficient, no byte[] in RAM
            try (OutputStream out    = conn.getOutputStream();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int    n;
                while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            }

            int code = conn.getResponseCode();
            if (code == 200 || code == 201) {
                // Construct public URL (works when bucket is set to Public)
                String publicUrl = SUPABASE_URL + "/storage/v1/object/public/"
                        + BUCKET + "/" + path;
                latestChunkUrl = publicUrl;
                Log.i(TAG, "✅ Chunk " + index + " uploaded: " + publicUrl);

                // Save metadata to Firestore (free tier — no storage billing)
                saveMetadataToFirestore(userId, index, publicUrl);

                // Clean up local temp file
                file.delete();
            } else {
                Log.e(TAG, "❌ Upload failed. HTTP " + code
                        + " — chunk " + index + " kept locally as fallback");
            }

        } catch (Exception e) {
            Log.e(TAG, "Upload error for chunk " + index + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Firestore metadata (URL + timestamp only — no files stored) ───────

    private void saveMetadataToFirestore(String userId, int index, String url) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId",      userId);
        data.put("sessionId",   sessionId);
        data.put("chunkIndex",  index);
        data.put("downloadUrl", url);
        data.put("timestamp",   System.currentTimeMillis());
        data.put("expiresAt",   System.currentTimeMillis() + 72L * 60 * 60 * 1000);

        FirebaseFirestore.getInstance()
                .collection("evidence")
                .add(data)
                .addOnSuccessListener(ref ->
                        Log.d(TAG, "Firestore metadata saved: " + ref.getId()))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Firestore metadata save failed: " + e.getMessage()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String getUserId() {
        // Try Firebase Auth UID first, fall back to PrefsManager
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            return FirebaseAuth.getInstance().getCurrentUser().getUid();
        }
        return prefs.getUserId();
    }
}
