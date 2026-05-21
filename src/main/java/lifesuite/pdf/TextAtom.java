package lifesuite.pdf;

// Beschreibt einen einzelnen Textbaustein einer PDF-Seite.
// Die Koordinaten sind in Bildpixeln der gerenderten Seite angegeben, damit Textauswahl und
// Suchhervorhebung direkt auf den gezeichneten Ebenen arbeiten können.
public class TextAtom {

    private final String text;
    private final double x;
    private final double y;
    private final double width;
    private final double height;

    public TextAtom(String text, double x, double y, double width, double height) {
        this.text = text == null ? "" : text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public String getText() {
        return text;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    // Prüft, ob der Textbaustein innerhalb des angegebenen Rechtecks liegt.
    // Es genügt, dass der Mittelpunkt des Bausteins im Rechteck enthalten ist.
    public boolean isWithin(double rectX, double rectY, double rectWidth, double rectHeight) {
        double centerX = x + width / 2.0;
        double centerY = y + height / 2.0;
        return centerX >= rectX && centerX <= rectX + rectWidth
            && centerY >= rectY && centerY <= rectY + rectHeight;
    }
}
