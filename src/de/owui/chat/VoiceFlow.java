package de.owui.chat;

/**
 * Zustand einer Sprach-Unterhaltung: an/aus und welche Stufe gerade laeuft.
 * Bewusst Android-frei, damit der Ablauf im JVM-Test durchgespielt werden kann.
 *
 * Grund fuer diese Klasse (Fehler in 1.7): der Modus wurde ueber einen einzelnen
 * "Sprachdurchlauf laeuft"-Schalter geführt, der nach der Antwort nie zurueckgesetzt
 * wurde. Der Wiedereinstieg "nach dem Vorlesen wieder aufnehmen" pruefte genau diesen
 * Schalter, fand ihn auf true und begann keine neue Aufnahme - die App zeigte weiter
 * "sprich" an, hoerte aber auf nichts. Deshalb hier genau eine Phase pro Augenblick,
 * und der Wiedereinstieg ist ein eigener Uebergang (SPEAKING -> NONE, Sitzung an).
 */
public class VoiceFlow {

    /** Modus aus, nichts laeuft. */
    public static final int NONE = 0;
    /** Mikrofon offen, der Nutzer spricht. */
    public static final int LISTENING = 1;
    /** Aufnahme ist weg, Whisper versteht sie. */
    public static final int TRANSLATING = 2;
    /** Frage steht, das Modell antwortet. */
    public static final int THINKING = 3;
    /** Antwort steht, sie wird vorgelesen. */
    public static final int SPEAKING = 4;

    /** Druck auf den Unterhaltungs-Knopf in dieser Phase. */
    public static final int PRESS_LISTEN = 0;        // zuhoeren beginnen
    public static final int PRESS_FINISH = 1;       // Aufnahme beenden, Antwort kommt
    public static final int PRESS_ABORT = 2;        // laufende Runde abbrechen
    public static final int PRESS_STOP_SPEECH = 3;  // Sprechen unterbrechen

    /** volatile: die Arbeits-Thread lesen mit (isThinking), der UI-Thread schreibt. */
    private volatile int phase = NONE;
    private volatile boolean session;

    public int phase() { return phase; }

    /** Unterhaltungs-Modus ist an (Knopf ist gruen), auch in der kurzen Pause zwischen zwei Runden. */
    public boolean sessionOn() { return session; }

    public boolean isListening() { return phase == LISTENING; }
    public boolean isTranslating() { return phase == TRANSLATING; }
    public boolean isThinking() { return phase == THINKING; }
    public boolean isSpeaking() { return phase == SPEAKING; }

    /** Etwas arbeitet gerade: Mikrofon, Whisper, Modell oder Stimme. */
    public boolean roundActive() { return session && phase != NONE; }

    public String phaseName() {
        switch (phase) {
            case LISTENING: return "aufnahme";
            case TRANSLATING: return "verstehe";
            case THINKING: return "denke";
            case SPEAKING: return "spreche";
            default: return session ? "pause" : "aus";
        }
    }

    /** Was ein Knopfdruck in dieser Phase bedeuten soll. */
    public int pressAction() {
        switch (phase) {
            case LISTENING: return PRESS_FINISH;
            case TRANSLATING:
            case THINKING: return PRESS_ABORT;
            case SPEAKING: return PRESS_STOP_SPEECH;
            default: return PRESS_LISTEN;       // Modus an und Pause, oder Modus aus
        }
    }

    /** Modus einschalten und die erste Aufnahme eroeffnen. false wenn schon etwas laeuft. */
    public boolean openSession() {
        if (phase != NONE) return false;
        session = true;
        phase = LISTENING;
        return true;
    }

    /** Naechste Aufnahme derselben Unterhaltung. Nur aus der Pause moeglich. */
    public boolean beginTurn() {
        if (!session || phase != NONE) return false;
        phase = LISTENING;
        return true;
    }

    /** Aufnahme geschlossen, Whisper uebernimmt. */
    public boolean finishRecording() {
        if (phase != LISTENING) return false;
        phase = TRANSLATING;
        return true;
    }

    /** Text ist da, die Frage geht ans Modell. */
    public boolean transcriptReady() {
        if (phase != TRANSLATING) return false;
        phase = THINKING;
        return true;
    }

    /**
     * Antwort ist fertig. speak=true -> Vorlesen, speak=false -> Unterhaltung endet
     * (Bild-Antwort oder nichts Vorlesbares).
     */
    public boolean answerReady(boolean speak) {
        if (phase != THINKING) return false;
        if (speak) {
            phase = SPEAKING;
        } else {
            phase = NONE;
            session = false;
        }
        return true;
    }

    /**
     * Vorlesen ist zu Ende. true = die naechste Aufnahme soll beginnen (Modus bleibt an).
     * loop=false heisst "nur eine Runde" und schliesst die Unterhaltung.
     */
    public boolean continueAfterSpeech(boolean ok, boolean loop) {
        if (phase != SPEAKING) return false;
        phase = NONE;
        if (!ok || !loop) {
            session = false;
            return false;
        }
        return true;
    }

    /** Hartes Ende: Aufnahme und Sitzung verwerfen. */
    public void closeSession() {
        phase = NONE;
        session = false;
    }
}
