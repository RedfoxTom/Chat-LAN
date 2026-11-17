package android.text;
public interface Spanned extends CharSequence {
    int SPAN_EXCLUSIVE_EXCLUSIVE = 33;
    int SPAN_MARK_MARK = 17;
    Object[] getSpans(int start, int end, Class type);
}
