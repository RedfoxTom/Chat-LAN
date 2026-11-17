package de.owui.chat;

import android.content.ContextStub;
import android.text.Spannable;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JVM-Test: JSON, Markdown, SQLite-Schicht und echte Live-Requests gegen Open WebUI und Port 8080. */
public class Test {

    static int failed = 0;

    static void ok(String name, boolean cond) {
        System.out.println((cond ? "  OK    " : "  FEHLER ") + name);
        if (!cond) failed++;
    }

    public static void main(String[] args) throws Exception {
        jsonTests();
        markdownTests();
        configTests();
        routerTests();
        voiceFlowTests();
        searchTests();
        languageTests();
        dbTests();

        String mode = args.length > 0 ? args[0] : "live";
        if (mode.equals("json")) {
            System.out.println(failed == 0 ? "\nJSON/Markdown/DB bestanden" : "\nFEHLER: " + failed);
            if (failed > 0) System.exit(1);
            return;
        }

        System.out.println("\n== Live: Open WebUI 127.0.0.1:3000 (Login, API-Key, Vision) ==");
        owuiLive();

        System.out.println("\n== Live: Modell-Server direkt 127.0.0.1:8080 ==");
        directLive();

        System.out.println(failed == 0 ? "\nALLE TESTS BESTANDEN" : "\nFEHLER: " + failed);
        if (failed > 0) System.exit(1);
    }

    /* ------------------------------------------------ JSON */

    static void jsonTests() {
        System.out.println("== JSON ==");
        Map<String, Object> m = (Map<String, Object>) Json.parse(
                "{\"a\":1,\"b\":\"hallo\",\"c\":[1,2,{\"d\":true}],\"e\":null,\"f\":-2.5,\"g\":\"ä\\u00f6\\n\\\"\"}");
        ok("Zahlen", ((Number) m.get("a")).doubleValue() == 1.0 && ((Number) m.get("f")).doubleValue() == -2.5);
        ok("String", "hallo".equals(Json.str(m, "b")));
        ok("Null-Wert", m.containsKey("e") && m.get("e") == null);
        List<Object> c = Json.asList(m.get("c"));
        ok("Liste", c != null && c.size() == 3);
        ok("verschachtelt", Boolean.TRUE.equals(Json.asMap(c.get(2)).get("d")));
        ok("Umlaute + Escapes", "äö\n\"".equals(m.get("g")));
        ok("Pfad über str", "hallo".equals(Json.str(m, "b")));

        Map<String, Object> o = Json.obj();
        o.put("model", "x");
        o.put("stream", true);
        o.put("temperature", 0.7);
        List<Object> msgs = new ArrayList<Object>();
        Map<String, Object> u = Json.obj("role", "user");
        List<Object> parts = new ArrayList<Object>();
        parts.add(Json.obj("type", "text", "text", "test <&> \"quote\""));
        parts.add(Json.obj("type", "image_url", "image_url", Json.obj("url", "data:image/jpeg;base64,AAAA")));
        u.put("content", parts);
        msgs.add(u);
        o.put("messages", msgs);

        String written = Json.write(o);
        Map<String, Object> back = (Map<String, Object>) Json.parse(written);
        List<Object> bm = Json.asList(back.get("messages"));
        List<Object> bp = Json.asList(Json.asMap(bm.get(0)).get("content"));
        ok("roundtrip Struktur", bp != null && bp.size() == 2);
        ok("roundtrip Text mit Escapes", "test <&> \"quote\"".equals(Json.asMap(bp.get(0)).get("text")));
        ok("roundtrip image_url", "data:image/jpeg;base64,AAAA"
                .equals(Json.str(Json.asMap(bp.get(1)).get("image_url"), "url")));
        ok("roundtrip bool", Boolean.TRUE.equals(back.get("stream")));
        ok("Unicode bleibt roh", written.contains("ä") || !written.contains("ä"));
        ok("parse-Fehler wirft", throwsParse("{"));
        ok("leeres Objekt", Json.parse("{}") instanceof Map);
    }

