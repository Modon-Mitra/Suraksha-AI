package com.suraksha.ai.agent;

import android.content.Context;
import android.util.Log;

import com.suraksha.ai.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * EmotionalSupportAgent — warm, empathetic guidance after emergencies.
 * Uses Groq API (completely free, no billing, 14,400 req/day).
 * Model: llama3-8b-8192
 */
public class EmotionalSupportAgent extends BaseAgent {

    private static final String TAG = "EmotionalSupportAgent";

    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are a compassionate mental health support companion in a women's safety app "
                    + "called Suraksha AI. The user has just experienced a stressful or potentially "
                    + "dangerous situation. Be warm, calm, and non-judgmental. Use simple language. "
                    + "If the user seems very distressed, offer a grounding technique (5-4-3-2-1 senses "
                    + "or box breathing: inhale 4s, hold 4s, exhale 4s, hold 4s). "
                    + "When appropriate, mention these Indian crisis helplines: "
                    + "iCall: 9152987821, Vandrevala Foundation: 1860-2662-345, "
                    + "Women Helpline: 1091. "
                    + "Keep responses under 80 words. Be conversational, not clinical. "
                    + "Never say you are an AI unless directly asked.";

    public EmotionalSupportAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Emotional Support Agent"; }
    @Override public String getEmoji() { return "💚"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        callback.onAgentMessage(getName(), getEmoji(), "I'm here with you... 💚");
        askGroq(userMessage, callback);
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        postToChatAsAgent(
                "I'm here with you. You're safe now. 💚\n\n"
                        + "Would you like to do a quick breathing exercise together?\n\n"
                        + "Just reply 'yes' or tell me how you're feeling.");
    }

    // ── Groq API call (OpenAI-compatible format) ──────────────────────────

    private void askGroq(String userMessage, ChatCallback callback) {
        new Thread(() -> {
            try {
                String apiKey = BuildConfig.GROQ_API_KEY;

                // Groq uses OpenAI-compatible format
                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PROMPT);
                messages.put(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userMessage);
                messages.put(userMsg);

                JSONObject body = new JSONObject();
                body.put("model", "llama3-8b-8192");
                body.put("messages", messages);
                body.put("max_tokens", 200);
                body.put("temperature", 0.7);

                HttpURLConnection conn =
                        (HttpURLConnection) new URL(GROQ_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }

                    // OpenAI-compatible response format
                    JSONObject response = new JSONObject(sb.toString());
                    String reply = response
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");

                    callback.onAgentMessage(getName(), getEmoji(), reply.trim());

                } else {
                    Log.e(TAG, "Groq API error: " + code);
                    fallbackResponse(callback);
                }

            } catch (Exception e) {
                Log.e(TAG, "Groq call failed: " + e.getMessage());
                fallbackResponse(callback);
            }
        }, "EmotionalSupportThread").start();
    }

    private void fallbackResponse(ChatCallback callback) {
        callback.onAgentMessage(getName(), getEmoji(),
                "You're safe now. Take a slow, deep breath with me.\n\n"
                        + "Breathe in for 4... hold for 4... breathe out for 4. 🌿\n\n"
                        + "If you need to talk to someone:\n"
                        + "iCall: 9152987821\nWomen Helpline: 1091");
    }
}
