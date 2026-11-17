package android.util;
/** Test-Base64: mit NO_WRAP wie der JDK-Encoder. */
public final class Base64 {
    public static final int STD = 0;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;
    public static final int DEFAULT = 0;
    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
    public static byte[] decode(String s, int flags) {
        return java.util.Base64.getDecoder().decode(s);
    }
}
