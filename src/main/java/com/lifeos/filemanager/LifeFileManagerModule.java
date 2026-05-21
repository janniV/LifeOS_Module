package com.lifeos.filemanager;

import core.AITool;
import core.AIToolSafety;
import core.AgentToolResult;
import core.CoreServices;
import core.LifeModule;
import core.LifeOSFileService;
import core.ModuleAIContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// LifeFileManager: Dateimanager-Modul fuer LifeOS.
// Das Modul durchsucht und verwaltet ausschliesslich die verwaltete LifeOS-Ordnerstruktur
// und stellt Samira passende Datei-Werkzeuge bereit.
public class LifeFileManagerModule implements LifeModule {

    // Stabile Modul-ID. Sie bleibt ueber alle Versionen identisch.
    public static final String MODULE_ID = "com.lifeos.filemanager";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long MAX_READ_SIZE = 200_000L;

    private CoreServices core;
    private LifeFileManagerView view;
    private ModuleAIContext aiContext;

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    @Override
    public String getModuleName() {
        return "LifeFileManager";
    }

    @Override
    public String getFontIconName() {
        return "fas-folder-open";
    }

    @Override
    public List<String> getRequestedPermissions() {
        return List.of("lifeos.files.read", "lifeos.files.write");
    }

    @Override
    public Node getMainView() {
        if (view == null) {
            view = new LifeFileManagerView();
            if (core != null) {
                view.setCore(core);
            }
        }
        return view;
    }

    @Override
    public void onInitialize(CoreServices core) {
        this.core = core;
        if (view != null) {
            view.setCore(core);
        }
    }

    @Override
    public void onShutdown() {
        if (view != null) {
            view.shutdown();
        }
    }

    @Override
    public Node getDashboardWidget() {
        FontIcon icon = new FontIcon("fas-folder-open");
        icon.setIconSize(20);
        Label title = new Label("LifeFileManager");
        title.setStyle("-fx-font-weight: bold;");
        HBox head = new HBox(8, icon, title);
        head.setAlignment(Pos.CENTER_LEFT);

        Label summary = new Label("Lade Uebersicht ...");
        summary.getStyleClass().add("text-muted");

        VBox widget = new VBox(8, head, summary);
        widget.setPadding(new Insets(14));
        widget.setCursor(Cursor.HAND);
        widget.setOnMouseClicked(event -> {
            if (core != null) {
                core.openModule(getModuleName());
            }
        });
        loadDashboardSummary(summary);
        return widget;
    }

    @Override
    public ModuleAIContext getAIContext() {
        if (aiContext == null) {
            aiContext = new ModuleAIContext(buildSituationalPrompt(), null, buildTools());
        }
        return aiContext;
    }

    // ----- Dashboard -----------------------------------------------------

    private void loadDashboardSummary(Label target) {
        Thread thread = new Thread(() -> {
            String result;
            try {
                LifeOSFileService fileService = core == null ? null : core.getFileService();
                if (fileService == null) {
                    result = "LifeOS-Dateidienst nicht verfuegbar.";
                } else {
                    long workspaces = countChildren(fileService.getWorkspacesRoot());
                    long exports = countChildren(fileService.getExportsRoot());
                    result = workspaces + " Arbeitsbereiche, " + exports + " Exporte";
                }
            } catch (Exception e) {
                result = "Uebersicht nicht verfuegbar.";
            }
            final String text = result;
            Platform.runLater(() -> target.setText(text));
        }, "lifefilemanager-dashboard");
        thread.setDaemon(true);
        thread.start();
    }

