package lifesuite;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import netscape.javascript.JSObject;

import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

// Hauptansicht des LifeSuite-Text-Editors. Die Ansicht ist als StackPane aufgebaut, damit der
// Zen-, Buch- und Präsentationsmodus eine ablenkungsfreie Oberfläche über dem Grundlayout
// einblenden können. Das Grundlayout ist ein BorderPane mit scrollbarer Werkzeugleiste oben,
// optionalem Inhaltsverzeichnis links und einem geteilten Arbeitsbereich aus Markdown-Eingabe
// und HTML-Vorschau in der Mitte.
public class EditorView extends StackPane {

    // Die unterstützten Ansichtsmodi des Editors.
    public enum ViewMode { CODE, SPLIT, PREVIEW, ZEN, BOOK, PRESENTATION }

    // Zeitabstand der automatischen Speicherung.
    private static final Duration AUTOSAVE_INTERVAL = Duration.seconds(10);

    // Zeitabstand der Backup-Historie.
    private static final Duration BACKUP_INTERVAL = Duration.minutes(10);

    // Höchstzahl aufbewahrter Sicherungen.
    private static final int MAX_BACKUPS = 50;

    private final BorderPane layout = new BorderPane();
    private final TextArea codeArea = new TextArea();
    private final WebView webView = new WebView();
    private final WebEngine webEngine = webView.getEngine();
    private final SplitPane splitPane = new SplitPane();
    private final ScrollPane bookScroll = new ScrollPane();
    private final ScrollPane toolbarScroll = new ScrollPane();
    private final VBox tocPanel = new VBox();
    private final ListView<MarkdownRenderer.Heading> tocList = new ListView<>();
    private final Button exitOverlay = new Button("Modus beenden");

    private final EditorDocument document;
    private final Path storagePath;
    private final EditorBridge bridge;

    private ViewMode mode = ViewMode.SPLIT;
    private boolean darkMode = false;
    private boolean modernDesign = true;
    private boolean tocVisible = false;

    private final PauseTransition renderDebounce = new PauseTransition(Duration.millis(320));
    private Timeline autosaveTimer;
    private Timeline backupTimer;
    private String lastBackupContent = "";
    private boolean updatingFromDocument = false;

    // Steuerelemente, deren Zustand bei einem Dokumentwechsel angeglichen werden muss.
    private ComboBox<String> fontCombo;
    private ComboBox<Integer> sizeCombo;
    private ColorPicker colorPicker;
    private ToggleButton hyphenButton;
    private ToggleButton alignLeft;
    private ToggleButton alignCenter;
    private ToggleButton alignRight;
    private ToggleButton alignJustify;
    private ToggleButton tocButton;
    private final ToggleGroup modeGroup = new ToggleGroup();

    // Erzeugt den Editor mit einem Speicherpfad für Dokument und Backup-Historie.
    public EditorView(Path storagePath) {
        this.storagePath = storagePath;
        this.document = EditorDocument.load(documentFile());
        this.lastBackupContent = document.getContent();
        this.bridge = new EditorBridge(this::onContentHeightReported);

        getStyleClass().add("lifesuite-editor");

        buildToolbar();
        buildCenter();
        buildTocPanel();
        buildExitOverlay();

        layout.setTop(toolbarScroll);
        getChildren().addAll(layout, exitOverlay);

        codeArea.setText(document.getContent());
        syncControlsFromDocument();
        installListeners();
        installPreviewBridge();

        applyMode(ViewMode.SPLIT);
        renderPreview();
        startTimers();

        installModuleStylesheet();
    }

    // Bindet das modul-eigene Stylesheet ein, sofern es im Klassenpfad gefunden wird.
    private void installModuleStylesheet() {
        var resource = getClass().getResource("/lifesuite/lifesuite.css");
        if (resource != null) {
            getStylesheets().add(resource.toExternalForm());
        }
    }

    // --- Aufbau der Werkzeugleiste -------------------------------------------------------------

