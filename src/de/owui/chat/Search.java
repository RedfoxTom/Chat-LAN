package de.owui.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Websuche: aus oder an, was aus der Frage wird, welche Angaben das Modell
 * bekommt und welche Quellen in der Blase stehen. Reine Funktionen - dieselben
 * Regeln laufen im APK und im JVM-Test.
 */
public class Search {

    public static final int OFF = 0;
    public static final int ON = 1;
    /** "an" hatte frueher den Wert 2 (1 war "auto"): beim Lesen mitverstanden. */
    public static final int ON_LEGACY = 2;

    /** Höchstens so viele Treffer werden ans Modell und in der Blase gezeigt. */
    public static final int MAX_HITS = 5;
    /** So viel Seitentext bekommt das Modell pro Fundstelle. */
    public static final int SITE_CHARS = 1400;

    public static class Hit {
        public String title = "";
        public String url = "";
        public String snippet = "";
        /** Text der Seite, wenn der Server die Seite gelesen hat (sonst leer). */
        public String content = "";
    }

    /* ------------------------------------------------ Modus: nur aus oder an */

    public static int normalizeMode(int m) {
        return (m == ON || m == ON_LEGACY) ? ON : OFF;
    }

    /** Der andere Modus - der Knopf schaltet um, statt eine Liste zu öffnen. */
    public static int otherMode(int m) {
        return normalizeMode(m) == ON ? OFF : ON;
    }

    public static String chipLabel(int m) {
        return normalizeMode(m) == ON ? Txt.t(R.string.search_on, "Search on") : Txt.t(R.string.search_off, "Search off");
    }

    public static boolean wantsSearch(int mode, String text) {
        return normalizeMode(mode) == ON && text != null && !text.trim().isEmpty();
    }

    /* ------------------------------------------------ Frage -> Suchbegriff */

    private static final String[] LEAD = new String[]{
            "kannst du mir bitte", "kannst du bitte", "kannst du mir", "könntest du mir", "könntest du",
            "kannst du", "kannst", "koenntest du", "bitte", "sag mir", "sage mir", "suche", "such",
            "googel", "googeln", "google", "recherchiere", "recherchier", "schau nach", "checke", "check",
            "prüfe", "pruefe", "finde", "zeige mir", "weisst du", "weißt du",
            // englische Bittfloskeln (oeffentliche Sprache ist Englisch)
            "could you tell me", "can you tell me", "could you please", "can you please", "what is the price of",
            "how much does it cost", "how much is", "how much are", "look up", "search for", "find out",
            "what is", "what are", "how much", "how many", "can you", "could you", "would you", "please",
            "tell me", "show me", "check",
    };

    private static final Pattern NUMBERS = Pattern.compile("[0-9][0-9.,:/-]");

    /** Woerter, die eine Folgefrage einleiten ("und mit 512 GB?", "wie sieht es morgen aus?"). */
    private static final String[] FOLLOW_UP = new String[]{
            "und", "auch", "mit", "bei", "beim", "für", "fuer", "wie", "was", "wann", "wo", "wohin",
            "dann", "dazu", "noch", "oder", "aber", "genau", "gleich", "selbe", "stattdessen",
            "alternativ", "günstiger", "gunstiger", "teurer", "billiger",
            // english follow-up words
            "and", "with", "or", "but", "also", "then", "more", "less", "cheaper", "instead",
    };

    /**
     * Aus der Frage einen Suchbegriff machen: Bittfloskeln vorne weg, nur die erste Zeile,
     * Fragezeichen und Mehrfach-Leerraum weg, max 160 Zeichen.
     */
    public static String query(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return sanitize(nl >= 0 ? text.substring(0, nl) : text);
    }

    /**
     * Kurze Folgefragen ("und mit 512 GB?", "wie sieht es morgen aus?") ergeben allein keinen
     * Suchbegriff. Dann wird die vorige Frage davor gesetzt.
     */
    public static String queryWithContext(String text, String previousQuestion) {
        String own = query(text);
        if (own.isEmpty()) return "";
        if (!isFollowUp(own)) return own;
        if (previousQuestion == null || previousQuestion.trim().isEmpty()) return own;
        String joined = sanitize(query(previousQuestion) + " " + own);
        return joined.isEmpty() ? own : joined;
    }

