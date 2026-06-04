package com.suraksha.ai.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.suraksha.ai.agent.auth.VoiceAuthManager;
import com.suraksha.ai.databinding.ActivityOnboardingBinding;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.ui.settings.ContactsAdapter;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.PrefsManager;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 101;

    private ActivityOnboardingBinding binding;

    private final List<EmergencyContact> contacts = new ArrayList<>();
    private ContactsAdapter adapter;
    private PrefsManager prefs;
    private VoiceAuthManager voiceAuthManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs             = new PrefsManager(this);
        voiceAuthManager  = new VoiceAuthManager(this);

        // =========================================
        // LOAD SAVED CONTACTS
        // =========================================

        contacts.addAll(prefs.getContacts());

        adapter = new ContactsAdapter(contacts, this::removeContact);

        binding.rvContacts.setLayoutManager(new LinearLayoutManager(this));
        binding.rvContacts.setAdapter(adapter);

        // =========================================
        // SAVE MY PHONE
        // =========================================

        binding.btnSaveMyPhone.setOnClickListener(v -> {

            String myPhone = binding.etMyPhone.getText().toString().trim();

            if (TextUtils.isEmpty(myPhone)) {
                Toast.makeText(this, "Enter your phone number", Toast.LENGTH_SHORT).show();
                return;
            }

            prefs.setMyPhone(myPhone);
            FirebaseLocationHelper.registerPhone(prefs.getUserId(), myPhone);
            Toast.makeText(this, "Phone number saved ✓", Toast.LENGTH_SHORT).show();
        });

        // =========================================
        // ADD CONTACT
        // =========================================

        binding.btnAddContact.setOnClickListener(v -> addContact());

        // =========================================
        // VOICE ENROLLMENT
        // =========================================

        // Restore status if already enrolled from a previous session
        if (voiceAuthManager.isEnrolled()) {
            setVoiceStatus(true);
        }

        binding.btnEnrollVoice.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                // Request permission — enrollment runs in onRequestPermissionsResult
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO);
            } else {
                startVoiceEnrollment();
            }
        });

        // =========================================
        // FINISH
        // =========================================

        binding.btnFinish.setOnClickListener(v -> finish());
    }

    // =========================================
    // VOICE ENROLLMENT
    // =========================================

    private void startVoiceEnrollment() {
        binding.btnEnrollVoice.setEnabled(false);
        binding.btnEnrollVoice.setText("🎙️ Recording... (4s)");
        binding.tvVoiceStatus.setText("● Recording — speak naturally...");
        binding.tvVoiceStatus.setTextColor(getColor(com.suraksha.ai.R.color.warning_yellow));

        voiceAuthManager.enrollVoicePrint(new VoiceAuthManager.VoiceEnrollCallback() {

            @Override
            public void onEnrolled() {
                binding.btnEnrollVoice.setEnabled(true);
                binding.btnEnrollVoice.setText("🎤 Re-record Voice");
                setVoiceStatus(true);
                Toast.makeText(OnboardingActivity.this,
                        "Voice ID saved ✓", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                binding.btnEnrollVoice.setEnabled(true);
                binding.btnEnrollVoice.setText("🎤 Record My Voice");
                setVoiceStatus(false);
                Toast.makeText(OnboardingActivity.this,
                        "Failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Updates the status label below the enroll button. */
    private void setVoiceStatus(boolean enrolled) {
        if (enrolled) {
            binding.tvVoiceStatus.setText("✓ Voice ID enrolled");
            binding.tvVoiceStatus.setTextColor(getColor(com.suraksha.ai.R.color.safe_green));
            binding.btnEnrollVoice.setBackgroundTintList(
                    getColorStateList(com.suraksha.ai.R.color.safe_green_dim));
        } else {
            binding.tvVoiceStatus.setText("○ Not enrolled");
            binding.tvVoiceStatus.setTextColor(getColor(com.suraksha.ai.R.color.text_secondary));
            binding.btnEnrollVoice.setBackgroundTintList(
                    getColorStateList(com.suraksha.ai.R.color.brand_blue_dim));
        }
    }

    // =========================================
    // PERMISSION RESULT
    // =========================================

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceEnrollment();
            } else {
                Toast.makeText(this,
                        "Microphone permission is needed for Voice ID",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // =========================================
    // ADD CONTACT
    // =========================================

    private void addContact() {

        String name       = binding.etName.getText().toString().trim();
        String phone      = binding.etPhone.getText().toString().trim();
        String trackingId = binding.etTrackingId.getText().toString().trim();

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(phone)) {
            Toast.makeText(this, "Please enter name and phone", Toast.LENGTH_SHORT).show();
            return;
        }

        for (EmergencyContact c : contacts) {
            if (c.phone.equals(phone)) {
                Toast.makeText(this, "Contact already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (!TextUtils.isEmpty(trackingId)) {
            saveContact(name, phone, trackingId);
            Toast.makeText(this, "Contact added ✓", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnAddContact.setEnabled(false);
        binding.btnAddContact.setText("Looking up...");

        FirebaseLocationHelper.lookupByPhone(phone,
                new FirebaseLocationHelper.TrackingIdCallback() {

                    @Override
                    public void onFound(String foundTrackingId) {
                        runOnUiThread(() -> {
                            binding.btnAddContact.setEnabled(true);
                            binding.btnAddContact.setText("+ Add Contact");
                            saveContact(name, phone, foundTrackingId);
                            Toast.makeText(OnboardingActivity.this,
                                    "Tracking connected ✓", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onNotFound() {
                        runOnUiThread(() -> {
                            binding.btnAddContact.setEnabled(true);
                            binding.btnAddContact.setText("+ Add Contact");
                            saveContact(name, phone, "");
                            Toast.makeText(OnboardingActivity.this,
                                    "Contact saved.\nYou can add Tracking ID later.",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    // =========================================
    // SAVE CONTACT
    // =========================================

    private void saveContact(String name, String phone, String trackingId) {
        contacts.add(new EmergencyContact(name, phone, trackingId));
        adapter.notifyItemInserted(contacts.size() - 1);
        prefs.saveContacts(contacts);
        clearInputs();
    }

    // =========================================
    // CLEAR INPUTS
    // =========================================

    private void clearInputs() {
        binding.etName.setText("");
        binding.etPhone.setText("");
        binding.etTrackingId.setText("");
    }

    // =========================================
    // REMOVE CONTACT
    // =========================================

    private void removeContact(int pos) {
        if (pos < 0 || pos >= contacts.size()) return;

        new AlertDialog.Builder(this)
                .setTitle("Remove Contact")
                .setMessage("Are you sure?")
                .setPositiveButton("Remove", (d, w) -> {
                    contacts.remove(pos);
                    adapter.notifyItemRemoved(pos);
                    prefs.saveContacts(contacts);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================
    // FINISH ONBOARDING
    // =========================================

    @Override
    public void finish() {
        prefs.setOnboardingDone();
        startActivity(new Intent(this, MainActivity.class));
        super.finish();
    }
}
