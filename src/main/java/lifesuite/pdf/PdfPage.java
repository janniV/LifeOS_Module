package lifesuite.pdf;

import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.List;

// Stellt eine einzelne PDF-Seite als Stapel exakt übereinanderliegender Ebenen dar.
// Der Aufbau folgt dem Ebenen-Konzept des PDF-Readers:
//   1. Bild-Ebene: das gerenderte Seitenbild als Basis.
//   2. Verdunkelungs-Ebene: ein Rechteck zum Abdunkeln der Seite im Dark-Modus.
//   3. Highlight-Ebene: ein Canvas für Textmarkierungen mit Multiplikations-Mischmodus,
//      damit der Text unter der Markierungsfarbe lesbar bleibt.
//   4. Such-Ebene: ein Canvas, der über die Suche gefundene Wörter hervorhebt.
//   5. Zeichen-Ebene: ein Canvas für freihändige Striche.
//   6. Event-Ebene: eine unsichtbare oberste Pane, die Mauseingaben verarbeitet und
//      interaktive Elemente wie verschiebbare Notizzettel aufnimmt.
public class PdfPage extends StackPane {

    private final PdfPageData data;

    private final Region dimLayer = new Region();
    private final Canvas highlightCanvas;
    private final Canvas searchCanvas;
    private final Canvas drawCanvas;
    private final Pane eventLayer = new Pane();

    // Striche der Highlight- und der Zeichen-Ebene werden getrennt gehalten, da sie auf
    // unterschiedlichen Canvas-Ebenen mit unterschiedlichem Mischmodus dargestellt werden.
    private final List<PdfStroke> highlightStrokes = new ArrayList<>();
    private final List<PdfStroke> penStrokes = new ArrayList<>();

    private PdfTool tool = PdfTool.HAND;
    private Color strokeColor = Color.web("#ffd54f");
    private double strokeWidth = 3.0;

    private PdfStroke activeStroke;
    private Rectangle selectionRectangle;
    private double selectionStartX;
    private double selectionStartY;
    private int stickyCount;

    public PdfPage(PdfPageData data) {
        this.data = data;
        double width = data.getWidth();
        double height = data.getHeight();

        ImageView imageLayer = new ImageView(data.getImage());
        imageLayer.setFitWidth(width);
        imageLayer.setFitHeight(height);

        dimLayer.setPrefSize(width, height);
        dimLayer.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
        dimLayer.setVisible(false);
        dimLayer.setMouseTransparent(true);

        highlightCanvas = new Canvas(width, height);
        // Der Multiplikations-Mischmodus hält den darunterliegenden Text lesbar.
        highlightCanvas.setBlendMode(BlendMode.MULTIPLY);
        highlightCanvas.setMouseTransparent(true);

        searchCanvas = new Canvas(width, height);
        searchCanvas.setMouseTransparent(true);

        drawCanvas = new Canvas(width, height);
        drawCanvas.setMouseTransparent(true);

        eventLayer.setPrefSize(width, height);
        eventLayer.setPickOnBounds(true);

        getStyleClass().add("pdf-page");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);
        getChildren().addAll(imageLayer, dimLayer, highlightCanvas, searchCanvas,
            drawCanvas, eventLayer);

