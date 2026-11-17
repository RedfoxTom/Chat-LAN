# Chat LAN

Nativer Android-Client für ein **lokales** Open WebUI (oder direkt für jeden
OpenAI-kompatiblen Modellserver) – ohne Cloud, ohne fremde Server, alles im eigenen Netz.

Kein React/Capacitor, kein Gradle: eine kleine native Android-App in Java, gebaut mit
`aapt2` + `javac` + `d8` + `apksigner`. APK ~160 kB, läuft ab Android 8 (API 26).

## Was die App kann

| Bereich | Wie |
|---|---|
| Chat | Open WebUI (`/api/chat/completions`) oder direkt (`/v1/chat/completions`), Streaming, Systemprompt, Temperatur, Historie in SQLite |
| Bilder | ComfyUI über Open WebUI (Workflow und Bild-Modell in den Einstellungen), Größen 832 / 1024 / 1280, Antwortbilder klickbar |
| Vision | Bild anhängen, Modell antwortet darauf |
| Diktat | `POST /v1/audio/transcriptions` (Whisper, `language=de`) oder die Telefon-Spracheingabe als Fallback |
| Sprach-Unterhaltung | Aufnahme → Verstehen → Antworten → Vorlesen, Gesprächsmodus mit Stufenanzeige (`VoiceFlow.java`) |
| Sprachausgabe | `POST /api/v1/audio/speech` (edge-tts über Open WebUI), 7 Stimmen zur Auswahl (de / AT / CH), Hörprobe in den Einstellungen |
| Websuche | `POST /api/v1/retrieval/process/web/search` (Open WebUI → SearXNG), Fundstellen als Systemblock ans Modell, klickbare Quellzeilen unter der Antwort |
| Markdown | Fette Absätze, Listen, Zitate, Codeblöcke – selbst gerendert, keine Bibliothek |

Bewusst **nicht** eingebaut: ein echter Realtime-Voice-Call (läuft über Cloud-Dienste).
Voice ist hier ASR → Chat → TTS, damit alles im Netz bleibt.

## Bauen

Werkzeugkasten (kein Gradle, kein Android Studio nötig):

```
JDK 17 oder neuer            -> javac, keytool
Android SDK:
  build-tools/34.0.0         -> aapt2, d8, zipalign, apksigner
  platforms/android-34       -> android.jar
python3                      -> dex in die APK einfügen
```

```bash
export JAVA_HOME=/pfad/zur/jdk          # Standard: $HOME/tools/owui-android/jdk
export ANDROID_HOME=/pfad/zum/sdk       # Standard: $HOME/Android/Sdk
cd chat-lan
bash build.sh                            # -> build/apk/owui-chat.apk
```

Beim ersten Lauf legt `build.sh` ein Signier-Keystore an (`build/owui-release.keystore`) und
schreibt sein Passwort in `build/owui-release.keystore.pass`. Keystore und Passwort liegen
nicht im Repository. **Beides sichern** – ohne sie sind künftige Versionen keine Updates
mehr, sondern nur als Neuinstallation verteilbar. Ein selbst gebauter APK hat
ausserdem einen anderen Schlüssel als die veröffentlichten Releases: vor der Installation
die Release-App vom Telefon entfernen, sonst will Android beide nebeneinander nicht.

LAN-CA für HTTPS (optional, siehe `assets/README.txt`):

```bash
cp /pfad/zu/deiner-lan-ca.crt assets/ca.crt
```

## Testen

Ein großer Teil der Logik (Markdown, ChatML, Token-Kürzung, JSON, Router, Suchregeln,
`VoiceFlow`) ist bewusst frei von Android-APIs und läuft deshalb auf normaler JVM –
die Android-Klassen für diese Tests liegen in `test/android/`.

```bash
export JAVA_HOME=/pfad/zur/jdk
export PATH="$JAVA_HOME/bin:$PATH"
javac -d build/jvm $(find src test -name '*.java' \
        | grep -v -e MainActivity -e LoginActivity -e '/Voice.java' -e '/Recorder.java')
java -cp build/jvm de.owui.chat.Test offline     # ohne Server, 130+ Prüfungen
OWUI_TOKEN=... java -cp build/jvm de.owui.chat.Test live   # gegen den echten Server
```

`live` braucht `OWUI_TOKEN` (und optional `OWUI_EMAIL`) aus der Umgebung. Ein Token liegt
nie im Repository.

## Gegenstück auf dem Server

Die App setzt ein Open WebUI (oder einen reinen Modellserver) im Netz voraus. Für
funktionierende **Websuche mit Seitentext** (nicht nur Snippets) sind diese Einstellungen nötig:

```
web.search.bypass_embedding_and_retrieval = True   # Seiten kommen als docs zurück
web.search.bypass_web_loader             = False   # Seiten werden wirklich geladen
web.search.result_count                  = 5
```

Ohne `bypass_embedding_and_retrieval` landen die Seiten nur in einer Vektorsammlung und
die App sieht bloß Snippets – Preis- und Wetterfragen gehen dann daneben. Prüfen:

```
GET /api/v1/configs/namespace/web.search
```

## Anmelden in der App

| Modus | Adresse |
|---|---|
| Open WebUI | `https://<server>:3443` (HTTPS durch eigene CA) oder `http://<server>:3000` |
| Direkt (OpenAI-kompatibel) | `http://<server>:8080/v1` – kein Login, kein API-Key nötig |

`Config.DEFAULT_URL` und die drei Vorschläge der Anmeldemaske stehen auf einem Beispielwert
(`192.168.1.20`, Ports 3443 / 3000 / 8080) – eine erfundene Adresse, kein reales Netz.
Anpassen: Standardwert in `src/de/owui/chat/Config.java`, Vorschläge in
`res/layout/activity_login.xml`.

## APK herunterladen

Fertige APKs liegen unter [Releases](../../releases). Die öffentlichen APKs werden mit
`OWUI_NO_CA=1` gebaut (keine LAN-CA in der APK) und mit dem öffentlichen Schlüssel
`build/owui-public.keystore` signiert. Wer stattdessen selbst mit dem eigenen Schlüssel baut
(`bash build.sh`, mit `assets/ca.crt`), muss die Vorgängerversion vor der Installation
deinstallieren – verschiedene Signaturen lassen kein Update zu.

## Versionen

`ROADMAP.md` führt die einzelnen Versionen mit dem Fehler, der dahinter repariert wurde,
und dem Test, der ihn abfängt.

## Lizenz

MIT – siehe `LICENSE`.
