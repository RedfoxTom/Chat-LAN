package android.text.format;
/** Test-Version: deutsche relative Zeit für die Seitenleiste. */
public final class DateUtils {
    public static final long MINUTE_IN_MILLIS = 60000L;
    public static final long HOUR_IN_MILLIS = 3600000L;
    public static final long DAY_IN_MILLIS = 86400000L;
    public static CharSequence getRelativeTimeSpanString(long start, long now, long minResolution) {
        long d = Math.max(0, now - start);
        if (d < 60000) return "gerade eben";
        if (d < 3600000) return (d / 60000) + " Min.";
        if (d < 86400000) return (d / 3600000) + " Std.";
        if (d < 7 * 86400000L) return (d / 86400000) + " T.";
        return String.valueOf(start / 1000);
    }
}
