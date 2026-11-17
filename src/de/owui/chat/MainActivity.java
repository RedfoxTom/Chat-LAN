package de.owui.chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Chat-Ansicht im Stil von ChatGPT: dunkel, Modelle oben, Eingaben unten. */
public class MainActivity extends Activity {

    private static final int REQ_IMAGE = 17;
    private static final int REQ_MIC = 18;
    private static final int REQ_SPEECH = 19;
    private static final int BUBBLE_TEXT = 0xFFECECE4;

    private Config cfg;
    private Db db;
    private Api api;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private ScrollView chatScroll;
    private LinearLayout chatList, chatListDrawer, attachBar, drawerPanel, dictBar;
    private TextView emptyHint, attachName, serverInfo, dictStatus;
    private EditText input;
    private Spinner modelSpinner;
    private ImageButton btnSend, btnAttach, btnAttachRemove, btnMic, btnChat, btnDictCancel;
    private ImageView attachPreview;
    private View drawer, drawerScrim;

    private List<ModelInfo> models = new ArrayList<ModelInfo>();
    private String chatId;
    private final List<Msg> history = new ArrayList<Msg>();

    private byte[] pendingImage;
    private Bitmap pendingBitmap;
    private String pendingName = "";

    private Recorder recorder;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable dictTicker;
    private boolean transcribing;

    /* ------------------------------------------------ Sprach-Unterhaltung (Voice Chat) */

    private Voice voice;
    /**
     * Zustand der Unterhaltung: Modus an/aus plus die eine Stufe, die gerade laeuft
     * (aufnahme -> verstehe -> denke -> spreche -> Pause -> aufnahme). Der Modus ist
     * damit nicht mehr an einen "laeuft noch"-Schalter gekoppelt, der eine spaetere
     * Aufnahme blockieren kann.
     */
    private final VoiceFlow flow = new VoiceFlow();
    private boolean chatAfterPermission;
    private Runnable voiceTicker;
    private long lastLoudAt;
    private long firstSpeechAt;
    /** Wann die aktuelle Aufnahme offen ging (Pegel danach eine Weile ignorieren). */
    private long turnOpenedAt;
    private TextView btnQualityBtn;
    private TextView btnSearchChip;

    /** Pegel unter dem Sprachaufnahme als Pause gilt (0..32767). */
    private static final int VOICE_LEVEL_MIN = 900;
    /** So lange nach dem letzten Lauten wird gewartet, dann ist die Aussage fertig. */
    private static final int VOICE_SILENCE_MS = 1500;
    /** Ohne jegliches Geraeusch nach so vielen Sekunden abbrechen. */
    private static final int VOICE_MAX_WAIT_MS = 15000;
    /** So lang nach dem Start einer Aufnahme gilt der Pegel noch als Nachhall der Ausgabe. */
    private static final int VOICE_WARMUP_MS = 400;
    /** Wie lang eine gesprochene Antwort hoechstens vorgelesen wird. */
    private static final int VOICE_MAX_CHARS = 1500;

    private static final String VOICE_SYSTEM_PROMPT =
            "You are in a spoken conversation (button press, speaking, the answer is read aloud). "
                    + "Answer as free speech in one or two short sentences, at most 60 words, "
                    + "no lists, no markdown, no links, no code, no preamble. "
                    + "Answer in the language the user is speaking.";

    private boolean generating;
    private boolean followScroll = true;
    private Msg streamingMsg;
    private TextView streamingView;
    private LinearLayout streamingRow;

    /* ---------------------------------------------------------------- lifecycle */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Txt.init(this);
        cfg = new Config(this);
        if (!cfg.hasAccess() || cfg.baseUrl.isEmpty()) {
            Intent i = new Intent(this, LoginActivity.class);
            i.putExtra("force", true);
            startActivity(i);
            finish();
            return;
        }
        db = new Db(this);
        api = new Api(this, cfg);
        voice = new Voice(this);

        setContentView(R.layout.activity_main);
        chatScroll = (ScrollView) findViewById(R.id.chatScroll);
        chatList = (LinearLayout) findViewById(R.id.chatList);
        chatListDrawer = (LinearLayout) findViewById(R.id.chatListDrawer);
        attachBar = (LinearLayout) findViewById(R.id.attachBar);
        emptyHint = (TextView) findViewById(R.id.emptyHint);
        attachName = (TextView) findViewById(R.id.attachName);
        serverInfo = (TextView) findViewById(R.id.serverInfo);
        input = (EditText) findViewById(R.id.input);
        modelSpinner = (Spinner) findViewById(R.id.modelSpinner);
        btnSend = (ImageButton) findViewById(R.id.btnSend);
        btnAttach = (ImageButton) findViewById(R.id.btnAttach);
        btnAttachRemove = (ImageButton) findViewById(R.id.btnAttachRemove);
        attachPreview = (ImageView) findViewById(R.id.attachPreview);
        btnMic = (ImageButton) findViewById(R.id.btnMic);
        btnChat = (ImageButton) findViewById(R.id.btnChat);
        btnDictCancel = (ImageButton) findViewById(R.id.btnDictCancel);
        btnQualityBtn = (TextView) findViewById(R.id.btnQuality);
        btnSearchChip = (TextView) findViewById(R.id.btnSearch);
        dictBar = (LinearLayout) findViewById(R.id.dictBar);
        dictStatus = (TextView) findViewById(R.id.dictStatus);
        drawer = findViewById(R.id.drawer);
        drawerPanel = (LinearLayout) findViewById(R.id.drawerPanel);
        drawerScrim = findViewById(R.id.drawerScrim);

