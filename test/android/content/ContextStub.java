package android.content;
import android.content.res.AssetManager;
/** Test-Kontext: liefert Speicher-Prefs, eine Zwischenablage und Assets aus test/assets. */
public class ContextStub extends Context {
    public final MemPrefs prefs = new MemPrefs();
    public final ClipboardManager clipboard = new ClipboardManager();
    private final AssetManager assets = new AssetManager();
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    public Object getSystemService(String name) { return CLIPBOARD_SERVICE.equals(name) ? clipboard : null; }
    public AssetManager getAssets() { return assets; }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
}
