package de.owui.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Anmeldung: Open-WebUI-Login (E-Mail/Passwort) oder API-Key gegen eine OpenAI-kompatible API. */
public class LoginActivity extends Activity {

    private Config cfg;
    private EditText url, email, pass, apiKey;
    private RadioButton modeOwui, modeKey;
    private CheckBox insecure;
    private LinearLayout owuiFields, keyFields;
    private TextView status;
    private Button connect;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Txt.init(this);
        cfg = new Config(this);
        setContentView(R.layout.activity_login);

        url = (EditText) findViewById(R.id.url);
        email = (EditText) findViewById(R.id.email);
        pass = (EditText) findViewById(R.id.pass);
        apiKey = (EditText) findViewById(R.id.apiKey);
        modeOwui = (RadioButton) findViewById(R.id.modeOwui);
        modeKey = (RadioButton) findViewById(R.id.modeKey);
        insecure = (CheckBox) findViewById(R.id.insecure);
        owuiFields = (LinearLayout) findViewById(R.id.owuiFields);
        keyFields = (LinearLayout) findViewById(R.id.keyFields);
        status = (TextView) findViewById(R.id.status);
        connect = (Button) findViewById(R.id.btnConnect);

        url.setText(cfg.baseUrl);
        email.setText(cfg.email);
        apiKey.setText(cfg.apiKey == null ? "" : cfg.apiKey);
        insecure.setChecked(cfg.allowAnyCert);
        if (Config.MODE_DIRECT.equals(cfg.mode)) modeKey.setChecked(true);
        syncFields();

        findViewById(R.id.presetOwui).setOnClickListener(v -> applyPort("https", Discover.PORT_TLS, false));
        findViewById(R.id.presetOwuiHttp).setOnClickListener(v -> applyPort("http", Discover.PORT_OWUI, false));
        findViewById(R.id.presetDirect).setOnClickListener(v -> applyPort("http", Discover.PORT_DIRECT, true));
        findViewById(R.id.modeGroup).setOnClickListener(v -> syncFields());
        modeOwui.setOnClickListener(v -> syncFields());
        modeKey.setOnClickListener(v -> syncFields());
        connect.setOnClickListener(v -> connect());

        boolean forced = getIntent() != null && getIntent().getBooleanExtra("force", false);
        if (!forced && cfg.hasAccess() && !cfg.baseUrl.isEmpty()) {
            openChat();
        }
    }

    private void syncFields() {
        boolean owui = modeOwui.isChecked();
        owuiFields.setVisibility(owui ? View.VISIBLE : View.GONE);
        keyFields.setVisibility(owui ? View.GONE : View.VISIBLE);
    }

    /** Preset-Zeile: nimmt den Host aus dem Feld und setzt darauf nur Protokoll und Port. */
    private void applyPort(String scheme, int port, boolean direct) {
        String host = Discover.hostOf(url.getText().toString());
        if (host.isEmpty()) {
            fail(getString(R.string.login_need_host));
            return;
        }
        url.setText(Discover.address(host, scheme, port));
        if (direct) modeKey.setChecked(true);
        else modeOwui.setChecked(true);
        syncFields();
    }

    /** Dem Nutzer zeigen, was die Suche gefunden hat, und die Felder darauf stellen. */
    private void announceFound(final String target, final boolean owui) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                url.setText(target);
                if (owui) modeOwui.setChecked(true);
                else modeKey.setChecked(true);
                syncFields();
                if (owui && target.startsWith("http://")) {
                    /* Ohne TLS geht das Mikrofon nicht - das soll nicht still passieren. */
                    status.setText(getString(R.string.login_found_webui, target)
                            + "  \u2013  " + getString(R.string.login_found_http_hint));
                } else {
                    status.setText(getString(owui
                            ? R.string.login_found_webui : R.string.login_found_direct, target));
                }
                status.setTextColor(Util.color(0xFF3FA66A));
            }
        });
    }

    private void connect() {
        if (busy) return;
        final String typed = url.getText().toString().trim();
        final String host = Discover.hostOf(typed);
        if (host.isEmpty()) {
            fail(getString(R.string.login_need_url));
            return;
        }
        /* Ohne Port in der Eingabe sucht die App Protokoll, Port und Modus selbst. */
        final boolean searchPorts = Discover.needsSearch(typed);
        final boolean owuiWanted = modeOwui.isChecked();
        final String em = email.getText().toString().trim();
        final String pw = pass.getText().toString();
        final String key = apiKey.getText().toString().trim();

        busy = true;
        connect.setEnabled(false);
        status.setText(getString(searchPorts ? R.string.login_searching : R.string.connecting));
        status.setTextColor(Util.color(0xFF9A9A92));

        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    cfg.allowAnyCert = insecure.isChecked();
                    cfg.token = "";
                    cfg.apiKey = "";

                    final Api api = new Api(LoginActivity.this, cfg);

                    String target;
                    boolean owui;
                    if (searchPorts) {
                        Discover.Report report = Discover.search(host, new Discover.Fetch() {
                            @Override
                            public Discover.Answer get(String absolute) throws IOException {
                                return api.probeAbsolute(absolute);
                            }
                        });
                        if (report.found == null) {
                            throw new IOException(report.tlsBlocked
                                    ? getString(R.string.login_detect_tls, host)
                                    : getString(R.string.login_detect_failed, host));
                        }
                        target = report.found.baseUrl;
                        owui = report.found.isWebUi();
                        announceFound(target, owui);
                    } else {
                        int port = Discover.portOf(typed);
                        String scheme = typed.indexOf("://") >= 0
                                ? typed.substring(0, typed.indexOf("://"))
                                : (owuiWanted ? "https" : "http");
                        target = Discover.address(host, scheme, port);
                        owui = owuiWanted;
                    }

                    cfg.baseUrl = target;
                    cfg.mode = owui ? Config.MODE_OWUI : Config.MODE_DIRECT;

                    if (owui) {
                        if (em.isEmpty() || pw.isEmpty()) {
                            throw new IOException(getString(R.string.login_need_email));
                        }
                        Map<String, Object> acc = api.signIn(em, pw);
                        Object t = acc.get("token");
                        if (t == null) throw new Api.ApiException(200, getString(R.string.err_login_no_token));
                        cfg.token = String.valueOf(t);
                        cfg.email = em;
                        Object n = acc.get("name");
                        if (n == null) n = acc.get("email");
                        cfg.userName = n == null ? em : String.valueOf(n);
                    } else if (!key.isEmpty()) {
                        cfg.apiKey = key;
                        cfg.userName = "API-Key";
                    }

                    final List<ModelInfo> models = api.listModels();
                    if (models.isEmpty()) {
                        throw new Api.ApiException(200,
                                getString(R.string.login_no_models_message));
                    }
                    boolean keep = false;
                    for (ModelInfo m : models) {
                        if (m.id != null && m.id.equals(cfg.model)) keep = true;
                    }
                    if (!keep) cfg.model = models.get(0).id;
                    cfg.save();

                    final int count = models.size();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LoginActivity.this, getString(R.string.login_models_count, count), Toast.LENGTH_SHORT).show();
                            openChat();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            connect.setEnabled(true);
                            fail(Api.explain(e));
                        }
                    });
                }
            }
        });
    }

    private void fail(String message) {
        status.setText(message);
        status.setTextColor(Util.color(0xFFE5484D));
    }

    private void openChat() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
