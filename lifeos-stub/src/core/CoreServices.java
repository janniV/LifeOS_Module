package core;

import javafx.scene.Node;
import java.nio.file.Path;

// Schnittstelle für die Dienste, die der Core den Modulen zur Verfügung stellt.
public interface CoreServices {

    // Ein modaler Dialog wird in einem einheitlichen, rahmenlosen Fenster (inklusive Theme-Support) angezeigt.
    void showDialog(String title, Node content);

    // Ein Modul wird in einem eigenständigen, neuen Fenster geöffnet.
    void openInNewWindow(LifeModule module);

    // Eine systemweite Benachrichtigung wird gesendet.
    void sendNotification(String message);

    // Die aktuellen globalen Einstellungen (Light/Dark Mode, Nutzername) werden abgerufen.
    // (Wird später durch eine vollwertige Settings-Klasse ersetzt)
    Object getGlobalSettings();

    // Öffnet das angegebene Modul im Hauptbereich.
    void openModule(String moduleName);

    // Gibt eine Liste der Namen aller verfügbaren Module zurück.
    java.util.List<String> getAvailableModules();

    // Stellt einen isolierten Speicherpfad für das angegebene Modul zur Verfügung.
    Path getModuleStoragePath(String moduleId);

    // Gibt die zentrale LOn-Dateiebene zurueck, damit Module innerhalb der verwalteten
    // Ordnerstruktur arbeiten koennen.
    default LifeOSFileService getFileService() { return null; }

    // Fragt globale oder systemweite Daten über einen Schlüssel an.
    Object requestSharedData(String dataKey);

    // Prüft anhand der Modul-ID, ob ein bestimmtes Modul installiert ist.
    boolean isModuleInstalled(String moduleId);

    // Startet den Download eines KI-Modells im Hintergrund (überlebt Modulwechsel).
    void startModelDownload(ModelDefinition model, java.util.function.Consumer<Double> onProgress, Runnable onComplete, java.util.function.Consumer<Exception> onError);

    // Setzt die ungelesene Nachrichtenanzahl für ein Modul (Badge in der Sidebar).
    void setModuleBadge(String moduleName, int count);

    // Gibt den aktuellen Download-Fortschritt zurück (0.0 bis 1.0), oder -1 wenn kein Download läuft.
    double getDownloadProgress();

    // Gibt den aktuellen Download-Statustext zurück.
    String getDownloadStatusText();

    // Gibt true zurück, wenn gerade ein Download läuft.
    boolean isDownloading();

    // Gibt den zentralen RoadmapService zurück (Lese- und Schreibzugriff für Module und KI).
    // Default-Implementierung gibt null zurück, damit Standalone-Module das Interface nicht
    // implementieren müssen — Aufrufer prüfen auf null.
    default RoadmapService getRoadmapService() { return null; }

    // Registriert ein neu geladenes Modul zur Laufzeit: onInitialize wird aufgerufen,
    // das Modul in die aktive Liste eingetragen und die Sidebar sofort aktualisiert.
    default void registerModule(LifeModule module) {}

    // Deregistriert ein Modul zur Laufzeit: onShutdown wird aufgerufen,
    // das Modul aus der aktiven Liste entfernt und die UI sofort aktualisiert.
    default void unregisterModule(String moduleName) {}
}
