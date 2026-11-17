package android.content;
import java.util.LinkedHashMap;
import java.util.Map;
public class ContentValues {
    public final Map<String, Object> values = new LinkedHashMap<String, Object>();
    public void put(String key, String v) { values.put(key, v); }
    public void put(String key, long v) { values.put(key, v); }
    public void put(String key, int v) { values.put(key, (long) v); }
    public void put(String key, byte[] v) { values.put(key, v); }
    public void putNull(String key) { values.put(key, null); }
    public int size() { return values.size(); }
}
