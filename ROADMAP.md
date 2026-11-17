# Chat LAN – Roadmap

Projekt: dieses Repository

## Versionen (verteilt über GitHub-Releases)

- 1.0 – 1.3  Basis: Chat, Bilder, Kompression, TTS, Diktat, Vision, Qualität
- 1.4  Bild-Modus ohne Sprach-Chat: Unterhaltungs-Knopf verschwindet im Bild-Modus,
       laufende Sprachrunde endet beim Modellwechsel, Bild-Antworten werden nie vorgelesen
       (Regeln in `src/de/owui/chat/Router.java`, abgesichert in `Test.routerTests()`)
- 1.5  Websuche und Stimmauswahl (Stand 17.11.2026; Websuche-Knopf in 1.6 vereinfacht):
       * Websuche-Knopf im Eingabefeld: Aus / Auto / An. Auto sucht bei Wetter, Preisen,
         News, Terminen, Öffi-Öffnungszeiten – Regeln und Suchbegriff-Bildung in
         `src/de/owui/chat/Search.java`, gesucht wird über
         `POST /api/v1/retrieval/process/web/search` (OpenWebUI → `searxng-local`).
         Die Fundstellen gehen als Systemblock ans Modell und stehen als klickbare
         Quellzeilen unter der Antwort (in SQLite als Anhang `text/uri-list`).
       * Stimmauswahl in den Einstellungen: Server-Standard / Tanja / Katja / Christoph /
         Conrad. Die App schickt dann `voice` mit; live verifiziert, dass OpenWebUI den
         Wert an `openai-edge-tts` durchreicht. Auswahl wird sofort gespeichert und ist als
         Hörprobe gleich zu hoeren. (In 1.7 ersetzt: diese Liste enthielt nur zwei echte
         Stimmen, siehe 1.7.)
- 1.6  Websuche nur noch Aus/An, und sie liest die Seiten wirklich (Stand 17.11.2026,
       gebaut, getestet, verteilt als GitHub-Release owui-chat-1.6.apk):
       * Ein Druck auf den Knopf schaltet um (Aus <-> An), Farbe und Text zeigen den Zustand,
         langes Drücken erklärt. Kein "Auto" mehr, kein Hinweis-Dialog statt Funktion.
       * Entscheidend war die Server-Einstellung (Namespace `web.search`, prüfen mit
         `GET /api/v1/configs/namespace/web.search`): `bypass_embedding_and_retrieval=True` –
         dann gibt der Such-Endpoint die geladenen Seiten direkt als `docs` zurück; ohne dieses
         Flag landen sie nur in einer Vektor-Sammlung und die App sieht bloß Snippets, das
         Modell antwortet dann nur mit dem Hinweis auf Portale. Dazu `bypass_web_loader=False`
         (Seiten werden wirklich geladen) und `result_count=5`. `web.fetch.max_content_length`
         ist ungesetzt – die App begrenzt selbst: sie ordnet jeder Fundstelle ihren Seitentext
         zu (`Search.parse` liest `items` + `docs`) und schickt einen Auszug von max. 2000
         Zeichen, Zahlenzeilen zuerst, Menu-/Cookie-Zeilen hinten.
       * Live verifiziert: Preissuche "Xiaomi 15T Pro Preis" -> 5 Fundstellen, 112192 Zeichen
         Seitentext, Antwort "449,99 € – Clevertronic …". Kurzfragen wie "und mit 512 GB"
         suchen jetzt mit dem Zusammenhang der vorigen Frage (`Search.queryWithContext`).
       * Vorgelesen wird nur im Chat-Modus (grüner Knopf) und beim Diktat-Unterhaltungsmodus;
         Diktat ohne Unterhaltung und getippte Fragen bleiben stumm. Einzelne Antworten lassen
         sich weiter per langem Druck auf die Nachricht vorlesen.

- 1.7  Stimme gefixt: "maennlich und weiblich klingen gleich" (Stand 17.11.2026, gebaut,
       getestet, verteilt als GitHub-Release owui-chat-1.7.apk):
       * Ursache: die Auswahl seit 1.5 nannte vier Stimmen, aber `de-DE-KatjaNeural` ist
         dieselbe Stimme wie `de-DE-TanjaNeural` und `de-DE-ConradNeural` dieselbe wie
         `de-DE-ChristophNeural` – gleicher Text, exakt gleiche Byte-Länge, die ersten
         ~4,7 kB identisch, Kurvenkorrelation 1,00. Es gab in `de-DE` also nur zwei echte
         Stimmen; außerdem ist der Server-Standard (`audio.tts.voice=de-DE-KatjaNeural`)
         weiblich. edge-tts selbst bietet für den deutschen Raum sechs Stimmen.
       * Neu: Tanja / Christoph (de), Ingrid / Jonas (AT), Leni / Jan (CH) – je drei weiblich
         und männlich, Labels zeigen Sprache. Alte gespeicherte Katja-/Conrad-Werte laufen
         über `Config.LEGACY_VOICES` auf Tanja/Christoph, unbekannte bleiben Server-Standard.
         Die Hörprobe sagt jetzt den Stimmnamen ("Christoph spricht."), und die Einstellungen
         zeigen unten, welche Stimme gerade aktiv ist.
       * Fehler im alten Spinner behoben: `setSelection()` nach dem Registrieren des Listeners
         setzte die gespeicherte Stimme beim Öffnen still zurück; jetzt steht die Auswahl vor
         dem Listener und ein `firstReport`-Guard ignoriert den ersten Callback.
       * Live-Test abgesichert: `Test.liveTests()` erzeugt für jede Stimme Audio und prüft die
         längste gemeinsame Byte-Folge (>200 Bytes gleich = dieselbe Stimme) – läuft durch,
           ebenso die übrigen 130+ Live-Prüfungen.

