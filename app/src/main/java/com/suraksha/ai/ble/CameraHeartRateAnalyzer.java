package com.suraksha.ai.ble;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Improved CameraHeartRateAnalyzer
 *
 * Improvements:
 * - Lower flat-signal threshold
 * - Center ROI sampling
 * - Exposure lock
 * - White balance stabilization
 * - Skip unstable startup frames
 * - Timestamp tracking
 * - Signal smoothing
 * - Normalized autocorrelation
 * - Motion noise rejection
 * - Better FPS handling
 */

@SuppressWarnings("deprecation")
public class CameraHeartRateAnalyzer {

    private static final String TAG = "CameraHR";

    private static final int DURATION_MS = 45000;
    private static final int STARTUP_SKIP_MS = 3000;

    private static final int MIN_SAMPLES = 80;

    private static final double MIN_BPM = 40.0;
    private static final double MAX_BPM = 180.0;

    private static final double MIN_SIGNAL_STD = 0.002;
    private static final double MAX_SIGNAL_STD = 25.0;

    private static final long WATCHDOG_MS = 4000;

    public interface Callback {
        void onProgress(int secondsRemaining);
        void onResult(int bpm);
        void onError(String msg);
    }

    private final Callback callback;

    // Stress level computed from HRV during analyze()
    // "Low" / "Moderate" / "High" / "--"
    private String lastStressLevel = "--";
    private int    lastSdnnMs       = 0;

    /** Returns the stress level from the last measurement: Low / Moderate / High */
    public String getLastStressLevel() { return lastStressLevel; }

    /** Returns the SDNN (heart rate variability) in milliseconds from last measurement */
    public int getLastSdnnMs() { return lastSdnnMs; }

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private Camera camera;
    private SurfaceTexture surfaceTexture;

    private boolean running = false;

    private long startTime = 0;
    private long lastFrame = 0;

    private final List<Double> samples = new ArrayList<>();
    private final List<Long> timestamps = new ArrayList<>();

    private final AtomicInteger textureName =
            new AtomicInteger(1);

    public CameraHeartRateAnalyzer(
            Context ctx,
            Callback callback
    ) {
        this.callback = callback;
    }

