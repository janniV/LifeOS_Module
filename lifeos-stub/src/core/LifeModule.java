package core;

import javafx.scene.Node;

// Schnittstelle für alle Module in LifeOSnext.
// Jedes Modul muss diese Schnittstelle implementieren, um vom Core geladen zu werden.
public interface LifeModule {

    // Der Anzeigename des Moduls wird für die Sidebar zurückgegeben.
    String getModuleName();

    // Der Pfad zum Icon des Moduls wird zurückgegeben.
    String getModuleIconPath();

    // Die grafische Hauptansicht des Moduls wird bereitgestellt.
    Node getMainView();

    // Diese Methode wird beim Start des Moduls durch den Core ausgeführt.
    // Die CoreServices werden übergeben, um Zugriff auf systemweite Funktionen zu ermöglichen.
    void onInitialize(CoreServices core);

    // Diese Methode wird beim Beenden des Moduls ausgeführt, um Ressourcen freizugeben.
    void onShutdown();

    // Der spezifische KI-Kontext des Moduls (Prompt-Layer, Tools, LoRA) wird zurückgegeben.
    ModuleAIContext getAIContext();

    // Der Name des FontAwesome-Icons für das moderne UI wird zurückgegeben (z.B. "fas-cube").
    default String getFontIconName() {
        return "fas-cube";
    }

    // Gibt eine eindeutige ID zurück, anhand derer ein isolierter Speicherordner für das Modul benannt wird.
    String getModuleId();

    // Wird vor der endgültigen Löschung des Modul-Ordners aufgerufen, um Aufräumarbeiten durchzuführen.
    default void onUninstall() {
        // Standardmäßig leer, falls keine Aufräumarbeiten erforderlich sind.
    }

    // Ein optionales Widget für die Startseite (Dashboard) wird bereitgestellt.
    // Wenn das Modul kein Widget anbietet, wird null zurückgegeben.
    default Node getDashboardWidget() {
        return null;
    }

    // Ein Modul kann weitere, eigenständige Ansichten (Sub-Module) für die Sidebar bereitstellen.
    default java.util.List<LifeModule> getSubModules() {
        return null;
    }
}