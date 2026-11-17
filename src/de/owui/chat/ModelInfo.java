package de.owui.chat;

/** Ein vom Server gemeldetes Modell, inklusive Fahigkeiten (sehen / Bilder erzeugen). */
public class ModelInfo {
    public final String id;
    public final String label;
    public final boolean vision;
    public final boolean imageGen;

    public ModelInfo(String id, String label, boolean vision) {
        this(id, label, vision, false);
    }

    public ModelInfo(String id, String label, boolean vision, boolean imageGen) {
        this.id = id;
        this.label = label == null || label.isEmpty() ? id : label;
        this.vision = vision;
        this.imageGen = imageGen;
    }

    @Override
    public String toString() {
        return label;
    }
}
