package de.lifeos.lifekalender;

import core.CoreServices;
import core.LifeModule;
import core.ModuleAIContext;
import de.lifeos.lifekalender.service.CalendarService;
import de.lifeos.lifekalender.service.EventService;
import de.lifeos.lifekalender.service.LLMExtractionService;
import de.lifeos.lifekalender.service.NotificationService;
import de.lifeos.lifekalender.tools.CreateCalendarTool;
import de.lifeos.lifekalender.tools.CreateEventTool;
import de.lifeos.lifekalender.tools.ExtractTermsTool;
import de.lifeos.lifekalender.tools.ImportICalTool;
import de.lifeos.lifekalender.ui.MainView;
import core.AITool;
import javafx.scene.Node;
import java.nio.file.Path;
import java.util.List;

/**
 * Hauptklasse des LifeKalender-Moduls für LifeOS.
 * Implementiert die LifeModule-Schnittstelle.
 */
public class LifeKalenderModule implements LifeModule {
    private static final String MODULE_ID = "com.lifeos.lifekalender";
    private static final String MODULE_NAME = "LifeKalender";

    private CoreServices core;
    private CalendarService calendarService;
    private EventService eventService;
    private NotificationService notificationService;
    private LLMExtractionService extractionService;
    private MainView mainView;

    public LifeKalenderModule() {
        // Parameterloser Konstruktor für den PluginLoader
    }

    @Override
    public String getModuleName() {
        return MODULE_NAME;
    }

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    @Override
    public String getModuleIconPath() {
        return null; // Kein eigenes Icon, nutze FontIcon
    }

    @Override
    public String getFontIconName() {
        return "fas-calendar-alt"; // FontAwesome 5 Icon für Kalender
    }

    @Override
    public Node getMainView() {
        if (mainView == null) {
            mainView = new MainView(core, calendarService, eventService, notificationService, extractionService);
        }
        return mainView.getView();
    }

    @Override
    public void onInitialize(CoreServices core) {
        this.core = core;
        
        // Speicherpfad für das Modul
        Path storagePath = core.getModuleStoragePath(getModuleId());
        
        // Dienste initialisieren
        calendarService = new CalendarService(storagePath);
        eventService = new EventService(storagePath, calendarService);
        notificationService = new NotificationService(core, eventService);
        extractionService = new LLMExtractionService(core, calendarService, eventService);
        
        // Standard-Kalender erstellen, falls nicht vorhanden
        if (calendarService.getAllCalendars().isEmpty()) {
            calendarService.createCalendar("Privat");
            calendarService.createCalendar("Uni");
            calendarService.createCalendar("Garten");
            calendarService.createCalendar("Arbeit");
        }
        
        // Benachrichtigungsdienst starten
        notificationService.start();
        
        // UI initialisieren (wird erst bei getMainView() erstellt)
    }

    @Override
    public void onShutdown() {
        // Benachrichtigungsdienst stoppen
        if (notificationService != null) {
            notificationService.stop();
        }
        
        // Dienste bereinigen
        calendarService = null;
        eventService = null;
        notificationService = null;
        extractionService = null;
        
        // UI bereinigen
        if (mainView != null) {
            mainView.cleanup();
            mainView = null;
        }
        
        core = null;
    }

    @Override
    public ModuleAIContext getAIContext() {
        // AI-Tools registrieren
        List<AITool> tools = List.of(
                new ExtractTermsTool(extractionService),
                new ImportICalTool(calendarService, eventService),
                new CreateCalendarTool(calendarService),
                new CreateEventTool(calendarService, eventService)
        );

        // Prompt-Kontext für Samira
        String situationalPrompt = """
Du bist der Assistent für das LifeKalender-Modul in LifeOS. 

Deine Aufgaben:
1. Hilf dem Nutzer bei der Verwaltung von Kalendern und Terminen.
2. Extrahiere Termine aus Texten, PDFs oder Bildern mit dem Tool lifekalender_extract_terms.
3. Importiere iCal (ICS)-Dateien mit dem Tool lifekalender_import_ical.
4. Erstelle neue Kalender mit dem Tool lifekalender_create_calendar.
5. Erstelle neue Termine mit dem Tool lifekalender_create_event.

Wichtige Regeln:
- Frage den Nutzer immer um Bestätigung, bevor du Termine oder Kalender erstellst oder importierst.
- Ordne Termine sinnvoll einem Kalender zu (z.B. "Vorlesung" → "Uni", "Gärtnern" → "Garten").
- Nutze die Tools nur, wenn sie zur Aufgabe passen.
- Gib klare und präzise Antworten auf Deutsch.
- Wenn du unsicher bist, frage den Nutzer nach weiteren Informationen.

Verfügbare Kalender:
- Privat (für persönliche Termine)
- Uni (für Studienveranstaltungen)
- Garten (für Gartenarbeit)
- Arbeit (für berufliche Termine)

Terminformat:
- Datum: YYYY-MM-DD
- Uhrzeit: HH:MM
- Titel: Kurze Beschreibung
- Ort: Wo der Termin stattfindet
- Details: Weitere Informationen
""";

        return new ModuleAIContext(
                situationalPrompt,
                null, // Kein LoRA-Adapter
                tools
        );
    }

    @Override
    public Node getDashboardWidget() {
        // Kein Dashboard-Widget für die erste Version
        return null;
    }

    @Override
    public List<LifeModule> getSubModules() {
        // Keine Submodule für die erste Version
        return List.of();
    }

    @Override
    public void onUninstall() {
        // Endgültige Aufräumarbeiten
        onShutdown();
        
        // Speicher bereinigen (optional)
        if (core != null) {
            Path storagePath = core.getModuleStoragePath(getModuleId());
            try {
                // Hier könnten alte Dateien gelöscht werden
                // Für jetzt: Nichts tun, um Datenverlust zu vermeiden
            } catch (Exception e) {
                System.err.println("Fehler beim Aufräumen: " + e.getMessage());
            }
        }
    }
}
