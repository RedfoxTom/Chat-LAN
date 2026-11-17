package android.graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;
public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig;
        public int outWidth;
        public int outHeight;
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len) {
        return decodeByteArray(data, off, len, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int off, int len, Options opt) {
        return decodeStream(new ByteArrayInputStream(data, off, len), opt);
    }

    public static Bitmap decodeStream(InputStream in) { return decodeStream(in, null); }

    public static Bitmap decodeStream(InputStream in, Options opt) {
        try {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            if (opt != null) {
                opt.outWidth = img.getWidth();
                opt.outHeight = img.getHeight();
                if (opt.inJustDecodeBounds) return null;
            }
            int w = img.getWidth(), h = img.getHeight();
            Bitmap b = new Bitmap(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) b.pixels[y * w + x] = img.getRGB(x, y) | 0xFF000000;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
