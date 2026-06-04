package com.suraksha.ai.ui;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * VoiceChatHelper — handles voice input in the Suraksha chat.
 *
 * Uses Android's built-in speech recognition (no API key needed).
 * Returns recognised text directly to SurakshaAgentChatActivity
 * which routes it to the orchestrator like any typed message.
 *
 * USAGE in SurakshaAgentChatActivity:
 *
 *   private VoiceChatHelper voiceHelper;
 *
 *   // In onCreate():
 *   voiceHelper = new VoiceChatHelper(this, text -> {
 *       binding.etMessage.setText(text);
 *       sendMessage();   // auto-send after recognition
 *   });
 *
 *   // In setupInputBar():
 *   binding.btnVoiceInput.setOnClickListener(v -> voiceHelper.startListening());
 *
 *   // In onActivityResult():
 *   voiceHelper.handleActivityResult(requestCode, resultCode, data);
 */
public class VoiceChatHelper {

    public static final int REQUEST_VOICE = 201;

    public interface VoiceResultCallback {
        void onResult(String recognisedText);
    }

    private final Activity         activity;
    private final VoiceResultCallback callback;

    public VoiceChatHelper(Activity activity, VoiceResultCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity,
                    "Speech recognition not available on this device",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Suraksha...");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            activity.startActivityForResult(intent, REQUEST_VOICE);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Could not start voice input", Toast.LENGTH_SHORT).show();
        }
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_VOICE) return;
        if (resultCode != Activity.RESULT_OK || data == null) return;

        ArrayList<String> results = data.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS);

        if (results != null && !results.isEmpty()) {
            String text = results.get(0);
            if (text != null && !text.isEmpty()) {
                callback.onResult(text);
            }
        }
    }
}
