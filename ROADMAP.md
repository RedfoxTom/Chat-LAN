# Chat LAN – Roadmap

Project: this repository.

## Versions (distributed as GitHub releases)

- 1.0 – 1.3  Base: chat, images, context compression, TTS, dictation, vision, quality
             (guarded by the `Test` suite)
- 1.4  Image mode stops talking: the conversation button disappears in image mode, a running
       voice round ends on model switch, image replies are never read aloud
       (rules in `src/de/owui/chat/Router.java`, guarded by `Test.routerTests()`)
- 1.5  Web search and voice selection (as of 2026-11-17; the search button was simplified in 1.6):
       * Web search button in the input field: off / auto / on. Auto searches for weather, prices,
         news, appointments, public transport opening times – the rules and the query construction
         live in `src/de/owui/chat/Search.java`, searching goes through
         `POST /api/v1/retrieval/process/web/search` (Open WebUI → its SearXNG backend).
         The found pages go to the model as a system block and stay available as clickable source
         lines under the answer (stored in SQLite as an `text/uri-list` attachment).
       * Voice selection in the settings: server default / Tanja / Katja / Christoph / Conrad.
         The app then sends `voice`; verified live that Open WebUI passes the value on to
         `openai-edge-tts`. The choice is saved immediately and can be heard right away as a
         sample. (Replaced in 1.7: that list only contained two real voices, see 1.7.)
- 1.6  Web search reduced to off / on, and it really reads the pages (built, tested, shipped as
       the GitHub release owui-chat-1.6.apk):
       * One tap toggles (off <-> on); colour and text show the state, a long press explains it.
         No "auto" any more, no info dialog instead of a function.
       * The decisive part was the server setting (namespace `web.search`, check with
         `GET /api/v1/configs/namespace/web.search`): `bypass_embedding_and_retrieval=True` –
         the search endpoint then returns the loaded pages directly as `docs`; without that flag
         they only end up in a vector collection and the app sees nothing but snippets, so the
         model answers with a reference to portals. Plus `bypass_web_loader=False` (pages are
         really fetched) and `result_count=5`. `web.fetch.max_content_length` is left unset – the
         app limits it itself: it attaches each hit to its page text (`Search.parse` reads `items`
         and `docs`) and sends an extract of at most 2000 characters, lines with numbers first,
         menu and cookie lines last.
       * Verified live: the price search "Xiaomi 15T Pro Preis" -> 5 hits, 112192 characters of
         page text, answer "449,99 € – Clevertronic …". Short follow-up questions like "und mit
         512 GB" now search using the context of the previous question (`Search.queryWithContext`).
       * Read aloud only in chat mode (green button) and in the dictation conversation mode;
         dictation without conversation and typed questions stay silent. Individual answers can
         still be read aloud through a long press on the message.
- 1.7  Voice fixed: "male and female sound the same" (built, tested, shipped as the GitHub
       release owui-chat-1.7.apk):
       * Cause: the list since 1.5 named four voices, but `de-DE-KatjaNeural` is the same voice as
         `de-DE-TanjaNeural` and `de-DE-ConradNeural` the same as `de-DE-ChristophNeural` – same
         text, exactly the same byte length, the first ~4.7 kB identical, curve correlation 1.00.
         So `de-DE` only had two real voices; and the server default (`audio.tts.voice=de-DE-KatjaNeural`)
         is female. edge-tts itself offers six voices for the German-speaking area.
       * New: Tanja / Christoph (de), Ingrid / Jonas (AT), Leni / Jan (CH) – three female and three
         male, labels show the language. Old stored Katja/Conrad values map to Tanja/Christoph
         through `Config.LEGACY_VOICES`, unknown values stay on the server default. The sample now
         says the voice name ("Christoph speaks."), and the settings show which voice is active.
       * Bug fixed in the old spinner: `setSelection()` after the listener was registered silently
         reset the stored voice when the screen opened; the selection is now set before the listener
         and a `firstReport` guard ignores the first callback.
       * Guarded by a live test: `Test.liveTests()` generates audio for every voice and checks the
         longest common byte sequence (>200 identical bytes = the same voice) – it passes, as do the
         other 130+ live checks.
