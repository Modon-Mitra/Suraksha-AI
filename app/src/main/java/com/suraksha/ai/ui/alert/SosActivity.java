package com.suraksha.ai.ui.alert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.suraksha.ai.databinding.ActivitySosBinding;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.service.MonitoringService;
import com.suraksha.ai.utils.PrefsManager;
import com.suraksha.ai.utils.SmsHelper;

import java.util.List;

public class SosActivity extends AppCompatActivity {

    public static final String ACTION_CANCEL_SOS =
            "com.suraksha.CANCEL_SOS";

    private ActivitySosBinding binding;

    private CountDownTimer timer;

    private MediaPlayer mediaPlayer;

    private FusedLocationProviderClient fusedLocation;

    private double currentLat = 0;

    private double currentLon = 0;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final BroadcastReceiver cancelReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context ctx,
                        Intent i
                ) {

                    finish();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        binding =
                ActivitySosBinding.inflate(
                        getLayoutInflater()
                );

        setContentView(binding.getRoot());

        fusedLocation =
                LocationServices
                        .getFusedLocationProviderClient(
                                this
                        );

        startLiveLocation();

        LocalBroadcastManager
                .getInstance(this)
                .registerReceiver(
                        cancelReceiver,
                        new IntentFilter(
                                ACTION_CANCEL_SOS
                        )
                );

        startAlarmSound();

        triggerVibration();

        showCountdownPhase();
    }

    // =========================
    // LIVE LOCATION
    // =========================

    private void startLiveLocation() {

        LocationRequest request =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        5000
                )
                        .setMinUpdateIntervalMillis(3000)
                        .build();

        LocationCallback callback =
                new LocationCallback() {

                    @Override
                    public void onLocationResult(
                            LocationResult result
                    ) {

                        Location location =
                                result.getLastLocation();

                        if (location != null) {

                            currentLat =
                                    location.getLatitude();

                            currentLon =
                                    location.getLongitude();
                        }
                    }
                };

        try {

            fusedLocation.requestLocationUpdates(
                    request,
                    callback,
                    Looper.getMainLooper()
            );

        } catch (SecurityException e) {

            e.printStackTrace();
        }
    }

    // =========================
    // COUNTDOWN
    // =========================

    private void showCountdownPhase() {

        binding.layoutCountdown
                .setVisibility(View.VISIBLE);

        binding.layoutActivated
                .setVisibility(View.GONE);

        binding.btnCancel
                .setOnClickListener(
                        v -> cancelSos()
                );

        timer = new CountDownTimer(
                10000,
                1000
        ) {

            @Override
            public void onTick(long ms) {

                binding.tvCountdown.setText(
                        String.valueOf(
                                ms / 1000 + 1
                        )
                );
            }

            @Override
            public void onFinish() {

                showActivatedPhase();
            }

        }.start();
    }

    // =========================
    // SOS ACTIVATED
    // =========================

    private void showActivatedPhase() {

        binding.layoutCountdown
                .setVisibility(View.GONE);

        binding.layoutActivated
                .setVisibility(View.VISIBLE);

        Intent svc =
                new Intent(
                        this,
                        MonitoringService.class
                );

        svc.putExtra(
                "cmd",
                MonitoringService.CMD_MANUAL_SOS
        );

        startService(svc);

        SmsHelper.sendSosMessages(this, currentLat, currentLon);

        binding.btnStopSos
                .setOnClickListener(
                        v -> stopSos()
                );
    }


    // =========================
    // ALARM SOUND
    // =========================

    private void startAlarmSound() {

        try {

            mediaPlayer =
                    MediaPlayer.create(
                            this,
                            Settings.System.DEFAULT_ALARM_ALERT_URI
                    );

            if (mediaPlayer != null) {

                mediaPlayer.setLooping(true);

                mediaPlayer.start();
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    // =========================
    // VIBRATION
    // =========================

    private void triggerVibration() {

        try {

            Vibrator vibrator =
                    (Vibrator)
                            getSystemService(
                                    VIBRATOR_SERVICE
                            );

            if (vibrator != null
                    && vibrator.hasVibrator()) {

                vibrator.vibrate(

                        VibrationEffect
                                .createWaveform(

                                        new long[]{
                                                0,
                                                500,
                                                300,
                                                500,
                                                300,
                                                700
                                        },

                                        -1
                                )
                );
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    // =========================
    // CANCEL SOS
    // =========================

    private void cancelSos() {

        if (timer != null) {

            timer.cancel();
        }

        handler.removeCallbacksAndMessages(null);

        stopAlarm();

        finish();
    }

    // =========================
    // STOP SOS
    // =========================

    private void stopSos() {

        handler.removeCallbacksAndMessages(null);

        stopAlarm();

        finish();
    }

    // =========================
    // STOP ALARM
    // =========================

    private void stopAlarm() {

        try {

            if (mediaPlayer != null) {

                mediaPlayer.stop();

                mediaPlayer.release();

                mediaPlayer = null;
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {

        if (timer != null) {

            timer.cancel();
        }

        handler.removeCallbacksAndMessages(null);

        stopAlarm();

        LocalBroadcastManager
                .getInstance(this)
                .unregisterReceiver(cancelReceiver);

        binding = null;

        super.onDestroy();
    }
}