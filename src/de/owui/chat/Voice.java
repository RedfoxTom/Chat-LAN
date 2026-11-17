package de.owui.chat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Sprachausgabe fuer die Unterhaltung: MP3 vom Server abspielen und den Text
 * fuer die Sprachausgabe aus Markdown formen.
 */
public class Voice {

    public interface Done {
        /** aufgerufen, wenn die Ausgabe zu Ende ist (ok=false bei Tonfehler). */
        void onDone(boolean ok, String error);
    }

    private final Context ctx;
    private MediaPlayer player;
    private volatile boolean speaking;
    private int session;

    public Voice(Context ctx) {
        this.ctx = ctx;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** Audio abspielen (MP3/WAV-Bytes aus /api/v1/audio/speech). */
    public void play(byte[] audio, final Done cb) {
        stop();
        if (audio == null || audio.length == 0) {
            if (cb != null) cb.onDone(false, Txt.t(R.string.err_no_audio, "The server returned no audio"));
            return;
        }
        final int mySession = ++session;
        File f = new File(ctx.getCacheDir(), "voice-output.mp3");
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(audio);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            if (cb != null) cb.onDone(false, Txt.t(R.string.err_audio_save, "Audio could not be saved: %1$s", e.getMessage()));
            return;
        }

        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (mySession != session) {   // zwischenzeitlich gestoppt
                        m.release();
                        return;
                    }
                    speaking = true;
                    m.start();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    release(m);
                    if (mySession == session) {
                        speaking = false;
                        if (cb != null) cb.onDone(true, null);
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    release(m);
                    if (mySession == session) {
                        speaking = false;
                        if (cb != null) cb.onDone(false, "Tonwiedergabe fehlgeschlagen (Fehler " + what + ")");
                    }
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            release(mp);
            if (cb != null) cb.onDone(false, "Tonwiedergabe: " + e.getMessage());
            return;
        }
        player = mp;
    }

    /** Wiedergabe abbrechen (Knopfdruck waehrend des Sprechens). */
    public void stop() {
        session++;
        speaking = false;
        release(player);
        player = null;
    }

    public void release() {
        stop();
    }

    private static void release(MediaPlayer mp) {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.stop();
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    /**
     * Antworttext fuer die Sprachausgabe: Markdown-Schmuck weg, Code-Blocke und
     * Bilder raus, Links nur als Text, am Satzende gekuerzt.
     */
    public static String spoken(String markdown, int maxChars) {
        if (markdown == null) return "";
        String s = Api.stripImageRefs(markdown).replace("\r\n", "\n").replace("\r", "\n");

        // Code-Blocke komplett entfernen (vorgelesen bringt dort nichts)
        StringBuilder kept = new StringBuilder();
        boolean inCode = false;
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) continue;
            kept.append(line).append('\n');
        }
        s = kept.toString();

        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");     // Link: nur der Text
        s = s.replaceAll("(?m)^[ \t]*#{1,6}[ \t]+", " ");          // Ueberschriften
        s = s.replaceAll("(?m)^[ \t]*[-*+][ \t]+", "");            // Aufzaehrungszeichen
        s = s.replaceAll("(?m)^[ \t]*\\d+[.)][ \t]+", "");         // nummerierte Listen
        s = s.replaceAll("(?m)^[ \t]*>[ \t]?", "");                // Zitate
        s = s.replaceAll("(?m)^[ \t]*[|:\\-]+[ \t]*$", "");        // Tabellen-Trennlinien
        s = s.replaceAll("[*`_]", " ");
        s = s.replaceAll("[|]{1,}", " ");                          // Markdown-Tabellen
        s = s.replaceAll("[ \t]+", " ");
        s = s.replaceAll("\\n{2,}", ". ");                          // Absatz -> Satzgrenze
        s = s.replaceAll("\\n", " ");
        s = s.replaceAll("\\s+([,.!?:;])", "$1");                  // kein Leerzeichen vor Satzzeichen
        s = s.replaceAll("[:]+([.!?])", "$1");                     // haessliche ":."-Konstrukte
        s = s.replaceAll("\\.{3,}", ". ");
        s = s.replaceAll("\\.\\s*\\.", ". ");
        s = s.replaceAll("(?<![\\wßäöü])\\.[ \\t]*", " ");          // verwaiste Punkte (leere Zeilen, Tabellen)
        s = s.replaceAll("\\s{2,}", " ").trim();

        if (s.length() > maxChars) {
            String head = s.substring(0, maxChars);
            int cut = Math.max(head.lastIndexOf(". "), head.lastIndexOf("! "));
            if (cut < maxChars / 2) cut = head.lastIndexOf("? ");
            s = cut > maxChars / 2 ? head.substring(0, cut + 1) : head + " …";
        }
        return s.trim();
    }
}
