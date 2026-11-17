package de.owui.chat;

import android.content.Context;
import android.content.res.Resources;

/**
 * UI-Texte. Standardsprache ist Englisch (res/values), Deutsch liegt als Overlay daneben
 * (res/values-de) und wird vom Telefon gewaehlt, wenn sein System Deutsch ist.
 * Oeffentliche Builds werden ohne res/values-de gebaut, dann ist die Oberflaechsprache Englisch.
 *
 * In Klassen ohne Kontext (Api, Search, Config, ...) wird der englische Text als fallback
 * mitgegeben; ohne Ressourcen (JVM-Tests) kommt er direkt zum Einsatz.
 */
public final class Txt {

    private static Resources res;

    /** Von jeder Activity beim Start aufgerufen. */
    static void init(Context c) {
        res = c.getResources();
    }

    public static String t(int id, String fallback) {
        if (res == null) return fallback;
        try {
            return res.getString(id);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String t(int id, String fallback, Object... args) {
        String raw = res == null ? fallback : null;
        if (raw == null) {
            try {
                return res.getString(id, args);
            } catch (Exception e) {
                raw = fallback;
            }
        }
        try {
            return String.format(raw, args);
        } catch (Exception e) {
            return raw;
        }
    }

    public static String[] array(int id, String[] fallback) {
        if (res == null) return fallback;
        try {
            String[] out = res.getStringArray(id);
            return out.length == fallback.length ? out : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private Txt() {
    }
}
