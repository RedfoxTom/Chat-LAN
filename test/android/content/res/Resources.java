package android.content.res;
import android.util.DisplayMetrics;
public class Resources {
    private final DisplayMetrics metrics = new DisplayMetrics();
    public DisplayMetrics getDisplayMetrics() { return metrics; }

    /** Kein Uebersetzer im JVM-Test: die App faellt auf ihren Programmtext zurueck. */
    public String getString(int id) { throw new UnsupportedOperationException("no resources on JVM"); }
    public String getString(int id, Object... formatArgs) { throw new UnsupportedOperationException("no resources on JVM"); }
    public String[] getStringArray(int id) { throw new UnsupportedOperationException("no resources on JVM"); }
}
