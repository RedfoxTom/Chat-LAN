package android.text.style;
public class RelativeSizeSpan {
    private final float size;
    public RelativeSizeSpan(float size) { this.size = size; }
    public float getSizeChange() { return size; }
    public String toString() { return "RelativeSizeSpan=" + size; }
}