    // Baut die scrollbare Werkzeugleiste mit allen Formatierungs- und Ansichtssteuerungen auf.
    private void buildToolbar() {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("lifesuite-toolbar");

        toolBar.getItems().addAll(buildModeButtons());
        toolBar.getItems().add(new Separator());
        toolBar.getItems().addAll(buildFontControls());
        toolBar.getItems().add(new Separator());
        toolBar.getItems().addAll(buildAlignmentButtons());
        toolBar.getItems().add(new Separator());
        toolBar.getItems().addAll(buildInlineFormatButtons());
        toolBar.getItems().add(new Separator());
        toolBar.getItems().add(buildInsertMenu());
        toolBar.getItems().add(buildPageLayoutMenu());
        toolBar.getItems().add(new Separator());
        toolBar.getItems().addAll(buildUtilityButtons());

        toolbarScroll.setContent(toolBar);
        toolbarScroll.setFitToHeight(true);
        toolbarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        toolbarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        toolbarScroll.getStyleClass().add("lifesuite-toolbar-scroll");
    }

    // Erzeugt die Umschaltflächen für die sechs Ansichtsmodi.
    private List<Node> buildModeButtons() {
        ToggleButton code = modeButton("fas-code", "Code-Ansicht", ViewMode.CODE);
        ToggleButton split = modeButton("fas-columns", "Geteilte Ansicht", ViewMode.SPLIT);
        ToggleButton preview = modeButton("fas-eye", "Vorschau", ViewMode.PREVIEW);
        ToggleButton zen = modeButton("fas-moon", "Zen-Modus", ViewMode.ZEN);
        ToggleButton book = modeButton("fas-book", "Buch-Modus", ViewMode.BOOK);
        ToggleButton presentation = modeButton("fas-desktop", "Präsentation", ViewMode.PRESENTATION);
        split.setSelected(true);
        return List.of(code, split, preview, zen, book, presentation);
    }

    // Erzeugt eine einzelne Modus-Umschaltfläche und verknüpft sie mit dem Moduswechsel.
    private ToggleButton modeButton(String iconCode, String tooltip, ViewMode targetMode) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(icon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.setToggleGroup(modeGroup);
        button.setUserData(targetMode);
        button.setOnAction(e -> applyMode(targetMode));
        return button;
    }

    // Erzeugt die Steuerelemente für Schriftart, Schriftgröße und Textfarbe.
    private List<Node> buildFontControls() {
        fontCombo = new ComboBox<>();
        fontCombo.getItems().addAll(javafx.scene.text.Font.getFamilies());
        fontCombo.setTooltip(new Tooltip("Schriftart"));
        fontCombo.setPrefWidth(150);
        fontCombo.setOnAction(e -> {
            if (!updatingFromDocument && fontCombo.getValue() != null) {
                document.setFontFamily(fontCombo.getValue());
                renderPreview();
            }
        });

        sizeCombo = new ComboBox<>();
        for (int s = 8; s <= 48; s += 2) {
            sizeCombo.getItems().add(s);
        }
        sizeCombo.setEditable(true);
        sizeCombo.setTooltip(new Tooltip("Schriftgröße in Punkt"));
        sizeCombo.setPrefWidth(80);
        sizeCombo.setOnAction(e -> {
            if (updatingFromDocument) {
                return;
            }
            Integer value = parseSize(sizeCombo.getEditor().getText());
            if (value != null) {
                document.setFontSizePt(value);
                renderPreview();
            }
        });

        colorPicker = new ColorPicker(Color.web("#222222"));
        colorPicker.setTooltip(new Tooltip("Textfarbe"));
        colorPicker.setOnAction(e -> {
            if (!updatingFromDocument) {
                document.setTextColor(toHex(colorPicker.getValue()));
                renderPreview();
            }
        });

        return List.of(fontCombo, sizeCombo, colorPicker);
    }

    // Erzeugt die Umschaltflächen für die vier Textausrichtungen.
    private List<Node> buildAlignmentButtons() {
        ToggleGroup alignGroup = new ToggleGroup();
        alignLeft = alignmentButton("fas-align-left", "Linksbündig", "left", alignGroup);
        alignCenter = alignmentButton("fas-align-center", "Zentriert", "center", alignGroup);
        alignRight = alignmentButton("fas-align-right", "Rechtsbündig", "right", alignGroup);
        alignJustify = alignmentButton("fas-align-justify", "Blocksatz", "justify", alignGroup);
        return List.of(alignLeft, alignCenter, alignRight, alignJustify);
    }

