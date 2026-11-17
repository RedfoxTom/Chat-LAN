package de.owui.chat;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leichtgewichtiges Markdown für Chat-Ausgabe: Code-Blöcke, Inline-Code,
 * Fett/Kursiv, Überschriften, Aufzählungen, Zitate, Links.
 */
public final class Markdown {

    static final int CODE_BG = 0xFF2B2B2B;
    static final int CODE_TEXT = 0xFFE8E1D4;
    static final int LINK = 0xFF8FB8DE;
    private static final int FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^[-*_]{3,}$");
    private static final Pattern LIST = Pattern.compile("^[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d+)[.)]\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile(
            "(`[^`\\n]+`)"
                    + "|(\\*\\*[^*\\n]+\\*\\*)"
                    + "|(__[^_\\n]+__)"
                    + "|(\\*[^*\\n]+\\*)"
                    + "|(_[^_\\n]+_)"
                    + "|\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private Markdown() {
    }

    public static CharSequence render(String src) {
        if (src == null || src.isEmpty()) return "";
        String s = src.replace("\r\n", "\n").replace("\r", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = s.split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (inCode) {
                    flushCode(out, code);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(raw).append('\n');
                continue;
            }
            if (t.isEmpty()) {
                out.append('\n');
                continue;
            }
            appendBlock(out, t);
        }
        if (inCode) {
            flushCode(out, code);
        }

        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        int start = 0;
        while (start < out.length() && out.charAt(start) == '\n') start++;
        if (start > 0) out.delete(0, start);
        return out;
    }

    private static void appendBlock(SpannableStringBuilder out, String t) {
        Matcher h = HEADING.matcher(t);
        if (h.matches()) {
            int level = h.group(1).length();
            int s = out.length();
            appendInline(out, h.group(2));
            int e = out.length();
            out.setSpan(new StyleSpan(Typeface.BOLD), s, e, FLAGS);
            out.setSpan(new RelativeSizeSpan(level <= 1 ? 1.28f : level == 2 ? 1.14f : 1.04f), s, e, FLAGS);
            out.append("\n\n");
            return;
        }
        if (RULE.matcher(t).matches()) {
            out.append("──────────────────\n");
            return;
        }
        Matcher li = LIST.matcher(t);
        if (li.matches()) {
            int s = out.length();
            out.append("• ");
            appendInline(out, li.group(1));
            out.setSpan(new LeadingMarginSpan.Standard(34), s, out.length(), FLAGS);
            out.append('\n');
            return;
        }
        Matcher ol = ORDERED.matcher(t);
        if (ol.matches()) {
            out.append(ol.group(1)).append(". ");
            appendInline(out, ol.group(2));
            out.append('\n');
            return;
        }
        if (t.startsWith(">")) {
            int s = out.length();
            appendInline(out, t.substring(1).trim());
            int e = out.length();
            out.setSpan(new QuoteSpan(), s, e, FLAGS);
            out.setSpan(new StyleSpan(Typeface.ITALIC), s, e, FLAGS);
            out.append('\n');
            return;
        }
        appendInline(out, t);
        out.append('\n');
    }

    private static void appendInline(SpannableStringBuilder out, String text) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.append(text, last, m.start());
            String tok = m.group(0);
            int s = out.length();
            if (m.group(1) != null) {
                out.append(tok.substring(1, tok.length() - 1));
                int e = out.length();
                out.setSpan(new TypefaceSpan("monospace"), s, e, FLAGS);
                out.setSpan(new RelativeSizeSpan(0.92f), s, e, FLAGS);
                out.setSpan(new ForegroundColorSpan(CODE_TEXT), s, e, FLAGS);
                out.setSpan(new BackgroundColorSpan(CODE_BG), s, e, FLAGS);
            } else if (m.group(2) != null || m.group(3) != null) {
                out.append(tok.substring(2, tok.length() - 2));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), FLAGS);
            } else if (m.group(4) != null || m.group(5) != null) {
                out.append(tok.substring(1, tok.length() - 1));
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), FLAGS);
            } else if (m.group(6) != null) {
                out.append(m.group(6));
                out.setSpan(new ForegroundColorSpan(LINK), s, out.length(), FLAGS);
                out.setSpan(new UnderlineSpan(), s, out.length(), FLAGS);
                String url = m.group(7);
                if (url != null && !url.isEmpty()) {
                    out.append(" (").append(url).append(")");
                }
            }
            last = m.end();
        }
        if (last < text.length()) out.append(text, last, text.length());
    }

    private static void flushCode(SpannableStringBuilder out, StringBuilder code) {
        String c = code.toString();
        while (c.endsWith("\n")) c = c.substring(0, c.length() - 1);
        code.setLength(0);
        if (c.isEmpty()) return;
        out.append('\n');
        int s = out.length();
        out.append(c);
        int e = out.length();
        out.setSpan(new TypefaceSpan("monospace"), s, e, FLAGS);
        out.setSpan(new AbsoluteSizeSpan(13, true), s, e, FLAGS);
        out.setSpan(new ForegroundColorSpan(CODE_TEXT), s, e, FLAGS);
        out.setSpan(new BackgroundColorSpan(CODE_BG), s, e, FLAGS);
        out.setSpan(new LeadingMarginSpan.Standard(28), s, e, FLAGS);
        out.append('\n');
    }
}
