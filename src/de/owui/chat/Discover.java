package de.owui.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-Suche: aus der bloßen Adresse (Host ohne Protokoll und Port) die richtige
 * Kombination aus Protokoll, Port und Anmelde-Modus holen. Bewusst Android-frei,
 * damit die Regeln im JVM-Test (Test.discoverTests) prüfbar sind.
 *
 * Erkennungsregeln, am echten Server gemessen:
 *   Open WebUI     GET /api/version -> 200 {"version":"0.13.3",...}   (ohne Zugang erlaubt)
 *   Modell-Server  GET /api/version -> 404, dafür GET /v1/models -> 200 JSON mit "data"
 * Open WebUI liefert auf /v1/models ohne Token die HTML-Seite der Weboberfläche, also ist
 * ein 200 allein kein Beweis: der Leib muss JSON mit Modelliste sein.
 *
 * Reihenfolge = Reihenfolge der Empfehlung: HTTPS-Open-WebUI (Diktat möglich),
 * dann Open WebUI ohne TLS, dann Modell-Server direkt.
 */
public class Discover {

    /** HTTPS-Front vor Open WebUI (Caddy in diesem Projekt). */
    public static final int PORT_TLS = 3443;
    /** Open WebUI ohne TLS. */
    public static final int PORT_OWUI = 3000;
    /** Modell-Server direkt (OpenAI-kompatibel, /v1). */
    public static final int PORT_DIRECT = 8080;

    /** Antwort eines Prüf-Requests: Status und Leib. */
    public static class Answer {
        public final int status;
        public final String body;

        public Answer(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Ein GET gegen eine absolute Prüf-URL (in der App: Api.probeAbsolute). */
    public interface Fetch {
        Answer get(String absoluteUrl) throws IOException;
    }

    /** Der Treffer: vollständige Basis-URL plus passender Modus. */
    public static class Found {
        public final String baseUrl;
        public final String mode;

        public Found(String baseUrl, String mode) {
            this.baseUrl = baseUrl;
            this.mode = mode;
        }

        public boolean isDirect() {
            return Config.MODE_DIRECT.equals(mode);
        }

        public boolean isWebUi() {
            return !isDirect();
        }
    }

    /** Ergebnis der Suche: Treffer oder die Liste der vergeblich probierten Adressen. */
    public static class Report {
        public Found found;
        public final List<String> tried = new ArrayList<String>();
        /** HTTPS hätte geantwortet, aber das Zertifikat kannte die App nicht. */
        public boolean tlsBlocked;
    }

    /* ---------------- Eingabe zerlegen ---------------- */

    /** Host aus jeder Eingabe: "https://a.b:3443/api" -> "a.b", "[::1]:3000" -> "[::1]". */
    public static String hostOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int cut = s.length();
        char[] stops = new char[]{'/', '?', '#', ' '};
        for (char c : stops) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut);
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(0, end + 1) : s;
        }
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** Port aus der Eingabe, -1 wenn keiner dasteht. */
    public static int portOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int start = 0;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) return -1;
            start = end + 1;
        }
        int colon = s.indexOf(':', start);
        if (colon < 0) return -1;
        int i = colon + 1;
        StringBuilder digits = new StringBuilder();
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            digits.append(s.charAt(i));
            i++;
        }
        if (digits.length() == 0) return -1;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** true = die Eingabe nennt keinen Port, das Suchen lohnt sich. */
    public static boolean needsSearch(String raw) {
        return !hostOf(raw).isEmpty() && portOf(raw) < 0;
    }

    /** Vollständige Adresse aus Host plus Protokoll/Port (für die Preset-Zeilen). */
    public static String address(String host, String scheme, int port) {
        return scheme + "://" + host + ":" + port;
    }

    /* ---------------- Suchen ---------------- */

    public static List<String> candidateUrls(String host) {
        List<String> out = new ArrayList<String>();
        if (host == null || host.isEmpty()) return out;
        out.add(address(host, "https", PORT_TLS));
        out.add(address(host, "http", PORT_OWUI));
        out.add(address(host, "http", PORT_DIRECT));
        return out;
    }

    /**
     * Probiert die Kandidaten der Reihe nach und hört beim ersten Treffer auf.
     * report.found ist null, wenn nichts geantwortet hat.
     */
    public static Report search(String host, Fetch fetch) {
        Report report = new Report();
        for (String base : candidateUrls(host)) {
            report.tried.add(base);

            try {
                Answer version = fetch.get(base + "/api/version");
                if (looksLikeWebUi(version)) {
                    report.found = new Found(base, Config.MODE_OWUI);
                    return report;
                }
            } catch (IOException e) {
                if (isTlsProblem(e)) report.tlsBlocked = true;
            }

            try {
                Answer models = fetch.get(base + "/v1/models");
                if (looksLikeModelServer(models)) {
                    report.found = new Found(base, Config.MODE_DIRECT);
                    return report;
                }
            } catch (IOException e) {
                if (isTlsProblem(e)) report.tlsBlocked = true;
            }
        }
        return report;
    }

    /** Open WebUI erkennt man am keylosen /api/version mit JSON-"version". */
    public static boolean looksLikeWebUi(Answer a) {
        if (a == null || !a.ok()) return false;
        String body = a.body;
        return body.contains("\"version\"");
    }

    /** Modell-Server: JSON-Modelliste, kein HTML. */
    public static boolean looksLikeModelServer(Answer a) {
        if (a == null || !a.ok()) return false;
        String body = a.body.trim();
        if (!body.startsWith("{") && !body.startsWith("[")) return false;
        return body.contains("\"data\"") || body.contains("\"object\"");
    }

    public static boolean isTlsProblem(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException
                    || t instanceof java.security.cert.CertificateException) return true;
        }
        return false;
    }
}