- 1.8  Voice conversation listens again after the answer (built, tested, shipped as the GitHub
       release owui-chat-1.8.apk):
       * Symptom: the answer arrived and was read aloud, after that the app no longer reacted to
         voice, even though the conversation button was still green (mode on).
       * Cause: `startVoiceTurn()` began with `if (voiceRunning || transcribing) return;`.
         `voiceRunning` was set to true on the first press and only back to false on abort – the
         re-entry after reading aloud called exactly that method, found true and returned
         immediately. So nothing was recorded any more while the bar ("● Keep talking …") and the
         button still showed "on". With the loop switched off ("one round only") the same state
         stayed permanently.
       * New: `src/de/owui/chat/VoiceFlow.java` holds exactly one stage at a time
         (recording -> understanding -> thinking -> speaking -> pause), separate from the mode
         (sessionOn). The mode is no longer tied to a "still running" switch: the pause after
         reading aloud is its own transition and the next recording starts. MainActivity no longer
         has the chatSession / voiceRunning / voiceAnswerPending fields.
       * Rules guarded in `Test.voiceFlowTests()`: 21 checks, among them four rounds in a row,
         "the pause does not block the new recording", "one round only", "an audio error ends the
         round", "a transition from the wrong stage is ignored".
       * Also: the button press is now defined per stage (stop recording / abort the round /
         interrupt speaking / start listening); a sample or "read aloud" of a single message no
         longer cuts into a running conversation (that was a second way into the same stuck state);
         a 400 ms tail after reading aloud does not count as a separate statement; every stage
         change is logged in logcat under "owui-voice" (`adb logcat -s owui-voice`) so such a case
         can be followed on the phone.
- 1.8.1  Public build, distribution changed (built, tested, shipped as the GitHub release
       owui-chat-1.8.1.apk, versionCode 10):
       * No LAN CA inside the APK any more: built with `OWUI_NO_CA=1`, `assets/ca.crt` is not
         bundled. Every installation has its own CA – it does not belong in a public artefact.
       * A different signing key than 1.0 – 1.8 (`CN=Chat LAN, O=chat-lan, C=DE`, the key lives
         outside the repository). Uninstall the previous version before installing: Android rejects
         an update over 1.8.
       * Ready-made APKs are now GitHub releases instead of `https://<server>:3443/dl/`.
       * Documentation neutralised: `Config.DEFAULT_URL` and the three suggestions in the login
         screen use an example value – no real LAN addresses in the repository.

## Quick table

| Version | Fixed / added | Guarded by |
|---|---|---|
| 1.0 – 1.3 | Base: chat, images, compression, TTS, dictation, vision, quality | `Test` suite |
| 1.4 | Image mode stops talking: voice button hidden in image mode, a running voice round ends on model switch, image replies are never read aloud | `Router.java`, `Test.routerTests()` |
| 1.5 | Web search (off / auto / on) and voice selection | `Search.java` |
| 1.6 | Web search reduced to off / on, and it really reads the pages (`bypass_embedding_and_retrieval=True` on the server) | live price-search check in `Test.liveTests()` |
| 1.7 | Voices fixed: "male and female sound the same" – Katja/Conrad are aliases of Tanja/Christoph, so `de-DE` only has two real voices; now 3 female + 3 male (de / AT / CH) | byte-comparison test per voice |
| 1.8 | Voice conversation listens again after the answer: one stage at a time in `VoiceFlow.java` instead of a "still running" flag | `Test.voiceFlowTests()` (21 checks) |
| 1.8.1 | Public build: no LAN CA inside the APK (`OWUI_NO_CA=1`), different signing key than 1.0 – 1.8 (uninstall the old version first), APKs distributed as GitHub releases, documentation neutralised (example address only) | release notes |

## Open voice items

1. A/B listening: same short text, Tanja / Ingrid / Leni (f) and Christoph / Jonas / Jan (m),
   compare duration and sound, then decide the day voice and the night voice. The app can do this
   by now: change the voice, the sample names it.
2. Speaking rate: `EDGE_SPEED` (default 1.0) or `rate` (e.g. `+10%`) – open question whether Open
   WebUI passes the parameter on (`audio.tts.openai.params` would be the place for it) before the
   app sends it itself.
3. Finding, as of 2026-11-17: edge-tts lists six voices for the German-speaking area (de-DE Tanja f
   / Christoph m, de-AT Ingrid f / Jonas m, de-CH Leni f / Jan m), but the list marks all of them
   as female – the gender in the list is unreliable, the voices themselves are different.
   `de-DE-KatjaNeural` and `de-DE-ConradNeural` are not own voices but alias names of Tanja and
   Christoph (identical audio). The server default is still `de-DE-KatjaNeural` (= Tanja, female).

Not planned for 1.5: a real realtime voice call (deliberately kept cloud-free, not set up).
