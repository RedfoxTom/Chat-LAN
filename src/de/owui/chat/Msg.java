package de.owui.chat;

import java.util.ArrayList;
import java.util.List;

/** Eine Chat-Nachricht. role: user | assistant | system */
public class Msg {
    public String role;
    public String text;
    public String model;
    public long created;
    public long dbId = -1;
    public final List<byte[]> images = new ArrayList<byte[]>();
    /** mime zu jedem Eintrag in images: Anhaenge sind JPEG, erzeugte Bilder PNG. */
    public final List<String> imageMimes = new ArrayList<String>();
    /** Webtreffer als Zeilen "Titel\tURL" – bleiben in der Blase und in der Datenbank. */
    public final List<String> sources = new ArrayList<String>();

    public void addImage(byte[] data, String mime) {
        if (data == null) return;
        images.add(data);
        imageMimes.add(mime == null || mime.isEmpty() ? "image/jpeg" : mime);
    }

    public void addSources(List<String> lines) {
        if (lines == null) return;
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) sources.add(line.trim());
        }
    }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    public Msg(String role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.created = System.currentTimeMillis();
    }

    public boolean hasImage() {
        return !images.isEmpty();
    }
}
