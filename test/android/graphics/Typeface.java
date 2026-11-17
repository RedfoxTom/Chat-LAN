package android.graphics;
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;
    public static Typeface DEFAULT = new Typeface("default");
    public final String family;
    public Typeface(String family) { this.family = family; }
    public static Typeface create(String family, int style) { return new Typeface(family); }
    public static Typeface create(Typeface base, int style) { return new Typeface(base == null ? "default" : base.family); }
    public String getFamily() { return family; }
}
