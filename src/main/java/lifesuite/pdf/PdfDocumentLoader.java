package lifesuite.pdf;

import javafx.concurrent.Task;
import javafx.scene.image.Image;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

// Liest ein PDF-Dokument über Apache PDFBox in einem Hintergrund-Thread ein.
// Für jede Seite wird ein Bild gerendert und es werden die Textbausteine samt Koordinaten
// ausgelesen. Das Ergebnis ist eine Liste von PdfPageData, aus der die Oberfläche die
// einzelnen Seitenansichten aufbaut. Die Klasse erweitert Task, damit der Ladevorgang ohne
// Blockieren des JavaFX-Anwendungsthreads abläuft und einen Fortschritt melden kann.
public class PdfDocumentLoader extends Task<List<PdfPageData>> {

    // Auflösung, mit der die Seitenbilder gerendert werden.
    private static final float RENDER_DPI = 144f;

    // Umrechnungsfaktor von PDF-Punkten (72 dpi) in Bildpixel der gerenderten Seite.
    private static final float SCALE = RENDER_DPI / 72f;

    private final File file;

    public PdfDocumentLoader(File file) {
        this.file = file;
    }

    // Der Ladevorgang läuft im Hintergrund-Thread und liefert die fertig verarbeiteten Seiten.
    @Override
    protected List<PdfPageData> call() throws Exception {
        List<PdfPageData> pages = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                if (isCancelled()) {
                    break;
                }
                updateMessage("Seite " + (pageIndex + 1) + " von " + pageCount + " wird geladen ...");
                BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, RENDER_DPI);
                Image image = toFxImage(rendered);
                List<TextAtom> atoms = extractAtoms(document, pageIndex);
                pages.add(new PdfPageData(image, atoms, rendered.getWidth(), rendered.getHeight()));
                updateProgress(pageIndex + 1, pageCount);
            }
        }
        return pages;
    }

    // Das von PDFBox gelieferte BufferedImage wird über einen PNG-Zwischenspeicher in ein
    // JavaFX-Bild umgewandelt. Dieser Weg vermeidet eine Abhängigkeit zu javafx-swing.
    private static Image toFxImage(BufferedImage bufferedImage) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "png", buffer);
        return new Image(new ByteArrayInputStream(buffer.toByteArray()));
    }

    // Die Textbausteine einer einzelnen Seite werden ausgelesen. Die von PDFBox in PDF-Punkten
    // gelieferten Koordinaten werden in Bildpixel der gerenderten Seite umgerechnet.
    private List<TextAtom> extractAtoms(PDDocument document, int pageIndex) throws Exception {
        List<TextAtom> atoms = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                for (TextPosition position : positions) {
                    double height = position.getHeightDir() * SCALE;
                    double x = position.getXDirAdj() * SCALE;
                    // PDFBox liefert die Grundlinie der Schrift; der obere Rand des Bausteins
                    // ergibt sich durch Abzug der Glyphenhöhe.
                    double y = position.getYDirAdj() * SCALE - height;
                    double width = position.getWidthDirAdj() * SCALE;
                    atoms.add(new TextAtom(position.getUnicode(), x, y, width, height));
                }
            }
        };
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        // Der Aufruf löst die Verarbeitung aus; der zurückgegebene Text wird nicht benötigt.
        stripper.getText(document);
        return atoms;
    }
}
