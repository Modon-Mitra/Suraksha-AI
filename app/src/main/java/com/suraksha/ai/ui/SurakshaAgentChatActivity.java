package com.suraksha.ai.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.suraksha.ai.agent.AgentOrchestrator;
import com.suraksha.ai.agent.BaseAgent;
import com.suraksha.ai.databinding.ActivitySurakshaAgentChatBinding;

import java.util.ArrayList;
import java.util.List;

public class SurakshaAgentChatActivity extends AppCompatActivity {

    private ActivitySurakshaAgentChatBinding binding;
    private AgentOrchestrator orchestrator;
    private ChatMessageAdapter adapter;
    private final List<ChatMessage> messages = new ArrayList<>();

    // ── Voice input ───────────────────────────────────────────────────────
    private VoiceChatHelper voiceHelper;
    private boolean         micActive = false;

    // ── Chat message model ────────────────────────────────────────────────

    public static class ChatMessage {
        public static final int TYPE_USER  = 0;
        public static final int TYPE_AGENT = 1;

        public int    type;
        public String text;
        public String agentName;
        public String agentEmoji;

        public ChatMessage(int type, String text, String agentName, String agentEmoji) {
            this.type      = type;
            this.text      = text;
            this.agentName = agentName;
            this.agentEmoji = agentEmoji;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySurakshaAgentChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        orchestrator = AgentOrchestrator.getInstance(this);

        // Init voice helper — recognised text auto-fills and sends
        voiceHelper = new VoiceChatHelper(this, recognisedText -> {
            binding.etMessage.setText(recognisedText);
            sendMessage();
        });

        setupRecyclerView();
        setupInputBar();
        setupHeader();
        updateAgentStatusBadge();

        orchestrator.setChatCallback(chatCallback);

        // Welcome message
        addAgentMessage("Suraksha", "🛡️",
                "Hello! I'm Suraksha, your personal safety AI. "
                        + "I have " + orchestrator.getAgentCount() + " agents ready.\n\n"
                        + "You can type or tap 🎤 to speak. Try:\n"
                        + "• 'I feel unsafe'\n"
                        + "• 'Find nearest police station'\n"
                        + "• 'Tell me about this app'");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Reset mic button state
        setMicActive(false);
        voiceHelper.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        orchestrator.clearChatCallback();
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new ChatMessageAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(lm);
        binding.rvMessages.setAdapter(adapter);
    }

    private void setupInputBar() {
        binding.btnSend.setOnClickListener(v -> sendMessage());

        binding.etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        // Voice input — toggles mic state visually
        binding.btnVoiceInput.setOnClickListener(v -> {
            setMicActive(true);
            voiceHelper.startListening();
        });
    }

    private void setupHeader() {
        binding.btnBack.setOnClickListener(v -> finish());
    }

    private void updateAgentStatusBadge() {
        int count = orchestrator.getAgentCount();
        binding.tvAgentStatus.setText(count + " agent" + (count == 1 ? "" : "s") + " ready");
    }

    // ── Mic state visual feedback ─────────────────────────────────────────

    private void setMicActive(boolean active) {
        micActive = active;
        binding.btnVoiceInput.setAlpha(active ? 0.5f : 1.0f);
        if (active) {
            showTypingStatus(true);
            binding.tvTypingStatus.setText("Listening...");
        } else {
            showTypingStatus(false);
        }
    }

    // ── Sending messages ──────────────────────────────────────────────────

    private void sendMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        if (text.toLowerCase().contains("cancel")) {
            orchestrator.cancelSosCountdown();
        }

        addUserMessage(text);
        binding.etMessage.setText("");
        showTypingStatus(true);
        orchestrator.onUserMessage(text, chatCallback);
    }

    // ── Chat callback ─────────────────────────────────────────────────────

    private final BaseAgent.ChatCallback chatCallback = (agentName, emoji, message) ->
            runOnUiThread(() -> {
                showTypingStatus(false);
                addAgentMessage(agentName, emoji, message);
            });

    // ── Message helpers ───────────────────────────────────────────────────

    private void addUserMessage(String text) {
        messages.add(new ChatMessage(ChatMessage.TYPE_USER, text, null, null));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    private void addAgentMessage(String agentName, String emoji, String text) {
        messages.add(new ChatMessage(ChatMessage.TYPE_AGENT, text, agentName, emoji));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();
    }

    private void scrollToBottom() {
        binding.rvMessages.smoothScrollToPosition(messages.size() - 1);
    }

    private void showTypingStatus(boolean show) {
        binding.tvTypingStatus.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show && !micActive) binding.tvTypingStatus.setText("Agent is thinking...");
    }
}