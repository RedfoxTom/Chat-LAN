package de.owui.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lokale Chat-Ablage (SQLite). Bilder hängen als JPEG-BLOB an der Nachricht. */
public class Db extends SQLiteOpenHelper {

    public static class ChatRef {
        public String id;
        public String title;
        public String model;
        public long created;
        public long updated;
    }

    public Db(Context c) {
        super(c, "chatlan.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat(id TEXT PRIMARY KEY, title TEXT, model TEXT, created INTEGER, updated INTEGER)");
        db.execSQL("CREATE TABLE msg(id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT, role TEXT, content TEXT, model TEXT, created INTEGER)");
        db.execSQL("CREATE TABLE att(id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, mime TEXT, data BLOB)");
        db.execSQL("CREATE INDEX idx_msg_chat ON msg(chat_id)");
        db.execSQL("CREATE INDEX idx_att_msg ON att(msg_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 -> v2: Spalten fehlen hier bewusst nichts
    }

    public String newChat(String model) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("title", "");
        v.put("model", model);
        v.put("created", now);
        v.put("updated", now);
        getWritableDatabase().insert("chat", null, v);
        return id;
    }

    public List<ChatRef> listChats(int limit) {
        List<ChatRef> out = new ArrayList<ChatRef>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, title, model, created, updated FROM chat ORDER BY updated DESC LIMIT " + limit, null);
        try {
            while (c.moveToNext()) {
                ChatRef r = new ChatRef();
                r.id = c.getString(0);
                r.title = c.getString(1);
                r.model = c.getString(2);
                r.created = c.getLong(3);
                r.updated = c.getLong(4);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public boolean exists(String chatId) {
        if (chatId == null || chatId.isEmpty()) return false;
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM chat WHERE id = ?", new String[]{chatId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public String modelOf(String chatId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT model FROM chat WHERE id = ?", new String[]{chatId});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally {
            c.close();
        }
    }

    /** Titel aus der ersten Nutzerfrage ableiten, Chat auf "zuletzt" stellen. */
    public void touchChat(String chatId, String firstUserText, String model) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("updated", System.currentTimeMillis());
        if (model != null) v.put("model", model);
        if (firstUserText != null && !firstUserText.isEmpty()) {
            Cursor c = db.rawQuery("SELECT title FROM chat WHERE id = ?", new String[]{chatId});
            String current = "";
            try {
                if (c.moveToFirst()) current = c.getString(0);
            } finally {
                c.close();
            }
            if (current == null || current.isEmpty()) v.put("title", Util.abbreviate(firstUserText, 60));
        }
        db.update("chat", v, "id = ?", new String[]{chatId});
    }

    public long addMessage(String chatId, Msg m) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("chat_id", chatId);
        v.put("role", m.role);
        v.put("content", m.text);
        v.put("model", m.model);
        v.put("created", m.created);
        long id = db.insert("msg", null, v);
        for (int i = 0; i < m.images.size(); i++) {
            ContentValues a = new ContentValues();
            a.put("msg_id", id);
            a.put("mime", i < m.imageMimes.size() ? m.imageMimes.get(i) : "image/jpeg");
            a.put("data", m.images.get(i));
            db.insert("att", null, a);
        }
        if (!m.sources.isEmpty()) {
            // Webtreffer kommen als Textzeilen in dieselbe Anhang-Tabelle
            ContentValues a = new ContentValues();
            a.put("msg_id", id);
            a.put("mime", "text/uri-list");
            a.put("data", Search.storedLines(m.sources).getBytes(java.nio.charset.Charset.forName("UTF-8")));
            db.insert("att", null, a);
        }
        db.update("chat", oneLong("updated", System.currentTimeMillis()), "id = ?", new String[]{chatId});
        return id;
    }

    public List<Msg> messages(String chatId) {
        List<Msg> out = new ArrayList<Msg>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, role, content, model, created FROM msg WHERE chat_id = ? ORDER BY id ASC",
                new String[]{chatId});
        try {
            while (c.moveToNext()) {
                Msg m = new Msg(c.getString(1), c.getString(2));
                m.dbId = c.getLong(0);
                m.model = c.getString(3);
                m.created = c.getLong(4);
                long msgId = c.getLong(0);
                Cursor a = db.rawQuery("SELECT data, mime FROM att WHERE msg_id = ? ORDER BY id ASC", new String[]{String.valueOf(msgId)});
                try {
                    while (a.moveToNext()) {
                        byte[] blob = a.getBlob(0);
                        String mime = a.getString(1);
                        if (mime != null && mime.startsWith("text/")) {
                            m.addSources(Search.sourceList(new String(blob, java.nio.charset.Charset.forName("UTF-8"))));
                        } else {
                            m.images.add(blob);
                            m.imageMimes.add(mime == null || mime.isEmpty() ? "image/jpeg" : mime);
                        }
                    }
                } finally {
                    a.close();
                }
                out.add(m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteMessage(long msgId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("att", "msg_id = ?", new String[]{String.valueOf(msgId)});
        db.delete("msg", "id = ?", new String[]{String.valueOf(msgId)});
    }

    public long lastMessageId(String chatId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM msg WHERE chat_id = ? ORDER BY id DESC LIMIT 1", new String[]{chatId});
        try {
            if (c.moveToFirst()) return c.getLong(0);
            return -1;
        } finally {
            c.close();
        }
    }

    public void deleteChat(String chatId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("att", "msg_id IN (SELECT id FROM msg WHERE chat_id = ?)", new String[]{chatId});
        db.delete("msg", "chat_id = ?", new String[]{chatId});
        db.delete("chat", "id = ?", new String[]{chatId});
    }

    private static ContentValues oneLong(String key, long value) {
        ContentValues v = new ContentValues();
        v.put(key, value);
        return v;
    }
}
