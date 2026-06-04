package com.suraksha.ai.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.suraksha.ai.agent.trigger.CodeWordDetector;
import com.suraksha.ai.model.EmergencyContact;
import com.suraksha.ai.model.SensorReading;
import com.suraksha.ai.utils.PrefsManager;

import java.util.ArrayList;
import java.util.List;

public class AgentOrchestrator {

    private static final String TAG = "AgentOrchestrator";

    private static AgentOrchestrator instance;

    public static AgentOrchestrator getInstance(Context context) {
        if (instance == null) {
            instance = new AgentOrchestrator(context.getApplicationContext());
        }
        return instance;
    }

    private final Context        context;
    private final PrefsManager   prefs;
    private final Handler        mainHandler = new Handler(Looper.getMainLooper());
    private final List<BaseAgent> agents     = new ArrayList<>();

    private BaseAgent.ChatCallback chatCallback;
    private ThreatContext.ThreatLevel lastThreatLevel = ThreatContext.ThreatLevel.LOW;
    private boolean emergencyCascadeActive = false;
    private Runnable pendingSosRunnable;

    // Code word debounce
    private long lastCodeWordTimeMs = 0;
    private static final long CODE_WORD_DEBOUNCE_MS = 10_000;

    private AgentOrchestrator(Context context) {
        this.context = context;
        this.prefs   = new PrefsManager(context);
        initAgents();
        registerCodeWordReceiver();
    }

    // ── Agent Registry ────────────────────────────────────────────────────

    private void initAgents() {
        agents.add(new ThreatDetectionAgent(context));
        agents.add(new SmartSosAgent(context));
        agents.add(new SafeRouteAgent(context));
        agents.add(new EvidenceCollectionAgent(context));
        agents.add(new FakeCallAgent(context));
        agents.add(new BehaviorPredictionAgent(context));
        agents.add(new NearbyGuardianAgent(context));
        agents.add(new CyberHarassmentAgent(context));
        agents.add(new EmotionalSupportAgent(context));
        agents.add(new HumanActivityAgent(context));
        ((HumanActivityAgent) agents.get(agents.size()-1)).start();
        Log.i(TAG, "AgentOrchestrator ready. Agents registered: " + agents.size());
    }

    // ── Chat Callback ─────────────────────────────────────────────────────

    public void setChatCallback(BaseAgent.ChatCallback cb) {
        this.chatCallback = cb;
        for (BaseAgent agent : agents) agent.setChatCallback(cb);
    }

    public void clearChatCallback() { this.chatCallback = null; }

    // ── User Message Entry Point ──────────────────────────────────────────

