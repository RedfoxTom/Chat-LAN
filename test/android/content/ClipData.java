package android.content;
public class ClipData {
    public static class Item {
        public final String text;
        Item(String t) { text = t; }
        public String getText() { return text; }
    }
    public static class Description {
        public final String label;
        Description(String l) { label = l; }
        public String getLabel() { return label; }
    }
    public final Item item;
    public final Description description;
    private ClipData(Item i, Description d) { item = i; description = d; }
    public static ClipData newPlainText(String label, String text) {
        return new ClipData(new Item(text), new Description(label));
    }
    public Item getItemAt(int i) { return item; }
    public Description getDescription() { return description; }
}
