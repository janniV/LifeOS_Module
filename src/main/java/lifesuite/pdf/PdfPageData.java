package lifesuite.pdf;

import javafx.scene.image.Image;

import java.util.List;

// Hält das Ergebnis der Hintergrundverarbeitung einer einzelnen PDF-Seite.
// Es umfasst das gerenderte Seitenbild, die ausgelesenen Textbausteine sowie die Bildmasse.
// Aus diesen Daten baut PdfPage anschließend den Ebenen-Stapel der Seite auf.
public class PdfPageData {

    private final Image image;
    private final List<TextAtom> atoms;
    private final double width;
    private final double height;

    public PdfPageData(Image image, List<TextAtom> atoms, double width, double height) {
        this.image = image;
        this.atoms = atoms;
        this.width = width;
        this.height = height;
    }

    public Image getImage() {
        return image;
    }

    public List<TextAtom> getAtoms() {
        return atoms;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }
}