    // Erzeugt eine einzelne Ausrichtungsfläche.
    private ToggleButton alignmentButton(String iconCode, String tooltip,
                                         String alignmentValue, ToggleGroup group) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(icon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.setToggleGroup(group);
        button.setOnAction(e -> {
            if (!updatingFromDocument) {
                document.setAlignment(alignmentValue);
                renderPreview();
            }
        });
        return button;
    }

    // Erzeugt die Flächen für fett, kursiv und unterstrichen sowie die Silbentrennung.
    private List<Node> buildInlineFormatButtons() {
        Button bold = iconButton("fas-bold", "Fett", () -> wrapSelection("**", "**"));
        Button italic = iconButton("fas-italic", "Kursiv", () -> wrapSelection("*", "*"));
        Button underline = iconButton("fas-underline", "Unterstrichen", () -> wrapSelection("++", "++"));

        hyphenButton = new ToggleButton();
        hyphenButton.setGraphic(icon("fas-text-width"));
        hyphenButton.setTooltip(new Tooltip("Silbentrennung"));
        hyphenButton.setOnAction(e -> {
            if (!updatingFromDocument) {
                document.setHyphenation(hyphenButton.isSelected());
                renderPreview();
            }
        });

        return List.of(bold, italic, underline, hyphenButton);
    }

    // Baut das Aufklappmenü zum Einfügen von Bildern, Tabellen, Code, Zitaten, Stickies und Formeln.
    private MenuButton buildInsertMenu() {
        MenuButton insert = new MenuButton();
        insert.setGraphic(icon("fas-plus"));
        insert.setTooltip(new Tooltip("Element einfügen"));

        MenuItem image = new MenuItem("Bild");
        image.setOnAction(e -> insertImage());
        MenuItem table = new MenuItem("Tabelle");
        table.setOnAction(e -> insertBlock(
            "\n| Spalte A | Spalte B |\n| --- | --- |\n| Wert 1 | Wert 2 |\n"));
        MenuItem codeBlock = new MenuItem("Code-Block");
        codeBlock.setOnAction(e -> insertBlock("\n```\nCode hier\n```\n"));
        MenuItem quote = new MenuItem("Zitat");
        quote.setOnAction(e -> insertBlock("\n> Zitat\n"));
        MenuItem sticky = new MenuItem("Notizzettel");
        sticky.setOnAction(e -> insertBlock("\n:::sticky\nNeue Notiz\n:::\n"));
        MenuItem formula = new MenuItem("Formel");
        formula.setOnAction(e -> insertBlock("$$ a^2 + b^2 = c^2 $$"));

        insert.getItems().addAll(image, table, codeBlock, quote, sticky,
            new SeparatorMenuItem(), formula);
        return insert;
    }

    // Baut das Aufklappmenü für Papierformat und Seitenränder.
    private MenuButton buildPageLayoutMenu() {
        MenuButton layoutMenu = new MenuButton();
        layoutMenu.setGraphic(icon("fas-file-alt"));
        layoutMenu.setTooltip(new Tooltip("Seitenlayout"));

        ToggleGroup formatGroup = new ToggleGroup();
        layoutMenu.getItems().addAll(
            pageFormatItem("A4", formatGroup),
            pageFormatItem("A3", formatGroup),
            pageFormatItem("Letter", formatGroup),
            new SeparatorMenuItem());

        ToggleGroup marginGroup = new ToggleGroup();
        layoutMenu.getItems().addAll(
            marginItem("Schmale Ränder", 12.0, marginGroup),
            marginItem("Normale Ränder", 25.0, marginGroup),
            marginItem("Breite Ränder", 38.0, marginGroup));
        return layoutMenu;
    }

