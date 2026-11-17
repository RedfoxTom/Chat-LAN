package de.owui.chat;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Mikrofon-Aufnahme fuer Diktat: 16 kHz Mono PCM, fertig verpackt als WAV.
 * Whisper (Open WebUI) mag WAV direkt, das Format ist also bewusst einfach.
 */
public class Recorder {

    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SECONDS = 60;

    private AudioRecord rec;
    private Thread thread;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private volatile boolean running;
    private volatile int peak;
    private volatile long startedAt;

    public void start() throws IOException {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min == AudioRecord.ERROR || min == AudioRecord.ERROR_BAD_VALUE) min = 3200;
        AudioRecord candidate = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 2, 4096));
        if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
            candidate.release();
            throw new IOException(Txt.t(R.string.err_mic, "Microphone not available (is access allowed?)"));
        }
        rec = candidate;
        pcm.reset();
        peak = 0;
        running = true;
        startedAt = System.currentTimeMillis();
        rec.startRecording();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[2048];
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    pcm.write(buf, 0, n);
                    int p = peak;
                    for (int i = 0; i + 1 < n; i += 2) {
                        short s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                        int a = s < 0 ? -s : s;
                        if (a > p) p = a;
                    }
                    peak = p;
                }
            }
        });
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    /** Lautstaerke seit dem letzten Aufruf (0..32767), fuer die Anzeige. */
    public int level() {
        int p = peak;
        peak = 0;
        return p;
    }

    public int seconds() {
        return running ? (int) ((System.currentTimeMillis() - startedAt) / 1000) : 0;
    }

    /** Stoppt und liefert WAV-Bytes, oder null wenn zu kurz. */
    public byte[] stop() {
        byte[] raw = closeAndRead();
        // unter ~0.4 s bringt Whisper nichts
        if (raw.length < SAMPLE_RATE / 2 * 4) return null;
        return wav(raw);
    }

    public void cancel() {
        closeAndRead();
    }

    private byte[] closeAndRead() {
        running = false;
        try {
            if (thread != null) thread.join(600);
        } catch (InterruptedException ignored) {
        }
        thread = null;
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (Exception ignored) {
        }
        rec = null;
        byte[] raw = pcm.toByteArray();
        pcm.reset();
        return raw;
    }

    private static byte[] wav(byte[] audio) {
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        le(h, 4, 36 + audio.length);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        le(h, 16, 16);
        le(h, 20, 1);                       // PCM
        le(h, 22, 1);                       // Mono
        le(h, 24, SAMPLE_RATE);
        le(h, 28, SAMPLE_RATE * 2);         // Bytes pro Sekunde
        le(h, 32, 2);                       // Block ausrichtung
        le(h, 34, 16);                      // Bits pro Sample
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        le(h, 40, audio.length);
        byte[] out = new byte[44 + audio.length];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(audio, 0, out, 44, audio.length);
        return out;
    }

    private static void le(byte[] b, int at, int v) {
        b[at] = (byte) (v & 0xFF);
        b[at + 1] = (byte) ((v >> 8) & 0xFF);
        b[at + 2] = (byte) ((v >> 16) & 0xFF);
        b[at + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
