package com.suraksha.ai.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.suraksha.ai.R;
import androidx.core.content.ContextCompat;

import com.suraksha.ai.service.MonitoringService;
import com.suraksha.ai.databinding.FragmentSettingsBinding;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.ui.CodeWordSetupActivity;
import com.suraksha.ai.utils.FirebaseLocationHelper;
import com.suraksha.ai.utils.PrefsManager;
import com.suraksha.ai.utils.UserGuideDownloader;

import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private final List<EmergencyContact> contacts = new ArrayList<>();
    private ContactsAdapter adapter;
    private PrefsManager prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs            = new PrefsManager(requireContext());

        adapter = new ContactsAdapter(contacts, this::removeContact);
        binding.rvContacts.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvContacts.setAdapter(adapter);

        binding.tvMyTrackingId.setText(prefs.getUserId());

        String savedPhone = prefs.getMyPhone();
        if (!savedPhone.isEmpty()) binding.etMyPhone.setText(savedPhone);
        binding.btnSaveMyPhone.setOnClickListener(v -> saveMyPhone());

        binding.etHrHigh.setText(String.valueOf(prefs.getHrHighThreshold()));
        binding.etHrLow.setText(String.valueOf(prefs.getHrLowThreshold()));

        binding.btnAddContact.setOnClickListener(v -> addContact());
        binding.btnSaveThresholds.setOnClickListener(v -> saveThresholds());

        binding.btnDownloadGuide.setOnClickListener(v ->
                UserGuideDownloader.download(requireContext()));
        binding.btnShareTracking.setOnClickListener(v -> shareTrackingId());

        // Home address (for Safe Route)
        String savedHome = prefs.getHomeAddress();
        if (!savedHome.isEmpty()) binding.etHomeAddress.setText(savedHome);
        binding.btnSaveHome.setOnClickListener(v -> {
            String addr = binding.etHomeAddress.getText().toString().trim();
            if (addr.isEmpty()) {
                binding.etHomeAddress.setError("Enter your home address");
                return;
            }
            prefs.setHomeAddress(addr);
            Toast.makeText(requireContext(), "Home address saved ✓",
                    Toast.LENGTH_SHORT).show();
        });

        // Voice verification toggle
        binding.switchVoiceVerify.setChecked(prefs.isVoiceVerifyEnabled());
        binding.switchVoiceVerify.setOnCheckedChangeListener((b, checked) -> {
            prefs.setVoiceVerifyEnabled(checked);
            Toast.makeText(requireContext(),
                    checked ? "Voice verification enabled"
                            : "Voice verification disabled",
                    Toast.LENGTH_SHORT).show();
        });

        setupCodeWord();
        loadContacts();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadContacts();
    }

    // =========================
    // Code Word
    // =========================

    private void setupCodeWord() {
        String savedWord = prefs.getCodeWord();
        if (savedWord != null && !savedWord.isEmpty()) {
            binding.tvCodeWordStatus.setText("✓ Code word set: \"" + savedWord + "\"");
            binding.tvCodeWordStatus.setTextColor(
                    requireContext().getColor(R.color.safe_green));
            binding.btnSetCodeWord.setText("🎤  Change Code Word");
        } else {
            binding.tvCodeWordStatus.setText("○ No code word set");
            binding.tvCodeWordStatus.setTextColor(
                    requireContext().getColor(R.color.text_secondary));
        }

        binding.switchCodeWord.setChecked(prefs.isCodeWordEnabled());

        binding.btnSetCodeWord.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CodeWordSetupActivity.class);
            startActivityForResult(intent, 901);
        });

        binding.switchCodeWord.setOnCheckedChangeListener((v, checked) -> {
            String word = prefs.getCodeWord();
            if (checked && (word == null || word.isEmpty())) {
                binding.switchCodeWord.setChecked(false);
                Toast.makeText(requireContext(),
                        "Set a code word first", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.setCodeWordEnabled(checked);
            if (checked) {
                sendServiceCommand(MonitoringService.CMD_CODEWORD_START);
                Toast.makeText(requireContext(),
                        "Code word detection ON", Toast.LENGTH_SHORT).show();
            } else {
                sendServiceCommand(MonitoringService.CMD_CODEWORD_STOP);
                Toast.makeText(requireContext(),
                        "Code word detection OFF — media won't be interrupted now",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Sends a command to the single CodeWordDetector that lives in the service. */
    private void sendServiceCommand(String cmd) {
        Intent i = new Intent(requireContext(), MonitoringService.class);
        i.putExtra("cmd", cmd);
        ContextCompat.startForegroundService(requireContext(), i);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 901) {
            setupCodeWord();
            if (prefs.isCodeWordEnabled()) {
                // Code word now active — start it in the service (single detector)
                sendServiceCommand(MonitoringService.CMD_CODEWORD_START);
            }
        }
    }

    // =========================
    // Save My Phone Number
    // =========================

    private void saveMyPhone() {
        String phone = binding.etMyPhone.getText().toString().trim();
        if (TextUtils.isEmpty(phone)) {
            Toast.makeText(requireContext(), "Enter your phone number", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setMyPhone(phone);
        FirebaseLocationHelper.registerPhone(prefs.getUserId(), phone);
        Toast.makeText(requireContext(),
                "Phone number saved ✓ Others can now find you", Toast.LENGTH_SHORT).show();
    }

    // =========================
    // Load Contacts
    // =========================

    private void loadContacts() {
        contacts.clear();
        contacts.addAll(prefs.getContacts());
        if (binding != null) adapter.notifyDataSetChanged();
    }

    // =========================
    // Add Contact
    // =========================

    private void addContact() {
        String name             = binding.etName.getText().toString().trim();
        String phone            = binding.etPhone.getText().toString().trim();
        String manualTrackingId = binding.etTrackingId.getText().toString().trim();

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(phone)) {
            Toast.makeText(requireContext(),
                    "Enter name and phone number", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!TextUtils.isEmpty(manualTrackingId)) {
            saveContact(name, phone, manualTrackingId);
            return;
        }

        binding.btnAddContact.setEnabled(false);
        binding.btnAddContact.setText("Looking up...");

        FirebaseLocationHelper.lookupByPhone(phone,
                new FirebaseLocationHelper.TrackingIdCallback() {
                    @Override
                    public void onFound(String trackingId) {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            binding.btnAddContact.setEnabled(true);
                            binding.btnAddContact.setText("+ Add Contact");
                            saveContact(name, phone, trackingId);
                        });
                    }

                    @Override
                    public void onNotFound() {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            binding.btnAddContact.setEnabled(true);
                            binding.btnAddContact.setText("+ Add Contact");
                            Toast.makeText(requireContext(),
                                    "Could not find automatically. Ask them to save their "
                                            + "phone number in Settings, or enter Tracking ID manually.",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void saveContact(String name, String phone, String trackingId) {
        contacts.add(new EmergencyContact(name, phone, trackingId));
        adapter.notifyItemInserted(contacts.size() - 1);
        prefs.saveContacts(contacts);
        binding.etName.setText("");
        binding.etPhone.setText("");
        binding.etTrackingId.setText("");
        Toast.makeText(requireContext(), "Contact added ✓", Toast.LENGTH_SHORT).show();
    }

    // =========================
    // Remove Contact
    // =========================

    private void removeContact(int pos) {
        contacts.remove(pos);
        adapter.notifyItemRemoved(pos);
        prefs.saveContacts(contacts);
    }

    // =========================
    // Share Tracking ID
    // =========================

    private void shareTrackingId() {
        String msg = "My Suraksha Tracking ID:\n\n"
                + prefs.getUserId()
                + "\n\nTrack me using Suraksha AI.";
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(intent, "Share Tracking ID"));
    }

    // =========================
    // Save Thresholds
    // =========================

    private void saveThresholds() {
        try {
            String highStr = binding.etHrHigh.getText().toString().trim();
            String lowStr  = binding.etHrLow.getText().toString().trim();

            if (highStr.isEmpty() || lowStr.isEmpty()) {
                Toast.makeText(requireContext(),
                        "Please enter both values", Toast.LENGTH_SHORT).show();
                return;
            }

            int high = Integer.parseInt(highStr);
            int low  = Integer.parseInt(lowStr);

            if (high < 100 || high > 220) {
                binding.etHrHigh.setError("Must be 100–220");
                binding.etHrHigh.requestFocus();
                Toast.makeText(requireContext(),
                        "High BPM should be between 100 and 220",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (low < 30 || low > 90) {
                binding.etHrLow.setError("Must be 30–90");
                binding.etHrLow.requestFocus();
                Toast.makeText(requireContext(),
                        "Low BPM should be between 30 and 90",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (low >= high) {
                binding.etHrLow.setError("Must be below High");
                Toast.makeText(requireContext(),
                        "Low threshold must be less than High", Toast.LENGTH_SHORT).show();
                return;
            }
            if (high - low < 20) {
                Toast.makeText(requireContext(),
                        "Keep at least 20 bpm between Low and High",
                        Toast.LENGTH_LONG).show();
                return;
            }

            prefs.setHrThresholds(high, low);
            Toast.makeText(requireContext(),
                    "Thresholds saved ✓  (Low " + low + " – High " + high + " bpm)",
                    Toast.LENGTH_SHORT).show();

        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(),
                    "Enter valid whole numbers only", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // NOTE: we do NOT stop code word detection here. It lives in the
        // foreground service and must keep running when Settings is closed.
        // The user controls it via the toggle (CMD_CODEWORD_START/STOP).
        binding = null;
    }
}