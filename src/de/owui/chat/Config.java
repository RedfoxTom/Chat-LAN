package de.owui.chat;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistente Einstellungen (SharedPreferences). Das Passwort wird nicht gespeichert. */
public class Config {
    public static final String MODE_OWUI = "owui";
    public static final String MODE_DIRECT = "direct";
    /** Beispielwert, keine echte Maschine: im eigenen Netz anpassen (siehe README). */
    public static final String DEFAULT_URL = "https://192.168.1.20:3443";

    public String baseUrl;
    public String mode;
    public String token;
    public String apiKey;
    public String email;
    public String userName;
    public String model;
    public String systemPrompt;
    public String lastChatId;
    public double temperature;
    public boolean streaming;
    public boolean allowAnyCert;
    /** true = Diktat ueber die Spracheingabe des Telefons, nicht ueber Whisper auf dem Server. */
    public boolean systemDict;
    /** Bildgroesse pro Anfrage an ComfyUI: 832x832 Standard, 1024x1024 HD, 1280x1280 HD+. */
    public String imageSize;
    /** Nach der gelesenen Antwort automatisch weiter aufnehmen (Gespraechsmodus). */
    public boolean voiceLoop;
    /** Websuche: Search.OFF (Aus) oder Search.ON (An). Vorgelesen wird nur in der Unterhaltung. */
    public int webSearch;
    /** Stimme fuer die Sprachausgabe; leer = die Stimme, die auf dem Server eingestellt ist. */
    public String ttsVoice;

    /**
     * Stimmen, die der Stimmdatenbank-Wirklichkeit entsprechen: de-DE stellt nur zwei
     * hoeren, die wirklich verschieden sind (Katja und Conrad sind die alten Namen von
     * Tanja und Christoph - gleiches Audio, gemessen identisch). Echte Auswahl gibt es
     * erst ueber de-AT und de-CH.
     */
    public static final String[] VOICE_IDS = new String[]{
            "", "de-DE-TanjaNeural", "de-DE-ChristophNeural",
            "de-AT-IngridNeural", "de-AT-JonasNeural",
            "de-CH-LeniNeural", "de-CH-JanNeural"};
    public static final String[] VOICE_LABELS = new String[]{
            "Server-Standard  (weiblich, de)", "Tanja  (weiblich, de)",
            "Christoph  (maennlich, de)", "Ingrid  (weiblich, oesterreichisch)",
            "Jonas  (maennlich, oesterreichisch)", "Leni  (weiblich, Schweizer Dialekt)",
            "Jan  (maennlich, Schweizer Dialekt)"};

    /** Alte Stimmenamen aus 1.5/1.6: dieselbe Stimme wie der neue Name, nicht verlieren. */
    private static final String[][] LEGACY_VOICES = new String[][]{
            {"de-DE-KatjaNeural", "de-DE-TanjaNeural"},
            {"de-DE-ConradNeural", "de-DE-ChristophNeural"}};

    private final SharedPreferences p;

    public Config(Context c) {
        p = c.getSharedPreferences("chatlan", Context.MODE_PRIVATE);
        baseUrl = p.getString("baseUrl", DEFAULT_URL);
        mode = p.getString("mode", MODE_OWUI);
        token = p.getString("token", "");
        apiKey = p.getString("apiKey", "");
        email = p.getString("email", "");
        userName = p.getString("userName", "");
        model = p.getString("model", "");
        systemPrompt = p.getString("systemPrompt", "");
        lastChatId = p.getString("lastChatId", "");
        temperature = Double.longBitsToDouble(p.getLong("temperature", Double.doubleToLongBits(0.7)));
        streaming = p.getBoolean("streaming", true);
        allowAnyCert = p.getBoolean("allowAnyCert", false);
        systemDict = p.getBoolean("systemDict", false);
        imageSize = normalizeSize(p.getString("imageSize", "832x832"));
        voiceLoop = p.getBoolean("voiceLoop", true);
        webSearch = Search.normalizeMode(p.getInt("webSearch", Search.ON));
        ttsVoice = normalizeVoice(p.getString("ttsVoice", ""));
    }

    /** Nur die Stimmen aus VOICE_IDS, damit ein alter oder Tipp-Fehler nicht ins Backend geht. */
    public static String normalizeVoice(String s) {
        if (s == null) return "";
        s = s.trim();
        for (String[] pair : LEGACY_VOICES) {
            if (pair[0].equals(s)) return pair[1];      // alter Name, gleiche Stimme
        }
        for (String id : VOICE_IDS) {
            if (id.equals(s)) return id;
        }
        return "";
    }

    /** Kurzer Name fuer die Hoerprobe: die App sagt "Das ist Tanja." in der gewaehlten Stimme. */
    public static String voiceShortName(String id) {
        String v = normalizeVoice(id);
        if (v.isEmpty()) return "die Standardstimme";
        for (int i = 0; i < VOICE_IDS.length; i++) {
            if (VOICE_IDS[i].equals(v)) {
                String l = VOICE_LABELS[i];
                int sp = l.indexOf(' ');
                return sp > 0 ? l.substring(0, sp) : l;
            }
        }
        return "die Standardstimme";
    }

    /** Etikett fuer die Anzeige; leer = Server-Standard. */
    public static String voiceLabel(String id) {
        String v = normalizeVoice(id);
        for (int i = 0; i < VOICE_IDS.length; i++) {
            if (VOICE_IDS[i].equals(v)) return VOICE_LABELS[i];
        }
        return VOICE_LABELS[0];
    }

    /** Nur die drei bekannten Groessen, damit ein alter Wert nicht ins Backend rutscht. */
    public static String normalizeSize(String s) {
        if (s == null) return "832x832";
        s = s.trim().toLowerCase();
        if (s.equals("1024x1024") || s.equals("1280x1280")) return s;
        return "832x832";
    }

    /** Kurzes Etikett fuer die App (832 / HD / 1280). */
    public static String sizeLabel(String s) {
        if ("1024x1024".equals(s)) return "HD";
        if ("1280x1280".equals(s)) return "HD+";
        return "832";
    }

    public void save() {
        p.edit()
                .putString("baseUrl", baseUrl)
                .putString("mode", mode)
                .putString("token", token)
                .putString("apiKey", apiKey)
                .putString("email", email)
                .putString("userName", userName)
                .putString("model", model)
                .putString("systemPrompt", systemPrompt)
                .putString("lastChatId", lastChatId)
                .putLong("temperature", Double.doubleToLongBits(temperature))
                .putBoolean("streaming", streaming)
                .putBoolean("allowAnyCert", allowAnyCert)
                .putBoolean("systemDict", systemDict)
                .putString("imageSize", imageSize)
                .putBoolean("voiceLoop", voiceLoop)
                .putInt("webSearch", webSearch)
                .putString("ttsVoice", ttsVoice)
                .apply();
    }

    public boolean hasAccess() {
        return MODE_DIRECT.equals(mode) || !token.isEmpty() || !apiKey.isEmpty();
    }

    public void clearAccess() {
        token = "";
        apiKey = "";
        userName = "";
        save();
    }

    public String serverLabel() {
        return baseUrl + (MODE_DIRECT.equals(mode) ? "  (direkt)" : "  (Open WebUI)");
    }
}
