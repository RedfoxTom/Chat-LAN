package android.content;
import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
/** JVM-Gegenstück zu android.content.Context (nur die von der App gerufenen Methoden). */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String CLIPBOARD_SERVICE = "clipboard";
    public SharedPreferences getSharedPreferences(String name, int mode) { throw new UnsupportedOperationException(); }
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public AssetManager getAssets() { return null; }
    public Resources getResources() { return null; }
    public File getFilesDir() { return new File(System.getProperty("java.io.tmpdir")); }
    public File getCacheDir() { return new File(System.getProperty("java.io.tmpdir")); }
    public String getString(int id) { return ""; }
}
