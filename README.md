# Chat LAN

Native Android client for a **self-hosted** Open WebUI (or for any OpenAI-compatible
model server) – no cloud, no third-party servers, everything stays on your own network.

No React/Capacitor, no Gradle: a small native Android app in Java, built with
`aapt2` + `javac` + `d8` + `apksigner`. The APK is about 160 kB and runs on Android 8
(API 26) or newer.

## What the app does

| Area | How |
|---|---|
| Chat | Open WebUI (`/api/chat/completions`) or direct (`/v1/chat/completions`), streaming, system prompt, temperature, history in SQLite |
| Images | ComfyUI through Open WebUI (workflow and image model chosen in the settings), sizes 832 / 1024 / 1280, images in replies are tappable |
| Vision | Attach an image, the model answers about it |
| Dictation | `POST /v1/audio/transcriptions` (Whisper, `language=de`), or the phone's own speech input as a fallback |
| Voice conversation | Record → understand → think → answer → read aloud, conversation mode with a stage indicator (`VoiceFlow.java`) |
| Text to speech | `POST /api/v1/audio/speech` (edge-tts through Open WebUI), 7 voices to choose from (de / AT / CH), preview in the settings |
| Web search | `POST /api/v1/retrieval/process/web/search` (Open WebUI → SearXNG); the found pages are handed to the model as a system block and shown as clickable source lines under the answer |
| Markdown | Bold paragraphs, lists, quotes, code blocks – rendered by hand, no library |

Deliberately **not** built in: a real realtime voice call (that runs through cloud
services). Voice here is ASR → chat → TTS, so everything stays in your own network.

The app interface itself is German.

## Interface language

The interface is **English**. Every label, button, hint, toast and error message is a text
resource in `res/values/strings.xml` and is referenced from the code (`@string/...` in the
layouts, `getString(R.string....)` / `Txt.t(R.string....)` in the Java files) – no UI sentence
is written into a layout or a Java file, so a wording lives in exactly one place. On a plain JVM
(the tests) the resources are stubbed and the code falls back to its English source text.

`res/values-de/strings.xml` is the German twin of the same key list. It is there because the
maintainer runs the app at home in German: an internal build keeps the overlay, a public build is
compiled without it and is English on every phone whatever the phone's system language is. Both
languages live in the same source tree, so a fix reaches both at the same time and a missing
translation fails a test (`Test.languageTests()`).

```bash
bash build.sh                # internal build: German overlay included
OWUI_PUBLIC=1 bash build.sh  # public build: English only, no LAN CA
```

## Building

The toolbox (no Gradle, no Android Studio needed):

```
JDK 17 or newer              -> javac, keytool
Android SDK:
  build-tools/34.0.0         -> aapt2, d8, zipalign, apksigner
  platforms/android-34       -> android.jar
python3                      -> injects the dex into the APK
```

```bash
export JAVA_HOME=/path/to/jdk          # default: $HOME/tools/owui-android/jdk
export ANDROID_HOME=/path/to/sdk       # default: $HOME/Android/Sdk
cd Chat-LAN
OWUI_PUBLIC=1 bash build.sh            # -> build/apk/owui-chat.apk (English, no LAN CA)
```

`OWUI_PUBLIC=1` is the profile for anything you hand to other people: English interface only
(the `res/values-de` overlay is not compiled in) and no LAN CA. Without it the APK additionally
carries the German overlay and your own `assets/ca.crt` – that is the home build.

On its first run `build.sh` creates a signing keystore (`build/owui-release.keystore`) and
writes its password to `build/owui-release.keystore.pass`. Neither the keystore nor the
password is in the repository. **Back both of them up** – without them future versions are
no longer updates but only installable as a fresh installation. A self-built APK also has a
different key than the published releases: remove the release app from the phone before
installing your own build, otherwise Android will not accept both side by side.

LAN CA for HTTPS (optional, see `assets/README.txt`):

```bash
cp /path/to/your-lan-ca.crt assets/ca.crt
```

## Testing

A large part of the logic (markdown, ChatML, token trimming, JSON, router, search rules,
`VoiceFlow`) is deliberately free of Android APIs and therefore runs on a plain JVM – the
Android classes needed for those tests live in `test/android/`.

```bash
export JAVA_HOME=/path/to/jdk
export PATH="$JAVA_HOME/bin:$PATH"
bash build.sh   # once: it generates the resource ids (R.java) that the app code references
javac -d build/jvm $(find src test -name '*.java' \
        | grep -v -e MainActivity -e LoginActivity -e '/Voice.java' -e '/Recorder.java') \
    build/apk/gen/de/owui/chat/R.java
java -cp build/jvm de.owui.chat.Test offline     # no server, 130+ checks
OWUI_TOKEN=... java -cp build/jvm de.owui.chat.Test live   # against the real server
```

`live` needs `OWUI_TOKEN` (and optionally `OWUI_EMAIL`) from the environment. A token is
never in the repository.

## Counterpart on the server

The app expects an Open WebUI (or a plain model server) on the network. For a **web search
that really returns page text** (not just snippets) these settings are needed:

```
web.search.bypass_embedding_and_retrieval = True   # pages come back as docs
web.search.bypass_web_loader             = False   # pages are actually fetched
web.search.result_count                  = 5
```

Without `bypass_embedding_and_retrieval` the pages only end up in a vector collection and
the app sees nothing but snippets – price and weather questions then go wrong. Check with:

```
GET /api/v1/configs/namespace/web.search
```

## Signing in to the app

| Mode | Address |
|---|---|
| Open WebUI | `https://<server>:3443` (HTTPS through your own CA) or `http://<server>:3000` |
| Direct (OpenAI-compatible) | `http://<server>:8080/v1` – no login, no API key needed |

`Config.DEFAULT_URL` and the three suggestions in the login screen are set to an example
value (`192.168.1.20`, ports 3443 / 3000 / 8080) – an invented address, not a real network.
To change it: the default in `src/de/owui/chat/Config.java`, the suggestions in
`res/layout/activity_login.xml`.

## Downloading the APK

Ready-made APKs are under [Releases](https://github.com/RedfoxTom/Chat-LAN/releases). The
public APKs are built with `OWUI_PUBLIC=1` (English interface, no LAN CA inside the APK) and
signed with the public key `owui-public.keystore`. If you instead build with your own key
(`bash build.sh`, with `assets/ca.crt`), you have to uninstall the previous version before
installing – different signatures do not allow an update.

## Versions

`ROADMAP.md` lists every version with the bug that was fixed behind it and the test that
catches it.

## Licence

MIT – see `LICENSE`.
