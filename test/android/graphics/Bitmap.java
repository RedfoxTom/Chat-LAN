package android.graphics;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
/** Test-Bitmap: Pixel in int[], Komprimierung über ImageIO. */
public class Bitmap {
    public enum Config { ARGB_8888, RGB_565, ALPHA_8 }
    public enum CompressFormat { JPEG, PNG, WEBP }
    public final int width, height;
    public final int[] pixels;
    public boolean recycled;

    public Bitmap(int w, int h) { width = w; height = h; pixels = new int[w * h]; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getByteCount() { return pixels.length * 4; }
    public void recycle() { recycled = true; }

    public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w, h); }

    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        Bitmap out = new Bitmap(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = Math.min(src.width - 1, (int) ((double) x / w * src.width));
                int sy = Math.min(src.height - 1, (int) ((double) y / h * src.height));
                out.pixels[y * w + x] = src.pixels[sy * src.width + sx];
            }
        }
        return out;
    }

    public boolean compress(CompressFormat format, int quality, OutputStream out) {
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) img.setRGB(x, y, pixels[y * width + x] & 0xFFFFFF);
            }
            String type = format == CompressFormat.PNG ? "png" : "jpg";
            return ImageIO.write(img, type, out);
        } catch (Exception e) {
            return false;
        }
    }
}
