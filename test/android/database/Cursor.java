package android.database;
public interface Cursor {
    boolean moveToFirst();
    boolean moveToNext();
    String getString(int index);
    long getLong(int index);
    int getInt(int index);
    byte[] getBlob(int index);
    int getCount();
    void close();
}