    public void onUserMessage(String message, BaseAgent.ChatCallback callback) {
        setChatCallback(callback);

        if (agents.isEmpty()) {
            mainHandler.post(() -> callback.onAgentMessage("Suraksha", "🛡️",
                    "Agents are initialising."));
            return;
        }

        String lower = message.toLowerCase().trim();

        // Step 0: Priority — if FakeCallAgent is waiting for language reply,
        // route directly to it before any other processing
        FakeCallAgent fakeCallAgent = (FakeCallAgent) getAgentByName("Fake Call Agent");
        if (fakeCallAgent != null && fakeCallAgent.isWaitingForLanguage()) {
            fakeCallAgent.handleMessage(message, callback);
            return;
        }

        // Step 1: Greetings
        if (isGreeting(lower)) {
            mainHandler.post(() -> callback.onAgentMessage(
                    "Suraksha", "🛡️", getGreetingResponse()));
            return;
        }

        // Step 2: App info / how to use
        if (isAppInfo(lower)) {
            mainHandler.post(() -> callback.onAgentMessage(
                    "Suraksha", "🛡️", getAppInfoResponse(lower)));
            return;
        }

        // Step 2.5: Mood check — distinguish "I'm fine" (positive) from
        // "I'm not feeling good" (distress). Negation is checked FIRST so
        // phrases like "not feeling good" don't match the positive branch.
        String moodResponse = checkMood(lower);
        if (moodResponse != null) {
            mainHandler.post(() -> callback.onAgentMessage(
                    "Suraksha", "🛡️", moodResponse));
            return;
        }

        // Step 3: Small talk
        if (isSmallTalk(lower)) {
            mainHandler.post(() -> callback.onAgentMessage(
                    "Suraksha", "🛡️", getSmallTalkResponse(lower)));
            return;
        }

        // Step 4: Direct action commands
        String directResponse = handleDirectCommand(lower, message);
        if (directResponse != null) {
            mainHandler.post(() -> callback.onAgentMessage(
                    "Suraksha", "🛡️", directResponse));
            return;
        }

        // Step 5: Route to specific agent
        BaseAgent target = routeMessage(lower);
        if (target != null) {
            target.handleMessage(message, callback);
            return;
        }

        // Step 6: No match — show menu
        mainHandler.post(() -> callback.onAgentMessage("Suraksha", "🛡️",
                "I'm here for you. How can I help?\n\n"
                        + "📍 *Location*\n"
                        + "  • 'Share my location'\n"
                        + "  • 'Where am I'\n\n"
                        + "📞 *Calls*\n"
                        + "  • 'Call mom / dad / home'\n"
                        + "  • 'Call [contact name]'\n\n"
                        + "🚨 *Safety*\n"
                        + "  • 'I feel unsafe'\n"
                        + "  • 'Send SOS'\n"
                        + "  • 'Find nearest police'\n"
                        + "  • 'Start recording'\n\n"
                        + "💬 *Other*\n"
                        + "  • 'Safe route home'\n"
                        + "  • 'Send fake call'\n"
                        + "  • 'I need support'\n"
                        + "  • 'About this app'"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // STEP 4 — DIRECT ACTION COMMANDS
    // ─────────────────────────────────────────────────────────────────────

    private String handleDirectCommand(String lower, String original) {

        // ── Cancel SOS ────────────────────────────────────────────────────
        if (lower.equals("cancel") || lower.equals("stop")
                || lower.contains("cancel sos") || lower.contains("stop sos")
                || lower.contains("cancel countdown") || lower.contains("i'm safe")
                || lower.contains("i am safe") || lower.contains("main theek hoon")
                || lower.contains("main safe hoon")) {
            cancelSosCountdown();
            emergencyCascadeActive = false;
            return "✅ SOS cancelled. Glad you're safe! 💚\n\nStay alert. I'm always here.";
        }

        // ── Share location with contacts ──────────────────────────────────
        if (lower.contains("share my location") || lower.contains("send my location")
                || lower.contains("share location") || lower.contains("send location")
                || lower.contains("location share karo") || lower.contains("location bhejo")
                || lower.contains("location send karo") || lower.contains("apna location")
                || lower.contains("tell them where i am") || lower.contains("notify contacts")) {
            return handleShareLocation();
        }

        // ── Where am I ────────────────────────────────────────────────────
        if (lower.equals("where am i") || lower.contains("what's my location")
                || lower.contains("my current location") || lower.contains("show my location")
                || lower.contains("mera location") || lower.contains("main kahan hoon")) {
            return handleWhereAmI();
        }

        // ── I'm safe / check-in ───────────────────────────────────────────
        if (lower.contains("i reached") || lower.contains("i have reached")
                || lower.contains("reached home") || lower.contains("reached safely")
                || lower.contains("pahunch gaya") || lower.contains("pahunch gayi")
                || lower.contains("tell them i'm safe") || lower.contains("notify i'm safe")
                || lower.contains("send safe message")) {
            return handleSafeCheckIn();
        }

        // ── Call mom ──────────────────────────────────────────────────────
        if (isMomCall(lower)) {
            return handleCallRelation("mom", new String[]{
                    "mom", "maa", "mamoni", "ma", "mother", "mummy",
                    "mumma", "aai", "amma", "mata"
            });
        }

        // ── Call dad ──────────────────────────────────────────────────────
        if (isDadCall(lower)) {
            return handleCallRelation("dad", new String[]{
                    "dad", "baba", "bapi", "papa", "father", "dada",
                    "bapu", "abba", "pita", "daddy"
            });
        }

        // ── Call home ─────────────────────────────────────────────────────
        if (lower.contains("call home") || lower.equals("call ghar")
                || lower.contains("phone ghar")) {
            return handleCallRelation("home", new String[]{
                    "home", "house", "ghar"
            });
        }

        // ── Call a specific contact by name ───────────────────────────────
        if (lower.startsWith("call ") || lower.startsWith("phone ")
                || lower.startsWith("ring ") || lower.startsWith("dial ")) {
            String targetName = extractCallTarget(lower);
            if (targetName != null && !targetName.isEmpty()) {
                return handleCallByName(targetName);
            }
        }

        // ── Show my contacts ──────────────────────────────────────────────
        if (lower.contains("show contacts") || lower.contains("my contacts")
                || lower.contains("trusted contacts") || lower.contains("emergency contacts")
                || lower.contains("list contacts") || lower.contains("mere contacts")
                || lower.contains("show guardians")) {
            return handleShowContacts();
        }

        // ── Battery / phone status ────────────────────────────────────────
        if (lower.contains("battery") || lower.contains("phone status")
                || lower.contains("battery kitna hai") || lower.contains("charge")) {
            return handleBatteryStatus();
        }

        // ── Current activity ──────────────────────────────────────────────
        if (lower.equals("what am i doing") || lower.contains("current activity")
                || lower.contains("am i walking") || lower.contains("meri activity")
                || lower.contains("detect activity")) {
            BaseAgent har = getAgentByName("Activity Agent");
            if (har != null && chatCallback != null) {
                har.handleMessage(original, chatCallback);
                return "";
            }
        }

        // ── Quick SOS ─────────────────────────────────────────────────────
        if (lower.equals("sos") || lower.equals("help") || lower.equals("help me")
                || lower.equals("bachao") || lower.equals("help karo")
                || lower.equals("madad karo")) {
            ThreatContext ctx = ThreatContext.fromCodeWord(true, 0.0, 0.0);
            onThreatLevelChanged(ctx);
            return "🚨 Emergency cascade activated! Alerting your contacts and agents now.";
        }

        return null;
    }

    // ── Location sharing ──────────────────────────────────────────────────

    private String handleShareLocation() {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts == null || contacts.isEmpty()) {
            return "📍 No trusted contacts saved yet.\n\n"
                    + "Go to Settings → Trusted Contacts to add someone.\n"
                    + "Once added, I'll share your live location with them.";
        }
        ThreatContext ctx = new ThreatContext();
        ctx.triggerReason = "Manual location share";
        ctx.threatLevel   = ThreatContext.ThreatLevel.MEDIUM;
        triggerAgentByName("Smart SOS Agent", ctx);
        StringBuilder sb = new StringBuilder("📍 Sharing your live location with:\n\n");
        for (EmergencyContact c : contacts) {
            sb.append("• ").append(c.name).append("\n");
        }
        sb.append("\nThey'll receive your Google Maps link. Stay safe! 💚");
        return sb.toString();
    }

    private String handleWhereAmI() {
        ThreatContext ctx = new ThreatContext();
        ctx.triggerReason = "Location check";
        triggerAgentByName("Nearby Guardian Agent", ctx);
        return "📍 Fetching your current location...\n\n"
                + "I'm also scanning for the nearest police station, "
                + "hospital, and safe places around you.";
    }

    private String handleSafeCheckIn() {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts == null || contacts.isEmpty()) {
            return "✅ Glad you're safe! 💚\n\n"
                    + "Add trusted contacts in Settings so I can notify them next time.";
        }
        ThreatContext ctx = new ThreatContext();
        ctx.triggerReason = "Safe check-in";
        ctx.threatLevel   = ThreatContext.ThreatLevel.LOW;
        triggerAgentByName("Smart SOS Agent", ctx);
        StringBuilder sb = new StringBuilder("✅ Safe arrival message sent to:\n\n");
        for (EmergencyContact c : contacts) {
            sb.append("• ").append(c.name).append("\n");
        }
        sb.append("\nThey'll know you're safe. 💚");
        return sb.toString();
    }

    // ── Call helpers ──────────────────────────────────────────────────────

    private String handleCallRelation(String relationDisplay, String[] aliases) {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts != null) {
            for (EmergencyContact c : contacts) {
                String cLower = c.name.toLowerCase().trim();
                for (String alias : aliases) {
                    if (cLower.contains(alias) || alias.contains(cLower)) {
                        dialPhone(c.phone);
                        return "📞 Calling " + c.name + "...";
                    }
                }
            }
        }
        return "📞 I couldn't find '" + relationDisplay + "' in your trusted contacts.\n\n"
                + "Go to Settings → Trusted Contacts and add them with a label like '"
                + aliases[0] + "'. Once added, just say 'call " + relationDisplay + "' anytime!";
    }

