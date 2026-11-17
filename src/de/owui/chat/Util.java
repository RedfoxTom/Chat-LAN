package de.owui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.format.DateUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Util {

    private Util() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** Bild decodieren und auf maxEdge kantenbeschneiden (RAM-schonend). */
    public static Bitmap decodeScaled(InputStream in, int maxEdge) throws IOException {
        byte[] raw = readAll(in);
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) throw new IOException("Bild konnte nicht gelesen werden");

        int sample = 1;
        while (probe.outWidth / sample > maxEdge * 2 || probe.outHeight / sample > maxEdge * 2) sample *= 2;

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
        if (bmp == null) throw new IOException("Bild konnte nicht decodiert werden");

        int w = bmp.getWidth(), h = bmp.getHeight();
        if (Math.max(w, h) > maxEdge) {
            float f = (float) maxEdge / Math.max(w, h);
            bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
        }
        return bmp;
    }

    /** Nach JPEG konvertieren; Transparenz wird auf Weiß gemischt (sonst wird JPEG schwarz). */
    public static byte[] toJpeg(Bitmap bmp, int quality) {
        Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bmp, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (flat != bmp) bmp.recycle();
        flat.recycle();
        return out.toByteArray();
    }

    public static void copyText(Context c, String s) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("chat", s));
    }

    public static int color(int argb) {
        return argb;
    }

    public static String when(long ts) {
        return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
