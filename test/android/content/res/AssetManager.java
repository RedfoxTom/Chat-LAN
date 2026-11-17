package android.content.res;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
/** Test-Version: liest Assets aus dem Ordner test/assets/. */
public class AssetManager {
    public InputStream open(String name) throws IOException {
        Path p = Paths.get(System.getProperty("owui.test.assets", "test"), "assets", name);
        return new ByteArrayInputStream(Files.readAllBytes(p));
    }
}