## Restliche Punkte Stimme

1. A/B-Hören: gleicher kurzer Text, Tanja / Ingrid / Leni (w) und Christoph / Jonas / Jan (m),
   Dauer und Klang vergleichen, dann Tages- und Nachtstimme festlegen. Die App kann das inzwischen:
   Stimme umstellen, Hörprobe nennt die Stimme.
2. Sprechtempo: `EDGE_SPEED` (Standard 1.0) bzw. `rate` (z. B. `+10%`) – offen ist, ob
   OpenWebUI den Parameter durchreicht (`audio.tts.openai.params` wäre der Ort dafuer),
   bevor die App ihn selbst schickt.
3. Auffälligkeit, gemessen am 17.11.2026: edge-tts listet für den deutschen Raum sechs Stimmen
   (de-DE Tanja w / Christoph m, de-AT Ingrid w / Jonas m, de-CH Leni w / Jan m), aber die
   Liste weist alle als weiblich aus – das Geschlecht in der Liste ist unzuverlässig, die
   Stimmen selbst sind verschieden. `de-DE-KatjaNeural` und `de-DE-ConradNeural` sind keine
   eigenen Stimmen, sondern Alias-Namen von Tanja und Christoph (identisches Audio).
   Server-Standard ist weiterhin `de-DE-KatjaNeural` (= Tanja, weiblich).

Nicht für 1.5: echter Realtime-Voice-Call (bewusst cloud-frei gelassen, noch nicht eingerichtet).

- 1.8  Sprach-Unterhaltung hoert nach der Antwort wieder zu (Stand 17.11.2026, gebaut,
       getestet, verteilt als GitHub-Release owui-chat-1.8.apk):
       * Symptom: die Antwort kam und wurde vorgelesen, danach reagierte die App nicht mehr
         auf Stimme, obwohl der Unterhaltungs-Knopf noch grün (Modus an) war.
       * Ursache: `startVoiceTurn()` begann mit `if (voiceRunning || transcribing) return;`.
         `voiceRunning` wurde beim ersten Druck auf true gesetzt und erst beim Abbruch wieder
         auf false - der Wiedereinstieg nach dem Vorlesen rief genau diese Methode auf, fand
         true und kehrte sofort zurück. Es lief also keine Aufnahme mehr, während Bar
         ("● Sprich weiter …") und Knopf weiter "an" zeigten. Mit ausgestellt Schleife
         ("nur eine Runde") blieb derselbe Zustand dauerhaft stehen.
       * Neu: `src/de/owui/chat/VoiceFlow.java` führt genau eine Stufe pro Augenblick
         (aufnahme -> verstehe -> denke -> spreche -> Pause) und getrennt davon den Modus
         (sessionOn). Der Modus ist damit nicht mehr an einen "laeuft noch"-Schalter gekoppelt;
         die Pause nach dem Vorlesen ist ein eigener Uebergang und die naechste Aufnahme beginnt.
         MainActivity hat keine chatSession-/voiceRunning-/voiceAnswerPending-Felder mehr.
       * Regeln abgesichert in `Test.voiceFlowTests()`: 21 Prüfungen, darunter vier Runden
         hintereinander, "Pause blockiert die neue Aufnahme nicht", "nur eine Runde",
         "Tonfehler beendet", "Uebergang aus falscher Stufe wird ignoriert".
       * Dazu: Knopfdruck je Stufe jetzt definiert (aufnahme beenden / Runde abbrechen /
         Sprechen unterbrechen / zuhoeren beginnen); eine Hoerprobe oder "Vorlesen" einer
         einzelnen Nachricht schneidet eine laufende Unterhaltung nicht mehr ab (war ein
         zweiter Weg in denselben Haenge-Zustand); 400 ms Nachhall nach dem Vorlesen zaehlen
         nicht als eigene Aussage; jede Stufenanderung steht in logcat unter "owui-voice"
         (`adb logcat -s owui-voice`), damit so ein Fall auf dem Telefon nachvollziehbar ist.
