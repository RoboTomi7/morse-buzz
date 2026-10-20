package dev.morsebuzz;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Small wrapper around SharedPreferences plus the broker presets shared with the sender page. */
final class Prefs {

    /** Public MQTT brokers. The index must match the BROKERS list in docs/index.html. */
    static final class Broker {
        final String name;
        final String tcpUri;   // used by this app
        final String wssUrl;   // used by the sender web page

        Broker(String name, String tcpUri, String wssUrl) {
            this.name = name;
            this.tcpUri = tcpUri;
            this.wssUrl = wssUrl;
        }
    }

    static final Broker[] BROKERS = {
            new Broker("HiveMQ public", "ssl://broker.hivemq.com:8883", "wss://broker.hivemq.com:8884/mqtt"),
            new Broker("EMQX public", "ssl://broker.emqx.io:8883", "wss://broker.emqx.io:8084/mqtt"),
    };

    static final String TOPIC_PREFIX = "morsebuzz/v1/";

    private static final String FILE = "morsebuzz";
    private static final String K_CODE = "code";
    private static final String K_BROKER = "broker";
    private static final String K_URL = "sender_url";
    private static final String K_ENABLED = "enabled";

    // No 0/O, 1/I/L so codes are easy to read aloud and type.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 8;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String code(Context c) {
        String code = sp(c).getString(K_CODE, null);
        if (code == null) {
            code = newCode(c);
        }
        return code;
    }

    static String newCode(Context c) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        String code = sb.toString();
        sp(c).edit().putString(K_CODE, code).apply();
        return code;
    }

    /** "ABCDEFGH" -> "ABCD-EFGH" for display. */
    static String pretty(String code) {
        if (code.length() != CODE_LEN) return code;
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    static int brokerIndex(Context c) {
        int i = sp(c).getInt(K_BROKER, 0);
        return (i >= 0 && i < BROKERS.length) ? i : 0;
    }

    static Broker broker(Context c) {
        return BROKERS[brokerIndex(c)];
    }

    static void setBrokerIndex(Context c, int i) {
        sp(c).edit().putInt(K_BROKER, i).apply();
    }

    static String senderUrl(Context c) {
        return sp(c).getString(K_URL, "");
    }

    static void setSenderUrl(Context c, String url) {
        sp(c).edit().putString(K_URL, url.trim()).apply();
    }

    /** Whether the user wants the service running (used to restart it after a reboot or update). */
    static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    static String topic(String code) {
        return TOPIC_PREFIX + code;
    }

    static String statusTopic(String code) {
        return TOPIC_PREFIX + code + "/status";
    }

    /** The link a sender opens, or null if no sender page URL has been set. */
    static String senderLink(Context c) {
        String base = senderUrl(c);
        if (base.isEmpty()) return null;
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "c=" + code(c) + "&b=" + brokerIndex(c);
    }
}
