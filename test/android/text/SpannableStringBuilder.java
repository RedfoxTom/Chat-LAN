package android.text;
import java.util.ArrayList;
import java.util.List;
/** Test-Version: merkt sich Span-Würfe, damit Tests die Formatierung prüfen können. */
public class SpannableStringBuilder implements Spannable {
    private final StringBuilder sb = new StringBuilder();
    public final List<Object[]> spans = new ArrayList<Object[]>();

    public SpannableStringBuilder() { }
    public SpannableStringBuilder(CharSequence text) { append(text); }

    public int length() { return sb.length(); }
    public char charAt(int i) { return sb.charAt(i); }
    public CharSequence subSequence(int a, int b) { return sb.subSequence(a, b); }
    public String toString() { return sb.toString(); }

    public SpannableStringBuilder append(char c) { sb.append(c); return this; }
    public SpannableStringBuilder append(CharSequence text) { sb.append(text); return this; }
    public SpannableStringBuilder append(CharSequence text, int start, int end) { sb.append(text, start, end); return this; }
    public SpannableStringBuilder append(Object text) { sb.append(text); return this; }
    public SpannableStringBuilder delete(int start, int end) { sb.delete(start, end); return this; }
    public SpannableStringBuilder insert(int where, CharSequence text) { sb.insert(where, text); return this; }
    public void getChars(int srcStart, int srcEnd, char[] dst, int dstStart) { sb.getChars(srcStart, srcEnd, dst, dstStart); }

    public void setSpan(Object what, int start, int end, int flags) { spans.add(new Object[]{what, start, end, flags}); }
    public void removeSpan(Object what) {
        for (int i = spans.size() - 1; i >= 0; i--) if (spans.get(i)[0] == what) spans.remove(i);
    }

    public Object[] getSpans(int start, int end, Class type) {
        List<Object> out = new ArrayList<Object>();
        for (Object[] s : spans) {
            int a = ((Integer) s[1]).intValue(), b = ((Integer) s[2]).intValue();
            if (a >= start && b <= end) out.add(s[0]);
        }
        return out.toArray();
    }
}