    private String handleCallByName(String targetName) {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts != null) {
            for (EmergencyContact c : contacts) {
                if (c.name.toLowerCase().contains(targetName.toLowerCase())) {
                    dialPhone(c.phone);
                    return "📞 Calling " + c.name + "...";
                }
            }
        }
        return "📞 I couldn't find '" + targetName + "' in your trusted contacts.\n\n"
                + "Your saved contacts are:\n" + buildContactList()
                + "\nOr go to Settings to add a new contact.";
    }

    private String extractCallTarget(String lower) {
        for (String verb : new String[]{"call ", "phone ", "ring ", "dial "}) {
            if (lower.startsWith(verb)) {
                return lower.substring(verb.length()).trim();
            }
        }
        return null;
    }

    private void dialPhone(String number) {
        try {
            Intent call = new Intent(Intent.ACTION_CALL,
                    Uri.parse("tel:" + number.replaceAll("\\s", "")));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(call);
        } catch (Exception e) {
            Log.e(TAG, "Could not initiate call: " + e.getMessage());
        }
    }

    // ── Show contacts ─────────────────────────────────────────────────────

    private String handleShowContacts() {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts == null || contacts.isEmpty()) {
            return "📋 No trusted contacts saved yet.\n\n"
                    + "Go to Settings → Trusted Contacts to add people who "
                    + "should be alerted in an emergency.";
        }
        StringBuilder sb = new StringBuilder("📋 Your trusted contacts:\n\n");
        for (int i = 0; i < contacts.size(); i++) {
            EmergencyContact c = contacts.get(i);
            sb.append(i + 1).append(". ").append(c.name)
                    .append(" — ").append(c.phone).append("\n");
        }
        sb.append("\nThese people will be alerted and receive your live location during an SOS.");
        return sb.toString();
    }

    private String buildContactList() {
        List<EmergencyContact> contacts = prefs.getContacts();
        if (contacts == null || contacts.isEmpty()) return "  (none saved)\n";
        StringBuilder sb = new StringBuilder();
        for (EmergencyContact c : contacts) {
            sb.append("  • ").append(c.name).append("\n");
        }
        return sb.toString();
    }

    // ── Battery status ────────────────────────────────────────────────────

    private String handleBatteryStatus() {
        try {
            android.content.IntentFilter ifilter =
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level  = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale  = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                int pct    = (int)(level / (float) scale * 100);
                boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                String icon = pct > 60 ? "🔋" : pct > 20 ? "🪫" : "⚠️";
                return icon + " Battery: " + pct + "% "
                        + (charging ? "(charging ⚡)" : "(not charging)")
                        + (pct < 20 ? "\n\n⚠️ Low battery! Charge your phone to keep monitoring active." : "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery check failed: " + e.getMessage());
        }
        return "🔋 Unable to read battery status right now.";
    }

    // ── Relation detection helpers ────────────────────────────────────────

    private boolean isMomCall(String lower) {
        if (!lower.startsWith("call ") && !lower.startsWith("phone ")
                && !lower.startsWith("ring ") && !lower.startsWith("dial ")) return false;
        String[] momWords = {"mom", "maa", "mamoni", "ma", "mother",
                "mummy", "mumma", "aai", "amma", "mata"};
        for (String w : momWords) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    private boolean isDadCall(String lower) {
        if (!lower.startsWith("call ") && !lower.startsWith("phone ")
                && !lower.startsWith("ring ") && !lower.startsWith("dial ")) return false;
        String[] dadWords = {"dad", "baba", "bapi", "papa", "father",
                "dada", "bapu", "abba", "pita", "daddy"};
        for (String w : dadWords) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    // ── Greeting ──────────────────────────────────────────────────────────

    private boolean isGreeting(String lower) {
        String[] greetings = {
                "hi", "hello", "hey", "hii", "helo", "heyy", "heya",
                "namaste", "namaskar", "good morning", "good afternoon",
                "good evening", "good night", "sup", "wassup", "yo",
                "howdy", "greetings", "hola"
        };
        for (String g : greetings) {
            if (lower.equals(g) || lower.startsWith(g + " ")
                    || lower.startsWith(g + "!") || lower.startsWith(g + ","))
                return true;
        }
        return false;
    }

    private String getGreetingResponse() {
        int h = new java.util.Calendar.Builder().build()
                .get(java.util.Calendar.HOUR_OF_DAY);
        String time = h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
                : h < 21 ? "Good evening" : "Hey, you're up late";

        // Rotate through warm openers so it feels fresh each time
        String[] openers = {
                time + "! 🛡️ So glad you're here. I'm Suraksha — think of me as a friend "
                        + "who's always got your back.",
                time + "! 🛡️ I'm right here with you. Whatever you need, I'm ready.",
                time + "! 🛡️ It's good to see you. I'm Suraksha, and keeping you safe "
                        + "is the only thing on my mind."
        };
        String opener = openers[(int)(System.currentTimeMillis() % openers.length)];

        return opener + "\n\n"
                + "I can do a lot — here are a few things people ask me most:\n"
                + "📞 \"Call mom\" — I'll dial them instantly\n"
                + "📍 \"Share my location\" — your people will know where you are\n"
                + "🚨 \"SOS\" — full emergency alert in seconds\n"
                + "📱 \"Fake call from Dad\" — a way out of awkward moments\n"
                + "🗺️ \"Safe route home\" — the safest path, not just the fastest\n\n"
                + "But honestly, you can just talk to me normally too. "
                + "How are you feeling today? 💚";
    }

    // ── App Info ──────────────────────────────────────────────────────────

    private boolean isAppInfo(String lower) {
        String[] appQueries = {
                "about this app", "about suraksha", "what is suraksha",
                "tell me about this app", "describe this app",
                "what does this app do", "app features", "what features",
                "how to use", "how do i use", "guide me", "tutorial",
                "how does this work", "what can you do", "features",
                "explain this app", "what is this app", "app info"
        };
        for (String q : appQueries) {
            if (lower.contains(q)) return true;
        }
        return false;
    }

    private String getAppInfoResponse(String lower) {
        if (lower.contains("how to use") || lower.contains("how do i use")
                || lower.contains("guide") || lower.contains("tutorial")) {
            return "📖 How to Use Suraksha AI\n\n"
                    + "1️⃣ Home Screen\n"
                    + "   Tap '● ACTIVE' to start safety monitoring.\n"
                    + "   The circle shows your heart rate and safety status.\n"
                    + "   Red SOS button sends emergency alerts instantly.\n\n"
                    + "2️⃣ This Chat (Suraksha Agent)\n"
                    + "   Tap the blue Suraksha button on the home screen.\n"
                    + "   Type what you need — agents respond and act.\n\n"
                    + "3️⃣ Code Word\n"
                    + "   Set a secret word in Settings.\n"
                    + "   Say it out loud to silently activate all agents.\n"
                    + "   Shouting it triggers panic mode immediately.\n\n"
                    + "4️⃣ Trusted Contacts\n"
                    + "   Add contacts in Settings.\n"
                    + "   They receive your live location + audio during SOS.\n\n"
                    + "5️⃣ Voice ID\n"
                    + "   Enroll your voice in Settings.\n"
                    + "   Ensures only you can trigger sensitive actions.\n\n"
                    + "💡 Keep monitoring active whenever you travel alone.";
        }

        if (lower.contains("features") || lower.contains("what features")
                || lower.contains("app features")) {
            return "✨ Suraksha AI Features\n\n"
                    + "🏠 Home Screen\n"
                    + "Heart rate monitor • Safety status • SOS • BLE watch\n\n"
                    + "🤖 10 AI Agents\n"
                    + "🎙️ Threat Detection — screams, panic, sudden impact\n"
                    + "🚨 Smart SOS — rich alerts with location + audio\n"
                    + "🗺️ Safe Route — safest path, not shortest\n"
                    + "📹 Evidence — auto-records and uploads securely\n"
                    + "📱 Fake Call — escape uncomfortable situations\n"
                    + "👁️ Behavior Prediction — detects if you're being followed\n"
                    + "🏥 Nearby Guardian — police, hospitals, safe places\n"
                    + "💬 Cyber Harassment — analyses threatening messages\n"
                    + "💚 Emotional Support — calm guidance after emergencies\n"
                    + "🏃 Activity Recogniser — STILL/WALKING/RUNNING/VEHICLE\n\n"
                    + "🔐 Security\n"
                    + "Voice ID • Code word • Encrypted storage\n\n"
                    + "Say 'how to use' for a step-by-step guide!";
        }

        return "🛡️ About Suraksha AI\n\n"
                + "Suraksha AI is a women's personal safety app that uses 10 AI agents "
                + "working silently in the background to protect you in real time.\n\n"
                + "🔑 What it does:\n"
                + "• Monitors your heart rate, motion, and audio for danger signs\n"
                + "• Sends instant SOS alerts with live location to trusted contacts\n"
                + "• Finds the safest route home — not just the shortest\n"
                + "• Generates fake calls to help you escape uncomfortable situations\n"
                + "• Detects if someone is following you using movement patterns\n"
                + "• Analyses threatening messages for cyber harassment\n"
                + "• Provides emotional support after stressful situations\n"
                + "• Records evidence and uploads it securely during emergencies\n\n"
                + "🔒 Privacy first — your voice data never leaves your device.\n\n"
                + "Say 'features' for a full list or 'how to use' for a guide!";
    }

    // ── Mood Check (positive vs distress) ─────────────────────────────────

    /**
     * Detects whether the user is expressing they're OK or in distress.
     * Returns a tailored response, or null if the message isn't a mood statement.
     *
     * IMPORTANT: negation/distress is checked FIRST, because "I'm not feeling
     * good" contains the word "good" and would otherwise match the positive branch.
     */
    private String checkMood(String lower) {

        // ── DISTRESS / NEGATIVE (checked first) ───────────────────────────
        String[] distress = {
                "not feeling good", "not feeling well", "not good", "not okay",
                "not ok", "not fine", "not alright", "not all right", "not safe",
                "i am scared", "i'm scared", "im scared", "feeling scared",
                "i am afraid", "i'm afraid", "im afraid", "feeling afraid",
                "i am not", "i'm not", "im not", "feeling unsafe", "feel unsafe",
                "i feel bad", "feeling bad", "feeling low", "feeling down",
                "feeling anxious", "i am anxious", "i'm anxious", "im anxious",
                "feeling nervous", "i am nervous", "i'm nervous",
                "feeling unwell", "i am unwell", "not well", "feeling sick",
                "panicking", "i am panicking", "feeling panicked",
                "dar lag raha", "mujhe dar", "darr lag raha",
                "voy lagche", "khub voy", "bhalo lagche na", "valo lagche na",
                "bhalo nei", "valo nei", "mon kharap", "tabiyat thik nahi",
                "tabiyat kharab", "i am in trouble", "in danger", "something wrong",
                "someone is following", "being followed", "help me"
        };
        for (String d : distress) {
            if (lower.contains(d)) {
                return getDistressResponse();
            }
        }

        // ── POSITIVE / REASSURING ─────────────────────────────────────────
        String[] positive = {
                "i am fine", "i'm fine", "im fine", "i am good", "i'm good",
                "im good", "i am alright", "i'm alright", "im alright",
                "i am all right", "i'm all right", "i am ok", "i'm ok", "im ok",
                "i am okay", "i'm okay", "im okay", "i am well", "i'm well",
                "i am great", "i'm great", "im great", "i am safe", "i'm safe",
                "im safe", "feeling good", "feeling great", "feeling fine",
                "feeling safe", "feeling happy", "i am happy", "i'm happy",
                "all good", "doing good", "doing fine", "doing well", "doing great",
                "main theek hoon", "main thik hoon", "sab theek", "bilkul theek",
                "ami bhalo achi", "ami valo achi", "bhalo achi", "valo achi"
        };
        for (String p : positive) {
            if (lower.contains(p)) {
                return getPositiveResponse();
            }
        }

        return null; // not a mood statement
    }

    private String getPositiveResponse() {
        String[] r = {
                "Glad to hear it! 💚 Feel free to tell me in case of any trouble, "
                        + "and I'll guide you right away. Stay safe!",
                "That's wonderful to hear! 😊 I'm always right here — just reach out "
                        + "the moment anything feels off, and I'll help you immediately.",
                "Love that you're doing well! 💚 Remember, if anything ever happens — "
                        + "big or small — just tell me and I'll take care of it. You're never alone."
        };
        return r[(int)(System.currentTimeMillis() % r.length)];
    }

    private String getDistressResponse() {
        return "Take a deep breath — I'm right here with you. 💚\n\n"
                + "You're not alone. Tell me what's happening and I'll guide you. "
                + "Here's what I can do right now:\n\n"
                + "🚨 Say \"SOS\" — alert your trusted contacts instantly\n"
                + "📍 Say \"Share my location\" — let your people know where you are\n"
                + "📞 Say \"Call mom\" (or any contact) — I'll dial them now\n"
                + "🏥 Say \"Find nearest police\" — I'll show you safe places nearby\n"
                + "📱 Say \"Fake call\" — an excuse to leave an uncomfortable situation\n"
                + "💚 Say \"I need support\" — I'm here to talk you through it\n\n"
                + "Just tell me what you need. I've got you. 🛡️";
    }

    // ── Small Talk ────────────────────────────────────────────────────────

    private boolean isSmallTalk(String lower) {
        // Exact-match short replies
        String[] exact = {
                "fine", "good", "okay", "ok", "great", "nice", "cool", "wow",
                "yes", "no", "yep", "nope", "sure", "hmm", "lol", "haha"
        };
        for (String s : exact) {
            if (lower.equals(s)) return true;
        }
        // Phrase matches
        String[] phrases = {
                "how are you", "how r u", "how are u", "what's up", "whats up",
                "how do you do", "thank", "who are you", "what are you",
                "tell me about yourself", "love you", "good night", "goodnight",
                "bored", "boring", "sad", "lonely", "alone", "how's it going",
                "hows it going", "you there", "are you there"
        };
        for (String s : phrases) {
            if (lower.contains(s)) return true;
        }
        return false;
    }

    private String getSmallTalkResponse(String lower) {
        if (lower.contains("how are you") || lower.contains("how r u")
                || lower.contains("how are u")) {
            String[] r = {
                    "I'm doing great — always wide awake and watching out for you! 💚 "
                            + "More importantly, how are YOU doing? Anything on your mind?",
                    "Me? I'm running at full strength, all 10 agents humming along. 🛡️ "
                            + "But enough about me — how's your day going?",
                    "I'm good, thank you for asking! That's sweet of you. 💚 "
                            + "How about you — are you somewhere safe and comfortable right now?"
            };
            return r[(int)(System.currentTimeMillis() % r.length)];
        }
        if (lower.contains("thank")) {
            String[] r = {
                    "Aww, you're so welcome! 😊 That's what I'm here for. Always.",
                    "Anytime, truly. 💚 Looking out for you is the best part of my day.",
                    "You don't even have to thank me — I've always got you. 🛡️ "
                            + "Is there anything else I can do?"
            };
            return r[(int)(System.currentTimeMillis() % r.length)];
        }
        if (lower.equals("ok") || lower.equals("okay") || lower.equals("fine")
                || lower.equals("good") || lower.equals("great") || lower.equals("nice")
                || lower.equals("cool") || lower.equals("thik hai") || lower.equals("thik ache")
                || lower.equals("okk") || lower.equals("okkk") || lower.equals("okkk")) {
            String[] r = {
                    "Love that! 😊 I'm right here if anything comes up.",
                    "Glad to hear it! 💚 Just say the word whenever you need me.",
                    "Perfect. 🛡️ I'll be here, keeping watch. Don't hesitate to reach out."
            };
            return r[(int)(System.currentTimeMillis() % r.length)];
        }
        if (lower.contains("who are you") || lower.contains("what are you")
                || lower.contains("tell me about yourself"))
            return "Great question! 🛡️ I'm Suraksha — your personal safety companion. "
                    + "I'm not just one assistant, I'm a team of 10 specialised agents all "
                    + "working together to keep you safe:\n\n"
                    + "🎙️ I listen for danger  •  🚨 I send SOS alerts\n"
                    + "🗺️ I find safe routes  •  📹 I record evidence\n"
                    + "📱 I make fake calls  •  👁️ I spot if you're followed\n"
                    + "🏥 I find police & hospitals  •  💬 I check threatening messages\n"
                    + "💚 I'm here for emotional support  •  🏃 I track your activity\n\n"
                    + "But beyond all that — I genuinely care about you. "
                    + "Want me to show you around? Just say 'how to use'. 💚";
        if (lower.contains("love you") || lower.contains("good night")
                || lower.contains("goodnight"))
            return "That means a lot. 💚 Sleep safe and sound — I'll be right here "
                    + "watching over things while you rest. Goodnight! 🛡️";
        if (lower.contains("bored") || lower.contains("boring"))
            return "Bored, huh? 😊 Well, I'd much rather you be safely bored than in "
                    + "any kind of trouble! Want to test a fake call, or set up your "
                    + "code word so you're extra protected?";
        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("alone"))
            return "I'm really sorry you're feeling that way. 💚 You're not alone right "
                    + "now — I'm here with you. Want to talk about it? Just say "
                    + "'I need support' and I'll be right here to listen.";
        return "I'm right here, listening. 💚 Tell me what's on your mind — "
                + "whether it's something you need help with or you just want to talk.";
    }

    // ── Routing ───────────────────────────────────────────────────────────

    private BaseAgent routeMessage(String lower) {
        for (BaseAgent agent : agents) {
            switch (agent.getName()) {
                case "Threat Detection Agent":
                    if (lower.contains("threat") || lower.contains("danger")
                            || lower.contains("unsafe") || lower.contains("scared")
                            || lower.contains("monitor audio") || lower.contains("start monitoring"))
                        return agent;
                    break;
                case "Smart SOS Agent":
                    if (lower.contains("sos") || lower.contains("emergency")
                            || lower.contains("send alert") || lower.contains("alert contacts"))
                        return agent;
                    break;
                case "Safe Route Agent":
                    if (lower.contains("route") || lower.contains("safe path")
                            || lower.contains("navigate") || lower.contains("take me home")
                            || lower.contains("safe route") || lower.contains("get home safely"))
                        return agent;
                    break;
                case "Fake Call Agent":
                    if (lower.contains("fake call") || lower.contains("call me")
                            || lower.contains("distraction") || lower.contains("get me out"))
                        return agent;
                    break;
                case "Nearby Guardian Agent":
                    if (lower.contains("police") || lower.contains("hospital")
                            || lower.contains("nearest") || lower.contains("safe place")
                            || lower.contains("find help") || lower.contains("nearby"))
                        return agent;
                    break;
                case "Evidence Collection Agent":
                    if (lower.contains("record") || lower.contains("evidence")
                            || lower.contains("start recording") || lower.contains("stop recording"))
                        return agent;
                    break;
                case "Behavior Prediction Agent":
                    if (lower.contains("following") || lower.contains("stalking")
                            || lower.contains("being followed") || lower.contains("suspicious"))
                        return agent;
                    break;
                case "Cyber Harassment Agent":
                    if (lower.contains("threatening") || lower.contains("abusive")
                            || lower.contains("harass") || lower.contains("analyse")
                            || lower.contains("analyze") || lower.contains("check this")
                            || lower.contains("is this") || lower.contains("this message")
                            || lower.contains("he said") || lower.contains("she said")
                            || lower.contains("they said") || lower.contains("someone sent"))
                        return agent;
                    break;
                case "Emotional Support Agent":
                    if (lower.contains("scared") || lower.contains("afraid")
                            || lower.contains("frightened") || lower.contains("anxious")
                            || lower.contains("anxiety") || lower.contains("panic")
                            || lower.contains("stressed") || lower.contains("stress")
                            || lower.contains("upset") || lower.contains("crying")
                            || lower.contains("feel bad") || lower.contains("feel sad")
                            || lower.contains("feel scared") || lower.contains("feel unsafe")
                            || lower.contains("i feel") || lower.contains("i am scared")
                            || lower.contains("i'm scared") || lower.contains("support")
                            || lower.contains("calm me") || lower.contains("need someone")
                            || lower.contains("talk to me") || lower.contains("help me calm")
                            || lower.contains("breathing") || lower.contains("overwhelmed")
                            || lower.contains("depressed") || lower.contains("lonely")
                            || lower.contains("dar lag") || lower.contains("darr lag")
                            || lower.contains("mujhe dar") || lower.contains("please help")
                            || lower.contains("voy") || lower.contains("voy lagche")
                            || lower.contains("panic") || lower.contains("panicking")
                            || lower.contains("khub voy") || lower.contains("mon kharap")
                            || lower.contains("kharap lagche") || lower.contains("valo nei")
                            || lower.contains("valo lagche naa") || lower.contains("sad"))
                        return agent;
                    break;
                case "Activity Agent":
                    if (lower.contains("activity") || lower.contains("doing")
                            || lower.contains("walking") || lower.contains("running")
                            || lower.contains("fall detect") || lower.contains("moving"))
                        return agent;
                    break;
            }
        }
        return null;
    }

    // ── Sensor Reading ────────────────────────────────────────────────────

    public void onSensorReading(SensorReading reading) {
        ThreatContext ctx = ThreatContext.fromSensors(
                reading.heartRate, reading.accelerationG, 0f,
                reading.latitude, reading.longitude);
        onThreatLevelChanged(ctx);
    }

    // ── Code Word Receiver ────────────────────────────────────────────────

    private void registerCodeWordReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                boolean panicMode = intent.getBooleanExtra(
                        CodeWordDetector.EXTRA_PANIC_MODE, false);
                Log.i(TAG, "Code word received. panic=" + panicMode);
                long now = System.currentTimeMillis();
                if (now - lastCodeWordTimeMs < CODE_WORD_DEBOUNCE_MS) {
                    Log.d(TAG, "Code word debounced — ignoring repeat");
                    return;
                }
                lastCodeWordTimeMs = now;
                ThreatContext triggerCtx = ThreatContext.fromCodeWord(panicMode, 0.0, 0.0);
                onThreatLevelChanged(triggerCtx);
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(
                receiver, new IntentFilter(CodeWordDetector.ACTION_CODEWORD_DETECTED));
    }

    // ── Threat Level Handler ──────────────────────────────────────────────

    public void onThreatLevelChanged(ThreatContext ctx) {
        ThreatContext.ThreatLevel newLevel = ctx.threatLevel;
        if (newLevel == lastThreatLevel && !ctx.triggeredByCodeWord) return;
        lastThreatLevel = newLevel;
        Log.i(TAG, "Threat level: " + newLevel + " score=" + ctx.threatScore);

        switch (newLevel) {
            case LOW:
                triggerAgentByName("Behavior Prediction Agent", ctx);
                break;
            case MEDIUM:
                triggerAgentByName("Nearby Guardian Agent", ctx);
                scheduleSosCountdown(ctx);
                break;
            case HIGH:
                cancelSosCountdown();
                triggerEmergencyCascade(ctx);
                break;
        }
    }

    // ── Emergency Cascade ─────────────────────────────────────────────────

    private void triggerEmergencyCascade(ThreatContext ctx) {
        if (emergencyCascadeActive) return;
        emergencyCascadeActive = true;
        Log.i(TAG, "EMERGENCY CASCADE triggered: " + ctx.triggerReason);
        triggerAgentByName("Evidence Collection Agent", ctx);
        triggerAgentByName("Smart SOS Agent", ctx);
        triggerAgentByName("Nearby Guardian Agent", ctx);
        triggerAgentByName("Threat Detection Agent", ctx);
        mainHandler.postDelayed(() -> {
            emergencyCascadeActive = false;
            ThreatContext supportCtx = new ThreatContext();
            supportCtx.triggerReason = "Post-emergency support";
            triggerAgentByName("Emotional Support Agent", supportCtx);
        }, 2 * 60 * 1000L);
    }

    // ── SOS Countdown ─────────────────────────────────────────────────────

    private void scheduleSosCountdown(ThreatContext ctx) {
        cancelSosCountdown();
        if (chatCallback != null) {
            chatCallback.onAgentMessage("Smart SOS Agent", "🚨",
                    "⚠️ Threat detected. Sending SOS in 30 seconds.\nSay 'cancel' or 'I'm safe' to stop.");
        }
        pendingSosRunnable = () -> triggerEmergencyCascade(ctx);
        mainHandler.postDelayed(pendingSosRunnable, 30_000);
    }

    public void cancelSosCountdown() {
        if (pendingSosRunnable != null) {
            mainHandler.removeCallbacks(pendingSosRunnable);
            pendingSosRunnable = null;
            Log.i(TAG, "SOS countdown cancelled");
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private void triggerAgentByName(String name, ThreatContext ctx) {
        for (BaseAgent agent : agents) {
            if (agent.getName().equals(name)) {
                agent.onAutonomousTrigger(ctx);
                return;
            }
        }
        Log.d(TAG, "Agent not registered yet: " + name);
    }

    private BaseAgent getAgentByName(String name) {
        for (BaseAgent agent : agents) {
            if (agent.getName().equals(name)) return agent;
        }
        return null;
    }

    public int getAgentCount() { return agents.size(); }

    // ── Passive Monitoring ────────────────────────────────────────────────

    /**
     * Starts passive background monitoring (audio scream/panic detection).
     * Called by MonitoringService when the user enables monitoring, so the
     * Threat Detection Agent listens continuously without a chat command.
     */
    public void startPassiveMonitoring() {
        for (BaseAgent agent : agents) {
            if (agent instanceof ThreatDetectionAgent) {
                ((ThreatDetectionAgent) agent).startAudioMonitoring();
                Log.i(TAG, "Passive audio monitoring started");
                return;
            }
        }
    }

    /** Stops passive background monitoring. */
    public void stopPassiveMonitoring() {
        for (BaseAgent agent : agents) {
            if (agent instanceof ThreatDetectionAgent) {
                ((ThreatDetectionAgent) agent).stopAudioMonitoring();
                Log.i(TAG, "Passive audio monitoring stopped");
                return;
            }
        }
    }

    public void destroy() {
        cancelSosCountdown();
        instance = null;
    }
}