package android.text;
public final class TextUtils {
    public enum TruncateAt { START, MIDDLE, END, MARQUEE }
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static CharSequence truncate(CharSequence s, int max, TruncateAt where) { return s; }
}
