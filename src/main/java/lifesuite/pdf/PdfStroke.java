package lifesuite.pdf;

import javafx.geometry.Point2D;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

// Beschreibt einen freihändig gezeichneten Strich auf einer PDF-Seite.
// Ein Strich besteht aus einer Folge von Punkten in Bildpixelkoordinaten sowie aus Farbe,
// Strichbreite und der Angabe, ob er als Textmarkierung (halbtransparent, Highlight-Ebene)
// oder als normaler Stiftstrich (deckend, Zeichen-Ebene) dargestellt wird.
public class PdfStroke {

    private final List<Point2D> points = new ArrayList<>();
    private final Color color;
    private final double width;
    private final boolean highlight;

    public PdfStroke(Color color, double width, boolean highlight) {
        this.color = color == null ? Color.BLACK : color;
        this.width = width;
        this.highlight = highlight;
    }

    // Ein weiterer Punkt wird an das Ende des Strichs angehängt.
    public void addPoint(double x, double y) {
        points.add(new Point2D(x, y));
    }

    public List<Point2D> getPoints() {
        return points;
    }

    public Color getColor() {
        return color;
    }

    public double getWidth() {
        return width;
    }

    public boolean isHighlight() {
        return highlight;
    }

    // Prüft, ob ein Punkt des Strichs näher als der angegebene Radius am Prüfpunkt liegt.
    // Dies wird vom Radierwerkzeug zur Treffererkennung verwendet.
    public boolean isNear(double px, double py, double radius) {
        for (Point2D point : points) {
            if (point.distance(px, py) <= radius) {
                return true;
            }
        }
        return false;
    }
}
