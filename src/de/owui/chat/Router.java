package de.owui.chat;

/**
 * Reine Entscheidungsregeln der Bottom-Bar, damit der Bild-Modus und die
 * Sprach-Unterhaltung sich nicht gegenseitig in die Quere kommen.
 */
public class Router {

    /** Ein Bild-Profil erzeugt aus dem Prompt ein Bild; ein angehaengtes Bild bleibt eine Textfrage. */
    public static boolean wantsPicture(boolean imageModelSelected, boolean hasAttachedImage) {
        return imageModelSelected && !hasAttachedImage;
    }

    /** Sprach-Unterhaltung (sprechen -> vorgelesen) gibt es nur ohne Bild-Modus und nur ueber Open WebUI. */
    public static boolean voiceChatAllowed(boolean owuiMode, boolean imageModelSelected) {
        return owuiMode && !imageModelSelected;
    }

    /** Eine Antwort wird vorgelesen: eine reine Bild-Antwort nie. */
    public static boolean maySpeak(boolean imageModelSelected, boolean hasText) {
        return !imageModelSelected && hasText;
    }

    /**
     * Vorgelesen wird nur in der Sprach-Unterhaltung. Diktat und getippte Fragen
     * bleiben stumm - die Antwort soll in dem Moment nicht angesagt werden.
     */
    public static boolean speaksOnAnswer(boolean voiceChatRound, boolean imageModelSelected, boolean hasText) {
        return voiceChatRound && maySpeak(imageModelSelected, hasText);
    }
}