    private long countChildren(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ----- KI-Kontext ----------------------------------------------------

    private String buildSituationalPrompt() {
        return "Du arbeitest im LifeFileManager, dem Dateimanager von LifeOS. "
            + "Er verwaltet ausschliesslich die LifeOS-Ordnerstruktur. "
            + "Pfade in den Werkzeugen sind relativ zum LifeOS-Datenstamm, "
            + "z. B. \"workspaces/Projekt1\", \"exports\" oder \"modules/<modulId>\". "
            + "Absolute Host-Pfade sind nicht erlaubt. "
            + "Geloeschte Dateien wandern in den LifeOS-Papierkorb und bleiben wiederherstellbar. "
            + "Nutze die Werkzeuge nur, wenn sie zur Aufgabe passen.";
    }

    private List<AITool> buildTools() {
        return List.of(
            new AITool(
                "files_list",
                "Listet Dateien und Ordner in einem Verzeichnis der LifeOS-Struktur auf. "
                    + "Verwende dieses Werkzeug, um den Inhalt eines Ordners zu sehen.",
                "{\"path\":\"workspaces\"}",
                AIToolSafety.READ_ONLY,
                this::toolList
            ),
            new AITool(
                "files_read",
                "Liest den Textinhalt einer Datei in der LifeOS-Struktur. "
                    + "Nur fuer Textdateien bis 200 KB geeignet.",
                "{\"path\":\"workspaces/Projekt1/notiz.txt\"}",
                AIToolSafety.READ_ONLY,
                this::toolRead
            ),
            new AITool(
                "files_search",
                "Sucht Dateien und Ordner, deren Name den Suchtext enthaelt. "
                    + "Optional kann ein Startordner angegeben werden.",
                "{\"query\":\"notiz\",\"path\":\"workspaces\"}",
                AIToolSafety.READ_ONLY,
                this::toolSearch
            ),
            new AITool(
                "files_create_folder",
                "Erstellt einen neuen Ordner in der LifeOS-Struktur.",
                "{\"path\":\"workspaces/Projekt1/Unterordner\"}",
                AIToolSafety.USER_DATA_WRITE,
                this::toolCreateFolder
            ),
            new AITool(
                "files_write",
                "Schreibt Text in eine Datei und legt sie bei Bedarf an. "
                    + "Eine vorhandene Datei wird ueberschrieben.",
                "{\"path\":\"workspaces/Projekt1/notiz.txt\",\"content\":\"Text\"}",
                AIToolSafety.USER_DATA_WRITE,
                true,
                this::toolWrite
            ),
            new AITool(
                "files_delete",
                "Verschiebt eine Datei oder einen Ordner in den LifeOS-Papierkorb. "
                    + "Die Aktion ist ueber den Papierkorb umkehrbar.",
                "{\"path\":\"workspaces/Projekt1/notiz.txt\"}",
                AIToolSafety.DESTRUCTIVE,
                true,
                this::toolDelete
            )
        );
    }

    // ----- Werkzeug-Aktionen ---------------------------------------------

    private String toolList(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            Path dir = resolveData(fileService, text(node, "path"));
            if (!Files.isDirectory(dir)) {
                return AgentToolResult.error("Der angegebene Pfad ist kein Ordner.");
            }
            List<Path> children;
            try (Stream<Path> stream = Files.list(dir)) {
                children = stream.sorted().collect(Collectors.toList());
            }
            StringBuilder builder = new StringBuilder();
            builder.append("Inhalt von ").append(displayPath(fileService, dir))
                .append(" (").append(children.size()).append(" Eintraege):");
            int shown = 0;
            for (Path child : children) {
                if (shown >= 500) {
                    builder.append("\n... (Liste gekuerzt)");
                    break;
                }
                boolean directory = Files.isDirectory(child);
                builder.append("\n").append(directory ? "[DIR]  " : "[FILE] ")
                    .append(child.getFileName());
                if (!directory) {
                    builder.append("  (").append(Files.size(child)).append(" B)");
                }
                shown++;
            }
            return AgentToolResult.success(builder.toString());
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    private String toolRead(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            Path file = resolveData(fileService, text(node, "path"));
            if (!Files.isRegularFile(file)) {
                return AgentToolResult.error("Der angegebene Pfad ist keine Datei.");
            }
            if (Files.size(file) > MAX_READ_SIZE) {
                return AgentToolResult.error("Die Datei ist groesser als 200 KB und kann nicht gelesen werden.");
            }
            String content = fileService.readTextFile(file);
            return AgentToolResult.success("Inhalt von " + displayPath(fileService, file) + ":\n" + content);
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    private String toolSearch(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            String query = text(node, "query");
            if (query == null || query.isBlank()) {
                return AgentToolResult.error("Es wurde kein Suchtext angegeben.");
            }
            String pathArgument = text(node, "path");
            Path base = pathArgument == null || pathArgument.isBlank()
                ? fileService.getDataRoot().normalize()
                : resolveData(fileService, pathArgument);
            String needle = query.toLowerCase(java.util.Locale.ROOT);
            List<String> hits;
            try (Stream<Path> stream = Files.walk(base, 12)) {
                hits = stream
                    .filter(path -> path.getFileName() != null
                        && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .limit(100)
                    .map(path -> displayPath(fileService, path))
                    .collect(Collectors.toList());
            }
            if (hits.isEmpty()) {
                return AgentToolResult.success("Keine Treffer fuer \"" + query + "\".");
            }
            return AgentToolResult.success("Treffer fuer \"" + query + "\" (" + hits.size() + "):\n"
                + String.join("\n", hits));
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    private String toolCreateFolder(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            Path dir = resolveData(fileService, text(node, "path"));
            fileService.createDirectories(dir);
            refreshView();
            return AgentToolResult.success("Ordner erstellt: " + displayPath(fileService, dir));
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    private String toolWrite(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            Path file = resolveData(fileService, text(node, "path"));
            if (Files.isDirectory(file)) {
                return AgentToolResult.error("Der angegebene Pfad ist ein Ordner.");
            }
            String content = text(node, "content");
            Path parent = file.getParent();
            if (parent != null) {
                fileService.createDirectories(parent);
            }
            fileService.writeTextFile(file, content == null ? "" : content);
            refreshView();
            return AgentToolResult.success("Datei geschrieben: " + displayPath(fileService, file));
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    private String toolDelete(String input) {
        try {
            LifeOSFileService fileService = requireFileService();
            JsonNode node = parse(input);
            Path target = resolveData(fileService, text(node, "path"));
            if (target.equals(fileService.getDataRoot().normalize())) {
                return AgentToolResult.error("Der LifeOS-Datenstamm kann nicht geloescht werden.");
            }
            if (!Files.exists(target)) {
                return AgentToolResult.error("Der angegebene Pfad existiert nicht.");
            }
            Path name = target.getFileName();
            fileService.moveToTrash(target, name == null ? "Eintrag" : name.toString());
            refreshView();
            return AgentToolResult.success("In den Papierkorb verschoben: " + displayPath(fileService, target));
        } catch (Exception e) {
            return AgentToolResult.error(e.getMessage());
        }
    }

    // ----- Werkzeug-Hilfsmethoden ----------------------------------------

    private LifeOSFileService requireFileService() throws IOException {
        LifeOSFileService fileService = core == null ? null : core.getFileService();
        if (fileService == null) {
            throw new IOException("Der LifeOS-Dateidienst ist nicht verfuegbar.");
        }
        return fileService;
    }

    private JsonNode parse(String input) throws IOException {
        return MAPPER.readTree(input == null || input.isBlank() ? "{}" : input);
    }

    private String text(JsonNode node, String key) {
        if (node != null && node.hasNonNull(key)) {
            return node.get(key).asText();
        }
        return null;
    }

    // Loest einen modulrelativen Pfad innerhalb des LifeOS-Datenstamms auf und prueft die Grenzen.
    private Path resolveData(LifeOSFileService fileService, String relative) throws IOException {
        Path base = fileService.getDataRoot().normalize();
        String clean = relative == null ? "" : relative.trim().replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        Path target = (clean.isEmpty() ? base : base.resolve(clean)).normalize();
        if (!target.startsWith(base) || !fileService.isInsideLifeOS(target)) {
            throw new IOException("Pfad liegt ausserhalb der verwalteten LifeOS-Struktur: " + relative);
        }
        return target;
    }

    private String displayPath(LifeOSFileService fileService, Path path) {
        Path base = fileService.getDataRoot().normalize();
        if (path.startsWith(base)) {
            String relative = base.relativize(path).toString().replace('\\', '/');
            return relative.isEmpty() ? "data" : "data/" + relative;
        }
        return path.toString();
    }

    private void refreshView() {
        if (view != null) {
            view.refresh();
        }
    }
}