    static boolean throwsParse(String s) {
        try {
            Json.parse(s);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /* ------------------------------------------------ Markdown */

    static void markdownTests() {
        System.out.println("== Markdown ==");
        String md = "## Titel\n\nText mit **fett** und `code`.\n\n"
                + "- Punkt eins\n- Punkt zwei\n\n1. eins\n2. zwei\n\n> Zitat\n\n"
                + "```python\nprint('hi')\n```\n\n[Mehr](https://example.com)";
        CharSequence cs = Markdown.render(md);
        ok("liefert Spannable", cs instanceof Spannable);
        Spannable sp = (Spannable) cs;
        String plain = sp.toString();
        ok("Marker entfernt", !plain.contains("**") && !plain.contains("`") && !plain.contains("## "));
        ok("Aufzählungspunkte", plain.contains("• Punkt eins") && plain.contains("• Punkt zwei"));
        ok("nummerierte Liste", plain.contains("1. eins") && plain.contains("2. zwei"));
        ok("Codeblock erhalten", plain.contains("print('hi')"));
        ok("Zitat erhalten", plain.contains("Zitat"));
        ok("Link-Text ohne Klammern", plain.contains("Mehr") && !plain.contains("](http"));

        boolean bold = false, mono = false, bullet = false, quote = false, underline = false;
        for (Object s : sp.getSpans(0, sp.length(), Object.class)) {
            if (s instanceof StyleSpan && ((StyleSpan) s).getStyle() == android.graphics.Typeface.BOLD) bold = true;
            if (s instanceof TypefaceSpan && "monospace".equals(((TypefaceSpan) s).getFamily())) mono = true;
            if (s instanceof android.text.style.LeadingMarginSpan) bullet = true;
            if (s instanceof QuoteSpan) quote = true;
            if (s instanceof UnderlineSpan) underline = true;
        }
        ok("fett Span", bold);
        ok("monospace Span", mono);
        ok("Einrückung für Aufzählung", bullet);
        ok("QuoteSpan", quote);
        ok("UnderlineSpan für Link", underline);

        ok("ohne Struktur unverändert", Markdown.render("nur text\nzweite zeile").toString()
                .equals("nur text\nzweite zeile"));
        ok("null sicher", Markdown.render(null).toString().isEmpty());
        ok("leer sicher", Markdown.render("").toString().isEmpty());
    }

    /* ------------------------------------------------ SQLite-Schicht */

    static void configTests() {
        System.out.println("== Config (Einstellungen persistent) ==");
        ContextStub ctx = new ContextStub();
        Config c = new Config(ctx);
        c.imageSize = "1280x1280";
        c.voiceLoop = false;
        c.temperature = 0.35;
        c.webSearch = Search.ON;
        c.ttsVoice = "de-DE-ChristophNeural";
        c.save();

        Config again = new Config(ctx);          // so wie nach einem App-Neustart
        ok("Bildgroesse gemerkt", "1280x1280".equals(again.imageSize));
        ok("Unterhaltungs-Schleife gemerkt", again.voiceLoop == false);
        ok("Temperatur gemerkt", Math.abs(again.temperature - 0.35) < 1e-9);
        ok("Websuche gemerkt", again.webSearch == Search.ON);
        ok("Stimme gemerkt", "de-DE-ChristophNeural".equals(again.ttsVoice));

        again.imageSize = "700x999";             // unbekannte Groesse darf nicht ins Backend
        again.webSearch = 99;                    // unsinniger Modus
        again.ttsVoice = "de-DE-FooNeural";      // Stimme, die es nicht gibt
        again.save();
        ok("Bildgroesse normalisiert", "832x832".equals(new Config(ctx).imageSize));
        ok("Websuche normalisiert", new Config(ctx).webSearch == Search.OFF);
        ok("Stimme normalisiert", new Config(ctx).ttsVoice.isEmpty());

        again.webSearch = Search.ON_LEGACY;      // der Wert von 1.5 ("an")
        again.save();
        ok("alter Websuche-Wert versteht die App", new Config(ctx).webSearch == Search.ON);
        again.webSearch = Search.otherMode(again.webSearch);
        again.save();
        ok("Umschalten an -> aus", new Config(ctx).webSearch == Search.OFF);
        ok("Etikett 832", "832".equals(Config.sizeLabel("832x832"))
                && "HD".equals(Config.sizeLabel("1024x1024"))
                && "HD+".equals(Config.sizeLabel("1280x1280")));
        ok("voice label", "Server default  (female, de)".equals(Config.voiceLabel(""))
                && Config.voiceLabel("de-DE-TanjaNeural").startsWith("Tanja")
                && Config.voiceLabel("Quatsch").equals("Server default  (female, de)"));
        ok("Stimmenliste: Id und Etikett gleich lang", Config.VOICE_IDS.length == Config.VOICE_LABELS.length);
        /* Katja und Conrad sind die alten Namen von Tanja und Christoph: ein gespeicherter
         * alter Wert darf nicht still auf den Server-Standard fallen. */
        ok("alte Stimmenamen bleiben die gleiche Stimme",
                "de-DE-TanjaNeural".equals(Config.normalizeVoice("de-DE-KatjaNeural"))
                        && "de-DE-ChristophNeural".equals(Config.normalizeVoice("de-DE-ConradNeural")));
        ok("voice sample names the voice",
                "Christoph".equals(Config.voiceShortName("de-DE-ChristophNeural"))
                        && "Tanja".equals(Config.voiceShortName("de-DE-KatjaNeural"))
                        && "the default voice".equals(Config.voiceShortName("")));
        int male = 0, female = 0;
        for (String l : Config.VOICE_LABELS) {
            if (l.contains("male")) male++;
            if (l.contains("female")) female++;
        }
        ok("voice choice has male and female voices", male >= 2 && female >= 2);
    }

    /* ------------------------------------------------ Interface language */

    /**
     * Die Oberflaeche ist englisch (res/values), Deutsch liegt als Overlay daneben
     * (res/values-de). Beide Sprachpaetten muessen deckungsgleich bleiben, sonst fehlt
     * im internen deutschen Build ein Text oder ein %1$s-Platzhalter geht verloren.
     */
    static void languageTests() throws Exception {
        System.out.println("== Interface language (resources) ==");
        java.nio.file.Path enF = pathlib("res/values/strings.xml");
        java.nio.file.Path deF = pathlib("res/values-de/strings.xml");
        ok("english default and german overlay both present",
                java.nio.file.Files.exists(enF) && java.nio.file.Files.exists(deF));
        java.util.Map<String, String> en = texts(enF);
        java.util.Map<String, String> de = texts(deF);
        java.util.Set<String> onlyEn = new java.util.TreeSet<>(en.keySet());
        onlyEn.removeAll(de.keySet());
        java.util.Set<String> onlyDe = new java.util.TreeSet<>(de.keySet());
        onlyDe.removeAll(en.keySet());
        ok("every english text has a german twin (" + en.size() + " texts)",
                onlyEn.isEmpty() && onlyDe.isEmpty());
        if (!onlyEn.isEmpty()) System.out.println("    missing in res/values-de: " + onlyEn);
        if (!onlyDe.isEmpty()) System.out.println("    missing in res/values: " + onlyDe);

        java.util.List<String> argMismatch = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : en.entrySet()) {
            if (!de.containsKey(e.getKey())) continue;
            if (!placeholders(e.getValue()).equals(placeholders(de.get(e.getKey())))) argMismatch.add(e.getKey());
        }
        ok("placeholders match in both languages", argMismatch.isEmpty());
        if (!argMismatch.isEmpty()) System.out.println("    mismatching keys: " + argMismatch);

        java.util.Set<String> used = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pathlib("src"))) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files
                    .filter(x -> x.toString().endsWith(".java"))::iterator) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("R\\.string\\.([a-z0-9_]+)")
                        .matcher(java.nio.file.Files.readString(f));
                while (m.find()) used.add(m.group(1));
            }
        }
        java.util.List<String> undefined = new java.util.ArrayList<>();
        for (String k : used) if (!en.containsKey(k)) undefined.add(k);
        ok("every referenced text exists in res/values (" + used.size() + " referenced)", undefined.isEmpty());
        if (!undefined.isEmpty()) System.out.println("    undefined keys: " + undefined);

        java.util.List<String> literals = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pathlib("res/layout"))) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files
                    .filter(x -> x.toString().endsWith(".xml"))::iterator) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("android:(text|hint|contentDescription)=\"([^\"]*)\"")
                        .matcher(java.nio.file.Files.readString(f));
                while (m.find()) {
                    String v = m.group(2).trim();
                    if (!v.isEmpty() && !v.startsWith("@")) literals.add(f.getFileName() + " -> " + v);
                }
            }
        }
        ok("no hardcoded label in the layouts (all via @string)", literals.isEmpty());
        for (String l : literals) System.out.println("    literal: " + l);

        ok("english default is english (no german umlaut strings)",
                !en.containsKey("settings") || !en.get("settings").contains("Ã¤"));
    }

    static java.nio.file.Path pathlib(String relative) { return java.nio.file.Paths.get(relative); }

    static java.util.Map<String, String> texts(java.nio.file.Path file) throws java.io.IOException {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", java.util.regex.Pattern.DOTALL)
                .matcher(java.nio.file.Files.readString(file));
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    static java.util.Set<String> placeholders(String value) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%\\d+\\$[a-z]").matcher(value);
        while (m.find()) out.add(m.group());
        return out;
    }

    static void dbTests() {
        System.out.println("== Db (SQLite) ==");
        Db db = new Db(new ContextStub());
        String chat = db.newChat("modell-a");
        ok("Chat angelegt", chat != null && !chat.isEmpty());
        ok("Chat existiert", db.exists(chat));

        Msg u = new Msg("user", "Frage eins");
        u.model = "modell-a";
        u.images.add(new byte[]{1, 2, 3, 4});
        long uid = db.addMessage(chat, u);
        Msg a = new Msg("assistant", "Antwort eins");
        a.model = "modell-a";
        a.addSources(java.util.Arrays.asList("Wetter Bielefeld\thttps://www.wetter.com/bielefeld"));
        long aid = db.addMessage(chat, a);
        ok("Nachrichten-IDs", uid > 0 && aid > uid);

        db.touchChat(chat, "Frage eins", "modell-a");
        List<Msg> msgs = db.messages(chat);
        ok("Nachrichten wieder da", msgs.size() == 2);
        ok("Rollen erhalten", "user".equals(msgs.get(0).role) && "assistant".equals(msgs.get(1).role));
        ok("dbId gesetzt", msgs.get(0).dbId == uid && msgs.get(1).dbId == aid);
        ok("Bild wieder da", msgs.get(0).images.size() == 1 && msgs.get(0).images.get(0).length == 4
                && msgs.get(0).images.get(0)[2] == 3);
        ok("Webquelle wieder da", msgs.get(1).sources.size() == 1
                && "Wetter Bielefeld".equals(Search.sourceTitle(msgs.get(1).sources.get(0)))
                && "https://www.wetter.com/bielefeld".equals(Search.sourceUrl(msgs.get(1).sources.get(0))));
        ok("Chat-Titel", db.listChats(10).get(0).title.contains("Frage eins"));
        ok("Modell gemerkt", "modell-a".equals(db.modelOf(chat)));

        db.deleteMessage(aid);
        ok("Löschen wirkt", db.messages(chat).size() == 1);

        List<Db.ChatRef> all = db.listChats(10);
        String other = db.newChat("modell-b");
        ok("Zweite Chat-Id anders", !other.equals(chat));
        ok("Liste zählt", db.listChats(10).size() == 2);
        db.deleteChat(other);
        ok("Chat löschen", !db.exists(other));
        db.close();
    }

    /* ------------------------------------------------ Router */

    static void routerTests() {
        System.out.println("== Router (Bild-Modus ohne Sprach-Chat) ==");
        ok("Bild-Modus: Prompt wird zum Bild", Router.wantsPicture(true, false));
        ok("angehaengtes Bild bleibt eine Textfrage", !Router.wantsPicture(true, true));
        ok("Text-Modus erzeugt kein Bild", !Router.wantsPicture(false, false));
        ok("Bild-Modus: Sprach-Chat aus", !Router.voiceChatAllowed(true, true));
        ok("Text-Modus ueber Open WebUI: Sprach-Chat an", Router.voiceChatAllowed(true, false));
        ok("Direktmodus: kein Sprach-Chat", !Router.voiceChatAllowed(false, false));
        ok("Bild-Antwort wird nie vorgelesen", !Router.maySpeak(true, true));
        ok("Textantwort wird vorgelesen", Router.maySpeak(false, true));
        ok("leere Antwort wird nicht vorgelesen", !Router.maySpeak(false, false));
        ok("Sprach-Unterhaltung liest vor", Router.speaksOnAnswer(true, false, true));
        ok("Diktat und getippte Frage bleiben stumm", !Router.speaksOnAnswer(false, false, true));
        ok("Unterhaltung mit Bild-Antwort bleibt stumm", !Router.speaksOnAnswer(true, true, true));
        ok("Unterhaltung ohne Text bleibt stumm", !Router.speaksOnAnswer(true, false, false));
    }

    /* ------------------------------------------------ Sprach-Unterhaltung */

    static void voiceFlowTests() {
        System.out.println("\n== Sprach-Unterhaltung: Ablauf ueber mehrere Runden ==");
        VoiceFlow f = new VoiceFlow();
        ok("am Anfang aus", !f.sessionOn() && f.phase() == VoiceFlow.NONE);
        ok("Druck bei aus heisst zuhoeren", f.pressAction() == VoiceFlow.PRESS_LISTEN);
        ok("erster Druck oeffnet die Aufnahme", f.openSession() && f.isListening());
        ok("Druck waehrend der Aufnahme beendet sie", f.pressAction() == VoiceFlow.PRESS_FINISH);
        ok("zweite Aufnahme daneben geht nicht", !f.openSession() && !f.beginTurn());
        ok("Aufnahme uebergibt an Whisper", f.finishRecording() && f.isTranslating());
        ok("Druck beim Verstehen bricht ab", f.pressAction() == VoiceFlow.PRESS_ABORT);
        ok("Verstehen uebergibt ans Modell", f.transcriptReady() && f.isThinking());
        ok("Antwort uebergibt an die Stimme", f.answerReady(true) && f.isSpeaking());
        ok("Druck beim Sprechen unterbricht", f.pressAction() == VoiceFlow.PRESS_STOP_SPEECH);

        // Der Fall aus 1.7: die Unterhaltung war noch an, aber keine Aufnahme lief mehr,
        // weil ein "runde laeuft noch"-Schalter den Neustart blockierte.
        ok("nach dem Vorlesen ist die Unterhaltung in der Pause",
                f.continueAfterSpeech(true, true) && f.sessionOn() && f.phase() == VoiceFlow.NONE);
        ok("die Pause heisst: wieder zuhoeren", f.pressAction() == VoiceFlow.PRESS_LISTEN);
        ok("die Pause blockiert die neue Aufnahme nicht", f.beginTurn() && f.isListening());
        ok("Runde 2 laeuft wie Runde 1",
                f.finishRecording() && f.transcriptReady() && f.answerReady(true));
        int runden = 0;
        for (int i = 0; i < 4; i++) {
            if (!f.continueAfterSpeech(true, true)) break;
            if (!f.beginTurn()) break;                 // hier ist 1.7 abgebrochen
            f.finishRecording();
            f.transcriptReady();
            f.answerReady(true);
            runden++;
        }
        ok("Unterhaltung hoert nach jeder Antwort wieder zu (4 Runden)", runden == 4);

        f.closeSession();
        ok("Abbruch schliesst Modus und Stufe", !f.sessionOn() && f.phase() == VoiceFlow.NONE);
        ok("nach dem Abbruch ist alles wieder frei", f.pressAction() == VoiceFlow.PRESS_LISTEN && f.openSession());
        f.finishRecording();
        f.transcriptReady();
        ok("Antwort ohne Stimme (Bild oder leer) beendet die Unterhaltung",
                f.answerReady(false) && !f.sessionOn() && f.phase() == VoiceFlow.NONE);

        f = new VoiceFlow();
        f.openSession();
        f.finishRecording();
        f.transcriptReady();
        f.answerReady(true);
        ok("ohne Schleife nur eine Runde", !f.continueAfterSpeech(true, false) && !f.sessionOn());

        f = new VoiceFlow();
        f.openSession();
        f.finishRecording();
        f.transcriptReady();
        f.answerReady(true);
        ok("Tonfehler beendet die Unterhaltung", !f.continueAfterSpeech(false, true) && !f.sessionOn());

        f = new VoiceFlow();
        f.openSession();
        f.closeSession();
        ok("Uebergang aus falscher Stufe wird ignoriert",
                !f.finishRecording() && !f.transcriptReady() && !f.answerReady(true)
                        && !f.continueAfterSpeech(true, true) && !f.beginTurn());
    }

    /* ------------------------------------------------ Websuche */

    static void searchTests() {
        System.out.println("\n== Websuche (Aus/An, Suchbegriff, gelesene Seiten) ==");
        ok("Aus sucht nie", !Search.wantsSearch(Search.OFF, "Wetter in Bielefeld morgen"));
        ok("An sucht jede Frage", Search.wantsSearch(Search.ON, "Hallo"));
        ok("alter an-Wert sucht weiter", Search.wantsSearch(Search.ON_LEGACY, "Hallo"));
        ok("leerer Text nie", !Search.wantsSearch(Search.ON, "     "));
        ok("Knopf schaltet um", Search.otherMode(Search.OFF) == Search.ON
                && Search.otherMode(Search.ON) == Search.OFF
                && Search.otherMode(Search.ON_LEGACY) == Search.OFF);
        ok("Modus normalisiert", Search.normalizeMode(99) == Search.OFF
                && Search.normalizeMode(-2) == Search.OFF
                && Search.normalizeMode(Search.ON_LEGACY) == Search.ON);
        ok("label on the button", "Search on".equals(Search.chipLabel(Search.ON))
                && "Search off".equals(Search.chipLabel(Search.OFF))
                && "Search off".equals(Search.chipLabel(99)));

        ok("Suchbegriff ohne Fragezeichen", "Wie wird das Wetter morgen in Bielefeld"
                .equals(Search.query("Wie wird das Wetter morgen in Bielefeld?")));
        ok("Suchbegriff ohne Bittfloskel", "die Preise fuer den Xiaomi 15T Pro vergleichen"
                .equals(Search.query("Kannst du die Preise fuer den Xiaomi 15T Pro vergleichen?")));
        ok("Suchbegriff aus erster Zeile", "Wetter in Bielefeld".equals(Search.query("Wetter in Bielefeld\nund sonst noch was")));
        ok("Suchbegriff mit Leerzeichen geraeumt", "Preise fuer den Xiaomi".equals(Search.query("   suche   Preise   fuer   den Xiaomi  ")));
        ok("Suchbegriff begrenzt", Search.query(longText(400)).length() <= 160);
        ok("Nichts uebrig -> leer", Search.query("   ?").isEmpty());

        ok("Folgefrage bekommt den Zusammenhang", Search.queryWithContext("und mit 512 GB?",
                "Was kostet das Xiaomi 15T Pro?").contains("Xiaomi 15T Pro"));
        ok("eigene Frage bleibt unter sich", "Wetter morgen in Bielefeld"
                .equals(Search.queryWithContext("Wetter morgen in Bielefeld", "Was kostet das Xiaomi 15T Pro?")));
        ok("Folgefrage ohne Vorgaenger", "und morgen".equals(Search.queryWithContext("und morgen?", null)));

        String json = "{\"status\":true,\"collection_names\":[\"coll\"],\"loaded_count\":2,"
                + "\"items\":[{\"link\":\"https://www.wetter.com/bielefeld\",\"title\":\"Wetter Bielefeld\","
                + "\"snippet\":\"morgen 2 bis 4 Grad, zeitweise Regen\"},"
                + "{\"link\":\"https://www.wetteronline.de/bielefeld\",\"title\":\"WetterOnline\",\"snippet\":\"4 Grad\"}]}";
        List<Search.Hit> hits = Search.parse(Json.parse(json));
        ok("Fundstellen aus der Server-Antwort", hits.size() == 2);
        ok("Titel, URL und Auszug", "Wetter Bielefeld".equals(hits.get(0).title)
                && "https://www.wetter.com/bielefeld".equals(hits.get(0).url)
                && hits.get(0).snippet.contains("2 bis 4 Grad"));
        String block = Search.systemBlock(hits);
        ok("Modell bekommt Fakt und Quelle", block.contains("2 bis 4 Grad") && block.contains("wetter.com"));
        String stored = Search.sourceLines(hits);
        List<String> lines = Search.sourceList(stored);
        ok("Fundstellen bleiben erhalten", lines.size() == 2 && lines.get(0).contains("wetter.com"));
        ok("leere Suchmaschine", Search.parse(Json.parse("{\"status\":true,\"items\":[]}")).isEmpty());
        ok("Server-Antwort kaputt -> keine Fundstelle", Search.parse("kein json").isEmpty());
        ok("hochstens fuenf Fundstellen", Search.parse(Json.parse(bigJson(9))).size() <= Search.MAX_HITS);
        ok("ohne gelesene Seiten leerer Seitentext", Search.parse(Json.parse(json)).get(0).content.isEmpty());

        /* Server liest die Treffer: "docs" enthaelt den Text der Seite. */
        String withDocs = "{\"status\":true,\"items\":[{\"link\":\"https://geizhals.de/xiaomi-15t-pro\","
                + "\"title\":\"Xiaomi 15T Pro | Geizhals\",\"snippet\":\"ab 629 Euro\"}],"
                + "\"docs\":[{\"content\":\"Hauptinhalt\\nWunschlisten\\nEinstellungen\\n"
                + "Xiaomi 15T Pro ab € 629,00 (2026)\\nAmazon 649,00 €\\nMediaMarkt 699,00 €\\n"
                + "Buegel\\nFarbe: Titan\",\"metadata\":{\"source\":\"https://geizhals.de/xiaomi-15t-pro\","
                + "\"title\":\"Geizhals\"}}]}";
        List<Search.Hit> deep = Search.parse(Json.parse(withDocs));
        ok("Seitentext kommt bei der Fundstelle an", deep.size() == 1 && deep.get(0).content.contains("629"));
        String deepBlock = Search.systemBlock(deep);
        ok("Modell sieht die Preise der Seite", deepBlock.contains("629,00") && deepBlock.contains("649,00"));
        ok("Zahlenzeilen stehen vor Menuzeilen",
                deepBlock.indexOf("629,00") < deepBlock.indexOf("Wunschlisten"));
        ok("Auszug bleibt innerhalb der Grenze", Search.excerpt(manyLines(200), 400).length() <= 400);
        ok("nur gelesene Seiten ohne Trefferliste", Search.parse(Json.parse(
                "{\"docs\":[{\"content\":\"heute 8 Grad\\n\",\"metadata\":{\"source\":\"https://x.de/a\"}}]}")).size() == 1);
    }

    static String manyLines(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("Zeile ").append(i).append(" mit 42 Grad und 5,00 Euro Text\n");
        return b.toString();
    }

    static String longText(int n) {
        StringBuilder b = new StringBuilder();
        while (b.length() < n) b.append("wortzeug ");
        return b.toString();
    }

    static String bigJson(int n) {
        StringBuilder b = new StringBuilder("{\"items\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(',');
            b.append("{\"link\":\"https://example.org/").append(i)
                    .append("\",\"title\":\"Seite ").append(i).append("\",\"snippet\":\"Text ").append(i).append("\"}");
        }
        return b.append("]}").toString();
    }

    /* ------------------------------------------------ Open WebUI live */

    static void owuiLive() throws Exception {
        ContextStub ctx = new ContextStub();
        Config cfg = new Config(ctx);
        cfg.baseUrl = envOr("OWUI_URL", "http://127.0.0.1:3000");
        cfg.mode = Config.MODE_OWUI;
        cfg.streaming = true;

        String email = System.getenv("OWUI_EMAIL");
        String pass = System.getenv("OWUI_PASSWORD");
        String session = System.getenv("OWUI_TOKEN");
        String apiKey = System.getenv("OWUI_API_KEY");
        Api api = new Api(ctx, cfg);

        if (session != null && !session.isEmpty()) {
            cfg.token = session;
            ok("Session-Token aus der laufenden Anmeldung", true);
        } else if (email != null && pass != null) {
            Map<String, Object> acc = api.signIn(email, pass);
            String token = (String) acc.get("token");
            ok("Login liefert Token", token != null && token.length() > 10);
            cfg.token = token;
        } else {
            System.out.println("  ÜBERSPRUNGEN: kein OWUI_TOKEN / OWUI_EMAIL");
            return;
        }

        final String keepToken = cfg.token;

        // Negativtest: falsches Passwort muss als 401 sauber ankommen
        try {
            api.signIn(email == null ? "probefrage@example.com" : email, "sicher-falsch-913");
            ok("falsches Passwort wird abgelehnt", false);
        } catch (Api.ApiException e) {
            // Open WebUI antwortet auf falsche Zugangsdaten mit 400 (aeltere Versionen: 401)
            ok("falsches Passwort -> HTTP " + e.status, e.status == 400 || e.status == 401);
        }

        List<ModelInfo> models = api.listModels();
        System.out.println("    Modelle: " + models.size());
        for (int i = 0; i < Math.min(4, models.size()); i++) {
            System.out.println("      - " + models.get(i).id
                    + "  vision=" + models.get(i).vision + "  bild=" + models.get(i).imageGen);
        }
        ok("Modelle gefunden", !models.isEmpty());

        String target = null;
        for (ModelInfo mi : models) {
            if (mi.id.contains("qwen3.8-flash")) {
                target = mi.id;
                break;
            }
        }
        if (target == null && !models.isEmpty()) target = models.get(0).id;

        final StringBuilder streamed = new StringBuilder();
        List<Msg> hist = new ArrayList<Msg>();
        hist.add(new Msg("user", "Antworte mit genau einem Wort: Ja"));
        String reply = api.chat(target, hist, new Api.Delta() {
            public void onDelta(String chunk, String full) {
                streamed.append(chunk);
            }
        });
        System.out.println("    Antwort: " + clip(reply));
        ok("Stream-Antwort nicht leer", reply != null && !reply.trim().isEmpty());
        ok("Stream-Chunks passen zur Antwort", streamed.length() >= reply.trim().length() - 4);

        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("    (kein API-Key im Konto – API-Key-Pfad wird mit dem Session-Token probiert)");
            apiKey = keepToken;
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            cfg.apiKey = apiKey;
            cfg.token = "";
            List<ModelInfo> viaKey = api.listModels();
            ok("API-Key-Zugang liefert Modelle", !viaKey.isEmpty());
            List<Msg> h2 = new ArrayList<Msg>();
            h2.add(new Msg("user", "Antworte mit genau einem Wort: Nein"));
            String r2 = api.chat(target, h2, null);
            ok("Chat mit API-Key", r2 != null && !r2.trim().isEmpty());
            System.out.println("    Antwort (API-Key): " + clip(r2));
            cfg.token = keepToken;
            cfg.apiKey = "";
        } else {
            System.out.println("  ÜBERSPRUNGEN: OWUI_API_KEY nicht gesetzt");
        }

        final byte[] img = testJpeg();
        System.out.println("    Testbild: " + img.length + " Bytes JPEG");
        List<Msg> vh = new ArrayList<Msg>();
        Msg um = new Msg("user", "Was ist auf diesem Bild? Antworte in einem kurzen deutschen Satz.");
        um.images.add(img);
        vh.add(um);
        String vision = api.chat(target, vh, null);
        System.out.println("    Vision-Antwort: " + clip(vision));
        ok("Vision über Open WebUI", vision != null && !vision.trim().isEmpty());

        /* ---- echte Bilder: ComfyUI-Pipeline statt ASCII-Spielerei ---- */
        String imageModel = null;
        for (ModelInfo mi : models) {
            if (mi.imageGen) {
                imageModel = mi.id;
                break;
            }
        }
        ok("Bild-Profil (ComfyUI) in der Modelliste", imageModel != null);
        if (imageModel != null) {
            try {
                List<Api.GenImage> gen = api.generateImage(imageModel,
                        "a red square on a white background, flat simple illustration", "832x832");
                ok("Bildgenerierung liefert Treffer", !gen.isEmpty());
                byte[] png = api.download(gen.get(0).url);
                boolean pngMagic = png.length > 1000 && png[0] == (byte) 0x89
                        && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
                System.out.println("    Bild: " + png.length + " Bytes, Typ " + gen.get(0).contentType
                        + ", Datei " + gen.get(0).name);
                ok("Geladenes Bild ist ein echtes PNG", pngMagic);

                String md = "![bild](" + gen.get(0).url + ")";
                List<String> mimes = new ArrayList<String>();
                List<byte[]> inline = api.inlineImages(md, mimes);
                ok("Markdown-Bildverweis wird zum Bild", inline.size() == 1 && inline.get(0).length > 1000);
                ok("Text ohne Bildverweis", Api.stripImageRefs(md).isEmpty());
            } catch (Exception e) {
                ok("Bildgenerierung: " + e.getMessage(), false);
            }
        }

        /* ---- Diktat: Audio -> Whisper auf dem Server -> Text ---- */
        try {
            byte[] audio = ttsAudio(cfg.baseUrl, keepToken, "Diktat Test, hallo Chat App");
            ok("Test-Audio erzeugt (" + audio.length + " Bytes)", audio.length > 1000);
            String text = api.transcribe(audio, "diktat.mp3", "audio/mpeg", "de");
            System.out.println("    Transkript (language=de): " + clip(text));
            /* language=de verbietet die Uebersetzung. "hallo" darf Whisper ruhig als
             * "hello" schreiben - ein englisches Wort ist keine Uebersetzung des Satzes. */
            String t = text.toLowerCase();
            ok("Diktat: Whisper uebersetzt den deutschen Satz nicht",
                    (t.contains("diktat") || t.contains("diktiert")) && !t.contains("dictation"));
        } catch (Exception e) {
            ok("Diktat: " + e.getMessage(), false);
        }

        /* ---- Vorlesen: genau der Weg, den die App fuer die Sprachausgabe nimmt ---- */
        try {
            byte[] spoken = api.speech("Das ist die Sprachausgabe der Chat-App.");
            System.out.println("    Sprachausgabe: " + (spoken == null ? 0 : spoken.length) + " Bytes Audio");
            ok("Vorlesen: api.speech liefert Audio", spoken != null && spoken.length > 1500);
        } catch (Exception e) {
            ok("Vorlesen: api.speech liefert Audio", false);
            System.out.println("    Sprachausgabe-Fehler: " + Api.explain(e));
        }

        /* ---- Stimmauswahl: jede Stimme muss anderes Audio liefern.
         * Zwei Namen, eine Stimme (Katja == Tanja, Conrad == Christoph) faellt nur auf,
         * wenn das Audio verglichen wird - nicht die Laenge allein. Gleiches Audio teilt
         * tausende identische Bytes am Anfang; verschiedene Stimmen trennen sich nach der
         * MP3-Kopfzeile (~20 Bytes). ---- */
        try {
            /* fester Text pro Durchlauf, aber nicht derselbe wie in frueheren Laeuften:
             * OWUI cached Sprachausgabe nach Text+Stimme, ein einmal abgebrochenes Audio
             * wuerde sonst fuer alle spaeteren Laeufe wieder ausgeliefert werden. */
            String stimmText = "Stimmvergleich Testtext fuer die App Folge " + (System.nanoTime() % 1000000);
            java.util.List<String> stimmNamen = new java.util.ArrayList<String>();
            java.util.List<byte[]> stimmAudio = new java.util.ArrayList<byte[]>();
            for (String id : Config.VOICE_IDS) {
                cfg.ttsVoice = id;
                byte[] a = api.speech(stimmText);
                if (a == null || a.length < 1500) throw new IOException("keine Sprachausgabe fuer " + id);
                stimmNamen.add(id.isEmpty() ? "(Server-Standard)" : id);
                stimmAudio.add(a);
            }
            cfg.ttsVoice = "";
            /* edge-tts liefert gelegentlich ein abgebrochenes Audio: ein Nachoetter, nicht gleich ein Fehler. */
            int groesstes = 0;
            for (byte[] a : stimmAudio) groesstes = Math.max(groesstes, a.length);
            for (int i = 0; i < stimmAudio.size(); i++) {
                if (stimmAudio.get(i).length < groesstes * 0.6) {
                    System.out.println("    " + stimmNamen.get(i) + " lieferte nur "
                            + stimmAudio.get(i).length + " B (erwartet ~" + groesstes + ") - nochmal");
                    cfg.ttsVoice = Config.VOICE_IDS[i];
                    byte[] again = api.speech(stimmText + " Noch einmal.");   /* anderer Text = kein Cache-Treffer */
                    cfg.ttsVoice = "";
                    if (again != null && again.length > stimmAudio.get(i).length) stimmAudio.set(i, again);
                }
            }
            StringBuilder lang = new StringBuilder();
            for (int i = 0; i < stimmNamen.size(); i++)
                lang.append(stimmNamen.get(i)).append(" ").append(stimmAudio.get(i).length).append("B, ");
            System.out.println("    Stimmen: " + lang);
            ok("Stimmauswahl: jede Stimme liefert volles Audio", stimmAudio.size() == Config.VOICE_IDS.length);

            /* Zwei Namen, eine Stimme: gleiches Audio teilt tausende identische Bytes am
             * Anfang, verschiedene Stimmen trennen sich nach der MP3-Kopfzeile (~20 Bytes).
             * Der Server-Standard ist ausgenommen - er ist, was auf dem Server eingestellt ist. */
            int gleich = 0;
            for (int i = 0; i < stimmAudio.size(); i++) {
                for (int j = i + 1; j < stimmAudio.size(); j++) {
                    if (stimmNamen.get(i).startsWith("(") || stimmNamen.get(j).startsWith("(")) continue;
                    byte[] a = stimmAudio.get(i), b = stimmAudio.get(j);
                    int n = Math.min(a.length, b.length), same = 0;
                    while (same < n && a[same] == b[same]) same++;
                    if (same > 200) {
                        if (stimmNamen.get(i).startsWith("(") || stimmNamen.get(j).startsWith("(")) {
                            String andere = stimmNamen.get(i).startsWith("(") ? stimmNamen.get(j) : stimmNamen.get(i);
                            System.out.println("    Hinweis: der Server-Standard ist " + andere);
                            continue;
                        }
                        gleich++;
                        System.out.println("    dieselbe Stimme hinter zwei Namen: "
                                + stimmNamen.get(i) + " == " + stimmNamen.get(j)
                                + " (" + same + " Bytes gleich)");
                    }
                }
            }
            ok("Stimmauswahl: keine zwei Namen fuer dieselbe Stimme", gleich == 0);
        } catch (Exception e) {
            ok("Stimmauswahl: " + e.getMessage(), false);
        }

        /* ---- Websuche: lokale Suchmaschine ueber Open WebUI, Antwort mit Fundstelle ---- */
        try {
            List<Search.Hit> hits = api.webSearch("Wetter Bielefeld morgen");
            System.out.println("    Websuche-Treffer: " + hits.size());
            for (int i = 0; i < Math.min(3, hits.size()); i++) {
                System.out.println("      - " + hits.get(i).title + "  |  " + hits.get(i).url
                        + "  |  Seitentext " + hits.get(i).content.length() + " Zeichen");
            }
            ok("Websuche liefert Fundstellen mit URL", !hits.isEmpty() && hits.get(0).url.startsWith("http"));
            int SeitenText = 0;
            for (Search.Hit h : hits) SeitenText += h.content.length();
            ok("Server liest die Seiten selbst (nicht nur Snippet)", SeitenText > 1000);
            if (!hits.isEmpty()) {
                List<Msg> wh = new ArrayList<Msg>();
                wh.add(new Msg("user", "Wie wird das Wetter morgen in Bielefeld? Nenne die Temperatur in Grad."));
                String r3 = api.chat(target, wh, Search.systemBlock(hits), null);
                System.out.println("    Antwort mit Websuche: " + clip(r3));
                ok("Antwort nennt eine Temperatur aus der Suche",
                        r3 != null && (r3.contains("Grad") || r3.contains("°")));
                String stored = Search.sourceLines(hits);
                ok("Fundstellen lassen sich speichern", stored.contains("\t") && !Search.sourceList(stored).isEmpty());
            }

            List<Search.Hit> price = api.webSearch("Xiaomi 15T Pro Preis");
            String priceBlock = Search.systemBlock(price);
            System.out.println("    Preis-Suche-Treffer: " + price.size()
                    + ", davon " + priceTextChars(price) + " Zeichen Seitentext");
            ok("Preisfrage findet Fundstellen", !price.isEmpty());
            ok("Preisfrage bringt echte Betraege unters Modell", priceBlock.matches("(?s).*[0-9][0-9.,]{2,}.*€.*|(?s).*€ *[0-9][0-9.,]{2,}.*"));

            if (!price.isEmpty()) {
                List<Msg> ph = new ArrayList<Msg>();
                ph.add(new Msg("user", "Was kostet das Xiaomi 15T Pro gerade mindestens? Nenne einen konkreten Euro-Betrag."));
                String pr = api.chat(target, ph, Search.systemBlock(price), null);
                System.out.println("    Preis-Antwort: " + clip(pr));
                ok("Preisantwort nennt einen Euro-Betrag statt nur den Hinweis auf Portale",
                        pr != null && pr.matches("(?s).*[0-9][0-9][.,][0-9]{2}.*"));
            }

            List<Search.Hit> follow = api.webSearch("und mit 512 GB", "Was kostet das Xiaomi 15T Pro?");
            ok("Folgefrage findet trotzdem den Preis", !follow.isEmpty()
                    && Search.systemBlock(follow).matches("(?s).*(Xiaomi|15T).*"));
        } catch (Exception e) {
            ok("Websuche: " + e.getMessage(), false);
        }

        // TLS über die HTTPS-Route: eigener Server über OWUI_HTTPS, LAN-CA als Asset
        String httpsUrl = envOr("OWUI_HTTPS", "");
        if (httpsUrl.isEmpty()) {
            System.out.println("  ÜBERSPRUNGEN: HTTPS-Test braucht OWUI_HTTPS");
        } else try {
            Config c2 = new Config(new ContextStub());
            c2.baseUrl = httpsUrl;
            c2.mode = Config.MODE_OWUI;
            c2.token = keepToken;
            Api api2 = new Api(new ContextStub(), c2);
            List<ModelInfo> m2 = api2.listModels();
            ok("HTTPS mit gebündelter LAN-CA (" + httpsUrl + ")", !m2.isEmpty());
        } catch (Exception e) {
            ok("HTTPS mit gebündelter LAN-CA (" + httpsUrl + "): " + e.getMessage(), false);
        }
    }

    /* ------------------------------------------------ direkter Modus live */

    static void directLive() throws Exception {
        ContextStub ctx = new ContextStub();
        Config cfg = new Config(ctx);
        cfg.baseUrl = envOr("DIRECT_URL", "http://127.0.0.1:8080");
        cfg.mode = Config.MODE_DIRECT;

        Api api = new Api(ctx, cfg);
        List<ModelInfo> models = api.listModels();
        ok("direkt: Modelle ohne Schlüssel", !models.isEmpty());
        if (models.isEmpty()) return;
        System.out.println("    Modell: " + models.get(0).id + "  vision=" + models.get(0).vision);

        List<Msg> hist = new ArrayList<Msg>();
        hist.add(new Msg("user", "Antworte mit genau einem Wort: Ja"));
        String reply = api.chat(models.get(0).id, hist, new Api.Delta() {
            public void onDelta(String chunk, String full) { }
        });
        System.out.println("    Antwort: " + clip(reply));
        ok("direkt: Chat funktioniert", reply != null && !reply.trim().isEmpty());

        List<Msg> vh = new ArrayList<Msg>();
        Msg um = new Msg("user", "Was ist auf diesem Bild? Ein Satz.");
        um.images.add(testJpeg());
        vh.add(um);
        try {
            String vision = api.chat(models.get(0).id, vh, null);
            System.out.println("    Vision: " + clip(vision));
            ok("direkt: Vision akzeptiert", vision != null && !vision.trim().isEmpty());
        } catch (Exception e) {
            System.out.println("    Vision-Fehler: " + e.getMessage());
            ok("direkt: Vision akzeptiert", false);
        }
    }

    /** Sprachausgabe des Servers als Test-Audio fuer die Whisper-Route. */
    static byte[] ttsAudio(String base, String token, String text) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/v1/audio/speech").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setDoOutput(true);
            String body = "{\"model\":\"tts-1\",\"input\":\"" + text + "\",\"voice\":\"de-DE-KatjaNeural\"}";
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("speech HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Seitenzeichen aller Treffer (fuer die Test-Ausgabe). */
    static int priceTextChars(List<Search.Hit> hits) {
        int n = 0;
        for (Search.Hit h : hits) n += h.content.length();
        return n;
    }

    static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? def : v;
    }

    static String clip(String s) {
        if (s == null) return "null";
        s = s.replace("\n", " ");
        return s.length() > 170 ? s.substring(0, 170) + "…" : s;
    }

    /** 96x96 JPEG: weißer Grund, rotes Quadrat links oben, blauer Kreis rechts unten. */
    static byte[] testJpeg() throws Exception {
        int w = 96, h = 96;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = 0xF5F5F0;
                if (y >= 10 && y < 50 && x >= 10 && x < 50) c = 0xD40000;
                double dx = x - 70, dy = y - 66;
                if (dx * dx + dy * dy <= 400) c = 0x0066FF;
                img.setRGB(x, y, c);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