    // Erzeugt einen Menüeintrag für ein Papierformat.
    private RadioMenuItem pageFormatItem(String format, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(format);
        item.setToggleGroup(group);
        item.setSelected(document.getPageFormat().equals(format));
        item.setOnAction(e -> {
            document.setPageFormat(format);
            renderPreview();
        });
        return item;
    }

    // Erzeugt einen Menüeintrag für eine Randbreite.
    private RadioMenuItem marginItem(String label, double marginMm, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(label);
        item.setToggleGroup(group);
        item.setSelected(Math.abs(document.getMarginMm() - marginMm) < 0.5);
        item.setOnAction(e -> {
            document.setMarginMm(marginMm);
            renderPreview();
        });
        return item;
    }

    // Erzeugt die Flächen für Inhaltsverzeichnis und PDF-Export.
    private List<Node> buildUtilityButtons() {
        tocButton = new ToggleButton();
        tocButton.setGraphic(icon("fas-list-ul"));
        tocButton.setTooltip(new Tooltip("Inhaltsverzeichnis"));
        tocButton.setOnAction(e -> setTocVisible(tocButton.isSelected()));

        Button export = iconButton("fas-file-pdf", "Als PDF exportieren", this::exportPdf);
        return List.of(tocButton, export);
    }

    // --- Aufbau des Arbeitsbereichs ------------------------------------------------------------

    // Baut den geteilten Arbeitsbereich aus Markdown-Eingabe und HTML-Vorschau.
    private void buildCenter() {
        codeArea.setWrapText(true);
        codeArea.getStyleClass().add("lifesuite-code");
        codeArea.setPromptText("Schreibe deinen Text im Markdown-Format ...");

        bookScroll.setFitToWidth(true);
        bookScroll.getStyleClass().add("lifesuite-book-scroll");

        splitPane.setDividerPositions(0.5);
    }

    // Baut das ein- und ausblendbare Inhaltsverzeichnis am linken Rand.
    private void buildTocPanel() {
        Label title = new Label("Inhalt");
        title.getStyleClass().add("lifesuite-toc-title");

        tocList.getStyleClass().add("lifesuite-toc-list");
        tocList.setOnMouseClicked(e -> jumpToHeading(tocList.getSelectionModel().getSelectedItem()));
        VBox.setVgrow(tocList, Priority.ALWAYS);

        tocPanel.getChildren().addAll(title, tocList);
        tocPanel.getStyleClass().add("lifesuite-toc");
        tocPanel.setPrefWidth(240);
        tocPanel.setPadding(new Insets(8));
    }

    // Baut die Schaltfläche, mit der ablenkungsfreie Modi wieder verlassen werden.
    private void buildExitOverlay() {
        exitOverlay.getStyleClass().add("lifesuite-exit-overlay");
        exitOverlay.setVisible(false);
        exitOverlay.setManaged(false);
        exitOverlay.setOnAction(e -> applyMode(ViewMode.SPLIT));
        StackPane.setAlignment(exitOverlay, Pos.TOP_RIGHT);
        StackPane.setMargin(exitOverlay, new Insets(14));
    }

    // --- Ereignisbehandlung --------------------------------------------------------------------

