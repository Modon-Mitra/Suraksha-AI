package com.suraksha.ai.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.suraksha.ai.model.EmergencyContact;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * PrefsManager – centralized SharedPreferences helper.
 */
public class PrefsManager {

    private static final String PREFS_NAME      = "suraksha_prefs";
    private static final String KEY_CONTACTS    = "emergency_contacts";
    private static final String KEY_ONBOARDED   = "onboarding_done";
    private static final String KEY_HR_HIGH     = "hr_high_threshold";
    private static final String KEY_HR_LOW      = "hr_low_threshold";
    private static final String KEY_MON_ACTIVE  = "monitoring_active";
    private static final String KEY_MY_PHONE    = "my_phone";
    private static final String KEY_MY_NAME     = "my_name";
    private static final String KEY_USER_ID     = "user_id";
    private static final String KEY_CODE_WORD         = "code_word";
    private static final String KEY_CODE_WORD_ENABLED = "code_word_enabled";
    private static final String KEY_HOME_ADDRESS      = "home_address";
    private static final String KEY_VOICE_VERIFY      = "voice_verify_enabled";

    // =====================================================
    // DEFAULT THRESHOLDS
    // =====================================================

    public static final int DEFAULT_HR_HIGH = 120;
    public static final int DEFAULT_HR_LOW  = 50;

    private final SharedPreferences prefs;
    private final Context ctx;

    // =====================================================
    // CONSTRUCTOR
    // =====================================================

    public PrefsManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = this.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // =====================================================
    // ONBOARDING
    // =====================================================

    public boolean isOnboardingDone() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboardingDone() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply();
    }

    // =====================================================
    // MONITORING
    // =====================================================

    public boolean isMonitoringActive() {
        return prefs.getBoolean(KEY_MON_ACTIVE, false);
    }

    public void setMonitoringActive(boolean active) {
        prefs.edit().putBoolean(KEY_MON_ACTIVE, active).apply();
    }

    // =====================================================
    // HEART RATE THRESHOLDS
    // =====================================================

    public int getHrHighThreshold() {
        return prefs.getInt(KEY_HR_HIGH, DEFAULT_HR_HIGH);
    }

    public int getHrLowThreshold() {
        return prefs.getInt(KEY_HR_LOW, DEFAULT_HR_LOW);
    }

    public void setHrThresholds(int high, int low) {
        prefs.edit()
                .putInt(KEY_HR_HIGH, high)
                .putInt(KEY_HR_LOW, low)
                .apply();
    }

    // =====================================================
    // MY NAME
    // =====================================================

    public String getMyName() {
        return prefs.getString(KEY_MY_NAME, "");
    }

    public void setMyName(String name) {
        prefs.edit().putString(KEY_MY_NAME, name).apply();
    }

    // =====================================================
    // MY PHONE
    // =====================================================

    public String getMyPhone() {
        return prefs.getString(KEY_MY_PHONE, "");
    }

    public void setMyPhone(String phone) {
        prefs.edit().putString(KEY_MY_PHONE, phone).apply();
    }

    // =====================================================
    // CODE WORD
    // =====================================================

    public String getCodeWord() {
        return prefs.getString(KEY_CODE_WORD, "");
    }

    public void setCodeWord(String word) {
        prefs.edit().putString(KEY_CODE_WORD, word).apply();
    }

    public boolean isCodeWordEnabled() {
        return prefs.getBoolean(KEY_CODE_WORD_ENABLED, false);
    }

    public void setCodeWordEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CODE_WORD_ENABLED, enabled).apply();
    }

    // =====================================================
    // HOME ADDRESS (for Safe Route navigation)
    // =====================================================

    public String getHomeAddress() {
        return prefs.getString(KEY_HOME_ADDRESS, "");
    }

    public void setHomeAddress(String address) {
        prefs.edit().putString(KEY_HOME_ADDRESS, address).apply();
    }

    // =====================================================
    // VOICE VERIFICATION (gate code word with voiceprint)
    // =====================================================

    public boolean isVoiceVerifyEnabled() {
        return prefs.getBoolean(KEY_VOICE_VERIFY, false);
    }

    public void setVoiceVerifyEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VOICE_VERIFY, enabled).apply();
    }

    // =====================================================
    // CONTACTS
    // =====================================================

    public List<EmergencyContact> getContacts() {

        List<EmergencyContact> list = new ArrayList<>();
        String json = prefs.getString(KEY_CONTACTS, "[]");

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new EmergencyContact(
                        obj.getString("name"),
                        obj.getString("phone"),
                        obj.optString("trackingId", "")
                ));
            }
        } catch (Exception ignored) {}

        return list;
    }

    public void saveContacts(List<EmergencyContact> contacts) {
        try {
            JSONArray arr = new JSONArray();
            for (EmergencyContact c : contacts) {
                JSONObject obj = new JSONObject();
                obj.put("name", c.name);
                obj.put("phone", c.phone);
                obj.put("trackingId", c.trackingId);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CONTACTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void updateTrackingId(int index, String trackingId) {
        List<EmergencyContact> contacts = getContacts();
        if (index >= 0 && index < contacts.size()) {
            contacts.get(index).trackingId = trackingId;
            saveContacts(contacts);
        }
    }

    public void removeContact(int index) {
        List<EmergencyContact> contacts = getContacts();
        if (index >= 0 && index < contacts.size()) {
            contacts.remove(index);
            saveContacts(contacts);
        }
    }

    // =====================================================
    // USER ID
    // =====================================================

    public String getUserId() {

        // 1. Firebase Auth UID (preferred after login)
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String uid = user.getUid();
            prefs.edit().putString(KEY_USER_ID, uid).apply();
            return uid;
        }

        // 2. Previously cached ID
        String saved = prefs.getString(KEY_USER_ID, null);
        if (saved != null && !saved.isEmpty()) return saved;

        // 3. Stable fallback
        String androidId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);

        String userId;
        if (androidId != null && androidId.length() >= 8) {
            userId = "SA-" + androidId.substring(0, 8).toUpperCase();
        } else {
            userId = "SA-" + java.util.UUID.randomUUID()
                    .toString().replace("-", "").substring(0, 8).toUpperCase();
        }

        prefs.edit().putString(KEY_USER_ID, userId).apply();
        return userId;
    }

    // =====================================================
    // RESET (dev/testing utility)
    // =====================================================

    public void resetOnboarding() {
        prefs.edit()
                .putBoolean(KEY_ONBOARDED, false)
                .remove(KEY_USER_ID)
                .apply();
    }
}