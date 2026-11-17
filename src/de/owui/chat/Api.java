package de.owui.chat;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Dünner Client für OpenAI-kompatible Chat-APIs:
 *  - Open WebUI  (Pfade unter /api,  Login über /api/v1/auths/signin)
 *  - direkt      (Pfade unter /v1,  API-Key oder keyless, z. B. Strata/LM Studio)
 *
 * TLS: System-CA plus im App-Asset liegt LAN-CA (assets/ca.crt) -> HTTPS im LAN ohne
 * Zertifikatswarnung. Optional "ungeprüft" für VPN-Adressen ohne passenden SAN.
 */
public class Api {

    public static class ApiException extends IOException {
        public final int status;
        public final String body;

        public ApiException(int status, String body) {
            super(hint(status, body));
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    public interface Delta {
        void onDelta(String chunk, String full);
    }

    private final Config cfg;
    private final Context ctx;
    private volatile boolean abort;
    private SSLContext sslCache;
    private boolean sslCacheInsecure;

    public Api(Context ctx, Config cfg) {
        this.ctx = ctx;
        this.cfg = cfg;
    }

    public void abort() {
        abort = true;
    }

    /* ---------------- URLs ---------------- */

    public String normalizedBase() {
        String u = cfg.baseUrl == null ? "" : cfg.baseUrl.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String apiRoot() {
        return Config.MODE_DIRECT.equals(cfg.mode) ? "/v1" : "/api";
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        String base = normalizedBase();
        if (base.isEmpty()) throw new IOException("Keine Server-Adresse eingetragen");
        return openUrl(new URL(base + path), method);
    }

    private HttpURLConnection openUrl(URL url, String method) throws IOException {
        HttpURLConnection c;
        if ("https".equalsIgnoreCase(url.getProtocol())) {
            HttpsURLConnection h = (HttpsURLConnection) url.openConnection();
            h.setSSLSocketFactory(sslContext().getSocketFactory());
            h.setHostnameVerifier(verifier);
            c = h;
        } else {
            c = (HttpURLConnection) url.openConnection();
        }
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(300000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "ChatLAN/1.0 Android");
        if (cfg.token != null && !cfg.token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.token);
        else if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        return c;
    }

    /* ---------------- Aufrufe ---------------- */

    /** Reicht um Erreichbarkeit + TLS zu prüfen; 401 bedeutet: Server ja, Zugang nein. */
    public int probe() throws IOException {
        HttpURLConnection c = open(apiRoot() + "/models", "GET");
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    public Map<String, Object> signIn(String email, String password) throws IOException {
        HttpURLConnection c = open("/api/v1/auths/signin", "POST");
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            byte[] payload = Json.write(Json.obj("email", email, "password", password)).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new ApiException(code, body);
            Map<String, Object> m = Json.asMap(Json.parse(body));
            if (m == null || Json.str(m, "token") == null) throw new IOException("Login ohne Token beantwortet");
            return m;
        } finally {
            c.disconnect();
        }
    }

    public List<ModelInfo> listModels() throws IOException {
        HttpURLConnection c = open(apiRoot() + "/models", "GET");
        try {
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new ApiException(code, body);
            Object root = Json.parse(body);
            List<Object> data = Json.asList(Json.path(root, "data"));
            if (data == null) data = Json.asList(root);
            List<ModelInfo> out = new ArrayList<ModelInfo>();
            if (data != null) {
                for (Object o : data) {
                    String id = Json.str(o, "id");
                    if (id == null) continue;
                    String label = Json.str(o, "name");
                    out.add(new ModelInfo(id, label, seesImages(o), generatesImages(o)));
                }
            }
            if (out.isEmpty()) throw new IOException("Server meldet keine Modelle");
            return out;
        } finally {
            c.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean seesImages(Object model) {
        Object mods = Json.path(model, "architecture", "input_modalities");
        List<Object> l = Json.asList(mods);
        if (l != null) {
            for (Object o : l) if ("image".equals(String.valueOf(o))) return true;
        }
        Object cap = Json.path(model, "meta", "capabilities", "vision");
        if (cap instanceof Boolean) return ((Boolean) cap).booleanValue();
        Object caps = Json.path(model, "capabilities", "vision");
        if (caps instanceof Boolean) return ((Boolean) caps).booleanValue();
        return false;
    }

    /** Ein Bild-Profil (ComfyUI-Pipeline) erzeugt Bilder, redet aber sonst wie das Basis-Modell. */
    private static boolean generatesImages(Object model) {
        Object v = Json.path(model, "info", "meta", "capabilities", "image_generation");
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        v = Json.path(model, "meta", "capabilities", "image_generation");
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        v = Json.path(model, "capabilities", "image_generation");
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        List<Object> feats = Json.asList(Json.path(model, "info", "meta", "defaultFeatureIds"));
        if (feats == null) feats = Json.asList(Json.path(model, "meta", "defaultFeatureIds"));
        if (feats != null) {
            for (Object o : feats) {
                if ("image_generation".equals(String.valueOf(o))) return true;
            }
        }
        return false;
    }

    /* ---------------- Bilder erzeugen und Diktat */

    public static class GenImage {
        public final String id;
        public final String url;
        public final String name;
        public final String contentType;

        GenImage(String id, String url, String name, String contentType) {
            this.id = id;
            this.url = url;
            this.name = name == null ? "generated-image" : name;
            this.contentType = contentType == null ? "image/png" : contentType;
        }
    }

    /**
     * Bild-Pipeline anstossen: Prompt -> fertige Bilder (ComfyUI haengt an Open WebUI,
     * deshalb nur im Open-WebUI-Modus; das dauert je nach Groesse einige Sekunden).
     * Die Groesse wird pro Anfrage mitgeschickt (serverseitiger Standard bleibt trotzdem
     * der Wert aus den Open-WebUI-Einstellungen).
     */
    public List<GenImage> generateImage(String model, String prompt, String size) throws IOException {
        if (Config.MODE_DIRECT.equals(cfg.mode)) {
            throw new IOException("Bildgenerierung gibt es nur im Open-WebUI-Modus");
        }
        Map<String, Object> req = Json.obj("model", model, "prompt", prompt);
        if (size != null && size.toLowerCase().contains("x")) req.put("size", size.toLowerCase());
        byte[] payload = Json.write(req).getBytes("UTF-8");
        HttpURLConnection c = open("/api/v1/images/generations", "POST");
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new ApiException(code, body);
            Object root = Json.parse(body);
            List<Object> arr = Json.asList(Json.path(root, "images"));
            if (arr == null) arr = Json.asList(root);
            List<GenImage> out = new ArrayList<GenImage>();
            if (arr != null) {
                for (Object o : arr) {
                    Map<String, Object> m = Json.asMap(o);
                    if (m != null) {
                        out.add(new GenImage(Json.str(m, "id"), Json.str(m, "url"),
                                Json.str(m, "name"), Json.str(m, "content_type")));
                    } else if (o instanceof String) {
                        out.add(new GenImage(null, (String) o, "generated-image", "image/png"));
                    }
                }
            }
            if (out.isEmpty()) throw new IOException("Server hat kein Bild zurueckgegeben");
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** Server-Datei holen (relativer Pfad wie /api/v1/files/<id>/content oder volle URL). */
    public byte[] download(String path) throws IOException {
        HttpURLConnection c;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            c = openUrl(new URL(path), "GET");
        } else {
            c = open(path.startsWith("/") ? path : "/" + path, "GET");
        }
        try {
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new ApiException(code, readBody(c.getErrorStream()));
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                if (out.size() == 0) throw new IOException("Der Server hat eine leere Datei geliefert");
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Diktat: Audio (WAV/MP3/WebM) -> Text. Open WebUI laeuft hier mit lokalem Whisper. */
    public String transcribe(byte[] audio, String filename, String mime) throws IOException {
        return transcribe(audio, filename, mime, java.util.Locale.getDefault().getLanguage());
    }

    /**
     * Wie oben, aber mit gesprochener Sprache. Die Sprache ist wichtig: ohne sie
     * uebersetzt Whisper aus dem Deutschen ins Englische.
     */
    public String transcribe(byte[] audio, String filename, String mime, String language) throws IOException {
        if (Config.MODE_DIRECT.equals(cfg.mode)) {
            throw new IOException("Diktat braucht den Open-WebUI-Modus (Whisper laeuft auf dem Server)");
        }
        String boundary = "----chatlan" + Long.toHexString(System.nanoTime());
        String lang = language == null || language.isEmpty() ? "auto" : language;
        ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n").getBytes("UTF-8"));
        form.write(audio);
        form.write(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"language\"\r\n\r\n"
                + lang + "\r\n").getBytes("UTF-8"));
        form.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        byte[] payload = form.toByteArray();
        HttpURLConnection c = open("/api/v1/audio/transcriptions", "POST");
        try {
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new ApiException(code, body);
            Map<String, Object> m = Json.asMap(Json.parse(body));
            String text = m == null ? null : Json.str(m, "text");
            return text == null ? "" : text.trim();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Sprachausgabe: Text -> Audio (MP3). Open WebUI leitet das an die LAN-Stimmedatenbank
     * weiter (openai-edge-tts). Die Stimme kommt aus den App-Einstellungen; ist sie leer,
     * nimmt der Server die dort eingestellte Stimme.
     */
    public byte[] speech(String text) throws IOException {
        if (Config.MODE_DIRECT.equals(cfg.mode)) {
            throw new IOException("Sprachausgabe gibt es nur im Open-WebUI-Modus");
        }
        if (text == null || text.trim().isEmpty()) throw new IOException("Es gibt nichts vorzulesen");
        Map<String, Object> body = Json.obj("input", text);
        String voice = Config.normalizeVoice(cfg.ttsVoice);
        if (!voice.isEmpty()) body.put("voice", voice);
        byte[] payload = Json.write(body).getBytes("UTF-8");
        HttpURLConnection c = open("/api/v1/audio/speech", "POST");
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "audio/mpeg, audio/*");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new ApiException(code, readBody(c.getErrorStream()));
            return Util.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /**
     * Websuche ueber Open WebUI (dort ist die lokale Suchmaschine eingestellt).
     * Gibt die Treffer zurueck, leer wenn die Suchmaschine nichts findet.
     */
    public List<Search.Hit> webSearch(String question) throws IOException {
        return webSearch(question, null);
    }

    /**
     * previousQuestion: eine kurze Folgefrage ("und mit 512 GB?") bekommt ohne die
     * Frage davor keinen brauchbaren Suchbegriff.
     */
    public List<Search.Hit> webSearch(String question, String previousQuestion) throws IOException {
        if (Config.MODE_DIRECT.equals(cfg.mode)) {
            throw new IOException("Websuche laeuft ueber Open WebUI – im Direktmodus gibt es sie nicht");
        }
        String q = Search.queryWithContext(question, previousQuestion);
        if (q.isEmpty()) throw new IOException("Kein Suchbegriff uebrig geblieben");
        List<Object> queries = new ArrayList<Object>();
        queries.add(q);
        byte[] payload = Json.write(Json.obj("queries", queries)).getBytes("UTF-8");
        HttpURLConnection c = open("/api/v1/retrieval/process/web/search", "POST");
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);
            int code = c.getResponseCode();
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code < 200 || code >= 300) throw new ApiException(code, body);
            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (Exception malformed) {
                throw new IOException("Suchantwort des Servers war unlesbar");
            }
            return Search.parse(parsed);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Markdown-Bilder in einer Antwort (data:... oder /api/v1/files/...) in Bytes umwandeln,
     * damit echte Bilder in der Blase landen statt Markdown-Text.
     */
    public List<byte[]> inlineImages(String text, List<String> mimes) throws IOException {
        List<byte[]> out = new ArrayList<byte[]>();
        if (text == null || text.indexOf("![") < 0) return out;
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("![", i);
            if (open < 0) break;
            int mid = text.indexOf("](", open);
            if (mid < 0) break;
            int close = text.indexOf(')', mid + 2);
            if (close < 0) break;
            String ref = text.substring(mid + 2, close).trim();
            byte[] data = null;
            if (ref.startsWith("data:image")) {
                data = decodeDataUri(ref);
                if (data != null && mimes != null) mimes.add(mimeOfDataUri(ref));
            } else if (ref.startsWith("/api") || ref.startsWith("http")) {
                try {
                    data = download(ref);
                } catch (IOException ignored) {
                }
                if (data != null && mimes != null) mimes.add("image/png");
            }
            if (data != null && data.length > 0) out.add(data);
            i = close + 1;
        }
        return out;
    }

    /** Markdown-Bild-Verweise aus dem Text entfernen (die Bilder stehen dann in der Blase). */
    public static String stripImageRefs(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("![", i);
            if (open < 0) {
                sb.append(text, i, text.length());
                break;
            }
            int mid = text.indexOf("](", open);
            if (mid < 0) {
                sb.append(text, i, text.length());
                break;
            }
            int close = text.indexOf(')', mid + 2);
            if (close < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, open);
            i = close + 1;
        }
        return sb.toString().replaceAll("[ \t]+\n", "\n").trim();
    }

    static byte[] decodeDataUri(String ref) {
        int at = ref.indexOf("base64,");
        if (at < 0) return null;
        try {
            return Base64.decode(ref.substring(at + 7).trim(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String mimeOfDataUri(String ref) {
        int semi = ref.indexOf(';');
        String head = ref.substring(0, semi < 0 ? ref.length() : semi);
        return head.startsWith("data:") ? head.substring(5) : "image/png";
    }

    public String chat(String model, List<Msg> history, Delta cb) throws IOException {
        return chat(model, history, null, cb);
    }

    /** Wie chat(...), aber mit zusaetzlichem System-Hinweis (z. B. fuer gesprochene Antworten). */
    public String chat(String model, List<Msg> history, String extraSystem, Delta cb) throws IOException {
        abort = false;
        byte[] payload = Json.write(buildBody(model, history, cfg.streaming, extraSystem)).getBytes("UTF-8");
        HttpURLConnection c = open(apiRoot() + "/chat/completions", "POST");
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.getOutputStream().write(payload);

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new ApiException(code, readBody(c.getErrorStream()));
            }
            String ctype = c.getContentType() == null ? "" : c.getContentType();
            if (cfg.streaming && (ctype.contains("event-stream") || ctype.isEmpty())) {
                String sse = readSse(c, cb);
                if (sse != null) return sse;
            }
            return readWhole(c, cb);
        } finally {
            c.disconnect();
        }
    }

    private Map<String, Object> buildBody(String model, List<Msg> history, boolean stream, String extraSystem) {
        List<Object> msgs = new ArrayList<Object>();
        if (cfg.systemPrompt != null && !cfg.systemPrompt.trim().isEmpty()) {
            msgs.add(Json.obj("role", "system", "content", cfg.systemPrompt.trim()));
        }
        if (extraSystem != null && !extraSystem.trim().isEmpty()) {
            msgs.add(Json.obj("role", "system", "content", extraSystem.trim()));
        }
        for (Msg m : history) {
            if (m.role == null) continue;
            boolean sendImage = "user".equals(m.role) && !m.images.isEmpty();
            if (!sendImage) {
                String text = m.text;
                if (text == null || text.trim().isEmpty()) {
                    // erzeugte Bilder der App bleiben local: das Sprachmodell kann sie nicht sehen
                    text = m.hasImage() ? "[Bild wurde erzeugt]" : "";
                }
                if (text.isEmpty()) continue;
                msgs.add(Json.obj("role", m.role, "content", text));
            } else {
                List<Object> parts = new ArrayList<Object>();
                if (m.text != null && !m.text.isEmpty()) parts.add(Json.obj("type", "text", "text", m.text));
                for (byte[] img : m.images) {
                    String data = "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP);
                    parts.add(Json.obj("type", "image_url", "image_url", Json.obj("url", data)));
                }
                msgs.add(Json.obj("role", m.role, "content", parts));
            }
        }
        Map<String, Object> body = Json.obj(
                "model", model,
                "messages", msgs,
                "stream", Boolean.valueOf(stream));
        if (cfg.temperature >= 0d) body.put("temperature", Double.valueOf(cfg.temperature));
        return body;
    }

    /** Liest SSE; gibt den Text zurück oder null, wenn kein einziges data:-Ereignis kam. */
    private String readSse(HttpURLConnection c, Delta cb) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"), 8192);
        StringBuilder full = new StringBuilder();
        boolean sawData = false;
        String line;
        while ((line = r.readLine()) != null) {
            if (abort) break;
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("data:")) continue;
            String p = line.substring(5).trim();
            if (p.isEmpty()) continue;
            if ("[DONE]".equals(p)) break;
            Object ev;
            try {
                ev = Json.parse(p);
            } catch (Exception malformed) {
                continue;
            }
            sawData = true;
            List<Object> choices = Json.asList(Json.path(ev, "choices"));
            if (choices == null || choices.isEmpty()) continue;
            Object first = choices.get(0);
            String chunk = Json.str(first, "delta", "content");
            if (chunk == null) chunk = Json.str(first, "message", "content");
            if (chunk == null) chunk = Json.str(first, "text");
            if (chunk != null && !chunk.isEmpty()) {
                full.append(chunk);
                if (cb != null) cb.onDelta(chunk, full.toString());
            }
            String fin = Json.str(first, "finish_reason");
            if (fin != null && !fin.isEmpty()) break;
        }
        r.close();
        if (!sawData && full.length() == 0) return null;
        return full.toString();
    }

    private String readWhole(HttpURLConnection c, Delta cb) throws IOException {
        String body = readBody(c.getInputStream());
        Map<String, Object> root = Json.asMap(Json.parse(body));
        if (root == null) throw new IOException("Antwort war kein JSON: " + clip(body, 200));
        if (root.containsKey("error") || root.containsKey("detail")) throw new ApiException(200, body);
        String text = Json.str(root, "choices", "0", "message", "content");
        if (text == null) {
            List<Object> choices = Json.asList(Json.path(root, "choices"));
            if (choices != null && !choices.isEmpty()) {
                Object first = choices.get(0);
                text = Json.str(first, "message", "content");
                if (text == null) text = Json.str(first, "text");
            }
        }
        if (text == null) throw new IOException("Antwort ohne Antworttext: " + clip(body, 200));
        if (cb != null) cb.onDelta(text, text);
        return text;
    }

    /* ---------------- TLS ---------------- */

    private SSLContext sslContext() throws IOException {
        boolean insecure = cfg.allowAnyCert;
        if (sslCache != null && sslCacheInsecure == insecure) return sslCache;
        try {
            if (insecure) {
                SSLContext any = SSLContext.getInstance("TLS");
                any.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, null);
                sslCache = any;
                sslCacheInsecure = true;
                return any;
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            for (TrustManager tm : sys.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    for (X509Certificate crt : ((X509TrustManager) tm).getAcceptedIssuers()) {
                        ks.setCertificateEntry("sys" + (n++), crt);
                    }
                }
            }
            for (X509Certificate crt : bundledCa()) {
                ks.setCertificateEntry("lan" + (n++), crt);
            }
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init(ks);
            SSLContext s = SSLContext.getInstance("TLS");
            s.init(null, f.getTrustManagers(), null);
            sslCache = s;
            sslCacheInsecure = false;
            return s;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS-Einrichtung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private List<X509Certificate> bundledCa() {
        List<X509Certificate> out = new ArrayList<X509Certificate>();
        InputStream in = null;
        try {
            in = ctx.getAssets().open("ca.crt");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (Certificate cert : cf.generateCertificates(in)) {
                if (cert instanceof X509Certificate) out.add((X509Certificate) cert);
            }
        } catch (Exception nothingInAssets) {
            // kein ca.crt dabei -> nur System-CAs
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private final HostnameVerifier verifier = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            if (cfg.allowAnyCert) return true;
            try {
                Certificate[] chain = session.getPeerCertificates();
                if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) return false;
                return matchesHost((X509Certificate) chain[0], hostname);
            } catch (Exception e) {
                return false;
            }
        }
    };

    private static boolean matchesHost(X509Certificate cert, String host) {
        String h = host.toLowerCase();
        Collection<? extends List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (Exception e) {
            return false;
        }
        if (sans == null) return false;
        for (List<?> san : sans) {
            if (san == null || san.size() < 2) continue;
            Object type = san.get(0);
            Object value = san.get(1);
            if (!(type instanceof Number) || value == null) continue;
            int kind = ((Number) type).intValue();
            String v = String.valueOf(value).toLowerCase();
            if (kind == 2) {
                if (v.equals(h)) return true;
                if (v.startsWith("*.") && h.endsWith(v.substring(1))) return true;
            } else if (kind == 1 && v.equals(h)) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- Hilfen ---------------- */

    /** Menschlich lesbare Fehlerbeschreibung für Verbindungsprobleme. */
    public static String explain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof javax.net.ssl.SSLException) {
                return "Zertifikat wird nicht akzeptiert. Die App bündelt die LAN-CA nur, wenn sie beim Bau "
                        + "als assets/ca.crt dabei lag. Für den Test in der Anmeldung "
                        + "„TLS-Zertifikat ungeprüft akzeptieren“ ankreuzen.";
            }
            if (t instanceof java.net.UnknownHostException) {
                return "Host nicht gefunden. Prüfe die Adresse und ob das Gerät im selben LAN bzw. VPN hängt.";
            }
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.SocketException) {
                return "Keine Verbindung zum Server. Läuft Open WebUI? Ist der Port in der Firewall offen "
                        + "(3443 / 3000 / 8080)?";
            }
            if (t instanceof ApiException && t.getMessage() != null) return t.getMessage();
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            b.append(line).append('\n');
        }
        r.close();
        return b.toString().trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String detailOf(String body) {
        String t = body == null ? "" : body.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                Object root = Json.parse(t);
                String d = Json.str(root, "detail");
                if (d == null) d = Json.str(root, "error", "message");
                if (d == null) d = Json.str(root, "error");
                if (d != null) return clip(String.valueOf(d), 240);
            } catch (Exception ignored) {
            }
        }
        return clip(t, 240);
    }

    private static String hint(int status, String body) {
        String detail = detailOf(body);
        if ((status == 400 || status == 401 || status == 422)
                && body != null && body.toLowerCase().contains("email or password")) {
            // Open WebUI meldet falsche Zugangsdaten als 400 mit englischem Text
            return "Login fehlgeschlagen: E-Mail oder Passwort ist falsch.";
        }
        switch (status) {
            case 401:
                return "Zugang abgelehnt (401). Login abgelaufen oder API-Key ungültig." + (detail.isEmpty() ? "" : " — " + detail);
            case 403:
                return "Zugriff verweigert (403). " + (detail.isEmpty() ? "Modell oder Funktion ist für diese Kontenrolle gesperrt." : detail);
            case 404:
                return "Endpunkt nicht gefunden (404). " + (detail.isEmpty() ? "Ist die Adresse einer Open-WebUI-Instanz oder einer OpenAI-API?" : detail);
            case 400:
            case 422:
                return "Anfrage abgelehnt (" + status + "). " + (detail.isEmpty() ? "Modell unbekannt?" : detail);
            case 502:
            case 503:
            case 504:
                return "Backend nicht erreichbar (" + status + "). " + (detail.isEmpty() ? "Läuft der Modell-Server?" : detail);
            default:
                return "HTTP " + status + (detail.isEmpty() ? "" : ": " + detail);
        }
    }
}