    /** Folgefrage = kurzes Stueck, das mit einem Anschlusswort beginnt. */
    private static boolean isFollowUp(String q) {
        if (wordCount(q) > 5) return false;
        String first = q.split("\\s+")[0].toLowerCase(Locale.GERMANY);
        for (String w : FOLLOW_UP) {
            if (first.equals(w)) return true;
        }
        return false;
    }

    private static String sanitize(String raw) {
        String t = raw == null ? "" : raw.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String low = t.toLowerCase(Locale.GERMANY);
            for (String lead : LEAD) {
                if (low.startsWith(lead)) {
                    t = t.substring(lead.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        t = t.replaceAll("[\\?！?!。，]+$", "").replaceAll("[ \\t]+", " ").trim();
        if (t.length() > 160) {
            int cut = t.lastIndexOf(' ', 160);
            t = (cut > 60 ? t.substring(0, cut) : t.substring(0, 160)).trim();
        }
        return t;
    }

    private static int wordCount(String s) {
        int n = 0;
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            boolean space = Character.isWhitespace(s.charAt(i));
            if (!space && !in) { n++; in = true; }
            if (space) in = false;
        }
        return n;
    }

    /* ------------------------------------------------ Server-Antwort -> Treffer */

    /**
     * Antwort von /api/v1/retrieval/process/web/search: "items" sind die Treffer,
     * "docs" der gelesene Text der Seiten (metadata.source ordnet ihn zu).
     */
    public static List<Hit> parse(Object parsed) {
        List<Hit> out = new ArrayList<Hit>();
        Map<?, ?> map = parsed instanceof Map ? (Map<?, ?>) parsed : null;

        Map<String, String> contentByUrl = new HashMap<String, String>();
        List<String[]> docs = new ArrayList<String[]>();          // url, Titel, Text
        if (map != null) {
            Object docList = map.get("docs");
            if (docList instanceof List) {
                for (Object o : (List<?>) docList) {
                    Map<?, ?> m = o instanceof Map ? (Map<?, ?>) o : null;
                    if (m == null) continue;
                    Object metaO = m.get("metadata");
                    Map<?, ?> meta = metaO instanceof Map ? (Map<?, ?>) metaO : null;
                    String url = meta != null ? text(firstNonNull(meta.get("source"), meta.get("link"))) : "";
                    if (url.isEmpty()) url = text(m.get("source"));
                    String title = meta != null ? text(firstNonNull(meta.get("title"), meta.get("name"))) : "";
                    String content = text(m.get("content"));
                    if (url.isEmpty()) continue;
                    if (!contentByUrl.containsKey(url)) contentByUrl.put(url, content);
                    docs.add(new String[]{url, title, content});
                }
            }
        }

        Object items = map != null ? firstNonNull(map.get("items"), map.get("results")) : parsed;
        List<?> list = items instanceof List ? (List<?>) items : null;
        if (list != null) {
            for (Object o : list) {
                Map<?, ?> m = o instanceof Map ? (Map<?, ?>) o : null;
                if (m == null) continue;
                Hit h = new Hit();
                h.title = text(firstNonNull(m.get("title"), m.get("name")));
                h.url = text(firstNonNull(m.get("link"), m.get("url")));
                h.snippet = clip(text(firstNonNull(m.get("snippet"), m.get("content"))), 600);
                if (h.title.isEmpty() && h.url.isEmpty()) continue;
                if (h.title.isEmpty()) h.title = h.url;
                String got = contentByUrl.get(h.url);
                h.content = got == null ? "" : got;
                out.add(h);
                if (out.size() >= MAX_HITS) break;
            }
        }

        if (out.isEmpty()) {                       // Server liefert nur gelesene Seiten
            for (String[] d : docs) {
                Hit h = new Hit();
                h.url = d[0];
                h.title = d[1].isEmpty() ? d[0] : d[1];
                h.content = d[2];
                out.add(h);
                if (out.size() >= MAX_HITS) break;
            }
        }
        return out;
    }

    /**
     * Auszug aus dem Seitentext: Zeilen mit Zahlen (Preise, Grade, Uhrzeiten, Daten) zuerst,
     * weil Menu- und Navigationszeilen sonst den Nutzen verdrängen.
     */
    public static String excerpt(String content, int budget) {
        if (content == null || content.trim().isEmpty() || budget <= 0) return "";
        Set<String> seen = new LinkedHashSet<String>();
        List<String> withNumbers = new ArrayList<String>();
        List<String> others = new ArrayList<String>();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.replaceAll("\\s+", " ").trim();
            if (line.length() < 6 || line.length() > 320) continue;
            if (!seen.add(line)) continue;
            if (NUMBERS.matcher(line).find()) withNumbers.add(line);
            else others.add(line);
        }
        StringBuilder b = new StringBuilder();
        for (String line : withNumbers) {
            if (b.length() + line.length() > budget) break;
            if (b.length() > 0) b.append("  ·  ");
            b.append(line);
        }
        for (String line : others) {
            if (b.length() >= budget / 2) break;
            if (b.length() + line.length() > budget) break;
            if (b.length() > 0) b.append("  ·  ");
            b.append(line);
        }
        String out = b.toString();
        return out.length() > budget ? out.substring(0, budget).trim() : out;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a == null ? b : a;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max).trim() + " …";
    }

