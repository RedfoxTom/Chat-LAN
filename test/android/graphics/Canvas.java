package android.graphics;
public class Canvas {
    public final Bitmap target;
    public Canvas(Bitmap b) { target = b; }
    public void drawColor(int color) { java.util.Arrays.fill(target.pixels, color); }
    public void drawBitmap(Bitmap src, float left, float top, Paint paint) {
        for (int y = 0; y < src.height; y++) {
            int ty = (int) top + y;
            if (ty < 0 || ty >= target.height) continue;
            for (int x = 0; x < src.width; x++) {
                int tx = (int) left + x;
                if (tx < 0 || tx >= target.width) continue;
                int c = src.pixels[y * src.width + x];
                int a = (c >>> 24) & 0xFF;
                int dst = target.pixels[ty * target.width + tx];
                if (a == 255) {
                    target.pixels[ty * target.width + tx] = c | 0xFF000000;
                } else if (a > 0) {
                    int r = ((c >> 16) & 0xFF) * a / 255 + ((dst >> 16) & 0xFF) * (255 - a) / 255;
                    int g = ((c >> 8) & 0xFF) * a / 255 + ((dst >> 8) & 0xFF) * (255 - a) / 255;
                    int b = (c & 0xFF) * a / 255 + (dst & 0xFF) * (255 - a) / 255;
                    target.pixels[ty * target.width + tx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
    }
}