    // Verbindet Texteingaben, verzögerte Vorschau-Erneürung und Tastaturkürzel.
    private void installListeners() {
        renderDebounce.setOnFinished(e -> renderPreview());

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            document.setContent(newText);
            renderDebounce.playFromStart();
            updateTableOfContents();
        });

        // Die Escape-Taste verlässt die ablenkungsfreien Modi.
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE
                && (mode == ViewMode.ZEN || mode == ViewMode.BOOK || mode == ViewMode.PRESENTATION)) {
                applyMode(ViewMode.SPLIT);
                event.consume();
            }
        });
    }

    // Stellt nach dem Laden der Vorschau die JavaBridge im WebView-Fenster bereit.
    private void installPreviewBridge() {
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) webEngine.executeScript("window");
                    window.setMember("javaBridge", bridge);
                } catch (Exception e) {
                    System.err.println("LifeSuite-Brücke konnte nicht gesetzt werden: " + e.getMessage());
                }
            }
        });
    }

    // Nimmt die vom Vorschau-Skript gemeldete Inhaltshöhe entgegen. Im Buch-Modus wird die
    // Vorschaufläche damit nahtlos an die Inhaltshöhe angepasst.
    private void onContentHeightReported(double height) {
        Platform.runLater(() -> {
            if (mode == ViewMode.BOOK) {
                webView.setPrefHeight(height + 40);
                webView.setMinHeight(height + 40);
            }
        });
    }

    // --- Modus- und Ansichtssteuerung ----------------------------------------------------------

    // Wechselt den Ansichtsmodus und baut den Arbeitsbereich entsprechend um.
    public void applyMode(ViewMode targetMode) {
        this.mode = targetMode;

        // Die Eingabe- und Vorschaukomponenten werden zuerst von allen Eltern gelöst.
        splitPane.getItems().clear();
        bookScroll.setContent(null);
        webView.setMinHeight(Region.USE_COMPUTED_SIZE);
        webView.setPrefHeight(Region.USE_COMPUTED_SIZE);

        boolean distractionFree = targetMode == ViewMode.ZEN
            || targetMode == ViewMode.BOOK
            || targetMode == ViewMode.PRESENTATION;
        layout.setTop(distractionFree ? null : toolbarScroll);
        layout.setLeft((tocVisible && !distractionFree) ? tocPanel : null);
        exitOverlay.setVisible(distractionFree);
        exitOverlay.setManaged(distractionFree);

        switch (targetMode) {
            case CODE:
            case ZEN:
                layout.setCenter(codeArea);
                break;
            case PREVIEW:
            case PRESENTATION:
                layout.setCenter(webView);
                break;
            case BOOK:
                bookScroll.setContent(webView);
                layout.setCenter(bookScroll);
                break;
            case SPLIT:
            default:
                splitPane.getItems().addAll(codeArea, webView);
                splitPane.setDividerPositions(0.5);
                layout.setCenter(splitPane);
                break;
        }

        // Die Modusfläche in der Werkzeugleiste wird mit dem aktiven Modus abgeglichen.
        for (var toggle : modeGroup.getToggles()) {
            if (toggle.getUserData() == targetMode) {
                toggle.setSelected(true);
            }
        }
        renderPreview();
    }

    // Schaltet das Inhaltsverzeichnis ein oder aus.
    private void setTocVisible(boolean visible) {
        this.tocVisible = visible;
        if (tocButton != null) {
            tocButton.setSelected(visible);
        }
        boolean distractionFree = mode == ViewMode.ZEN
            || mode == ViewMode.BOOK
            || mode == ViewMode.PRESENTATION;
        layout.setLeft((visible && !distractionFree) ? tocPanel : null);
        if (visible) {
            updateTableOfContents();
        }
    }

    // --- Vorschau und Inhaltsverzeichnis -------------------------------------------------------

    // Erzeugt die HTML-Vorschau aus dem aktuellen Dokument und lädt sie in die WebView.
    private void renderPreview() {
        String html = MarkdownRenderer.buildPreviewDocument(
            document, darkMode, mode == ViewMode.PRESENTATION);
        webEngine.loadContent(html);
        updateTableOfContents();
    }

    // Liest die Überschriften aus dem Dokument und befüllt das Inhaltsverzeichnis.
    private void updateTableOfContents() {
        List<MarkdownRenderer.Heading> headings =
            MarkdownRenderer.extractHeadings(document.getContent());
        tocList.getItems().setAll(headings);
    }

    // Springt zur ausgewählten Überschrift. Die Markdown-Eingabe setzt den Cursor an die
    // Überschriftenzeile, die Vorschau scrollt zum zugehörigen Anker.
    private void jumpToHeading(MarkdownRenderer.Heading heading) {
        if (heading == null) {
            return;
        }
        int offset = offsetOfLine(heading.line);
        codeArea.positionCaret(offset);
        codeArea.requestFocus();
        try {
            webEngine.executeScript(
                "var el=document.getElementById('" + heading.id + "');"
                + "if(el)el.scrollIntoView({behavior:'smooth'});");
        } catch (Exception ignored) {
            // Ein fehlgeschlagener Sprung in der Vorschau bleibt ohne Auswirkung auf die Eingabe.
        }
    }

    // Berechnet den Zeichenversatz des Zeilenanfangs der angegebenen Zeile.
    private int offsetOfLine(int lineIndex) {
        String[] lines = codeArea.getText().split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lineIndex && i < lines.length; i++) {
            offset += lines[i].length() + 1;
        }
        return offset;
    }

    // --- Einfüge- und Formatierungsaktionen ---------------------------------------------------

    // Umschließt die aktuelle Auswahl mit den übergebenen Markierungszeichen.
    private void wrapSelection(String left, String right) {
        String selected = codeArea.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            int pos = codeArea.getCaretPosition();
            codeArea.insertText(pos, left + right);
            codeArea.positionCaret(pos + left.length());
        } else {
            codeArea.replaceSelection(left + selected + right);
        }
        codeArea.requestFocus();
    }

    // Fügt einen Textbaustein an der aktuellen Cursorposition ein.
    private void insertBlock(String text) {
        codeArea.insertText(codeArea.getCaretPosition(), text);
        codeArea.requestFocus();
    }

    // Öffnet einen Dateidialog zur Bildauswahl und fügt einen passenden Markdown-Verweis ein.
    private void insertImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Bild auswählen");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Bilder", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        File file = (getScene() != null && getScene().getWindow() != null)
            ? chooser.showOpenDialog(getScene().getWindow())
            : null;
        if (file != null) {
            insertBlock("\n![Bild](" + file.toURI() + ")\n");
        } else {
            insertBlock("\n![Bildbeschreibung](https://)\n");
        }
    }

    // Druckt die HTML-Vorschau über einen Druckdialog. Wird dort ein PDF-Drucker gewählt,
    // entsteht ein PDF-Export des Dokuments.
    private void exportPdf() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            return;
        }
        boolean proceed = (getScene() != null && getScene().getWindow() != null)
            ? job.showPrintDialog(getScene().getWindow())
            : job.showPrintDialog(null);
        if (proceed) {
            webEngine.print(job);
            job.endJob();
        }
    }

    // --- Theme-Anbindung -----------------------------------------------------------------------

    // Übernimmt einen Theme-Zustand. Die Methode wird vom Core nach einem Theme-Wechsel und
    // beim Start des eigenständigen Modus aufgerufen.
    public void applyTheme(boolean modern, boolean dark, double fontScale) {
        this.modernDesign = modern;
        this.darkMode = dark;
        if (dark) {
            if (!getStyleClass().contains("dark")) {
                getStyleClass().add("dark");
            }
        } else {
            getStyleClass().remove("dark");
        }
        setStyle(themeVariables(modern, dark)
            + " -fx-font-size: " + (13.0 * Math.max(0.5, fontScale)) + "px;");
        renderPreview();
    }

    // Liefert die CSS-Farbvariablen für die vier Kombinationen aus Design und Modus.
    // Die Werte entsprechen den vom LifeOS-Core verwendeten Paletten, damit das Modul im
    // eigenständigen Betrieb dieselbe Darstellung wie innerhalb von LifeOS erhält.
    private String themeVariables(boolean modern, boolean dark) {
        if (modern && dark) {
            return "-color-accent-emphasis:#e07840; -color-bg-default:#1a1714;"
                + " -color-bg-subtle:#212019; -color-fg-default:#e8e0d4;"
                + " -color-fg-muted:#9a9080; -color-border-default:#2e2b26;";
        }
        if (modern) {
            return "-color-accent-emphasis:#c75f29; -color-bg-default:#faf6f0;"
                + " -color-bg-subtle:#f1ebe1; -color-fg-default:#2a2520;"
                + " -color-fg-muted:#6b6055; -color-border-default:#d8cfc0;";
        }
        if (dark) {
            return "-color-accent-emphasis:#4db6ac; -color-bg-default:#1a1a1a;"
                + " -color-bg-subtle:#202020; -color-fg-default:#c0c0c0;"
                + " -color-fg-muted:#787878; -color-border-default:#282828;";
        }
        return "-color-accent-emphasis:#2d8a82; -color-bg-default:#f5f5f5;"
            + " -color-bg-subtle:#ececec; -color-fg-default:#2a2a2a;"
            + " -color-fg-muted:#666666; -color-border-default:#d4d4d4;";
    }

    // --- Persistenz ----------------------------------------------------------------------------

    // Startet die Zeitgeber für automatische Speicherung und Backup-Historie.
    private void startTimers() {
        autosaveTimer = new Timeline(new KeyFrame(AUTOSAVE_INTERVAL, e -> saveDocument()));
        autosaveTimer.setCycleCount(Animation.INDEFINITE);
        autosaveTimer.play();

        backupTimer = new Timeline(new KeyFrame(BACKUP_INTERVAL, e -> writeBackupIfChanged()));
        backupTimer.setCycleCount(Animation.INDEFINITE);
        backupTimer.play();
    }

    // Speichert das Dokument als JSON-Datei im Modulspeicher.
    private void saveDocument() {
        try {
            document.save(documentFile());
        } catch (Exception e) {
            System.err.println("LifeSuite-Dokument konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    // Schreibt eine Sicherung in die Historie, sofern sich der Inhalt seit der letzten
    // Sicherung geändert hat.
    private void writeBackupIfChanged() {
        if (!document.getContent().equals(lastBackupContent)) {
            document.writeBackup(historyDir(), MAX_BACKUPS);
            lastBackupContent = document.getContent();
        }
    }

    // Beendet die Zeitgeber und speichert den aktuellen Stand. Wird beim Schließen aufgerufen.
    public void shutdown() {
        if (autosaveTimer != null) {
            autosaveTimer.stop();
        }
        if (backupTimer != null) {
            backupTimer.stop();
        }
        saveDocument();
    }

    // --- Hilfsmethoden -------------------------------------------------------------------------

    // Gleicht die Werte der Werkzeugleiste mit dem geladenen Dokument ab.
    private void syncControlsFromDocument() {
        updatingFromDocument = true;
        fontCombo.setValue(document.getFontFamily());
        sizeCombo.setValue((int) Math.round(document.getFontSizePt()));
        if (document.getTextColor() != null && !document.getTextColor().isBlank()) {
            try {
                colorPicker.setValue(Color.web(document.getTextColor()));
            } catch (Exception ignored) {
                // Eine ungültige Farbangabe bleibt ohne Auswirkung auf die Auswahl.
            }
        }
        hyphenButton.setSelected(document.isHyphenation());
        switch (document.getAlignment()) {
            case "center": alignCenter.setSelected(true); break;
            case "right": alignRight.setSelected(true); break;
            case "justify": alignJustify.setSelected(true); break;
            default: alignLeft.setSelected(true); break;
        }
        updatingFromDocument = false;
    }

    // Wandelt eine JavaFX-Farbe in eine hexadezimale CSS-Farbangabe um.
    private String toHex(Color color) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
    }

    // Liest eine Schriftgröße aus einer Texteingabe; ungültige Eingaben liefern null.
    private Integer parseSize(String raw) {
        try {
            return (int) Math.round(Double.parseDouble(raw.trim().replace(",", ".")));
        } catch (Exception e) {
            return null;
        }
    }

    // Erzeugt eine Schaltfläche mit Symbol und Tooltip, die eine Aktion auslöst.
    private Button iconButton(String iconCode, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
        return button;
    }

    // Erzeugt ein Symbol. Schlägt die Symbolerzeugung fehl, wird ersatzweise ein Textsymbol genutzt.
    private Node icon(String iconCode) {
        try {
            return new FontIcon(iconCode);
        } catch (Exception e) {
            return new Label("?");
        }
    }

    // Liefert den Dateipfad des Dokuments im Modulspeicher.
    private Path documentFile() {
        return storagePath.resolve("document.json");
    }

    // Liefert den Ordner der Backup-Historie im Modulspeicher.
    private Path historyDir() {
        return storagePath.resolve("history");
    }

    // Gibt das aktuelle Dokumentmodell zurück.
    public EditorDocument getDocument() {
        return document;
    }
}
