package android.content;
public class ClipboardManager {
    public ClipData clip;
    public void setPrimaryClip(ClipData data) { clip = data; }
    public ClipData getPrimaryClip() { return clip; }
}
