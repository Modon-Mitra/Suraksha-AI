package com.suraksha.ai.agent;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suraksha.ai.ui.FakeCallActivity;
import com.suraksha.ai.ui.FakeCallScript;

/**
 * FakeCallAgent — generates a convincing fake incoming call with TTS voice.
 *
 * Language is selected via chat (not a dialog) so it works from any context.
 * Gender is auto-detected from the caller name.
 */
public class FakeCallAgent extends BaseAgent {

    private static final String TAG = "FakeCallAgent";

    private static final String[] DEFAULT_CALLERS = {
            "Mom", "Priya", "Didi", "Rahul", "Neha", "Rohan"
    };
    private int callerIndex = 0;

    // Pending call state — set when waiting for language selection
    private String  pendingCallerName = null;
    private int     pendingDelayMs    = 0;
    private boolean waitingForLanguage = false;

    public FakeCallAgent(Context context) {
        super(context);
    }

    @Override public String getName()  { return "Fake Call Agent"; }
    @Override public String getEmoji() { return "📱"; }

    // ── Chat entry point ──────────────────────────────────────────────────

    @Override
    public void handleMessage(String userMessage, ChatCallback callback) {
        String lower = userMessage.toLowerCase();

        // ── If waiting for language selection ─────────────────────────────
        if (waitingForLanguage) {
            String lang = parseLanguageChoice(lower);
            if (lang != null) {
                waitingForLanguage = false;
                String caller = pendingCallerName;
                int    delay  = pendingDelayMs;
                pendingCallerName = null;
                pendingDelayMs    = 0;

                boolean isMale = FakeCallScript.isMale(caller);
                callback.onAgentMessage(getName(), getEmoji(),
                        "📱 Incoming call from " + caller
                                + (delay > 0 ? " in " + delay / 1000 + " seconds..." : " now!"));

                if (delay > 0) {
                    triggerFakeCallDelayed(caller, lang, delay);
                } else {
                    triggerFakeCall(caller, lang);
                }
                return;
            }
            // Not a language choice — fall through to normal handling
            waitingForLanguage = false;
        }

        // ── Language specified directly in message ────────────────────────
        // e.g. "fake call from Mom in Hindi"
        if (lower.contains("fake call") || lower.contains("call me")
                || lower.contains("escape") || lower.contains("get me out")
                || lower.contains("distraction")) {

            String callerName = extractCallerName(userMessage);
            int    delayMs    = extractDelay(lower);
            String lang       = extractLanguageFromMessage(lower);

            if (lang != null) {
                // Language specified in message — launch directly
                callback.onAgentMessage(getName(), getEmoji(),
                        "📱 Incoming call from " + callerName
                                + (delayMs > 0 ? " in " + delayMs / 1000 + " seconds..." : " now!"));
                if (delayMs > 0) {
                    triggerFakeCallDelayed(callerName, lang, delayMs);
                } else {
                    triggerFakeCall(callerName, lang);
                }
            } else {
                // No language specified — ask via chat
                pendingCallerName  = callerName;
                pendingDelayMs     = delayMs;
                waitingForLanguage = true;

                boolean isMale = FakeCallScript.isMale(callerName);
                callback.onAgentMessage(getName(), getEmoji(),
                        "📱 Fake call from **" + callerName + "**"
                                + " (" + (isMale ? "👨 Male voice" : "👩 Female voice") + ")\n\n"
                                + "Select language:\n"
                                + "1️⃣  English\n"
                                + "2️⃣  Hindi\n"
                                + "3️⃣  Bengali\n\n"
                                + "Reply with 1, 2, or 3");
            }

        } else if (lower.equals("1") || lower.equals("2") || lower.equals("3")
                || lower.contains("english") || lower.contains("hindi")
                || lower.contains("bengali")) {
            // User replied with language but we lost pending state
            callback.onAgentMessage(getName(), getEmoji(),
                    "Say 'fake call' first, then I'll ask you to pick a language.");

        } else if (lower.contains("help") || lower.contains("how")) {
            callback.onAgentMessage(getName(), getEmoji(),
                    "I generate a fake incoming call to help you escape.\n\n"
                            + "• 'Fake call' — picks from your contacts\n"
                            + "• 'Fake call from Mom' — Mom calls\n"
                            + "• 'Fake call from Rahul in Hindi' — direct\n"
                            + "• 'Fake call from Dad in 10 seconds' — delayed\n\n"
                            + "Supports English, Hindi and Bengali voices.");
        } else {
            callback.onAgentMessage(getName(), getEmoji(),
                    "Say 'fake call' and I'll generate an incoming call to help you escape.");
        }
    }

