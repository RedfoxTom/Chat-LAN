package android.text.style;
public class BackgroundColorSpan {
    private final int color;
    public BackgroundColorSpan(int color) { this.color = color; }
    public int getBackgroundColor() { return color; }
    public String toString() { return "BackgroundColorSpan=" + Integer.toHexString(color); }
}
