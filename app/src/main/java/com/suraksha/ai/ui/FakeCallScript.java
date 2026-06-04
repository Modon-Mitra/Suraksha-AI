package com.suraksha.ai.ui;

import java.util.Locale;

/**
 * FakeCallScript — stores realistic conversation scripts for fake calls.
 *
 * Scripts are designed to sound like a real worried family member or friend.
 * Each script has 3-4 lines with natural pauses between them.
 *
 * Gender is auto-detected from the caller name.
 * Language is chosen by the user each time.
 */
public class FakeCallScript {

    public static final String LANG_ENGLISH = "English";
    public static final String LANG_HINDI   = "Hindi";
    public static final String LANG_BENGALI = "Bengali";

    public static final String[] LANGUAGES = {
            LANG_ENGLISH, LANG_HINDI, LANG_BENGALI
    };

    // ── Gender detection ──────────────────────────────────────────────────

    private static final String[] MALE_KEYWORDS = {
            "dad", "baba", "bapi", "papa", "father", "dada", "bapu",
            "abba", "pita", "daddy", "bhai", "brother", "dada", "uncle",
            "chachu", "mama", "kaka", "rahul", "rohan", "amit", "raj",
            "suresh", "vikram", "arjun", "saurav", "sourav", "arnab",
            "subhro", "subho", "abir", "ayan", "anirban"
    };

    private static final String[] FEMALE_KEYWORDS = {
            "mom", "maa", "mamoni", "ma", "mother", "mummy", "mumma",
            "aai", "amma", "mata", "didi", "sister", "boudi", "kakima",
            "pishi", "mausi", "dida", "thakuma", "nani", "priya", "neha",
            "sunita", "anita", "rekha", "riya", "tania", "puja", "sneha",
            "ankita", "debasmita", "shreya", "auntie", "aunty"
    };

    public static boolean isMale(String callerName) {
        if (callerName == null) return false;
        String lower = callerName.toLowerCase(Locale.getDefault());
        for (String k : MALE_KEYWORDS) {
            if (lower.contains(k)) return true;
        }
        // Default: female if not recognized as male
        // (safer default for women's safety app)
        return false;
    }

    // ── Script lines with delay before each line (milliseconds) ──────────
    // { delayBeforeMs, text }

    public static String[][] getScript(String language, boolean isMale,
                                       String callerName) {
        switch (language) {
            case LANG_HINDI:
                return isMale
                        ? getMaleHindi(callerName)
                        : getFemaleHindi(callerName);
            case LANG_BENGALI:
                return isMale
                        ? getMaleBengali(callerName)
                        : getFemaleBengali(callerName);
            default:
                return isMale
                        ? getMaleEnglish(callerName)
                        : getFemaleEnglish(callerName);
        }
    }

    // ── English Scripts ───────────────────────────────────────────────────

    private static String[][] getMaleEnglish(String name) {
        return new String[][] {
                // { delay_ms, text }
                {"500",  "Hello? Hey, where are you right now?"},
                {"4000", "I've been waiting outside for like 15 minutes. Are you coming?"},
                {"5000", "Listen, just tell me where you are. I'll come and get you."},
                {"6000", "Okay okay, I'm on my way. Don't move. I'll be there in 5 minutes."},
                {"7000", "Just stay where you are. I'm coming right now."}
        };
    }

    private static String[][] getFemaleEnglish(String name) {
        return new String[][] {
                {"500",  "Hello? Beta, are you okay? Where are you?"},
                {"4000", "I was getting so worried. When are you coming home?"},
                {"5000", "Should I come and pick you up? Just say the word."},
                {"6000", "Okay listen, don't worry. I'm coming right now to get you."},
                {"7000", "Stay where you are. I'll be there very soon. Don't go anywhere."}
        };
    }

    // ── Hindi Scripts ─────────────────────────────────────────────────────

    private static String[][] getMaleHindi(String name) {
        return new String[][] {
                {"500",  "Haan bolo, kahan ho tum abhi?"},
                {"4000", "Main bahar wait kar raha hoon pichle kaafi der se. Aa rahe ho na?"},
                {"5000", "Arre bata do kahan ho, main aa jaata hoon."},
                {"6000", "Theek hai, main nikal raha hoon. Wahi raho, 5 minute mein pahuch jaata hoon."},
                {"7000", "Chinta mat karo, main aa raha hoon. Kahin mat jaana."}
        };
    }

    private static String[][] getFemaleHindi(String name) {
        return new String[][] {
                {"500",  "Hello? Beta, theek ho? Kahan ho abhi?"},
                {"4000", "Main bahut pareshan ho gayi thi. Ghar kab aa rahe ho?"},
                {"5000", "Main aa jaati hoon lene, bata do kahan ho."},
                {"6000", "Arre mat ghabrao, main nikal rahi hoon. Wahi rukna."},
                {"7000", "Thodi der mein pahuch jaaungi. Kahin mat jaana mere aane se pehle."}
        };
    }

    // ── Bengali Scripts ───────────────────────────────────────────────────

    private static String[][] getMaleBengali(String name) {
        return new String[][] {
                {"500",  "Hyan bolo, ekhon kothai acho?"},
                {"4000", "Aami baire opekkha korchi onek khon dhore. Ashcho to?"},
                {"5000", "Arre bolo kothai acho, parle location pathao, aami eshe nebo."},
                {"6000", "Thik aache, aami berochhi. Tumi voy peyo na, 5 minute e ashe porbo."},
                {"7000", "Chinta koro na, aami ashchi. Kothao jeo na."}
        };
    }

    private static String[][] getFemaleBengali(String name) {
        return new String[][] {
                {"500",  "Hyan? Kothay acho tumi? Thik acho to?"},
                {"4000", "Aami khub chintito hoye porechi. Bari kokhon ashbe?"},
                {"5000", "Aami baba ke pathacchi, bolo kothai acho, parle location pathao."},
                {"6000", "Arre ghabrio na, baba asche ekhuni. Tumi voy peyo na."},
                {"7000", "Ektu por pouchhe jabe. babar asar age kothao jeo na."}
        };
    }

    // ── Locale for TTS ────────────────────────────────────────────────────

    public static Locale getTtsLocale(String language) {
        switch (language) {
            case LANG_HINDI:   return new Locale("hi", "IN");
            case LANG_BENGALI: return new Locale("bn", "IN");
            default:           return new Locale("en", "IN");
        }
    }
}
