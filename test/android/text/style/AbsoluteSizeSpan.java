package android.text.style;
public class AbsoluteSizeSpan {
    private final int size;
    private final boolean dip;
    public AbsoluteSizeSpan(int size) { this(size, false); }
    public AbsoluteSizeSpan(int size, boolean dip) { this.size = size; this.dip = dip; }
    public int getSize() { return size; }
    public boolean dip() { return dip; }
    public String toString() { return "AbsoluteSizeSpan=" + size; }
}
