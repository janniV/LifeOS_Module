package lifesuite.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// Hauptansicht des PDF-Readers von LifeSuite.
// Die Ansicht besteht aus einer Werkzeugleiste und einem scrollbaren Bereich, der die einzelnen
// Seiten als Ebenen-Stapel (PdfPage) untereinander darstellt. Das Laden eines Dokuments erfolgt
// über PdfDocumentLoader in einem Hintergrund-Thread. Zusätzlich nimmt die Ansicht über
// onExternalStrokeReceived Zeichendaten eines externen Geräts (z. B. eines Tablets) entgegen.
public class PdfReaderView extends BorderPane {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VBox pagesBox = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane(pagesBox);
    private final List<PdfPage> pages = new ArrayList<>();
    private final Label statusLabel = new Label("Kein Dokument geladen.");
    private final TextField searchField = new TextField();

    private PdfTool currentTool = PdfTool.HAND;
    private Color currentColor = Color.web("#ffd54f");
    private double currentWidth = 3.0;
    private boolean darkPages = false;

    // Trefferpositionen der letzten Suche für die schrittweise Navigation.
    private final List<double[]> searchPositions = new ArrayList<>();
    private int searchCursor = -1;

    public PdfReaderView() {
        getStyleClass().add("lifesuite-pdf");

        setTop(buildToolbar());

        pagesBox.setAlignment(Pos.TOP_CENTER);
        pagesBox.setPadding(new Insets(20));
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("lifesuite-pdf-scroll");
        setCenter(scrollPane);

        statusLabel.getStyleClass().add("lifesuite-pdf-status");
        statusLabel.setPadding(new Insets(4, 10, 4, 10));
        setBottom(statusLabel);
    }

    // --- Aufbau der Werkzeugleiste -------------------------------------------------------------

    // Baut die Werkzeugleiste mit Datei-, Werkzeug-, Such- und Darstellungssteuerung auf.
    private ToolBar buildToolbar() {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("lifesuite-pdf-toolbar");

        Button open = iconButton("fas-folder-open", "PDF öffnen", this::openDocument);

        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton hand = toolButton("fas-hand-paper", "Hand", PdfTool.HAND, toolGroup);
        ToggleButton select = toolButton("fas-i-cursor", "Textauswahl", PdfTool.SELECT, toolGroup);
        ToggleButton highlight = toolButton("fas-highlighter", "Textmarker", PdfTool.HIGHLIGHT, toolGroup);
        ToggleButton pen = toolButton("fas-pen", "Stift", PdfTool.PEN, toolGroup);
        ToggleButton eraser = toolButton("fas-eraser", "Radierer", PdfTool.ERASER, toolGroup);
        hand.setSelected(true);

        ColorPicker colorPicker = new ColorPicker(currentColor);
        colorPicker.setTooltip(new Tooltip("Stift- und Markerfarbe"));
        colorPicker.setOnAction(e -> {
            currentColor = colorPicker.getValue();
            applyStrokeStyleToPages();
        });

        ComboBox<Double> widthCombo = new ComboBox<>();
        widthCombo.getItems().addAll(2.0, 3.0, 5.0, 8.0, 12.0);
        widthCombo.setValue(currentWidth);
        widthCombo.setTooltip(new Tooltip("Strichbreite"));
        widthCombo.setOnAction(e -> {
            if (widthCombo.getValue() != null) {
                currentWidth = widthCombo.getValue();
                applyStrokeStyleToPages();
            }
        });

        Button sticky = iconButton("fas-sticky-note", "Notizzettel hinzufügen", this::addStickyToVisiblePage);

        searchField.setPromptText("Im Dokument suchen ...");
        searchField.setPrefWidth(180);
        searchField.setOnAction(e -> runSearch());
        Button searchButton = iconButton("fas-search", "Suchen", this::runSearch);
        Button searchNext = iconButton("fas-arrow-down", "Nächster Treffer", this::jumpToNextMatch);

        ToggleButton darkMode = new ToggleButton();
        darkMode.setGraphic(icon("fas-moon"));
        darkMode.setTooltip(new Tooltip("Seiten abdunkeln"));
        darkMode.setOnAction(e -> {
            darkPages = darkMode.isSelected();
            for (PdfPage page : pages) {
                page.setDarkMode(darkPages);
            }
        });

        toolBar.getItems().addAll(open, new Separator(),
            hand, select, highlight, pen, eraser, new Separator(),
            colorPicker, widthCombo, sticky, new Separator(),
            searchField, searchButton, searchNext, new Separator(), darkMode);
        return toolBar;
    }