        installEventHandlers();
    }

    // --- Werkzeug- und Darstellungssteuerung ---------------------------------------------------

    // Setzt das aktive Werkzeug für diese Seite.
    public void setTool(PdfTool tool) {
        this.tool = tool;
    }

    // Setzt Farbe und Breite für neue Striche.
    public void setStrokeStyle(Color color, double width) {
        this.strokeColor = color;
        this.strokeWidth = width;
    }

    // Schaltet die Verdunkelungs-Ebene für den Dark-Modus ein oder aus.
    public void setDarkMode(boolean dark) {
        dimLayer.setVisible(dark);
    }

    // --- Mauseingaben --------------------------------------------------------------------------

    // Verbindet die Event-Ebene mit den werkzeugabhängigen Mausaktionen.
    private void installEventHandlers() {
        eventLayer.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        eventLayer.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        eventLayer.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
    }

    // Beginnt je nach Werkzeug einen Strich, eine Auswahl oder eine Radieraktion.
    private void onPressed(MouseEvent event) {
        switch (tool) {
            case PEN:
                activeStroke = new PdfStroke(strokeColor, strokeWidth, false);
                activeStroke.addPoint(event.getX(), event.getY());
                penStrokes.add(activeStroke);
                break;
            case HIGHLIGHT:
                activeStroke = new PdfStroke(strokeColor, Math.max(strokeWidth, 14.0), true);
                activeStroke.addPoint(event.getX(), event.getY());
                highlightStrokes.add(activeStroke);
                break;
            case ERASER:
                eraseAt(event.getX(), event.getY());
                break;
            case SELECT:
                beginSelection(event.getX(), event.getY());
                break;
            default:
                break;
        }
    }

    // Setzt die laufende Aktion fort, während die Maus gezogen wird.
    private void onDragged(MouseEvent event) {
        switch (tool) {
            case PEN:
            case HIGHLIGHT:
                if (activeStroke != null) {
                    activeStroke.addPoint(event.getX(), event.getY());
                    redrawStrokes();
                }
                break;
            case ERASER:
                eraseAt(event.getX(), event.getY());
                break;
            case SELECT:
                updateSelection(event.getX(), event.getY());
                break;
            default:
                break;
        }
    }

    // Schliesst die laufende Aktion ab.
    private void onReleased(MouseEvent event) {
        if (tool == PdfTool.SELECT) {
            finishSelection();
        }
        activeStroke = null;
    }

    // --- Textauswahl ---------------------------------------------------------------------------

    // Beginnt das Aufziehen eines Auswahlrechtecks.
    private void beginSelection(double x, double y) {
        selectionStartX = x;
        selectionStartY = y;
        selectionRectangle = new Rectangle(x, y, 0, 0);
        selectionRectangle.setFill(Color.rgb(64, 132, 220, 0.25));
        selectionRectangle.setStroke(Color.rgb(64, 132, 220, 0.9));
        selectionRectangle.setMouseTransparent(true);
        eventLayer.getChildren().add(selectionRectangle);
    }

    // Passt das Auswahlrechteck an die aktuelle Mausposition an.
    private void updateSelection(double x, double y) {
        if (selectionRectangle == null) {
            return;
        }
        selectionRectangle.setX(Math.min(selectionStartX, x));
        selectionRectangle.setY(Math.min(selectionStartY, y));
        selectionRectangle.setWidth(Math.abs(x - selectionStartX));
        selectionRectangle.setHeight(Math.abs(y - selectionStartY));
    }

    // Ermittelt die Textbausteine innerhalb des Auswahlrechtecks, kopiert ihren Text in die
    // Zwischenablage und hebt sie auf der Such-Ebene hervor.
    private void finishSelection() {
        if (selectionRectangle == null) {
            return;
        }
        double rectX = selectionRectangle.getX();
        double rectY = selectionRectangle.getY();
        double rectWidth = selectionRectangle.getWidth();
        double rectHeight = selectionRectangle.getHeight();
        eventLayer.getChildren().remove(selectionRectangle);
        selectionRectangle = null;

        StringBuilder selectedText = new StringBuilder();
        GraphicsContext context = searchCanvas.getGraphicsContext2D();
        context.clearRect(0, 0, searchCanvas.getWidth(), searchCanvas.getHeight());
        context.setFill(Color.rgb(64, 132, 220, 0.35));
        for (TextAtom atom : data.getAtoms()) {
            if (atom.isWithin(rectX, rectY, rectWidth, rectHeight)) {
                context.fillRect(atom.getX(), atom.getY(), atom.getWidth(), atom.getHeight());
                selectedText.append(atom.getText());
            }
        }
        if (selectedText.length() > 0) {
            ClipboardContent content = new ClipboardContent();
            content.putString(selectedText.toString());
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    // --- Striche -------------------------------------------------------------------------------

    // Entfernt Striche, die näher als der Radierradius an der angegebenen Stelle liegen.
    private void eraseAt(double x, double y) {
        double radius = Math.max(strokeWidth, 12.0);
        boolean changed = penStrokes.removeIf(stroke -> stroke.isNear(x, y, radius));
        changed |= highlightStrokes.removeIf(stroke -> stroke.isNear(x, y, radius));
        if (changed) {
            redrawStrokes();
        }
    }

    // Zeichnet die Highlight- und die Zeichen-Ebene anhand der gespeicherten Striche neu.
    private void redrawStrokes() {
        drawStrokeLayer(highlightCanvas, highlightStrokes);
        drawStrokeLayer(drawCanvas, penStrokes);
    }

    // Zeichnet eine Strichliste auf den angegebenen Canvas.
    private void drawStrokeLayer(Canvas canvas, List<PdfStroke> strokes) {
        GraphicsContext context = canvas.getGraphicsContext2D();
        context.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        context.setLineCap(StrokeLineCap.ROUND);
        context.setLineJoin(StrokeLineJoin.ROUND);
        for (PdfStroke stroke : strokes) {
            List<Point2D> points = stroke.getPoints();
            if (points.isEmpty()) {
                continue;
            }
            context.setStroke(stroke.getColor());
            context.setLineWidth(stroke.getWidth());
            context.beginPath();
            context.moveTo(points.get(0).getX(), points.get(0).getY());
            for (int i = 1; i < points.size(); i++) {
                context.lineTo(points.get(i).getX(), points.get(i).getY());
            }
            context.stroke();
            if (points.size() == 1) {
                // Ein einzelner Punkt wird als kleiner Kreis dargestellt.
                double r = stroke.getWidth() / 2.0;
                context.setFill(stroke.getColor());
                context.fillOval(points.get(0).getX() - r, points.get(0).getY() - r,
                    stroke.getWidth(), stroke.getWidth());
            }
        }
    }

    // Nimmt einen vollständigen Strich entgegen und stellt ihn dar. Diese Methode wird für
    // die von einem externen Tablet empfangenen Striche verwendet.
    public void addExternalStroke(PdfStroke stroke) {
        if (stroke.isHighlight()) {
            highlightStrokes.add(stroke);
        } else {
            penStrokes.add(stroke);
        }
        redrawStrokes();
    }

    // --- Suche ---------------------------------------------------------------------------------

    // Hebt die übergebenen Treffer auf der Such-Ebene hervor.
    public void showSearchMatches(List<TextAtom> matches) {
        GraphicsContext context = searchCanvas.getGraphicsContext2D();
        context.clearRect(0, 0, searchCanvas.getWidth(), searchCanvas.getHeight());
        context.setFill(Color.rgb(255, 165, 0, 0.45));
        for (TextAtom atom : matches) {
            context.fillRect(atom.getX(), atom.getY(), atom.getWidth(), atom.getHeight());
        }
    }

    // Löscht alle Hervorhebungen der Such-Ebene.
    public void clearSearch() {
        searchCanvas.getGraphicsContext2D().clearRect(0, 0,
            searchCanvas.getWidth(), searchCanvas.getHeight());
    }

    // --- Notizzettel ---------------------------------------------------------------------------

    // Fügt der Event-Ebene einen verschiebbaren Notizzettel hinzu.
    public void addSticky() {
        VBox sticky = new VBox();
        sticky.setPrefSize(180, 150);
        sticky.setLayoutX(40 + (stickyCount % 5) * 24);
        sticky.setLayoutY(40 + (stickyCount % 5) * 24);
        sticky.setStyle("-fx-background-color: #ffe082; -fx-effect: dropshadow(gaussian,"
            + " rgba(0,0,0,0.35), 10, 0.2, 0, 4);");
        stickyCount++;

        Label handle = new Label("Notizzettel");
        handle.setMaxWidth(Double.MAX_VALUE);
        handle.setStyle("-fx-background-color: #ffc107; -fx-padding: 4 8 4 8;"
            + " -fx-font-weight: bold; -fx-cursor: move;");

        TextArea note = new TextArea();
        note.setWrapText(true);
        note.setStyle("-fx-control-inner-background: #fff3cd;");
        VBox.setVgrow(note, javafx.scene.layout.Priority.ALWAYS);

        // Der Notizzettel wird über seine Kopfzeile innerhalb der Event-Ebene verschoben.
        final double[] dragOffset = new double[2];
        handle.setOnMousePressed(event -> {
            dragOffset[0] = event.getSceneX() - sticky.getLayoutX();
            dragOffset[1] = event.getSceneY() - sticky.getLayoutY();
            event.consume();
        });
        handle.setOnMouseDragged(event -> {
            sticky.setLayoutX(event.getSceneX() - dragOffset[0]);
            sticky.setLayoutY(event.getSceneY() - dragOffset[1]);
            event.consume();
        });

        sticky.getChildren().addAll(handle, note);
        eventLayer.getChildren().add(sticky);
    }

    // --- Zugriff -------------------------------------------------------------------------------

    public PdfPageData getData() {
        return data;
    }
}