    // =====================================================
    // WATCHDOG
    // =====================================================

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {

            long since =
                    System.currentTimeMillis() - lastFrame;

            if (running && since > WATCHDOG_MS) {

                Log.w(TAG,
                        "No frames for "
                                + since
                                + "ms");

                finishMeasurement();

            } else if (running) {

                mainHandler.postDelayed(
                        this,
                        WATCHDOG_MS
                );
            }
        }
    };

    // =====================================================
    // START
    // =====================================================

    public void start() {

        if (running) return;

        try {

            camera = Camera.open();

            Camera.Parameters p =
                    camera.getParameters();

            // -----------------------------------------
            // FLASH
            // -----------------------------------------

            List<String> flashModes =
                    p.getSupportedFlashModes();

            if (flashModes != null
                    && flashModes.contains(
                    Camera.Parameters.FLASH_MODE_TORCH
            )) {

                p.setFlashMode(
                        Camera.Parameters.FLASH_MODE_TORCH
                );
            }

            // -----------------------------------------
            // EXPOSURE LOCK
            // -----------------------------------------

            if (p.isAutoExposureLockSupported()) {
                p.setAutoExposureLock(true);
            }

            // -----------------------------------------
            // WHITE BALANCE
            // -----------------------------------------

            List<String> wb =
                    p.getSupportedWhiteBalance();

            if (wb != null
                    && wb.contains(
                    Camera.Parameters.WHITE_BALANCE_INCANDESCENT
            )) {

                p.setWhiteBalance(
                        Camera.Parameters
                                .WHITE_BALANCE_INCANDESCENT
                );
            }

            // -----------------------------------------
            // PREVIEW SIZE
            // -----------------------------------------

            Camera.Size best =
                    smallestSize(
                            p.getSupportedPreviewSizes()
                    );

            if (best != null) {

                p.setPreviewSize(
                        best.width,
                        best.height
                );
            }

            // -----------------------------------------
            // FPS
            // -----------------------------------------

            try {

                List<int[]> fpsRanges =
                        p.getSupportedPreviewFpsRange();

                if (fpsRanges != null
                        && !fpsRanges.isEmpty()) {

                    int[] range = fpsRanges.get(0);

                    p.setPreviewFpsRange(
                            range[0],
                            range[1]
                    );
                }

            } catch (Exception ignored) {
            }

            try {
                p.setPreviewFrameRate(30);
            } catch (Exception ignored) {
            }

            camera.setParameters(p);

            // -----------------------------------------
            // SURFACE TEXTURE
            // -----------------------------------------

            surfaceTexture =
                    new SurfaceTexture(
                            textureName.getAndIncrement()
                    );

            camera.setPreviewTexture(surfaceTexture);

            running = true;

            startTime =
                    System.currentTimeMillis();

            lastFrame = startTime;

            samples.clear();
            timestamps.clear();

            // -----------------------------------------
            // PREVIEW CALLBACK
            // -----------------------------------------

            camera.setPreviewCallback((data, cam) -> {

                if (!running || data == null) {
                    return;
                }

                long now =
                        System.currentTimeMillis();

                lastFrame = now;

                long elapsed =
                        now - startTime;

                // ---------------------------------
                // Skip unstable startup frames
                // ---------------------------------

                if (elapsed < STARTUP_SKIP_MS) {
                    return;
                }

                Camera.Size sz =
                        cam.getParameters()
                                .getPreviewSize();

                double lum =
                        avgLuminance(
                                data,
                                sz.width,
                                sz.height
                        );

                samples.add(lum);
                timestamps.add(now);

                int remaining =
                        (int) Math.max(
                                0,
                                (DURATION_MS - elapsed)
                                        / 1000
                        );

                mainHandler.post(() ->
                        callback.onProgress(
                                remaining
                        )
                );

                if (elapsed >= DURATION_MS) {
                    finishMeasurement();
                }
            });

            camera.startPreview();

            mainHandler.postDelayed(
                    watchdog,
                    WATCHDOG_MS
            );

            Log.d(TAG, "Camera started");

        } catch (Exception e) {

            Log.e(TAG,
                    "Camera failed",
                    e);

            running = false;

            releaseCamera();

            mainHandler.post(() ->
                    callback.onError(
                            "Camera error: "
                                    + e.getMessage()
                    )
            );
        }
    }

    // =====================================================
    // STOP
    // =====================================================

    public void stop() {

        running = false;

        mainHandler.removeCallbacks(
                watchdog
        );

        releaseCamera();
    }

    private void releaseCamera() {

        if (camera != null) {

            try {

                camera.setPreviewCallback(null);

                camera.stopPreview();

                camera.release();

            } catch (Exception ignored) {
            }

            camera = null;
        }

        surfaceTexture = null;
    }

    // =====================================================
    // FINISH
    // =====================================================

    private synchronized void finishMeasurement() {

        if (!running) return;

        running = false;

        mainHandler.removeCallbacks(
                watchdog
        );

        releaseCamera();

        int bpm = analyze();

        mainHandler.post(() ->
                callback.onResult(bpm)
        );
    }

    // =====================================================
    // CENTER ROI LUMINANCE
    // =====================================================

    private double avgLuminance(
            byte[] data,
            int w,
            int h
    ) {

        int startX = w / 4;
        int endX = 3 * w / 4;

        int startY = h / 4;
        int endY = 3 * h / 4;

        long sum = 0;
        int count = 0;

        for (int y = startY;
             y < endY;
             y += 2) {

            int row = y * w;

            for (int x = startX;
                 x < endX;
                 x += 2) {

                sum += data[row + x] & 0xFF;

                count++;
            }
        }

        return count > 0
                ? (double) sum / count
                : 0;
    }

    // =====================================================
    // ANALYSIS
    // =====================================================

    private int analyze() {

        int n = samples.size();

        Log.d(TAG,
                "Analyzing "
                        + n
                        + " samples");

        if (n < MIN_SAMPLES) {

            Log.w(TAG,
                    "Too few samples");

            return -1;
        }

        // -----------------------------------------
        // REAL FPS
        // -----------------------------------------

        long first =
                timestamps.get(0);

        long last =
                timestamps.get(
                        timestamps.size() - 1
                );

        double elapsedSec =
                (last - first) / 1000.0;

        if (elapsedSec <= 0) {
            return -1;
        }

        double fps =
                n / elapsedSec;

        Log.d(TAG,
                "FPS = " + fps);

        // -----------------------------------------
        // DETREND
        // -----------------------------------------

        int winSize =
                Math.max(
                        1,
                        (int) (fps * 2)
                );

        double[] detrended =
                new double[n];

        for (int i = 0; i < n; i++) {

            int from =
                    Math.max(
                            0,
                            i - winSize
                    );

            int to =
                    Math.min(
                            n - 1,
                            i + winSize
                    );

            double sum = 0;

            for (int j = from;
                 j <= to;
                 j++) {

                sum += samples.get(j);
            }

            double avg =
                    sum / (to - from + 1);

            detrended[i] =
                    samples.get(i) - avg;
        }

        // -----------------------------------------
        // SMOOTHING
        // -----------------------------------------

        double[] smooth =
                new double[n];

        for (int i = 1;
             i < n - 1;
             i++) {

            smooth[i] =
                    (
                            detrended[i - 1]
                                    + detrended[i]
                                    + detrended[i + 1]
                    ) / 3.0;
        }

        // -----------------------------------------
        // NORMALIZATION
        // -----------------------------------------

        double mean = 0;

        for (double v : smooth) {
            mean += v;
        }

        mean /= smooth.length;

        for (int i = 0; i < smooth.length; i++) {
            smooth[i] -= mean;
        }

        // -----------------------------------------
        // SIGNAL QUALITY
        // -----------------------------------------

        double std =
                stdDev(smooth);

        Log.d(TAG,
                "STD = " + std);

        if (std < MIN_SIGNAL_STD) {

            Log.w(TAG,
                    "Signal too weak");

            return -1;
        }

        if (std > MAX_SIGNAL_STD) {

            Log.w(TAG,
                    "Motion noise detected");

            return -1;
        }

        // -----------------------------------------
        // AUTOCORRELATION
        // -----------------------------------------

        int minLag =
                Math.max(
                        1,
                        (int) (
                                fps * 60.0
                                        / MAX_BPM
                        )
                );

        int maxLag =
                Math.min(
                        n / 2,
                        (int) (
                                fps * 60.0
                                        / MIN_BPM
                        )
                );

        if (minLag >= maxLag) {
            return -1;
        }

        double bestCorr =
                Double.NEGATIVE_INFINITY;

        int bestLag = -1;

        for (int lag = minLag;
             lag <= maxLag;
             lag++) {

            double corr = 0;

            int count = n - lag;

            for (int i = 0;
                 i < count;
                 i++) {

                corr +=
                        smooth[i]
                                * smooth[i + lag];
            }

            corr /=
                    (count * std * std);

            if (corr > bestCorr) {

                bestCorr = corr;

                bestLag = lag;
            }
        }

        if (bestLag < 1) {
            return -1;
        }

        // -----------------------------------------
        // BPM
        // -----------------------------------------

        double periodSec =
                bestLag / fps;

        double bpm =
                60.0 / periodSec;

        Log.d(TAG,
                "BPM = "
                        + bpm
                        + " Corr = "
                        + bestCorr);

        if (bpm < MIN_BPM
                || bpm > MAX_BPM) {

            return -1;
        }

        // -----------------------------------------
        // HRV → STRESS LEVEL
        // -----------------------------------------
        computeStress(smooth, fps, bpm);

        return (int) Math.round(bpm);
    }

    // =====================================================
    // HRV-BASED STRESS COMPUTATION
    // Detects peaks in the PPG signal, measures the beat-to-beat
    // interval variability (SDNN), and maps it to a stress level.
    // Higher variability = more relaxed; lower variability = more stressed.
    // =====================================================

    private void computeStress(double[] signal, double fps, double bpm) {
        try {
            double std = stdDev(signal);
            double peakThreshold = std * 0.5;

            // Detect peaks (local maxima above threshold)
            List<Integer> peakIndices = new ArrayList<>();
            for (int i = 1; i < signal.length - 1; i++) {
                if (signal[i] > peakThreshold
                        && signal[i] > signal[i - 1]
                        && signal[i] >= signal[i + 1]) {
                    // Enforce minimum spacing (refractory ~ 0.3s)
                    if (peakIndices.isEmpty()
                            || (i - peakIndices.get(peakIndices.size() - 1)) > fps * 0.3) {
                        peakIndices.add(i);
                    }
                }
            }

            if (peakIndices.size() < 4) {
                // Not enough beats for reliable HRV
                lastStressLevel = "--";
                lastSdnnMs = 0;
                return;
            }

            // Inter-beat intervals (IBI) in milliseconds
            List<Double> ibis = new ArrayList<>();
            for (int i = 1; i < peakIndices.size(); i++) {
                double samplesBetween = peakIndices.get(i) - peakIndices.get(i - 1);
                double ibiMs = (samplesBetween / fps) * 1000.0;
                if (ibiMs > 300 && ibiMs < 1500) { // plausible IBI range
                    ibis.add(ibiMs);
                }
            }

            if (ibis.size() < 3) {
                lastStressLevel = "--";
                lastSdnnMs = 0;
                return;
            }

            // SDNN = standard deviation of IBIs (key HRV metric)
            double meanIbi = 0;
            for (double v : ibis) meanIbi += v;
            meanIbi /= ibis.size();

            double variance = 0;
            for (double v : ibis) variance += (v - meanIbi) * (v - meanIbi);
            double sdnn = Math.sqrt(variance / ibis.size());

            lastSdnnMs = (int) Math.round(sdnn);

            // Map SDNN + BPM to stress level
            // High SDNN (>50ms) = relaxed; Low SDNN (<20ms) = stressed
            // Elevated BPM pushes stress higher
            if (sdnn > 50 && bpm < 90) {
                lastStressLevel = "Low";
            } else if (sdnn < 20 || bpm > 100) {
                lastStressLevel = "High";
            } else {
                lastStressLevel = "Moderate";
            }

            Log.d(TAG, "HRV: SDNN=" + lastSdnnMs + "ms, stress=" + lastStressLevel);

        } catch (Exception e) {
            lastStressLevel = "--";
            lastSdnnMs = 0;
        }
    }

    // =====================================================
    // STD DEV
    // =====================================================

    private double stdDev(double[] arr) {

        double mean = 0;

        for (double v : arr) {
            mean += v;
        }

        mean /= arr.length;

        double var = 0;

        for (double v : arr) {

            double d = v - mean;

            var += d * d;
        }

        return Math.sqrt(
                var / arr.length
        );
    }

    // =====================================================
    // SMALLEST SIZE
    // =====================================================

    private Camera.Size smallestSize(
            List<Camera.Size> sizes
    ) {

        if (sizes == null
                || sizes.isEmpty()) {

            return null;
        }

        Camera.Size best =
                sizes.get(0);

        for (Camera.Size s : sizes) {

            if (s.width * s.height
                    < best.width * best.height) {

                best = s;
            }
        }

        return best;
    }
}