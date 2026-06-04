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
 * CyberHarassmentAgent — analyses messages for threatening/abusive content.
 * Uses Groq API (completely free, no billing, 14,400 req/day).
 * Model: llama3-8b-8192
 */
public class CyberHarassmentAgent extends BaseAgent {

    private static final String TAG = "CyberHarassmentAgent";

    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are a cyber harassment analyst for a women's safety app. "
                    + "Analyse the message the user provides and respond ONLY with a JSON object "
                    + "in this exact format (no markdown, no extra text, no code blocks):\n"
                    + "{\n"
                    + "  \"category\": \"threatening|abusive|manipulative|safe\",\n"
                    + "  \"severity\": 1,\n"
                    + "  \"explanation\": \"one sentence explaining why\",\n"
                    + "  \"recommendation\": \"one clear action the user should take\",\n"
                    + "  \"evidence_tip\": \"how to preserve evidence if needed\"\n"
                    + "}\n"
                    + "severity must be an integer 1-10. 1=harmless, 10=immediate danger. "
                    + "Return ONLY the JSON. No other text whatsoever.";

    public CyberHarassmentAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Cyber Harassment Agent"; }
    @Override public String getEmoji() { return "💬"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        if (lower.contains("is this") || lower.contains("analyse")
                || lower.contains("analyze") || lower.contains("check this")
                || lower.contains("threatening") || lower.contains("abusive")
                || lower.contains("harass") || lower.contains("he said")
                || lower.contains("she said") || lower.contains("they said")
                || lower.contains("someone sent") || lower.contains("this message")
                || userMessage.length() > 30) {

            callback.onAgentMessage(getName(), getEmoji(),
                    "Analysing message for threats... 🔍");
            analyseWithGroq(userMessage, callback);

        } else if (lower.contains("help") || lower.contains("how")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Paste any suspicious message into the chat and I'll analyse it "
                            + "for threats, abuse, or manipulation.\n\n"
                            + "I'll tell you:\n"
                            + "• How serious it is (1-10 severity)\n"
                            + "• What type of harassment it is\n"
                            + "• What you should do\n"
                            + "• How to preserve evidence");
        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Paste the message you want me to analyse.");
        }
    }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        postToChatAsAgent(
                "💬 If you've received threatening or abusive messages, "
                        + "paste them here and I'll analyse them for you.");
    }

    // ── Groq API analysis ─────────────────────────────────────────────────

    private void analyseWithGroq(String message, ChatCallback callback) {
        new Thread(() -> {
            try {
                String apiKey = BuildConfig.GROQ_API_KEY;

                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", SYSTEM_PROMPT);
                messages.put(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "Analyse this message: " + message);
                messages.put(userMsg);

                JSONObject body = new JSONObject();
                body.put("model", "llama3-8b-8192");
                body.put("messages", messages);
                body.put("max_tokens", 300);
                body.put("temperature", 0.1);

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

                    JSONObject response = new JSONObject(sb.toString());
                    String raw = response
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim();

                    // Strip accidental markdown fences from LLM output
                    raw = raw.replaceAll("(?s)```json\\s*", "")
                            .replaceAll("```", "")
                            .trim();

                    JSONObject result      = new JSONObject(raw);
                    String category        = result.getString("category");
                    int    severity        = result.getInt("severity");
                    String explanation     = result.getString("explanation");
                    String recommendation  = result.getString("recommendation");
                    String evidenceTip     = result.getString("evidence_tip");

                    String severityEmoji   = severity >= 8 ? "🔴"
                            : severity >= 5 ? "🟡" : "🟢";

                    String reply = severityEmoji + " **" + category.toUpperCase()
                            + "** (severity: " + severity + "/10)\n\n"
                            + "📋 " + explanation + "\n\n"
                            + "✅ What to do: " + recommendation + "\n\n"
                            + "📁 Evidence tip: " + evidenceTip;

                    callback.onAgentMessage(getName(), getEmoji(), reply);

                    if (severity >= 8) {
                        callback.onAgentMessage(getName(), getEmoji(),
                                "⚠️ This message indicates serious danger.\n"
                                        + "Consider contacting police (100) immediately.\n"
                                        + "Say 'send SOS' to alert your emergency contacts.");
                    }

                } else {
                    Log.e(TAG, "Groq API error: " + code);
                    fallbackAnalysis(message, callback);
                }

            } catch (Exception e) {
                Log.e(TAG, "Groq analysis failed: " + e.getMessage());
                fallbackAnalysis(message, callback);
            }
        }, "CyberHarassThread").start();
    }

    // ── Keyword fallback when API unavailable ─────────────────────────────

    private void fallbackAnalysis(String message, ChatCallback callback) {
        String lower = message.toLowerCase();
        boolean isThreatening = lower.contains("kill") || lower.contains("hurt")
                || lower.contains("find you") || lower.contains("come for you")
                || lower.contains("marunga") || lower.contains("teri khair nahi")
                || lower.contains("chod nahi") || lower.contains("rape") || lower.contains("gangrape")
                || lower.contains("mere felbo") || lower.contains("fuck") || lower.contains("asshole")
                || lower.contains("motherfucker") || lower.contains("fucker") || lower.contains("bitch")
                || lower.contains("dick") || lower.contains("pussy") || lower.contains("cunt")
                || lower.contains("choda")|| lower.contains("chudbo");

        if (isThreatening) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "🔴 **THREATENING** — This message contains threatening language.\n\n"
                            + "✅ What to do: Screenshot and save immediately, then block sender.\n\n"
                            + "📁 Evidence tip: Don't delete — report to police with screenshots.\n\n"
                            + "National helpline: 1091");
        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Analysis complete. If this feels threatening, trust your instincts.\n\n"
                            + "Women Helpline: 1091");
        }
    }
}
