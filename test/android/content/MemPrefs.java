package android.content;
import java.util.HashMap;
import java.util.Map;
/** Testimplementierung: legt Einstellungen im Speicher ab. */
public class MemPrefs implements SharedPreferences {
    public final Map<String, Object> data = new HashMap<String, Object>();
    public String getString(String k, String d) { Object v = data.get(k); return v == null ? d : (String) v; }
    public int getInt(String k, int d) { Object v = data.get(k); return v == null ? d : (Integer) v; }
    public long getLong(String k, long d) { Object v = data.get(k); return v == null ? d : (Long) v; }
    public float getFloat(String k, float d) { Object v = data.get(k); return v == null ? d : (Float) v; }
    public boolean getBoolean(String k, boolean d) { Object v = data.get(k); return v == null ? d : (Boolean) v; }
    public Editor edit() { return new Ed(); }
    private class Ed implements Editor {
        public Editor putString(String k, String v) { data.put(k, v); return this; }
        public Editor putInt(String k, int v) { data.put(k, v); return this; }
        public Editor putLong(String k, long v) { data.put(k, v); return this; }
        public Editor putFloat(String k, float v) { data.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { data.put(k, v); return this; }
        public Editor remove(String k) { data.remove(k); return this; }
        public void apply() { }
        public boolean commit() { return true; }
    }
}
