package android.text.style;
public class TypefaceSpan {
    private final String family;
    public TypefaceSpan(String family) { this.family = family; }
    public String getFamily() { return family; }
    public String toString() { return "TypefaceSpan=" + family; }
}
