package com.suraksha.ai.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.suraksha.ai.utils.PrefsManager;

/**
 * BaseAgent — abstract parent all 9 agents extend.
 *
 * Every agent gets:
 *  - context / prefs for Android access
 *  - a ChatCallback to post messages into the chat UI
 *  - two entry points: handleMessage() for user-initiated chat
 *                      onAutonomousTrigger() for sensor-driven activation
 *  - postToChatAsAgent() helper so agents can send replies without
 *    worrying about which thread they're on
 */
public abstract class BaseAgent {

    // ── Injected by AgentOrchestrator ─────────────────────────────────────
    protected Context      context;
    protected PrefsManager prefs;

    /** Implement to send a message bubble into the Suraksha chat UI. */
    public interface ChatCallback {
        /**
         * @param agentName  Display name, e.g. "Smart SOS Agent"
         * @param emoji      Single emoji prefix, e.g. "🚨"
         * @param message    The message text to show
         */
        void onAgentMessage(String agentName, String emoji, String message);
    }

    protected ChatCallback chatCallback;

    // ── Constructor ───────────────────────────────────────────────────────

    public BaseAgent(Context context) {
        this.context = context.getApplicationContext();
        this.prefs   = new PrefsManager(this.context);
    }

    // ── Identity (each agent implements these) ────────────────────────────

    /** Short display name shown in chat UI, e.g. "Threat Detection Agent" */
    public abstract String getName();

    /** Single emoji shown before agent name in chat, e.g. "🎙️" */
    public abstract String getEmoji();

    // ── Entry points ──────────────────────────────────────────────────────

    /**
     * Called when the user types or speaks a message in the Suraksha chat.
     * The orchestrator routes the message here if it's relevant to this agent.
     *
     * @param userMessage  Raw text the user sent
     * @param callback     Used to send replies back to the chat UI
     */
    public abstract void handleMessage(String userMessage, ChatCallback callback);

    /**
     * Called autonomously by AgentOrchestrator when sensor data or a code
     * word trigger suggests danger — no user input involved.
     *
     * @param ctx  Snapshot of current threat context (threat level, location, BPM…)
     */
    public abstract void onAutonomousTrigger(ThreatContext ctx);

    // ── Helper ────────────────────────────────────────────────────────────

    /**
     * Posts a message to the chat UI from this agent.
     * Safe to call from any thread — always runs on main thread.
     */
    protected void postToChatAsAgent(String message) {
        if (chatCallback == null) return;
        new Handler(Looper.getMainLooper()).post(() ->
                chatCallback.onAgentMessage(getName(), getEmoji(), message));
    }

    /** Inject the chat callback (called by AgentOrchestrator on init). */
    public void setChatCallback(ChatCallback cb) {
        this.chatCallback = cb;
    }
}
