package android.content;
/** JVM-Gegenstück zu android.content.SharedPreferences. */
public interface SharedPreferences {
    String getString(String key, String def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    float getFloat(String key, float def);
    boolean getBoolean(String key, boolean def);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        void apply();
        boolean commit();
    }
}