    // ── Public state getter ──────────────────────────────────────────────

    /** Called by AgentOrchestrator to check if we're waiting for a language reply */
    public boolean isWaitingForLanguage() { return waitingForLanguage; }

    // ── Autonomous trigger ────────────────────────────────────────────────

    @Override
    public void onAutonomousTrigger(ThreatContext ctx) {
        if (ctx.threatLevel == ThreatContext.ThreatLevel.LOW
                || ctx.threatLevel == ThreatContext.ThreatLevel.MEDIUM) {
            postToChatAsAgent(
                    "📱 Feeling uncomfortable? Say 'fake call' and I'll generate "
                            + "a convincing incoming call to help you leave gracefully.");
        }
    }

    // ── Core launch ───────────────────────────────────────────────────────

    public void triggerFakeCall(String callerName, String language) {
        Intent intent = new Intent(context, FakeCallActivity.class);
        intent.putExtra(FakeCallActivity.EXTRA_CALLER_NAME, callerName);
        intent.putExtra(FakeCallActivity.EXTRA_LANGUAGE, language);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.i(TAG, "Fake call → caller=" + callerName
                + " lang=" + language
                + " gender=" + (FakeCallScript.isMale(callerName) ? "male" : "female"));
    }

    public void triggerFakeCallDelayed(String callerName, String language, int delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> triggerFakeCall(callerName, language), delayMs);
    }

    // ── Language parsing ──────────────────────────────────────────────────

    /** Parses language from user choice reply: "1", "2", "3", or name */
    private String parseLanguageChoice(String lower) {
        if (lower.equals("1") || lower.contains("english") || lower.contains("eng"))
            return FakeCallScript.LANG_ENGLISH;
        if (lower.equals("2") || lower.contains("hindi") || lower.contains("hind"))
            return FakeCallScript.LANG_HINDI;
        if (lower.equals("3") || lower.contains("bengali") || lower.contains("bangla")
                || lower.contains("beng"))
            return FakeCallScript.LANG_BENGALI;
        return null;
    }

    /** Extracts language if specified inline: "fake call from Mom in Hindi" */
    private String extractLanguageFromMessage(String lower) {
        if (lower.contains("hindi") || lower.contains("hind"))
            return FakeCallScript.LANG_HINDI;
        if (lower.contains("bengali") || lower.contains("bangla"))
            return FakeCallScript.LANG_BENGALI;
        if (lower.contains("english") || lower.contains("eng"))
            return FakeCallScript.LANG_ENGLISH;
        return null; // not specified
    }

    // ── Caller name + delay parsing ───────────────────────────────────────

    private String extractCallerName(String message) {
        String lower = message.toLowerCase();
        int fromIdx = lower.indexOf(" from ");
        if (fromIdx != -1) {
            String name = message.substring(fromIdx + 6).trim();
            // Strip "in Hindi", "in 10 seconds" suffixes
            for (String strip : new String[]{" in hindi", " in bengali", " in english",
                    " in bangla", " in 1", " in 2", " in 3", " in 4", " in 5",
                    " in 6", " in 7", " in 8", " in 9"}) {
                int idx = name.toLowerCase().indexOf(strip);
                if (idx != -1) name = name.substring(0, idx).trim();
            }
            if (!name.isEmpty()) return name;
        }
        String caller = DEFAULT_CALLERS[callerIndex % DEFAULT_CALLERS.length];
        callerIndex++;
        return caller;
    }

    private int extractDelay(String lower) {
        try {
            int inIdx = lower.indexOf(" in ");
            if (inIdx == -1) return 0;
            String after  = lower.substring(inIdx + 4).trim();
            String[] parts = after.split("\\s+");
            if (parts.length >= 1) {
                int seconds = Integer.parseInt(parts[0]);
                return seconds * 1000;
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }
}
