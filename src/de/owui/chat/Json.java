package de.owui.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler JSON-Leser/-Schreiber (Android und JVM).
 * Werte: Map<String,Object>, List<Object>, String, Long/Double, Boolean, null.
 * Bewusst eigenständig: derselbe Code läuft im APK und im JVM-Test.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String s) {
        P p = new P(s);
        Object v = p.readValue();
        p.skipWs();
        if (!p.done()) throw new IllegalArgumentException("JSON: Zeichen nach Wert bei " + p.i);
        return v;
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        writeValue(v, b);
        return b.toString();
    }

    /* ---------- bequemer Zugriff ---------- */

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static Object path(Object o, String... keys) {
        Object cur = o;
        for (String k : keys) {
            Map<String, Object> m = asMap(cur);
            if (m == null) return null;
            cur = m.get(k);
            if (cur == null) return null;
        }
        return cur;
    }

    public static String str(Object o, String... keys) {
        Object v = path(o, keys);
        return v == null ? null : String.valueOf(v);
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /* ---------- Schreiben ---------- */

    @SuppressWarnings("unchecked")
    private static void writeValue(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) b.append(',');
                first = false;
                writeString(e.getKey(), b);
                b.append(':');
                writeValue(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) b.append(',');
                first = false;
                writeValue(o, b);
            }
            b.append(']');
        } else if (v instanceof String) {
            writeString((String) v, b);
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else b.append(Double.toString(d));
        } else if (v instanceof Number) {
            b.append(v.toString());
        } else {
            writeString(v.toString(), b);
        }
    }

    private static void writeString(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c == '\b') b.append("\\b");
            else if (c == '\f') b.append("\\f");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    /* ---------- Lesen ---------- */

    private static final class P {
        final String src;
        int i;

        P(String s) {
            src = s == null ? "" : s;
        }

        boolean done() {
            return i >= src.length();
        }

        IllegalArgumentException err(String what) {
            return new IllegalArgumentException("JSON: " + what + " bei Position " + i);
        }

        void skipWs() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
        }

        Object readValue() {
            skipWs();
            if (done()) throw err("unerwartendes Ende");
            char c = src.charAt(i);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return readNumber();
        }

        void expect(String lit) {
            if (!src.startsWith(lit, i)) throw err("'" + lit + "' erwartet");
            i += lit.length();
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (!done() && src.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (done() || src.charAt(i) != '"') throw err("Schlüssel erwartet");
                String k = readString();
                skipWs();
                if (done() || src.charAt(i) != ':') throw err("':' erwartet");
                i++;
                m.put(k, readValue());
                skipWs();
                if (done()) throw err("Objekt nicht geschlossen");
                char c = src.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw err("',' oder '}' erwartet");
            }
        }

        List<Object> readArray() {
            List<Object> l = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (!done() && src.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(readValue());
                skipWs();
                if (done()) throw err("Array nicht geschlossen");
                char c = src.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw err("',' oder ']' erwartet");
            }
        }

        String readString() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (done()) throw err("String nicht geschlossen");
                char c = src.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (done()) throw err("Escape endet abgebrochen");
                char e = src.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': {
                        if (i + 4 > src.length()) throw err("zu kurzer \\u-Escape");
                        int cp = Integer.parseInt(src.substring(i, i + 4), 16);
                        i += 4;
                        if (cp >= 0xD800 && cp <= 0xDBFF && i + 6 <= src.length()
                                && src.charAt(i) == '\\' && src.charAt(i + 1) == 'u') {
                            int lo = Integer.parseInt(src.substring(i + 2, i + 6), 16);
                            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                i += 6;
                                b.appendCodePoint(Character.toCodePoint((char) cp, (char) lo));
                                break;
                            }
                        }
                        b.append((char) cp);
                        break;
                    }
                    default: throw err("unbekannter Escape '\\" + e + "'");
                }
            }
        }

        Object readNumber() {
            int start = i;
            if (!done() && (src.charAt(i) == '-' || src.charAt(i) == '+')) i++;
            boolean frac = false;
            while (!done()) {
                char c = src.charAt(i);
                if (c >= '0' && c <= '9') { i++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    if (c == '.') frac = true;
                    i++;
                    continue;
                }
                break;
            }
            String t = src.substring(start, i);
            if (t.isEmpty() || "-".equals(t) || "+".equals(t)) throw err("Zahl erwartet");
            try {
                if (frac) return Double.valueOf(t);
                return Long.valueOf(t);
            } catch (NumberFormatException e) {
                return Double.valueOf(t);
            }
        }
    }
}
