package com.lifeos.filemanager;

import core.CoreServices;
import core.LifeOSFileService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

// JavaFX-Hauptansicht des LifeFileManager.
// Die Ansicht arbeitet ausschliesslich innerhalb der verwalteten LifeOS-Ordnerstruktur.
public class LifeFileManagerView extends BorderPane {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // Eintraege werden zuerst nach Typ (Ordner vor Datei), dann alphabetisch sortiert.
    private static final Comparator<FileEntry> ENTRY_ORDER =
        Comparator.comparing(FileEntry::isDirectory).reversed()
            .thenComparing(e -> e.getName().toLowerCase(Locale.ROOT));

    // Dateiendungen, die der eingebaute Text-Editor oeffnen darf.
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "md", "json", "xml", "csv", "log", "yml", "yaml", "ini", "cfg",
        "properties", "java", "js", "mjs", "ts", "html", "htm", "css", "py",
        "sh", "bat", "kt", "gradle", "sql", "gitignore"
    );

    private static final long MAX_TEXT_SIZE = 2_000_000L;

    private CoreServices core;
    private LifeOSFileService fileService;
    private Path dataRoot;
    private Path currentPath;

    private final ExecutorService ioExecutor =
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lifefilemanager-io");
            thread.setDaemon(true);
            return thread;
        });

    private final ObservableList<FileEntry> items = FXCollections.observableArrayList();
    private final TableView<FileEntry> table = new TableView<>(items);
    private final ListView<ScopeLocation> scopeList = new ListView<>();
    private final HBox breadcrumbBar = new HBox(2);
    private final Label statusLabel = new Label();
    private final Label pathLabel = new Label();

    private Button upButton;
    private Button refreshButton;
    private Button newFolderButton;
    private Button newFileButton;
    private Button renameButton;
    private Button deleteButton;
    private Button openButton;

    private boolean suppressScopeEvent;

    public LifeFileManagerView() {
        setTop(buildHeader());
        setLeft(buildScopePanel());
        setCenter(buildTable());
        setBottom(buildStatusBar());
        setDisableActions(true);
        statusLabel.setText("Warte auf LifeOS-Dienste ...");
    }

    // Der Core wird gesetzt und die Ansicht auf den ersten Ordner navigiert.
    public void setCore(CoreServices core) {
        this.core = core;
        this.fileService = core == null ? null : core.getFileService();
        if (fileService == null) {
            statusLabel.setText("Der LifeOS-Dateidienst ist nicht verfuegbar.");
            table.setPlaceholder(new Label("Kein Zugriff auf die LifeOS-Dateistruktur."));
            return;
        }
        this.dataRoot = fileService.getDataRoot().normalize();
        populateScopes();
        setDisableActions(false);
        navigateTo(determineStartPath());
    }

    // Laedt den aktuellen Ordner neu (z. B. nach Aenderungen durch Agenten-Tools).
    public void refresh() {
        Platform.runLater(this::reload);
    }

    // Beendet Hintergrundarbeit und sichert den zuletzt geoeffneten Ordner.
    public void shutdown() {
        saveStateNow();
        ioExecutor.shutdownNow();
    }

    // ----- UI-Aufbau -----------------------------------------------------

    private Node buildHeader() {
        upButton = iconButton("fas-arrow-up", "Eine Ebene nach oben", this::goUp);
        refreshButton = iconButton("fas-sync-alt", "Aktualisieren", this::reload);
        newFolderButton = iconButton("fas-folder-plus", "Neuer Ordner", this::createFolder);
        newFileButton = iconButton("fas-file-medical", "Neue Textdatei", this::createTextFile);
        renameButton = iconButton("fas-i-cursor", "Umbenennen", this::renameSelected);
        deleteButton = iconButton("fas-trash", "In den Papierkorb verschieben", this::deleteSelected);
        openButton = iconButton("fas-folder-open", "Oeffnen", this::openSelected);

        ToolBar toolBar = new ToolBar(
            upButton, refreshButton, separator(),
            openButton, newFolderButton, newFileButton, separator(),
            renameButton, deleteButton
        );

        breadcrumbBar.setAlignment(Pos.CENTER_LEFT);
        breadcrumbBar.setPadding(new Insets(6, 10, 6, 10));

        return new VBox(toolBar, breadcrumbBar);
    }

    private Node buildScopePanel() {
        Label header = new Label("Orte");
        header.setPadding(new Insets(10, 10, 6, 12));
        header.getStyleClass().add("text-muted");

        scopeList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (!suppressScopeEvent && value != null) {
                navigateTo(value.path());
            }
        });
        VBox.setVgrow(scopeList, Priority.ALWAYS);

        VBox panel = new VBox(header, scopeList);
        panel.setPrefWidth(195);
        panel.setMinWidth(160);
        return panel;
    }

    private Node buildTable() {
        TableColumn<FileEntry, FileEntry> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        nameColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(FileEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(entry.getName());
                    setGraphic(new FontIcon(entry.isDirectory() ? "fas-folder" : "fas-file-alt"));
                }
            }
        });
        nameColumn.setPrefWidth(320);

        TableColumn<FileEntry, String> sizeColumn = new TableColumn<>("Groesse");
        sizeColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
            cd.getValue().isDirectory() ? "" : formatSize(cd.getValue().getSize())));
        sizeColumn.setPrefWidth(110);

        TableColumn<FileEntry, String> modifiedColumn = new TableColumn<>("Geaendert");
        modifiedColumn.setCellValueFactory(cd -> new ReadOnlyStringWrapper(
            TIME_FORMAT.format(Instant.ofEpochMilli(cd.getValue().getLastModified()))));
        modifiedColumn.setPrefWidth(150);

        table.getColumns().add(nameColumn);
        table.getColumns().add(sizeColumn);
        table.getColumns().add(modifiedColumn);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Dieser Ordner ist leer."));

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> updateActionState());

        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelected();
            }
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected();
            } else if (event.getCode() == KeyCode.BACK_SPACE) {
                goUp();
            } else if (event.getCode() == KeyCode.DELETE) {
                deleteSelected();
            } else if (event.getCode() == KeyCode.F5) {
                reload();
            }
        });
        return table;
    }

    private Node buildStatusBar() {
        statusLabel.getStyleClass().add("text-muted");
        pathLabel.getStyleClass().add("text-muted");
        HBox bar = new HBox(statusLabel, growingSpacer(), pathLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        return bar;
    }

    // ----- Navigation ----------------------------------------------------

    private void navigateTo(Path target) {
        if (fileService == null || target == null) {
            return;
        }
        Path normalized = target.normalize();
        if (!normalized.startsWith(dataRoot) || !fileService.isInsideLifeOS(normalized)) {
            statusLabel.setText("Pfad liegt ausserhalb der verwalteten LifeOS-Struktur.");
            return;
        }
        currentPath = normalized;
        updateBreadcrumb();
        syncScopeSelection();
        updateActionState();
        reload();
        saveStateAsync();
    }

    private void reload() {
        if (currentPath == null) {
            return;
        }
        final Path dir = currentPath;
        statusLabel.setText("Lade ...");
        ioExecutor.submit(() -> {
            List<FileEntry> entries = new ArrayList<>();
            String error = null;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(path -> {
                    try {
                        boolean directory = Files.isDirectory(path);
                        long size = directory ? 0L : Files.size(path);
                        long modified = Files.getLastModifiedTime(path).toMillis();
                        entries.add(new FileEntry(path, directory, size, modified));
                    } catch (IOException ignored) {
                        // Einzelne unlesbare Eintraege werden uebersprungen.
                    }
                });
            } catch (IOException e) {
                error = "Ordner konnte nicht gelesen werden: " + e.getMessage();
            }
            entries.sort(ENTRY_ORDER);
            final String resultError = error;
            Platform.runLater(() -> {
                if (!dir.equals(currentPath)) {
                    return;
                }
                items.setAll(entries);
                if (resultError != null) {
                    statusLabel.setText(resultError);
                } else {
                    statusLabel.setText(entries.size() == 1 ? "1 Eintrag" : entries.size() + " Eintraege");
                }
            });
        });
    }

    private void goUp() {
        if (currentPath == null || currentPath.equals(dataRoot)) {
            return;
        }
        Path parent = currentPath.getParent();
        if (parent != null && parent.startsWith(dataRoot)) {
            navigateTo(parent);
        }
    }

    private void openSelected() {
        FileEntry selected = selectedEntry();
        if (selected == null) {
            return;
        }
        if (selected.isDirectory()) {
            navigateTo(selected.getPath());
        } else {
            openFileEntry(selected);
        }
    }

    private void openFileEntry(FileEntry entry) {
        if (entry.getSize() > MAX_TEXT_SIZE) {
            showInfo("Vorschau nicht moeglich",
                "Die Datei ist groesser als 2 MB und kann im LifeFileManager nicht angezeigt werden.");
            return;
        }
        if (!looksTextual(entry)) {
            showInfo("Vorschau nicht moeglich",
                "Fuer diesen Dateityp gibt es in dieser Version keine eingebaute Vorschau.");
            return;
        }
        final Path file = entry.getPath();
        ioExecutor.submit(() -> {
            try {
                String content = fileService.readTextFile(file);
                Platform.runLater(() -> showTextEditor(file, content));
            } catch (IOException e) {
                Platform.runLater(() ->
                    statusLabel.setText("Datei konnte nicht gelesen werden: " + e.getMessage()));
            }
        });
    }

    // ----- Aktionen ------------------------------------------------------

    private void createFolder() {
        if (currentPath == null) {
            return;
        }
        promptText("Neuer Ordner", "Name des Ordners", "", name -> {
            Path target = currentPath.resolve(name).normalize();
            if (!target.startsWith(currentPath)) {
                statusLabel.setText("Ungueltiger Ordnername.");
                return;
            }
            ioExecutor.submit(() -> {
                try {
                    fileService.createDirectories(target);
                    Platform.runLater(this::reload);
                } catch (IOException e) {
                    Platform.runLater(() ->
                        statusLabel.setText("Ordner konnte nicht erstellt werden: " + e.getMessage()));
                }
            });
        });
    }

    private void createTextFile() {
        if (currentPath == null) {
            return;
        }
        promptText("Neue Textdatei", "Dateiname (z. B. notiz.txt)", "", name -> {
            Path target = currentPath.resolve(name).normalize();
            if (!target.startsWith(currentPath)) {
                statusLabel.setText("Ungueltiger Dateiname.");
                return;
            }
            ioExecutor.submit(() -> {
                try {
                    if (Files.exists(target)) {
                        Platform.runLater(() -> statusLabel.setText("Datei existiert bereits."));
                        return;
                    }
                    fileService.writeTextFile(target, "");
                    Platform.runLater(this::reload);
                } catch (IOException e) {
                    Platform.runLater(() ->
                        statusLabel.setText("Datei konnte nicht erstellt werden: " + e.getMessage()));
                }
            });
        });
    }

    private void renameSelected() {
        FileEntry selected = selectedEntry();
        if (selected == null) {
            return;
        }
        promptText("Umbenennen", "Neuer Name", selected.getName(), name -> {
            Path target = selected.getPath().resolveSibling(name).normalize();
            if (!target.startsWith(currentPath) || !fileService.isInsideLifeOS(target)) {
                statusLabel.setText("Ungueltiger Name.");
                return;
            }
            ioExecutor.submit(() -> {
                try {
                    Files.move(selected.getPath(), target);
                    Platform.runLater(this::reload);
                } catch (IOException e) {
                    Platform.runLater(() ->
                        statusLabel.setText("Umbenennen fehlgeschlagen: " + e.getMessage()));
                }
            });
        });
    }

    private void deleteSelected() {
        FileEntry selected = selectedEntry();
        if (selected == null) {
            return;
        }
        confirm("In den Papierkorb verschieben",
            "\"" + selected.getName() + "\" wird in den LifeOS-Papierkorb verschoben "
                + "und kann von dort wiederhergestellt werden.",
            () -> ioExecutor.submit(() -> {
                try {
                    fileService.moveToTrash(selected.getPath(), selected.getName());
                    Platform.runLater(() -> {
                        statusLabel.setText("In den Papierkorb verschoben: " + selected.getName());
                        reload();
                    });
                } catch (IOException e) {
                    Platform.runLater(() ->
                        statusLabel.setText("Loeschen fehlgeschlagen: " + e.getMessage()));
                }
            }));
    }

    // ----- Dialoge -------------------------------------------------------

    private void showTextEditor(Path file, String content) {
        TextArea area = new TextArea(content);
        area.setStyle("-fx-font-family: 'monospace';");
        area.setPrefRowCount(22);
        area.setPrefColumnCount(74);
        VBox.setVgrow(area, Priority.ALWAYS);

        Label info = new Label(relativeLabel(file));
        info.getStyleClass().add("text-muted");

        Button save = new Button("Speichern");
        save.setDefaultButton(true);
        Button close = new Button("Schliessen");

        save.setOnAction(event -> {
            String text = area.getText();
            ioExecutor.submit(() -> {
                try {
                    fileService.writeTextFile(file, text);
                    Platform.runLater(() -> {
                        sendNotification("LifeFileManager: gespeichert " + file.getFileName());
                        reload();
                    });
                } catch (IOException e) {
                    Platform.runLater(() ->
                        statusLabel.setText("Speichern fehlgeschlagen: " + e.getMessage()));
                }
            });
        });
        close.setOnAction(event -> closeWindow(area));

        HBox actions = new HBox(8, info, growingSpacer(), close, save);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, area, actions);
        box.setPadding(new Insets(14));
        box.setPrefSize(640, 460);
        showDialog("Datei: " + file.getFileName(), box);
    }

    private void promptText(String title, String prompt, String initial, Consumer<String> onConfirm) {
        TextField field = new TextField(initial == null ? "" : initial);
        Label label = new Label(prompt);

        Button ok = new Button("OK");
        ok.setDefaultButton(true);
        Button cancel = new Button("Abbrechen");
        cancel.setCancelButton(true);

        ok.setOnAction(event -> {
            String value = field.getText() == null ? "" : field.getText().trim();
            closeWindow(field);
            if (!value.isEmpty()) {
                onConfirm.accept(value);
            }
        });
        cancel.setOnAction(event -> closeWindow(field));

        HBox actions = new HBox(8, cancel, ok);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(10, label, field, actions);
        box.setPadding(new Insets(16));
        box.setPrefWidth(380);
        showDialog(title, box);
        Platform.runLater(field::requestFocus);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(360);

        Button confirmButton = new Button("Verschieben");
        confirmButton.setDefaultButton(true);
        Button cancel = new Button("Abbrechen");
        cancel.setCancelButton(true);

        confirmButton.setOnAction(event -> {
            closeWindow(label);
            onConfirm.run();
        });
        cancel.setOnAction(event -> closeWindow(label));

        HBox actions = new HBox(8, cancel, confirmButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(14, label, actions);
        box.setPadding(new Insets(16));
        box.setPrefWidth(400);
        showDialog(title, box);
    }

    private void showInfo(String title, String message) {
        Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(360);

        Button ok = new Button("Schliessen");
        ok.setDefaultButton(true);
        ok.setOnAction(event -> closeWindow(label));

        HBox actions = new HBox(ok);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(14, label, actions);
        box.setPadding(new Insets(16));
        box.setPrefWidth(400);
        showDialog(title, box);
    }

    // ----- Hilfsmethoden -------------------------------------------------

    private void populateScopes() {
        scopeList.getItems().setAll(
            new ScopeLocation("Arbeitsbereiche", fileService.getWorkspacesRoot().normalize()),
            new ScopeLocation("Modul-Daten", fileService.getModuleDataRoot().normalize()),
            new ScopeLocation("Exporte", fileService.getExportsRoot().normalize()),
            new ScopeLocation("Modelle", fileService.getModelsRoot().normalize()),
            new ScopeLocation("Alle LifeOS-Daten", dataRoot)
        );
    }

    private Path determineStartPath() {
        Path saved = loadSavedPath();
        if (saved != null && Files.isDirectory(saved)
            && saved.startsWith(dataRoot) && fileService.isInsideLifeOS(saved)) {
            return saved;
        }
        Path workspaces = fileService.getWorkspacesRoot().normalize();
        return Files.isDirectory(workspaces) ? workspaces : dataRoot;
    }

    private void updateBreadcrumb() {
        breadcrumbBar.getChildren().clear();
        if (currentPath == null) {
            return;
        }
        breadcrumbBar.getChildren().add(crumbButton("LifeOS-Daten", dataRoot));
        if (!currentPath.equals(dataRoot)) {
            Path relative = dataRoot.relativize(currentPath);
            Path accumulated = dataRoot;
            for (Path segment : relative) {
                accumulated = accumulated.resolve(segment);
                breadcrumbBar.getChildren().add(new Label("/"));
                breadcrumbBar.getChildren().add(crumbButton(segment.toString(), accumulated));
            }
        }
        pathLabel.setText(relativeLabel(currentPath));
    }

    private Button crumbButton(String text, Path target) {
        Button button = new Button(text);
        button.getStyleClass().addAll("button", "flat");
        button.setOnAction(event -> navigateTo(target));
        return button;
    }

    private void syncScopeSelection() {
        suppressScopeEvent = true;
        ScopeLocation best = null;
        for (ScopeLocation scope : scopeList.getItems()) {
            if (currentPath.startsWith(scope.path())) {
                if (best == null || scope.path().getNameCount() > best.path().getNameCount()) {
                    best = scope;
                }
            }
        }
        if (best != null) {
            scopeList.getSelectionModel().select(best);
        } else {
            scopeList.getSelectionModel().clearSelection();
        }
        suppressScopeEvent = false;
    }

    private void updateActionState() {
        boolean hasSelection = selectedEntry() != null;
        renameButton.setDisable(!hasSelection);
        deleteButton.setDisable(!hasSelection);
        openButton.setDisable(!hasSelection);
        upButton.setDisable(currentPath == null || currentPath.equals(dataRoot));
    }

    private void setDisableActions(boolean disable) {
        for (Button button : List.of(upButton, refreshButton, newFolderButton,
                newFileButton, renameButton, deleteButton, openButton)) {
            button.setDisable(disable);
        }
    }

    private FileEntry selectedEntry() {
        return table.getSelectionModel().getSelectedItem();
    }

    private boolean looksTextual(FileEntry entry) {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return TEXT_EXTENSIONS.contains(name.substring(dot + 1));
        }
        return entry.getSize() <= 65_536L;
    }

    private String relativeLabel(Path path) {
        if (dataRoot == null || !path.startsWith(dataRoot)) {
            return path.toString();
        }
        return "data/" + dataRoot.relativize(path).toString().replace('\\', '/');
    }

    private Button iconButton(String iconCode, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(new FontIcon(iconCode));
        button.setTooltip(new Tooltip(tooltip));
        button.getStyleClass().add("flat");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Region separator() {
        Region region = new Region();
        region.setMinWidth(6);
        return region;
    }

    private Region growingSpacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void closeWindow(Node anchor) {
        if (anchor.getScene() != null && anchor.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    private void showDialog(String title, Node content) {
        if (core != null) {
            core.showDialog(title, content);
        }
    }

    private void sendNotification(String message) {
        if (core != null) {
            core.sendNotification(message);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ----- Zustand -------------------------------------------------------

    private Path stateFile() {
        return core.getModuleStoragePath(LifeFileManagerModule.MODULE_ID).resolve("state.json");
    }

    private Path loadSavedPath() {
        if (core == null) {
            return null;
        }
        try {
            Path file = stateFile();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            ObjectNode node = (ObjectNode) MAPPER.readTree(Files.readString(file));
            if (node.hasNonNull("lastPath")) {
                return dataRoot.resolve(node.get("lastPath").asText()).normalize();
            }
        } catch (Exception ignored) {
            // Ein beschaedigter Zustand wird stillschweigend ignoriert.
        }
        return null;
    }

    private void saveStateAsync() {
        ioExecutor.submit(this::saveStateNow);
    }

    private void saveStateNow() {
        if (core == null || currentPath == null || dataRoot == null) {
            return;
        }
        try {
            Path storage = core.getModuleStoragePath(LifeFileManagerModule.MODULE_ID);
            Files.createDirectories(storage);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("lastPath", dataRoot.relativize(currentPath).toString().replace('\\', '/'));
            Files.writeString(storage.resolve("state.json"), MAPPER.writeValueAsString(node));
        } catch (Exception ignored) {
            // Der Zustand ist optionaler Komfort; Fehler bleiben ohne Folgen.
        }
    }

    // Ein benannter Ort innerhalb der LifeOS-Struktur fuer die Seitenliste.
    private record ScopeLocation(String label, Path path) {
        @Override
        public String toString() {
            return label;
        }
    }
}