        btnSend.setOnClickListener(v -> {
            if (generating) api.abort();
            else send();
        });
        btnAttach.setOnClickListener(v -> pickImage());
        btnAttachRemove.setOnClickListener(v -> clearImage());
        btnMic.setOnClickListener(v -> toggleMic());          // Diktat wie in 1.1
        btnChat.setOnClickListener(v -> onChatPress());       // Sprach-Unterhaltung
        btnDictCancel.setOnClickListener(v -> onCancelPress());
        btnQualityBtn.setOnClickListener(v -> qualityDialog());
        btnSearchChip.setOnClickListener(v -> toggleSearch());
        btnSearchChip.setOnLongClickListener(v -> {
            searchDialog();
            return true;
        });
        findViewById(R.id.btnMenu).setOnClickListener(v -> openDrawer());
        findViewById(R.id.btnNew).setOnClickListener(v -> newChat());
        findViewById(R.id.btnMore).setOnClickListener(v -> moreMenu());
        findViewById(R.id.btnDrawerNew).setOnClickListener(v -> {
            closeDrawer();
            newChat();
        });
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
        drawerScrim.setOnClickListener(v -> closeDrawer());

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < models.size()) {
                    ModelInfo picked = models.get(pos);
                    cfg.model = picked.id;
                    cfg.save();
                    onModelChosen(picked);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        chatScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) ->
                followScroll = !chatScroll.canScrollVertically(1));

        input.setOnEditorActionListener((v, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                send();
                return true;
            }
            return false;
        });

        openChat(restoreChatId());
        refreshModels();
        updateSendIcon();
        updateChatButton();
        updateQualityChip();
        updateInputHint();
        updateSearchChip();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        api.abort();
        if (dictTicker != null) ui.removeCallbacks(dictTicker);
        if (voiceTicker != null) ui.removeCallbacks(voiceTicker);
        if (recorder != null) recorder.cancel();
        if (voice != null) voice.release();
        worker.shutdownNow();
        db.close();
    }

    /* ---------------------------------------------------------------- Chat öffnen */

    private String restoreChatId() {
        String id = cfg.lastChatId;
        if (id == null || !db.exists(id)) {
            List<Db.ChatRef> all = db.listChats(1);
            id = all.isEmpty() ? null : all.get(0).id;
        }
        return id == null ? db.newChat(cfg.model) : id;
    }

    private void openChat(String id) {
        chatId = id;
        cfg.lastChatId = id;
        cfg.save();
        history.clear();
        history.addAll(db.messages(id));
        rebuildChatList();
        clearImage();
        String m = db.modelOf(id);
        int idx = modelIndex(m);
        if (idx >= 0) modelSpinner.setSelection(idx);
        updateChatButton();
        updateQualityChip();
        updateInputHint();
        updateSearchChip();
        scrollToEnd(true);
    }

    private void newChat() {
        String model = selectedModelId();
        openChat(db.newChat(model == null ? cfg.model : model));
        if (drawer.getVisibility() == View.VISIBLE) buildDrawer();
        input.requestFocus();
    }

    private void rebuildChatList() {
        chatList.removeAllViews();
        for (Msg m : history) chatList.addView(messageView(m));
        emptyHint.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /* ---------------------------------------------------------------- Nachrichten */

    private View messageView(Msg m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Util.dp(this, 4);
        rlp.bottomMargin = Util.dp(this, 4);
        row.setLayoutParams(rlp);

        if (m.hasImage()) {
            row.addView(imageView(m));
            if (m.text == null || m.text.trim().isEmpty()) {
                return row;   // reine Bild-Nachricht: keine leere Blase darunter
            }
        }

        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setTextColor(BUBBLE_TEXT);
        tv.setLineSpacing(0, 1.25f);

        if ("user".equals(m.role)) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.gravity = Gravity.END;
            tv.setLayoutParams(tlp);
            tv.setBackgroundResource(R.drawable.bg_user_bubble);
            int pad = Util.dp(this, 12);
            tv.setPadding(pad + Util.dp(this, 4), pad, pad + Util.dp(this, 4), pad);
            tv.setText(m.text);
        } else {
            tv.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            tv.setText(Markdown.render(m.text));
        }
        attachMessageMenu(tv, m);
        row.addView(tv);
        if (m.hasSources()) row.addView(sourcesView(m));
        return row;
    }

    /** Kleine Quellzeilen unter einer Antwort: Titel plus klickbare URL. */
    private TextView sourcesView(Msg m) {
        StringBuilder b = new StringBuilder();
        b.append(getString(R.string.web_found));
        for (String line : m.sources) {
            b.append('\n').append(Search.sourceTitle(line));
            String url = Search.sourceUrl(line);
            if (!url.isEmpty()) b.append('\n').append(url);
        }
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(Util.color(0xFF8B8B84));
        tv.setLineSpacing(0, 1.2f);
        tv.setPadding(0, Util.dp(this, 6), 0, 0);
        tv.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        tv.setLinkTextColor(Util.color(0xFF10A37F));
        tv.setText(b.toString());
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        return tv;
    }

    private void attachMessageMenu(final TextView tv, final Msg m) {
        tv.setOnLongClickListener(v -> {
            messageMenu(m);
            return true;
        });
    }

    private void send() {
        if (generating) return;
        if (flow.isListening() && recorder != null && recorder.isRunning()) {
            toast(getString(R.string.toast_rec_running));
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingImage == null) return;

        String model = selectedModelId();
        if (model == null || model.isEmpty()) {
            toast(getString(R.string.toast_no_model));
            refreshModels();
            return;
        }
        if (pendingImage != null && modelLooksBlind(model)) {
            toast(getString(R.string.toast_model_no_vision));
            return;
        }

        Msg user = new Msg("user", text);
        user.model = model;
        if (pendingImage != null) user.addImage(pendingImage, "image/jpeg");
        user.dbId = db.addMessage(chatId, user);
        db.touchChat(chatId, text, model);
        history.add(user);
        chatList.addView(messageView(user));

        input.setText("");
        boolean picture = wantsPicture(model, pendingImage != null);
        String prompt = text;
        clearImage();
        emptyHint.setVisibility(View.GONE);
        scrollToEnd(true);

        if (picture) {
            startImage(model, prompt);      // ein Bild wird nie vorgelesen
        } else {
            startAssistant(model, user);
        }
    }

    /** Ein Bild-Profil erzeugt automatisch ein Bild; ein angehanges Bild bleibt eine Textfrage. */
    private boolean wantsPicture(String modelId, boolean attachedImage) {
        return Router.wantsPicture(isImageModel(modelId), attachedImage);
    }

    /** true, wenn das gewaehlte Modell ueber das ComfyUI-Bildprofil laeuft. */
    private boolean isImageModel(String modelId) {
        if (!Config.MODE_OWUI.equals(cfg.mode)) return false;
        for (ModelInfo m : models) {
            if (modelId != null && modelId.equals(m.id)) return m.imageGen;
        }
        return false;
    }

    /** Der Bild-Modus ist an (Bottom-Bar und Qualitaets-Chip haengen daran). */
    private boolean imageMode() {
        return isImageModel(selectedModelId());
    }

    /**
     * Modell gewechselt: der Bild-Modus beendet jede laufende Sprachrunde,
     * denn ein Prompt soll ein Bild erzeugen und keine gesprochene Antwort.
     */
    private void onModelChosen(ModelInfo picked) {
        if (picked.imageGen) {
            if (flow.sessionOn() || (voice != null && voice.isSpeaking())) {
                abortVoice();
                toast(getString(R.string.toast_image_mode_chat_off));
            }
        }
        updateQualityChip();
        updateChatButton();
        updateInputHint();
        updateSearchChip();
    }

    /** Im Bild-Modus steht im Eingabefeld, was ComfyUI braucht. */
    private void updateInputHint() {
        if (input == null) return;
        input.setHint(imageMode() ? getString(R.string.hint_image_prompt) : getString(R.string.ask));
    }

    /**
     * Die Frage vor dieser. Kurze Folgefragen ("und mit 512 GB?") ergeben allein keinen
     * Suchbegriff, dann hilft die vorherige Frage.
     */
    private String previousUserText(Msg current) {
        int idx = history.indexOf(current);
        if (idx < 0) return null;
        for (int i = idx - 1; i >= 0; i--) {
            Msg m = history.get(i);
            if ("user".equals(m.role) && !m.hasImage()
                    && m.text != null && !m.text.trim().isEmpty()) return m.text;
        }
        return null;
    }

    /** Legt die Platzhalter-Blase an und fragt den Server. */
    private void startAssistant(String model, Msg prompt) {
        final Msg asst = new Msg("assistant", "");
        asst.model = model;
        history.add(asst);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        final TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setTextColor(Util.color(0xFF9A9A92));
        tv.setLineSpacing(0, 1.25f);
        tv.setText("…");
        row.addView(tv);
        chatList.addView(row);

        streamingMsg = asst;
        streamingView = tv;
        streamingRow = row;
        generating = true;
        updateSendIcon();
        scrollToEnd(true);

        final boolean useSearch = Config.MODE_OWUI.equals(cfg.mode) && !prompt.hasImage()
                && Search.wantsSearch(cfg.webSearch, prompt.text);
        final String earlierQuestion = previousUserText(prompt);

        worker.execute(new Runnable() {
            @Override
            public void run() {
                List<Search.Hit> hits = null;
                String searchBlock = "";
                if (useSearch) {
                    try {
                        hits = api.webSearch(prompt.text, earlierQuestion);
                    } catch (final Exception e) {
                        final String why = Api.explain(e);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast("Websuche: " + why);
                            }
                        });
                    }
                    if (hits != null && !hits.isEmpty()) searchBlock = Search.systemBlock(hits);
                    final int found = hits == null ? 0 : hits.size();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (found > 0 && streamingView != null) {
                                streamingView.setText(getString(R.string.web_search_reading, found));
                            } else if (found == 0) {
                                toast(getString(R.string.toast_search_nothing));
                            }
                        }
                    });
                }
                String searchExtra = searchBlock;
                if (flow.isThinking()) {
                    searchExtra = searchExtra.isEmpty() ? VOICE_SYSTEM_PROMPT : searchExtra + "\n" + VOICE_SYSTEM_PROMPT;
                }
                final String extraSystem = searchExtra.isEmpty() ? null : searchExtra;
                try {
                    String reply = api.chat(model, history, extraSystem, new Api.Delta() {
                        @Override
                        public void onDelta(String chunk, String full) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (streamingView == null) return;
                                    streamingView.setTextColor(BUBBLE_TEXT);
                                    if (full.indexOf("data:image") >= 0) {
                                        streamingView.setText(getString(R.string.status_image_loading));
                                    } else {
                                        streamingView.setText(full.isEmpty() ? "…" : full);
                                    }
                                    if (followScroll) chatScroll.fullScroll(View.FOCUS_DOWN);
                                }
                            });
                        }
                    });
                    String clean = reply == null ? "" : reply;
                    List<byte[]> extra = new ArrayList<byte[]>();
                    List<String> extraMimes = new ArrayList<String>();
                    try {
                        extra = api.inlineImages(clean, extraMimes);
                    } catch (Exception ignored) {
                    }
                    if (!extra.isEmpty()) clean = Api.stripImageRefs(clean);
                    finishAssistant(clean, extra, extraMimes, null, hits);
                } catch (final Exception e) {
                    finishAssistant(null, null, null, e, hits);
                }
            }
        });
    }

    /** Darf aus dem Arbeits-Thread aufgerufen werden; die Views laufen im UI-Thread. */
    private void finishAssistant(final String reply, final List<byte[]> images,
                                final List<String> mimes, final Exception error) {
        finishAssistant(reply, images, mimes, error, null);
    }

    private void finishAssistant(final String reply, final List<byte[]> images, final List<String> mimes,
                                final Exception error, final List<Search.Hit> hits) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finishAssistantOnUi(reply, images, mimes, error, hits);
            }
        });
    }

    private void finishAssistantOnUi(String reply, List<byte[]> images, List<String> mimes,
                                    Exception error, List<Search.Hit> hits) {
        final Msg asst = streamingMsg;
        final TextView tv = streamingView;
        final LinearLayout row = streamingRow;
        streamingMsg = null;
        streamingView = null;
        streamingRow = null;
        generating = false;
        updateSendIcon();
        if (asst == null) return;

        if (reply != null) {
            asst.text = reply;
            if (images != null) {
                for (int i = 0; i < images.size(); i++) {
                    asst.addImage(images.get(i), mimes != null && i < mimes.size() ? mimes.get(i) : "image/png");
                }
            }
            if (asst.text.trim().isEmpty() && !asst.hasImage()) {
                history.remove(asst);
                rebuildChatList();
                toast(getString(R.string.toast_empty_reply));
            } else {
                if (hits != null && !hits.isEmpty()) {
                    asst.addSources(Search.sourceList(Search.sourceLines(hits)));
                }
                asst.dbId = db.addMessage(chatId, asst);
                if (row != null && asst.hasImage()) row.addView(imageView(asst), 0);
                if (tv != null) {
                    if (asst.text.trim().isEmpty() && asst.hasImage()) {
                        tv.setVisibility(View.GONE);   // reine Bild-Antwort
                    } else {
                        tv.setTextColor(BUBBLE_TEXT);
                        tv.setText(Markdown.render(asst.text));
                        attachMessageMenu(tv, asst);
                    }
                }
                if (row != null && asst.hasSources()) row.addView(sourcesView(asst));
            }
        } else {
            history.remove(asst);
            if (tv != null) {
                tv.setTextColor(Util.color(0xFFE5484D));
                tv.setText("⚠ " + Api.explain(error));
                tv.setOnLongClickListener(v -> {
                    regenerate();
                    return true;
                });
            }
            if (error instanceof Api.ApiException && ((Api.ApiException) error).status == 401) {
                sessionExpired();
            }
        }

        boolean hasText = reply != null && !asst.text.trim().isEmpty();
        boolean pictureAnswer = isImageModel(asst.model);
        boolean voiceRound = flow.isThinking();
        if (Router.speaksOnAnswer(voiceRound, pictureAnswer, hasText)) {
            flow.answerReady(true);                 // Stufe: spreche
            logVoice(getString(R.string.log_answer_read));
            speak(asst.text, true);
        } else if (voiceRound) {
            flow.answerReady(false);                // ohne Stimme endet die Unterhaltung
            if (reply == null) {
                endVoiceLoop(null);                 // Fehler steht schon in der Blase
            } else {
                endVoiceLoop(pictureAnswer
                        ? getString(R.string.tts_no_image)
                        : getString(R.string.tts_nothing));
            }
        }
        // Diktat und getippte Fragen bleiben stumm: vorgelesen wird nur in der
        // Sprach-Unterhaltung. Einzelne Antworten gehen ueber das Menue getString(R.string.msg_read_aloud).

        scrollToEnd(false);
    }

    /** Letzte Antwort neu erzeugen (Antwort weg, Frage behalten). */
    private void regenerate() {
        if (generating) return;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).role)) {
                Msg gone = history.remove(i);
                if (gone.dbId > 0) db.deleteMessage(gone.dbId);
                break;
            }
        }
        int lastUser = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role)) {
                lastUser = i;
                break;
            }
        }
        rebuildChatList();
        if (lastUser < 0) return;
        Msg user = history.get(lastUser);
        String model = user.model != null && !user.model.isEmpty() ? user.model : selectedModelId();
        if (model == null || model.isEmpty()) {
            toast(getString(R.string.toast_no_model));
            return;
        }
        startAssistantOrImage(model, user);
    }

    /** Bild-Profil mit Bild-Modus -> Bild generieren, sonst ganz normal fragen. */
    private void startAssistantOrImage(String model, Msg user) {
        if (wantsPicture(model, user.hasImage())) startImage(model, user.text == null ? "" : user.text);
        else startAssistant(model, user);
    }

    private void messageMenu(final Msg m) {
        final boolean assistant = "assistant".equals(m.role);
        String[] items = assistant
                ? new String[]{getString(R.string.msg_copy_answer), getString(R.string.msg_read_aloud), getString(R.string.msg_regenerate), getString(R.string.msg_delete)}
                : new String[]{getString(R.string.msg_copy_question), getString(R.string.msg_delete)};
        new AlertDialog.Builder(this)
                .setTitle(assistant ? getString(R.string.label_answer) : getString(R.string.label_question))
                .setItems(items, (d, which) -> {
                    String label = items[which];
                    if (label.endsWith("kopieren")) {
                        Util.copyText(this, m.text);
                        toast("Kopiert");
                    } else if (label.equals(getString(R.string.msg_read_aloud))) {
                        if (m.text == null || m.text.trim().isEmpty()) toast(getString(R.string.toast_no_text));
                        else speak(m.text, false);
                    } else if (label.equals(getString(R.string.msg_regenerate))) {
                        regenerate();
                    } else {
                        if (m.dbId > 0) db.deleteMessage(m.dbId);
                        history.remove(m);
                        rebuildChatList();
                    }
                })
                .show();
    }

    /* ---------------------------------------------------------------- Bilder */

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.attach_image)), REQ_IMAGE);
        } catch (Exception e) {
            toast(getString(R.string.toast_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_SPEECH) {
            if (result == RESULT_OK && data != null) {
                ArrayList<String> found = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (found != null && !found.isEmpty()) appendToInput(found.get(0));
                else toast("Nichts verstanden");
            }
            return;
        }
        if (request != REQ_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IllegalStateException(getString(R.string.toast_image_unreadable));
                    Bitmap bmp;
                    try {
                        bmp = Util.decodeScaled(in, 1152);
                    } finally {
                        in.close();
                    }
                    final byte[] jpg = Util.toJpeg(bmp, 82);
                    final String name = displayName(uri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pendingImage = jpg;
                            pendingBitmap = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                            pendingName = name;
                            showAttachBar();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(getString(R.string.toast_image_error, e.getMessage()));
                        }
                    });
                }
            }
        });
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return getString(R.string.img_label);
    }

    private void showAttachBar() {
        if (pendingImage == null) return;
        attachBar.setVisibility(View.VISIBLE);
        attachPreview.setImageBitmap(pendingBitmap);
        attachName.setText(pendingName + "  ·  " + Math.max(1, pendingImage.length / 1024) + " KB");
    }

    private void clearImage() {
        pendingImage = null;
        pendingBitmap = null;
        pendingName = "";
        if (attachBar != null) attachBar.setVisibility(View.GONE);
        if (attachPreview != null) attachPreview.setImageDrawable(null);
    }

    /* ------------------------------------------------ echtes Bild und Bild-Modus */

    /** Prompt -> ComfyUI-Pipeline -> echtes PNG in der Blase. */
    private void startImage(final String model, final String prompt) {
        final Msg asst = new Msg("assistant", "");
        asst.model = model;
        history.add(asst);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        final TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setTextColor(Util.color(0xFF9A9A92));
        tv.setLineSpacing(0, 1.25f);
        tv.setText(getString(R.string.status_image_generating, cfg.imageSize));
        row.addView(tv);
        chatList.addView(row);

        streamingMsg = asst;
        streamingView = tv;
        streamingRow = row;
        generating = true;
        updateSendIcon();
        scrollToEnd(true);

        worker.execute(new Runnable() {
            @Override
            public void run() {
                List<byte[]> pics = new ArrayList<byte[]>();
                List<String> mimes = new ArrayList<String>();
                try {
                    List<Api.GenImage> gen = api.generateImage(model, prompt, cfg.imageSize);
                    for (Api.GenImage g : gen) {
                        pics.add(api.download(g.url));
                        mimes.add(g.contentType);
                    }
                    finishAssistant("", pics, mimes, null);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finishAssistant(null, null, null, e);
                        }
                    });
                }
            }
        });
    }

    private ImageView imageView(final Msg m) {
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
        ilp.gravity = "user".equals(m.role) ? Gravity.END : Gravity.START;
        ilp.topMargin = Util.dp(this, 4);
        ilp.bottomMargin = Util.dp(this, 6);
        iv.setLayoutParams(ilp);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth(Util.dp(this, 320));
        iv.setMaxHeight(Util.dp(this, 430));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundResource(R.drawable.bg_field);
        iv.setImageBitmap(decodeScaledBytes(m.images.get(0), 640));
        iv.setOnClickListener(v -> imageViewer(m));
        iv.setOnLongClickListener(v -> {
            messageMenu(m);
            return true;
        });
        return iv;
    }

    private static Bitmap decodeScaledBytes(byte[] bytes, int longestPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        int longest = Math.max(o.outWidth, o.outHeight);
        while (longest / sample > longestPx * 2 && sample < 32) sample *= 2;
        if (sample > 1) {
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void imageViewer(Msg m) {
        Bitmap bmp = decodeScaledBytes(m.images.get(0), 1400);
        if (bmp == null) {
            toast(getString(R.string.toast_image_show));
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Util.dp(this, 10);
        iv.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this).setView(iv).setPositiveButton(getString(R.string.btn_close), null).show();
    }

    /* ------------------------------------------------ Diktat */

    private void toggleMic() {
        if (transcribing) return;
        if (flow.sessionOn() || (voice != null && voice.isSpeaking())) {
            toast(getString(R.string.toast_conv_running));
            return;
        }
        if (recorder != null && recorder.isRunning()) {
            stopDictation(false);
            return;
        }
        if (cfg.systemDict || !Config.MODE_OWUI.equals(cfg.mode)) {
            systemDictation();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startDictation();
    }

    private void startDictation() {
        Recorder r = new Recorder();
        try {
            r.start();
        } catch (Exception e) {
            toast("Mikrofon: " + e.getMessage());
            return;
        }
        recorder = r;
        dictBar.setVisibility(View.VISIBLE);
        btnMic.setColorFilter(Util.color(0xFFE5484D));
        btnMic.setContentDescription(getString(R.string.desc_stop_rec));
        dictStatus.setText(getString(R.string.rec_status));
        dictTicker = new Runnable() {
            @Override
            public void run() {
                if (recorder == null || !recorder.isRunning()) return;
                int sec = recorder.seconds();
                int bars = Math.min(8, recorder.level() / 3000);
                StringBuilder sb = new StringBuilder(getString(R.string.rec_status_secs, sec));
                for (int i = 0; i < 8; i++) sb.append(i < bars ? "▁▂▃▄▅▆▇█".charAt(i) : '·');
                dictStatus.setText(sb.toString());
                if (sec >= Recorder.MAX_SECONDS) {
                    toast(getString(R.string.rec_time_limit));
                    stopDictation(false);
                    return;
                }
                ui.postDelayed(this, 250);
            }
        };
        ui.postDelayed(dictTicker, 250);
    }

    private void stopDictation(boolean discard) {
        if (dictTicker != null) {
            ui.removeCallbacks(dictTicker);
            dictTicker = null;
        }
        byte[] wav = null;
        if (recorder != null) {
            if (discard) recorder.cancel();
            else wav = recorder.stop();
            recorder = null;
        }
        btnMic.setColorFilter(Util.color(0xFF9A9AA8));
        btnMic.setContentDescription("Diktieren");
        if (discard) {
            dictBar.setVisibility(View.GONE);
            toast("Diktat verworfen");
            return;
        }
        if (wav == null) {
            dictBar.setVisibility(View.GONE);
            toast(getString(R.string.rec_too_short));
            return;
        }
        transcribing = true;
        dictBar.setVisibility(View.VISIBLE);
        dictStatus.setText(getString(R.string.status_transcribing));
        final byte[] audio = wav;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = api.transcribe(audio, "diktat.wav", "audio/wav");
                } catch (final Exception e) {
                    final String msg = Api.explain(e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            transcribing = false;
                            dictBar.setVisibility(View.GONE);
                            toast("Diktat: " + msg);
                        }
                    });
                    return;
                }
                final String got = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        transcribing = false;
                        dictBar.setVisibility(View.GONE);
                        if (got.isEmpty()) {
                            toast(getString(R.string.rec_no_text));
                            return;
                        }
                        appendToInput(got);
                    }
                });
            }
        });
    }

    private void appendToInput(String text) {
        String current = input.getText().toString();
        if (current.trim().isEmpty()) input.setText(text);
        else input.setText(current.trim() + " " + text);
        input.setSelection(input.getText().length());
        input.requestFocus();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (chatAfterPermission) {
                chatAfterPermission = false;
                startVoiceTurn();
            } else {
                startDictation();
            }
        } else {
            toast(getString(R.string.mic_blocked));
        }
    }

    /** Fallback: Spracheingabe des Telefons (Direktmodus oder wenn so eingestellt). */
    private void systemDictation() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Sprechen Sie …");
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            toast(getString(R.string.mic_no_input));
        }
    }

    /* ------------------------------------------ Sprach-Unterhaltung (Voice Chat) */

    /**
     * Unterhaltungs-Knopf (analog zum Voice-Knopf im Webbrowser): druecken -> sprechen
     * -> Antwort wird vorgelesen. Nochmal druecken beendet die Unterhaltung.
     * Das Mikrofon-Symbol daneben bleibt reines Diktat wie in 1.1.
     */
    private void onChatPress() {
        if (voice != null && voice.isSpeaking()) {
            voice.stop();                                   // Sprechen unterbrechen
            if (flow.isSpeaking()) {
                if (flow.continueAfterSpeech(true, cfg.voiceLoop)) startVoiceTurn();
                else endVoiceLoop("Unterhaltung beendet");
            } else {
                toast(getString(R.string.tts_interrupted));            // einzelne Nachricht, keine Unterhaltung
            }
            return;
        }
        switch (flow.pressAction()) {
            case VoiceFlow.PRESS_FINISH:
                finishVoiceRecording();                     // Aufnahme zu Ende, Antwort kommt
                return;
            case VoiceFlow.PRESS_ABORT:
                abortVoice();
                toast("Unterhaltung beendet");
                return;
            default:
                break;                                      // PRESS_LISTEN: zuhoeren
        }
        if (transcribing || (recorder != null && recorder.isRunning())) {
            toast(getString(R.string.toast_dict_running));
            return;
        }
        if (!Router.voiceChatAllowed(Config.MODE_OWUI.equals(cfg.mode), imageMode())) {
            toast(imageMode()
                    ? getString(R.string.toast_image_mode_no_voice)
                    : getString(R.string.toast_voice_needs_webui));
            return;
        }
        startVoiceTurn();
    }

    private void onCancelPress() {
        if (flow.sessionOn()) {
            abortVoice();
            toast("Sprachrunde abgebrochen");
            return;
        }
        stopDictation(true);
    }

    private void updateChatButton() {
        if (btnChat == null) return;
        if (!Router.voiceChatAllowed(Config.MODE_OWUI.equals(cfg.mode), imageMode())) {
            // Im Bild-Modus gibt es keine Sprach-Unterhaltung: der Prompt geht direkt in ComfyUI.
            btnChat.setVisibility(View.GONE);
            btnChat.setEnabled(false);
            btnChat.setContentDescription(getString(R.string.desc_conv_image_off));
            return;
        }
        boolean on = flow.sessionOn();
        btnChat.setVisibility(View.VISIBLE);
        btnChat.setEnabled(true);
        btnChat.setColorFilter(Util.color(on ? 0xFF10A37F : 0xFF9A9AA8));
        btnChat.setContentDescription(on
                ? getString(R.string.desc_conv_on)
                : getString(R.string.desc_conv_off));
    }

    /** Aufnahme starten; endet von selbst, wenn die Aussage fertig ist. */
    private void startVoiceTurn() {
        if (flow.isListening()) return;                     // diese Runde ist schon offen
        if (flow.roundActive()) {
            toast(getString(R.string.toast_prev_round));
            return;
        }
        if (generating) {
            toast(getString(R.string.toast_prev_answer));
            return;
        }
        if (!Router.voiceChatAllowed(Config.MODE_OWUI.equals(cfg.mode), imageMode())) {
            toast(imageMode()
                    ? getString(R.string.toast_image_mode_no_round)
                    : getString(R.string.toast_voice_needs_webui));
            return;
        }
        final String model = voiceModelId();
        if (model == null || model.isEmpty()) {
            toast(getString(R.string.toast_no_model));
            refreshModels();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            chatAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        final boolean opening = !flow.sessionOn();
        boolean started = opening ? flow.openSession() : flow.beginTurn();
        if (!started) {
            toast(getString(R.string.toast_conv_started));
            return;
        }
        voice.stop();
        Recorder r = new Recorder();
        try {
            r.start();
        } catch (Exception e) {
            flow.closeSession();
            updateChatButton();
            hideVoiceBar();
            toast("Mikrofon: " + e.getMessage());
            return;
        }
        recorder = r;
        turnOpenedAt = System.currentTimeMillis();
        lastLoudAt = turnOpenedAt;
        firstSpeechAt = 0;
        btnChat.setColorFilter(Util.color(0xFFE5484D));
        btnChat.setContentDescription(getString(R.string.desc_conv_stop));
        dictBar.setVisibility(View.VISIBLE);
        dictStatus.setText(getString(R.string.rec_speak_now));
        updateChatButton();
        logVoice(opening ? getString(R.string.log_conv_first) : getString(R.string.log_conv_next));
        if (opening) toast(getString(R.string.toast_conv_on));

        voiceTicker = new Runnable() {
            @Override
            public void run() {
                if (!flow.isListening() || recorder == null || !recorder.isRunning()) return;
                long now = System.currentTimeMillis();
                int sec = recorder.seconds();
                int level = recorder.level();
                if (now - turnOpenedAt < VOICE_WARMUP_MS) level = 0;   // Nachhall der Stimme ist keine eigene Aussage
                if (level >= VOICE_LEVEL_MIN) {
                    lastLoudAt = now;
                    if (firstSpeechAt == 0) firstSpeechAt = now;
                }
                int bars = Math.min(8, level / 3000);
                StringBuilder sb = new StringBuilder("● Sprich jetzt  ").append(sec).append(" s   ");
                for (int i = 0; i < 8; i++) sb.append(i < bars ? "▁▂▃▄▅▆▇█".charAt(i) : '·');
                dictStatus.setText(sb.toString());

                if (sec >= Recorder.MAX_SECONDS) {
                    toast(getString(R.string.rec_time_limit));
                    finishVoiceRecording();
                    return;
                }
                if (firstSpeechAt != 0 && now - lastLoudAt >= VOICE_SILENCE_MS) {
                    finishVoiceRecording();      // Aussage fertig
                    return;
                }
                if (firstSpeechAt == 0 && sec * 1000 >= VOICE_MAX_WAIT_MS) {
                    abortVoice();
                    toast("Nichts gesprochen");
                    return;
                }
                ui.postDelayed(this, 200);
            }
        };
        ui.postDelayed(voiceTicker, 200);
    }

    /** Aufnahme schliessen und direkt als Frage senden (kein Umweg ueber das Eingabefeld). */
    private void finishVoiceRecording() {
        if (voiceTicker != null) {
            ui.removeCallbacks(voiceTicker);
            voiceTicker = null;
        }
        byte[] wav = null;
        if (recorder != null) {
            wav = recorder.stop();
            recorder = null;
        }
        btnChat.setColorFilter(Util.color(0xFF10A37F));
        if (wav == null) {
            abortVoice();
            toast(getString(R.string.rec_too_short));
            return;
        }
        flow.finishRecording();                             // aufnahme -> verstehe
        logVoice(getString(R.string.log_rec_whisper));
        final byte[] audio = wav;
        final String model = voiceModelId();
        dictStatus.setText(getString(R.string.status_understanding));
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = api.transcribe(audio, "sprache.wav", "audio/wav");
                } catch (final Exception e) {
                    final String msg = Api.explain(e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            abortVoice();
                            toast("Verstehen: " + msg);
                        }
                    });
                    return;
                }
                final String got = text == null ? "" : text.trim();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (got.isEmpty()) {
                            abortVoice();
                            toast("Nichts verstanden");
                            return;
                        }
                        askByVoice(model, got);
                    }
                });
            }
        });
    }

    private void askByVoice(String model, String text) {
        Msg user = new Msg("user", text);
        user.model = model;
        user.dbId = db.addMessage(chatId, user);
        db.touchChat(chatId, text, model);
        history.add(user);
        chatList.addView(messageView(user));
        emptyHint.setVisibility(View.GONE);
        scrollToEnd(true);
        flow.transcriptReady();                             // verstehe -> denke
        logVoice("verstanden: " + text);
        dictStatus.setText(getString(R.string.status_thinking));
        startAssistant(model, user);
    }

    /**
     * Antwort laut vorlesen. voiceRound=true: die Antwort gehoert zur
     * Sprach-Unterhaltung, haengt also eine neue Aufnahme daran.
     */
    private void speak(final String text, final boolean voiceRound) {
        if (!voiceRound && flow.roundActive()) {
            /* Eine einzelne Hoerprobe (getString(R.string.msg_read_aloud), Stimm-Auswahl) darf eine laufende
             * Unterhaltung nicht abschneiden: die laufende Ausgabe verlire damit ihren
             * Abschluss und die Unterhaltung bliebe in der Stufe "spreche" haengen. */
            if (flow.isListening()) {
                toast(getString(R.string.toast_rec_running));
            } else {
                abortVoice();
                toast(getString(R.string.toast_conv_end));
            }
            return;
        }
        final String spoken = Voice.spoken(text, VOICE_MAX_CHARS);
        if (spoken.isEmpty()) {
            if (voiceRound) endVoiceLoop(getString(R.string.tts_nothing));
            return;
        }
        if (voiceRound) {
            dictBar.setVisibility(View.VISIBLE);
            dictStatus.setText(getString(R.string.status_tts));
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final byte[] audio;
                try {
                    audio = api.speech(spoken);
                } catch (final Exception e) {
                    final String msg = Api.explain(e);
                    if (!voiceRound) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            endVoiceLoop("Sprachausgabe: " + msg);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (voiceRound && !flow.isSpeaking()) return;   // Unterhaltung schon beendet
                        if (voiceRound) dictStatus.setText(getString(R.string.status_speaking));
                        voice.play(audio, new Voice.Done() {
                            @Override
                            public void onDone(boolean ok, String error) {
                                onSpokenDone(ok, error, voiceRound);
                            }
                        });
                    }
                });
            }
        });
    }

    /** Die Ausgabe ist vorbei: naechste Aufnahmeeroeffnen oder Unterhaltung schliessen. */
    private void onSpokenDone(boolean ok, String error, boolean voiceRound) {
        if (!voiceRound) return;
        if (!flow.sessionOn()) {
            hideVoiceBar();
            return;
        }
        if (!ok) {
            endVoiceLoop(error == null ? "Sprachausgabe fehlgeschlagen" : error);
            return;
        }
        boolean nextTurn = flow.continueAfterSpeech(true, cfg.voiceLoop);   // spreche -> Pause
        logVoice(nextTurn ? getString(R.string.log_turn_next) : getString(R.string.log_turn_off));
        if (!nextTurn) {
            endVoiceLoop(cfg.voiceLoop ? null
                    : getString(R.string.toast_conv_one));
            return;
        }
        dictStatus.setText(getString(R.string.rec_speak_more));
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (flow.sessionOn()) startVoiceTurn();
                else endVoiceLoop(null);
            }
        }, 250);
    }

    /** Sprachrunde abschliessen (Grund != null -> Hinweis). */
    private void endVoiceLoop(String message) {
        flow.closeSession();
        updateChatButton();
        if (voiceTicker != null) {
            ui.removeCallbacks(voiceTicker);
            voiceTicker = null;
        }
        hideVoiceBar();
        logVoice("Unterhaltung beendet");
        if (message != null) toast(message);
    }

    /** Harter Abbruch: Aufnahme verwerfen, Sprechen stoppen. */
    private void abortVoice() {
        if (voice != null) voice.stop();
        if (voiceTicker != null) {
            ui.removeCallbacks(voiceTicker);
            voiceTicker = null;
        }
        if (recorder != null) {
            recorder.cancel();
            recorder = null;
        }
        flow.closeSession();
        updateChatButton();
        hideVoiceBar();
        logVoice("Sprachrunde abgebrochen");
    }

    private void hideVoiceBar() {
        dictBar.setVisibility(View.GONE);
        if (btnChat != null) {
            boolean on = flow.sessionOn();
            btnChat.setColorFilter(Util.color(on ? 0xFF10A37F : 0xFF9A9AA8));
            btnChat.setContentDescription(on
                    ? getString(R.string.desc_conv_on_short) : getString(R.string.desc_conv_off));
        }
    }

    /** Jede Stufenanderung in logcat: "owui-voice" zeigt, wo eine Unterhaltung haengen blieb. */
    private void logVoice(String what) {
        android.util.Log.i("owui-voice", flow.phaseName() + " | " + what);
    }

    /** Sprachrunden brauchen ein antwortendes Modell: das Bild-Profil ist dafuer nicht gedacht. */
    private String voiceModelId() {
        String sel = selectedModelId();
        for (ModelInfo m : models) {
            if (sel != null && sel.equals(m.id) && !m.imageGen) return sel;
        }
        for (ModelInfo m : models) {
            if (!m.imageGen) return m.id;
        }
        return sel;
    }

    /* ---------------------------------------------------- Bildgroesse (HD-Option) */

    private void updateQualityChip() {
        if (btnQualityBtn == null) return;
        boolean show = isImageModel(selectedModelId());
        btnQualityBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        btnQualityBtn.setText(Config.sizeLabel(cfg.imageSize));
        btnQualityBtn.setTextColor(Util.color("832x832".equals(cfg.imageSize) ? 0xFF9A9AA8 : 0xFF10A37F));
    }

    private void qualityDialog() {
        final String[] sizes = new String[]{"832x832", "1024x1024", "1280x1280"};
        String[] labels = new String[]{
                getString(R.string.size_std),
                getString(R.string.size_hd),
                getString(R.string.size_hdplus)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_image_size))
                .setSingleChoiceItems(labels, indexOf(sizes, cfg.imageSize), (d, which) -> {
                    cfg.imageSize = Config.normalizeSize(sizes[which]);
                    cfg.save();
                    updateQualityChip();
                    d.dismiss();
                    toast(getString(R.string.toast_image_size, cfg.imageSize));
                })
                .setNegativeButton(getString(R.string.btn_close), null)
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    /* --------------------------------------------------------------- Websuche */

    private void updateSearchChip() {
        if (btnSearchChip == null) return;
        boolean show = !imageMode() && Config.MODE_OWUI.equals(cfg.mode);
        btnSearchChip.setVisibility(show ? View.VISIBLE : View.GONE);
        btnSearchChip.setText(Search.chipLabel(cfg.webSearch));
        boolean on = Search.normalizeMode(cfg.webSearch) == Search.ON;
        btnSearchChip.setTextColor(Util.color(on ? 0xFF2E7CF6 : 0xFF9A9AA8));
        btnSearchChip.setContentDescription(on
                ? getString(R.string.desc_search_on)
                : getString(R.string.desc_search_off));
    }

    /** Ein Druck auf den Knopf schaltet um: aus <-> an. Kein Auswahl-Dialog noetig. */
    private void toggleSearch() {
        int next = Search.otherMode(cfg.webSearch);
        cfg.webSearch = next;
        cfg.save();
        updateSearchChip();
        toast(Search.ON == next
                ? getString(R.string.toast_search_on)
                : getString(R.string.toast_search_off));
    }

    /** Langer Druck auf den Knopf: Erklaerung und Auswahl. */
    private void searchDialog() {
        final int[] modes = new int[]{Search.OFF, Search.ON};
        String[] labels = new String[]{
                getString(R.string.search_choice_off),
                getString(R.string.search_choice_on)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_web_search))
                .setMessage(getString(R.string.search_help_message))
                .setSingleChoiceItems(labels, indexOf(modes, cfg.webSearch), (d, which) -> {
                    cfg.webSearch = Search.normalizeMode(modes[which]);
                    cfg.save();
                    updateSearchChip();
                    d.dismiss();
                    toast(getString(R.string.toast_search_chip, Search.chipLabel(cfg.webSearch)));
                })
                .setNegativeButton(getString(R.string.btn_close), null)
                .show();
    }

    private static int indexOf(int[] arr, int v) {
        int normalized = Search.normalizeMode(v);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == normalized) return i;
        }
        return 1;
    }

    /* --------------------------------------------------------------- Stimme */

    /** Auswahllfeld fuer die Stimme; die Auswahl wird sofort gespeichert und ist gleich zu hoeren. */
    private void addVoiceChoice(LinearLayout box) {
        box.addView(label(getString(R.string.label_tts_voice)));
        final Spinner voice = new Spinner(this);
        voice.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, Txt.array(R.array.voice_labels, Config.VOICE_LABELS)));
        final int start = voiceIndex(cfg.ttsVoice);
        voice.setSelection(start);

        /* Ein Spinner meldet seine Startauswahl selbst. Diese erste Meldung darf die
         * gespeicherte Stimme nicht still auf "Server-Standard" zuruecksetzen. */
        final boolean[] firstReport = new boolean[]{true};

        final TextView active = new TextView(this);
        active.setTextSize(12);
        active.setTextColor(Util.color(0xFF9A9A92));
        active.setText(getString(R.string.tts_now, Config.voiceLabel(cfg.ttsVoice)));

        voice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (firstReport[0]) {
                    firstReport[0] = false;
                    if (position != start) voice.setSelection(start);
                    return;
                }
                String picked = Config.normalizeVoice(Config.VOICE_IDS[clampVoice(position)]);
                cfg.ttsVoice = picked;
                cfg.save();
                active.setText(getString(R.string.tts_now, Config.voiceLabel(picked)));
                /* Die Hoerprobe nennt ihren eigenen Namen, damit hoeren ist, dass die
                 * gewaehlte Stimme wirklich gesprochen wird (und nicht eine andere). */
                speak(getString(R.string.tts_sample, Config.voiceShortName(picked)), false);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        box.addView(voice);
        box.addView(active);
    }

    private static int clampVoice(int i) {
        if (i < 0) return 0;
        return i >= Config.VOICE_IDS.length ? 0 : i;
    }

    private static int voiceIndex(String id) {
        String v = Config.normalizeVoice(id);
        for (int i = 0; i < Config.VOICE_IDS.length; i++) {
            if (Config.VOICE_IDS[i].equals(v)) return i;
        }
        return 0;
    }

    /* ---------------------------------------------------------------- Seitenleiste */

    private void openDrawer() {
        buildDrawer();
        serverInfo.setText(cfg.serverLabel());
        drawer.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(-Util.dp(this, 320));
        drawerPanel.animate().translationX(0f).setDuration(180).start();
        drawerScrim.setAlpha(0f);
        drawerScrim.animate().alpha(1f).setDuration(180).start();
    }

    private void closeDrawer() {
        drawerPanel.animate().translationX(-Util.dp(this, 320)).setDuration(160)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        drawer.setVisibility(View.GONE);
                        drawerPanel.setTranslationX(0f);
                    }
                }).start();
        drawerScrim.animate().alpha(0f).setDuration(160).start();
    }

    private void buildDrawer() {
        chatListDrawer.removeAllViews();
        List<Db.ChatRef> chats = db.listChats(120);
        if (chats.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.empty_chats));
            empty.setTextColor(Util.color(0xFF77776E));
            empty.setTextSize(14);
            int pad = Util.dp(this, 14);
            empty.setPadding(pad, pad, pad, pad);
            chatListDrawer.addView(empty);
            return;
        }
        for (final Db.ChatRef c : chats) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundResource(R.drawable.bg_row);
            int pad = Util.dp(this, 12);
            item.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Util.dp(this, 3);
            lp.bottomMargin = Util.dp(this, 3);
            lp.leftMargin = Util.dp(this, 8);
            lp.rightMargin = Util.dp(this, 8);
            item.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setTextSize(15);
            title.setTextColor(BUBBLE_TEXT);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(c.title);

            TextView sub = new TextView(this);
            sub.setTextSize(12);
            sub.setTextColor(Util.color(0xFF77776E));
            sub.setText(Util.when(c.updated) + (c.model.isEmpty() ? "" : "  ·  " + c.model));

            item.addView(title);
            item.addView(sub);
            item.setOnClickListener(v -> {
                openChat(c.id);
                closeDrawer();
            });
            item.setOnLongClickListener(v -> {
                confirmDelete(c);
                return true;
            });
            chatListDrawer.addView(item);
        }
    }

    private void confirmDelete(final Db.ChatRef c) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_delete_chat))
                .setMessage(c.title)
                .setPositiveButton(getString(R.string.btn_delete), (d, w) -> {
                    db.deleteChat(c.id);
                    if (c.id.equals(chatId)) openChat(db.newChat(cfg.model));
                    buildDrawer();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /* ---------------------------------------------------------------- Menü, Einstellungen */

    private void moreMenu() {
        String[] items = new String[]{
                getString(R.string.settings),
                getString(R.string.menu_reload_models),
                getString(R.string.menu_change_server),
                getString(R.string.logout)
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    String label = items[which];
                    if (label.equals(getString(R.string.settings))) settingsDialog();
                    else if (label.equals(getString(R.string.menu_reload_models))) refreshModels();
                    else if (label.equals(getString(R.string.menu_change_server))) {
                        Intent i = new Intent(this, LoginActivity.class);
                        i.putExtra("force", true);
                        startActivity(i);
                    } else logout();
                })
                .show();
    }

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(this, 18);
        box.setPadding(pad, pad, pad / 2, pad / 4);

        EditText temp = new EditText(this);
        temp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        temp.setText(String.valueOf(cfg.temperature));
        temp.setHint("0.7");

        EditText sys = new EditText(this);
        sys.setText(cfg.systemPrompt);
        sys.setHint("System-Prompt (optional)");
        sys.setMinLines(2);
        sys.setGravity(Gravity.TOP);

        CheckBox stream = new CheckBox(this);
        stream.setText(getString(R.string.setting_stream));
        stream.setChecked(cfg.streaming);

        CheckBox trust = new CheckBox(this);
        trust.setText(getString(R.string.setting_trust_tls));
        trust.setChecked(cfg.allowAnyCert);

        CheckBox dict = new CheckBox(this);
        dict.setText(getString(R.string.setting_dictate_phone));
        dict.setChecked(cfg.systemDict);

        CheckBox loop = new CheckBox(this);
        loop.setText(getString(R.string.setting_conv_auto));
        loop.setChecked(cfg.voiceLoop);

        TextView speakNote = new TextView(this);
        speakNote.setText(getString(R.string.setting_read_note));
        speakNote.setTextSize(12);
        speakNote.setTextColor(Util.color(0xFF9A9A92));

        box.addView(label("Temperatur (0.0 – 2.0)"));
        box.addView(temp);
        addVoiceChoice(box);
        box.addView(label("System-Prompt"));
        box.addView(sys);
        box.addView(stream);
        box.addView(trust);
        box.addView(dict);
        box.addView(loop);
        box.addView(speakNote);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings))
                .setView(box)
                .setPositiveButton(getString(R.string.btn_save), (d, w) -> {
                    double t = cfg.temperature;
                    try {
                        t = Double.parseDouble(temp.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    if (t < 0) t = 0;
                    if (t > 2) t = 2;
                    cfg.temperature = t;
                    cfg.systemPrompt = sys.getText().toString().trim();
                    cfg.streaming = stream.isChecked();
                    cfg.allowAnyCert = trust.isChecked();
                    cfg.systemDict = dict.isChecked();
                    cfg.voiceLoop = loop.isChecked();
                    cfg.save();
                    toast("Gespeichert");
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Util.color(0xFF9A9A92));
        tv.setPadding(0, Util.dp(this, 10), 0, Util.dp(this, 2));
        return tv;
    }

    private void logout() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.logout))
                .setMessage(getString(R.string.logout_message))
                .setPositiveButton(getString(R.string.logout), (d, w) -> {
                    cfg.clearAccess();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.putExtra("force", true);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void sessionExpired() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_login_expired))
                .setMessage(getString(R.string.login_expired_message))
                .setPositiveButton(getString(R.string.btn_login_again), (d, w) -> {
                    cfg.clearAccess();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.putExtra("force", true);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Weiter versuchen", null)
                .show();
    }

    /* ---------------------------------------------------------------- Modelle */

    private void refreshModels() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<ModelInfo> list = api.listModels();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyModels(list);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("Modelle: " + Api.explain(e));
                        }
                    });
                }
            }
        });
    }

    private void applyModels(List<ModelInfo> list) {
        models = list;
        List<String> labels = new ArrayList<String>();
        for (ModelInfo m : list) {
            String tail = m.imageGen ? "  ·  erzeugt Bilder" : (m.vision ? "  ·  sieht Bilder" : "");
            labels.add(m.label + tail);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        int idx = modelIndex(cfg.model);
        if (idx < 0 && !list.isEmpty()) idx = 0;
        if (idx >= 0) {
            modelSpinner.setSelection(idx);
            cfg.model = list.get(idx).id;
            cfg.save();
        }
        updateQualityChip();
        updateChatButton();
        updateInputHint();
        updateSearchChip();
    }

    private int modelIndex(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < models.size(); i++) {
            if (id.equals(models.get(i).id)) return i;
        }
        return -1;
    }

    private String selectedModelId() {
        int pos = modelSpinner == null ? -1 : modelSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < models.size()) return models.get(pos).id;
        return cfg.model == null || cfg.model.isEmpty() ? null : cfg.model;
    }

    /** Open WebUI nennt die Fähigkeiten; fehlt dort vision, kann das Modell keine Bilder. */
    private boolean modelLooksBlind(String id) {
        if (!Config.MODE_OWUI.equals(cfg.mode)) return false;
        for (ModelInfo m : models) {
            if (id.equals(m.id)) return !m.vision;
        }
        return false;
    }

    /* ---------------------------------------------------------------- Kleinigkeiten */

    private void updateSendIcon() {
        if (generating) {
            btnSend.setImageResource(R.drawable.ic_stop);
            btnSend.setContentDescription("Stoppen");
        } else {
            btnSend.setImageResource(R.drawable.ic_send);
            btnSend.setContentDescription(getString(R.string.desc_send));
        }
    }

    private void scrollToEnd(boolean force) {
        if (force) followScroll = true;
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                if (followScroll) chatScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