    /* ------------------------------------------------ Was das Modell bekommt */

    /** System-Hinweis mit Fundstellen und Seitentext; das Modell soll damit antworten. */
    public static String systemBlock(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append("Just searched with the search engine on your own LAN server; the pages themselves were read. ")
                .append("The extracts below are real content from those pages and fresher than your knowledge.\n")
                .append("Name concrete values from the extracts: prices with the euro amount, temperatures in degrees, ")
                .append("rates, times of day, names, the date of the page. Do NOT just say that portals exist for this - ")
                .append("use the numbers from the pages that were found. If nothing fits, say so openly. ")
                .append("Cite the source by name. Answer in the language of the user's question.\n");
        int n = 1;
        for (Hit h : hits) {
            b.append('\n').append(n++).append(") ").append(h.title);
            if (!h.url.isEmpty()) b.append("  [").append(h.url).append(']');
            b.append('\n');
            if (!h.snippet.isEmpty()) b.append("   Note: ").append(clip(h.snippet, 300)).append('\n');
            String ex = excerpt(h.content, SITE_CHARS);
            if (!ex.isEmpty()) b.append("   Page text: ").append(ex).append('\n');
        }
        return b.toString().trim();
    }

    /* ------------------------------------------------ Quellen fuer die Blase */

    /** Eine Zeile pro Treffer: "Titel\tURL" – so liegt es in der Datenbank. */
    public static String sourceLines(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (Hit h : hits) {
            if (h.url.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(h.title).append('\t').append(h.url);
        }
        return b.toString();
    }

    /** Bereits gespeicherte Quellzeilen wieder in Speicherform bringen. */
    public static String storedLines(List<String> lines) {
        if (lines == null) return "";
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(line.trim());
        }
        return b.toString();
    }

    public static List<String> sourceList(String stored) {
        List<String> out = new ArrayList<String>();
        if (stored == null || stored.trim().isEmpty()) return out;
        for (String line : stored.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** Titel aus einer Quellzeile (fuer die Anzeige). */
    public static String sourceTitle(String line) {
        if (line == null) return "";
        int tab = line.indexOf('\t');
        return tab < 0 ? line : line.substring(0, tab).trim();
    }

    /** URL aus einer Quellzeile (fuer den Link). */
    public static String sourceUrl(String line) {
        if (line == null) return "";
        int tab = line.indexOf('\t');
        return tab < 0 ? line.trim() : line.substring(tab + 1).trim();
    }
}