    // Erzeugt eine Werkzeug-Umschaltfläche und verknüpft sie mit der Werkzeugauswahl.
    private ToggleButton toolButton(String iconCode, String tooltip, PdfTool tool, ToggleGroup group) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(icon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.setToggleGroup(group);
        button.setOnAction(e -> {
            currentTool = tool;
            for (PdfPage page : pages) {
                page.setTool(tool);
            }
        });
        return button;
    }

    // --- Laden eines Dokuments -----------------------------------------------------------------

    // Öffnet einen Dateidialog und startet das Laden des gewählten PDF-Dokuments.
    private void openDocument() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("PDF-Dokument öffnen");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File file = (getScene() != null && getScene().getWindow() != null)
            ? chooser.showOpenDialog(getScene().getWindow())
            : null;
        if (file != null) {
            loadDocument(file);
        }
    }

    // Startet den Hintergrund-Ladevorgang für die angegebene Datei.
    public void loadDocument(File file) {
        PdfDocumentLoader loader = new PdfDocumentLoader(file);
        statusLabel.textProperty().bind(loader.messageProperty());

        loader.stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                statusLabel.textProperty().unbind();
                showPages(loader.getValue());
                statusLabel.setText(loader.getValue().size() + " Seite(n) geladen.");
            } else if (newState == Worker.State.FAILED) {
                statusLabel.textProperty().unbind();
                statusLabel.setText("Das Dokument konnte nicht geladen werden.");
            }
        });

        Thread thread = new Thread(loader, "lifesuite-pdf-loader");
        thread.setDaemon(true);
        thread.start();
    }

    // Baut aus den geladenen Seitendaten die Seitenansichten auf.
    private void showPages(List<PdfPageData> pageDataList) {
        pages.clear();
        pagesBox.getChildren().clear();
        searchPositions.clear();
        searchCursor = -1;
        for (PdfPageData data : pageDataList) {
            PdfPage page = new PdfPage(data);
            page.setTool(currentTool);
            page.setStrokeStyle(currentColor, currentWidth);
            page.setDarkMode(darkPages);
            pages.add(page);
            pagesBox.getChildren().add(page);
        }
    }

    // --- Suche ---------------------------------------------------------------------------------

    // Durchsucht alle Seiten nach dem eingegebenen Begriff und hebt die Treffer hervor.
    private void runSearch() {
        String query = searchField.getText();
        searchPositions.clear();
        searchCursor = -1;
        if (query == null || query.isBlank()) {
            for (PdfPage page : pages) {
                page.clearSearch();
            }
            return;
        }
        int total = 0;
        for (PdfPage page : pages) {
            List<TextAtom> matches = findMatches(page, query.toLowerCase());
            page.showSearchMatches(matches);
            if (!matches.isEmpty()) {
                searchPositions.add(new double[] {
                    page.getBoundsInParent().getMinY(), matches.get(0).getY() });
                total += countMatchGroups(page, query.toLowerCase());
            }
        }
        statusLabel.setText(total + " Treffer für \"" + query + "\".");
        if (!searchPositions.isEmpty()) {
            jumpToNextMatch();
        }
    }

    // Ermittelt alle Textbausteine einer Seite, die zu einem Treffer des Suchbegriffs gehören.
    private List<TextAtom> findMatches(PdfPage page, String lowerQuery) {
        List<TextAtom> atoms = page.getData().getAtoms();
        StringBuilder pageText = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (TextAtom atom : atoms) {
            int start = pageText.length();
            pageText.append(atom.getText().toLowerCase());
            ranges.add(new int[] { start, pageText.length() });
        }
        List<TextAtom> result = new ArrayList<>();
        String text = pageText.toString();
        int from = 0;
        int hit;
        while ((hit = text.indexOf(lowerQuery, from)) >= 0) {
            int end = hit + lowerQuery.length();
            for (int i = 0; i < atoms.size(); i++) {
                int[] range = ranges.get(i);
                if (range[0] < end && range[1] > hit) {
                    result.add(atoms.get(i));
                }
            }
            from = hit + Math.max(1, lowerQuery.length());
        }
        return result;
    }

    // Zählt die Anzahl der Trefferstellen einer Seite.
    private int countMatchGroups(PdfPage page, String lowerQuery) {
        StringBuilder pageText = new StringBuilder();
        for (TextAtom atom : page.getData().getAtoms()) {
            pageText.append(atom.getText().toLowerCase());
        }
        String text = pageText.toString();
        int count = 0;
        int from = 0;
        int hit;
        while ((hit = text.indexOf(lowerQuery, from)) >= 0) {
            count++;
            from = hit + Math.max(1, lowerQuery.length());
        }
        return count;
    }

    // Scrollt zur nächsten Trefferstelle.
    private void jumpToNextMatch() {
        if (searchPositions.isEmpty()) {
            return;
        }
        searchCursor = (searchCursor + 1) % searchPositions.size();
        double[] position = searchPositions.get(searchCursor);
        scrollToContentY(position[0] + position[1]);
    }

    // --- Externe Zeichendaten ------------------------------------------------------------------

    // Empfängt Zeichendaten eines externen Geräts im JSON-Format und trägt sie in die
    // Zeichen-Ebene der betreffenden Seite ein. Erwartetes Format:
    // {"page":0,"color":"#ff0000","width":2.5,"highlight":false,
    //  "points":[{"x":10,"y":20,"pressure":0.8}, ...]}
    public void onExternalStrokeReceived(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            int pageIndex = root.path("page").asInt(0);
            if (pageIndex < 0 || pageIndex >= pages.size()) {
                return;
            }
            Color color = Color.web(root.path("color").asText("#ff3030"));
            double width = root.path("width").asDouble(2.5);
            boolean highlight = root.path("highlight").asBoolean(false);

            PdfStroke stroke = new PdfStroke(color, width, highlight);
            for (JsonNode point : root.path("points")) {
                double x = point.path("x").asDouble();
                double y = point.path("y").asDouble();
                stroke.addPoint(x, y);
            }
            PdfPage targetPage = pages.get(pageIndex);
            Platform.runLater(() -> targetPage.addExternalStroke(stroke));
        } catch (Exception e) {
            System.err.println("Externe Zeichendaten konnten nicht verarbeitet werden: "
                + e.getMessage());
        }
    }

    // --- Theme -------------------------------------------------------------------------------

    // Übernimmt einen Theme-Zustand für Classic-/Modern-Design und Light-/Dark-Modus.
    public void applyTheme(boolean modern, boolean dark, double fontScale) {
        if (dark) {
            if (!getStyleClass().contains("dark")) {
                getStyleClass().add("dark");
            }
        } else {
            getStyleClass().remove("dark");
        }
        String accent = modern ? (dark ? "#e07840" : "#c75f29") : (dark ? "#4db6ac" : "#2d8a82");
        String bg = dark ? "#1a1714" : "#e9e5dd";
        String subtle = dark ? "#212019" : "#f1ebe1";
        String fg = dark ? "#e8e0d4" : "#2a2520";
        setStyle("-color-accent-emphasis:" + accent + "; -color-bg-default:" + bg
            + "; -color-bg-subtle:" + subtle + "; -color-fg-default:" + fg
            + "; -fx-font-size:" + (13.0 * Math.max(0.5, fontScale)) + "px;");
    }

    // --- Hilfsmethoden -------------------------------------------------------------------------

    // Gibt die Strichfarbe und -breite an alle Seiten weiter.
    private void applyStrokeStyleToPages() {
        for (PdfPage page : pages) {
            page.setStrokeStyle(currentColor, currentWidth);
        }
    }

    // Fügt der im Sichtbereich liegenden Seite einen Notizzettel hinzu.
    private void addStickyToVisiblePage() {
        PdfPage page = findVisiblePage();
        if (page != null) {
            page.addSticky();
        }
    }

    // Ermittelt die Seite, die dem Mittelpunkt des Sichtbereichs am nächsten liegt.
    private PdfPage findVisiblePage() {
        if (pages.isEmpty()) {
            return null;
        }
        double contentHeight = pagesBox.getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double centerY = scrollPane.getVvalue() * Math.max(0, contentHeight - viewportHeight)
            + viewportHeight / 2.0;
        for (PdfPage page : pages) {
            if (page.getBoundsInParent().getMinY() <= centerY
                && page.getBoundsInParent().getMaxY() >= centerY) {
                return page;
            }
        }
        return pages.get(0);
    }

    // Scrollt den Sichtbereich so, dass die angegebene Inhaltshöhe sichtbar wird.
    private void scrollToContentY(double contentY) {
        double contentHeight = pagesBox.getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double scrollable = Math.max(1, contentHeight - viewportHeight);
        double value = Math.max(0, Math.min(1, (contentY - viewportHeight / 2.0) / scrollable));
        scrollPane.setVvalue(value);
    }

    // Erzeugt eine Schaltfläche mit Symbol und Tooltip.
    private Button iconButton(String iconCode, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    // Erzeugt ein Symbol; schlägt die Symbolerzeugung fehl, wird ein Textsymbol genutzt.
    private Node icon(String iconCode) {
        try {
            return new FontIcon(iconCode);
        } catch (Exception e) {
            return new Label("?");
        }
    }
}
