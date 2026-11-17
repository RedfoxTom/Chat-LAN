package android.text.style;
public interface LeadingMarginSpan {
    int getLeadingMargin(boolean first);
    void drawLeadingMargin(Object canvas, Object paint, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, Object fmt);
    class Standard implements LeadingMarginSpan {
        private final int margin;
        public Standard(int margin) { this.margin = margin; }
        public Standard(int margin, int color) { this.margin = margin; }
        public int getLeadingMargin(boolean first) { return margin; }
        public void drawLeadingMargin(Object canvas, Object paint, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, Object fmt) { }
        public String toString() { return "LeadingMarginSpan.Standard=" + margin; }
    }
}
