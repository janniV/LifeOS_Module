package lifesuite;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Datenmodell eines im LifeSuite-Editor bearbeiteten Dokuments.
// Die Klasse hält sowohl den Markdown-Inhalt als auch die dokumentweiten Layout-Eigenschaften
// (Schrift, Ausrichtung, Seitenformat) und übernimmt das Speichern, Laden sowie die Verwaltung
// der Backup-Historie. Die Felder sind öffentlich über Getter/Setter erreichbar, damit Jackson
// das Objekt als JSON serialisieren kann.
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditorDocument {

    // Eine gemeinsame Jackson-Instanz wird für alle Lese- und Schreibvorgänge verwendet.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Zeitstempelmuster für die Dateinamen der Backup-Historie. Das Muster ist sortierbar,
    // damit ältere Sicherungen allein anhand des Dateinamens erkannt werden können.
    private static final DateTimeFormatter BACKUP_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private String title = "Unbenanntes Dokument";
    private String content = defaultContent();
    private String fontFamily = "Inter";
    private double fontSizePt = 12.0;
    private String textColor = "";
    private String alignment = "left";
    private boolean hyphenation = false;
    private String pageFormat = "A4";
    private double marginMm = 25.0;
    private String lastModified = "";

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? "" : title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content == null ? "" : content; }

    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) {
        this.fontFamily = (fontFamily == null || fontFamily.isBlank()) ? "Inter" : fontFamily;
    }

    public double getFontSizePt() { return fontSizePt; }
    public void setFontSizePt(double fontSizePt) {
        // Die Schriftgröße wird auf einen sinnvollen Bereich begrenzt, damit die Vorschau lesbar bleibt.
        this.fontSizePt = Math.max(6.0, Math.min(96.0, fontSizePt));
    }

    public String getTextColor() { return textColor; }
    public void setTextColor(String textColor) { this.textColor = textColor == null ? "" : textColor; }

    public String getAlignment() { return alignment; }
    public void setAlignment(String alignment) {
        // Nur die vier unterstützten Ausrichtungen werden übernommen, andere Eingaben fallen auf links zurück.
        if ("center".equals(alignment) || "right".equals(alignment) || "justify".equals(alignment)) {
            this.alignment = alignment;
        } else {
            this.alignment = "left";
        }
    }

    public boolean isHyphenation() { return hyphenation; }
    public void setHyphenation(boolean hyphenation) { this.hyphenation = hyphenation; }

    public String getPageFormat() { return pageFormat; }
    public void setPageFormat(String pageFormat) {
        // Nur die unterstützten Papierformate werden akzeptiert.
        if ("A3".equals(pageFormat) || "Letter".equals(pageFormat)) {
            this.pageFormat = pageFormat;
        } else {
            this.pageFormat = "A4";
        }
    }

    public double getMarginMm() { return marginMm; }
    public void setMarginMm(double marginMm) {
        this.marginMm = Math.max(0.0, Math.min(80.0, marginMm));
    }

    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified == null ? "" : lastModified;
    }

    // Liefert die Breite des aktuellen Papierformats in Millimetern.
    public double pageWidthMm() {
        switch (pageFormat) {
            case "A3": return 297.0;
            case "Letter": return 215.9;
            default: return 210.0;
        }
    }

    // Liefert die Höhe des aktuellen Papierformats in Millimetern.
    public double pageHeightMm() {
        switch (pageFormat) {
            case "A3": return 420.0;
            case "Letter": return 279.4;
            default: return 297.0;
        }
    }

    // Speichert das Dokument als JSON-Datei. Der Zeitstempel der letzten Änderung wird dabei gesetzt.
    public void save(Path file) throws IOException {
        this.lastModified = LocalDateTime.now().toString();
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }

    // Lädt ein Dokument aus einer JSON-Datei. Existiert die Datei nicht oder ist sie fehlerhaft,
    // wird ein neues Dokument mit Standardwerten zurückgegeben, damit der Editor immer startet.
    public static EditorDocument load(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                return MAPPER.readValue(file.toFile(), EditorDocument.class);
            }
        } catch (Exception e) {
            System.err.println("LifeSuite-Dokument konnte nicht geladen werden: " + e.getMessage());
        }
        return new EditorDocument();
    }

    // Schreibt eine zeitgestempelte Sicherung in den Historienordner und entfernt überzählige
    // Sicherungen. Der Parameter maxBackups begrenzt die Anzahl der aufbewahrten Sicherungen.
    public void writeBackup(Path historyDir, int maxBackups) {
        try {
            Files.createDirectories(historyDir);
            String stamp = LocalDateTime.now().format(BACKUP_STAMP);
            Path target = historyDir.resolve("backup-" + stamp + ".json");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), this);
            pruneBackups(historyDir, maxBackups);
        } catch (Exception e) {
            System.err.println("LifeSuite-Sicherung konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    // Listet die vorhandenen Sicherungen, neueste zuerst. Die Sortierung nutzt den sortierbaren
    // Zeitstempel im Dateinamen.
    public static List<Path> listBackups(Path historyDir) {
        List<Path> backups = new ArrayList<>();
        if (historyDir == null || !Files.isDirectory(historyDir)) {
            return backups;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "backup-*.json")) {
            for (Path p : stream) {
                backups.add(p);
            }
        } catch (IOException e) {
            System.err.println("LifeSuite-Sicherungen konnten nicht gelesen werden: " + e.getMessage());
        }
        backups.sort(Collections.reverseOrder());
        return backups;
    }

    // Entfernt die ältesten Sicherungen, sobald die Höchstzahl überschritten wird.
    private static void pruneBackups(Path historyDir, int maxBackups) {
        List<Path> backups = listBackups(historyDir);
        for (int i = maxBackups; i < backups.size(); i++) {
            try {
                Files.deleteIfExists(backups.get(i));
            } catch (IOException e) {
                System.err.println("Alte LifeSuite-Sicherung konnte nicht entfernt werden: " + e.getMessage());
            }
        }
    }

    // Liefert den Beispielinhalt, der bei einem frisch angelegten Dokument angezeigt wird.
    // Der Text demonstriert die wichtigsten unterstützten Markdown-Elemente.
    private static String defaultContent() {
        return "# Willkommen bei LifeSuite\n\n"
            + "Dies ist dein neues Dokument. Schreibe links im **Markdown-Code** und sieh rechts "
            + "die formatierte Vorschau.\n\n"
            + "## Was du kannst\n\n"
            + "- Text **fett**, *kursiv* oder ++unterstrichen++ formatieren\n"
            + "- Überschriften erzeugen ein automatisches Inhaltsverzeichnis\n"
            + "- Literaturverweise wie [[Mustermann 2024]] werden hervorgehoben\n\n"
            + "> Ein Zitat hebt wichtige Aussagen hervor.\n\n"
            + "```\nCode-Blöcke behalten ihre Formatierung.\n```\n\n"
            + "Eine Formel mit MathJax: $$E = mc^2$$\n";
    }
}
