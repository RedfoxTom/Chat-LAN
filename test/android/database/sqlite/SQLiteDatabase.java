package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
import java.util.*;
import java.util.regex.*;
/** Sehr kleiner In-Memory-Ersatz für die von Db.java benutzten SQL-Formulierungen. */
public class SQLiteDatabase {
    static class Row { final Map<String, Object> cols = new LinkedHashMap<String, Object>(); }
    final Map<String, List<Row>> tables = new LinkedHashMap<String, List<Row>>();
    final Map<String, Long> seq = new HashMap<String, Long>();
    public final List<String> executed = new ArrayList<String>();

    public void execSQL(String sql) {
        executed.add(sql);
        String s = sql.trim();
        if (s.toLowerCase().startsWith("create table")) {
            Matcher m = Pattern.compile("(?i)create table\\s+(\\w+)").matcher(s);
            if (m.find()) { tables.put(m.group(1), new ArrayList<Row>()); seq.put(m.group(1), 0L); }
        }
    }

    public long insert(String table, String nullColumn, ContentValues v) {
        List<Row> rows = tables.get(table);
        if (rows == null) throw new IllegalStateException("keine Tabelle " + table);
        long id = seq.merge(table, 1L, new java.util.function.BiFunction<Long, Long, Long>() {
            public Long apply(Long a, Long b) { return a + b; }
        });
        Row r = new Row();
        r.cols.putAll(v.values);
        if (!r.cols.containsKey("id") || r.cols.get("id") == null) r.cols.put("id", id);
        rows.add(r);
        return id;
    }

    public int update(String table, ContentValues v, String where, String[] args) {
        List<Row> rows = tables.get(table);
        if (rows == null) return 0;
        String col = whereColumn(where);
        int n = 0;
        for (Row r : rows) {
            if (matches(r, col, args)) {
                r.cols.putAll(v.values);
                n++;
            }
        }
        return n;
    }

    public int delete(String table, String where, String[] args) {
        List<Row> rows = tables.get(table);
        if (rows == null) return 0;
        String col = whereColumn(where);
        List<String> sub = subquery(where);
        int n = 0;
        Iterator<Row> it = rows.iterator();
        while (it.hasNext()) {
            Row r = it.next();
            if (sub != null) {
                List<Row> inner = tables.get(sub.get(0));
                Set<Object> ids = new HashSet<Object>();
                if (inner != null) {
                    for (Row ir : inner) if (matches(ir, sub.get(1), args)) ids.add(ir.cols.get(sub.get(2)));
                }
                if (ids.contains(r.cols.get(col))) { it.remove(); n++; }
            } else if (matches(r, col, args)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    private static String whereColumn(String where) {
        Matcher m = Pattern.compile("(?i)^\\s*(\\w+)").matcher(where == null ? "" : where);
        return m.find() ? m.group(1) : null;
    }

    /** unterstützt "col IN (SELECT id FROM msg WHERE chat_id = ?)" */
    private static List<String> subquery(String where) {
        if (where == null) return null;
        Matcher m = Pattern.compile(
                "(?i)IN\\s*\\(\\s*SELECT\\s+(\\w+)\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(\\w+)\\s*=\\s*\\?\\s*\\)").matcher(where);
        if (!m.find()) return null;
        return Arrays.asList(m.group(2), m.group(3), m.group(1));
    }

    private static boolean matches(Row r, String col, String[] args) {
        if (col == null) return true;
        Object have = r.cols.get(col);
        if (args == null || args.length == 0) return have != null;
        String want = args[0];
        if (have == null) return false;
        if (have instanceof Long) {
            try { return ((Long) have) == Long.parseLong(want); } catch (NumberFormatException ignored) { }
        }
        return String.valueOf(have).equals(want);
    }

    private static final Pattern SELECT = Pattern.compile(
            "(?is)^select\\s+(.+?)\\s+from\\s+(\\w+)"
                    + "(?:\\s+where\\s+(\\w+)\\s*=\\s*\\?)?"
                    + "(?:\\s+order\\s+by\\s+(\\w+)(?:\\s+(asc|desc))?)?"
                    + "(?:\\s+limit\\s+(\\?|\\d+))?\\s*$");

    public Cursor rawQuery(String sql, String[] args) {
        Matcher m = SELECT.matcher(sql.trim());
        if (!m.matches()) throw new IllegalStateException("Stub-Parser kennt diese Abfrage nicht: " + sql);
        List<String> cols = new ArrayList<String>();
        for (String c : m.group(1).split(",")) cols.add(c.trim());
        String table = m.group(2);
        final String whereCol = m.group(3);
        final String orderCol = m.group(4);
        boolean desc = "desc".equalsIgnoreCase(m.group(5));
        String limitTok = m.group(6);
        List<Row> rows = tables.get(table);
        if (rows == null) rows = new ArrayList<Row>();
        List<Row> out = new ArrayList<Row>();
        for (Row r : rows) if (matches(r, whereCol, args)) out.add(r);
        if (orderCol != null) {
            Collections.sort(out, new Comparator<Row>() {
                public int compare(Row a, Row b) {
                    long x = num(a.cols.get(orderCol)), y = num(b.cols.get(orderCol));
                    return desc ? Long.compare(y, x) : Long.compare(x, y);
                }
            });
        }
        if (limitTok != null) {
            int lim = "?".equals(limitTok) ? Integer.parseInt(args[args.length - 1]) : Integer.parseInt(limitTok);
            if (out.size() > lim) out = new ArrayList<Row>(out.subList(0, lim));
        }
        return new RowCursor(cols, out);
    }

    private static long num(Object o) {
        if (o instanceof Long) return (Long) o;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    static class RowCursor implements Cursor {
        final List<String> cols;
        final List<Row> rows;
        int i = -1;
        RowCursor(List<String> c, List<Row> r) { cols = c; rows = r; }
        public boolean moveToFirst() { i = 0; return !rows.isEmpty(); }
        public boolean moveToNext() { i++; return i < rows.size(); }
        Object val(int index) { return rows.get(i).cols.get(cols.get(index)); }
        public String getString(int index) { Object v = val(index); return v == null ? null : String.valueOf(v); }
        public long getLong(int index) { Object v = val(index); return v instanceof Long ? (Long) v : num(v); }
        public int getInt(int index) { return (int) getLong(index); }
        public byte[] getBlob(int index) { return (byte[]) val(index); }
        public int getCount() { return rows.size(); }
        public void close() { }
    }
}
