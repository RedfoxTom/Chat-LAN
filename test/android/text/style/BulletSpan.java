package android.text.style;
public class BulletSpan {
    private final int gap;
    public BulletSpan() { this(34); }
    public BulletSpan(int gapWidth) { this.gap = gapWidth; }
    public BulletSpan(int gapWidth, int color) { this.gap = gapWidth; }
    public int getGapWidth() { return gap; }
    public String toString() { return "BulletSpan=" + gap; }
}
