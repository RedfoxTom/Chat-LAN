package android.database.sqlite;
import android.content.Context;
/** Test-Helfer: legt eine gemeinsame In-Memory-Datenbank an. */
public abstract class SQLiteOpenHelper {
    private static final SQLiteDatabase SHARED = new SQLiteDatabase();
    protected SQLiteOpenHelper(Context c, String name, Object factory, int version) { }
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);
    public SQLiteDatabase getWritableDatabase() {
        SQLiteDatabase db = SHARED;
        if (db.executed.isEmpty()) onCreate(db);
        return db;
    }
    public SQLiteDatabase getReadableDatabase() { return getWritableDatabase(); }
    public void close() { }
}
