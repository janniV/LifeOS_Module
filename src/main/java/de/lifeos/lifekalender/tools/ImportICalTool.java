package de.lifeos.lifekalender.tools;

import core.AITool;
import core.AIToolSafety;
import core.AgentToolResult;
import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.service.CalendarService;
import de.lifeos.lifekalender.service.EventService;
import de.lifeos.lifekalender.util.ICalParser;
import java.util.List;
import java.util.Map;

/**
 * AI-Tool zum Importieren von iCal (ICS)-Dateien.
 */
public class ImportICalTool implements AITool {
    private final CalendarService calendarService;
    private final EventService eventService;

    public ImportICalTool(CalendarService calendarService, EventService eventService) {
        this.calendarService = calendarService;
        this.eventService = eventService;
    }

    @Override
    public String getName() {
        return "lifekalender_import_ical";
    }

    @Override
    public String getDescription() {
        return "Importiere Termine aus einer iCal (ICS)-Datei. " +
                "Die Termine werden einem bestehenden Kalender zugeordnet oder ein neuer Kalender wird erstellt. " +
                "Der Nutzer muss den Import manuell bestätigen.";
    }

    @Override
    public String getInputSchema() {
        return "{\n" +
               "    \"type\": \"object\",\n" +
               "    \"properties\": {\n" +
               "        \"icalContent\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Inhalt der ICS-Datei als String\"\n" +
               "        },\n" +
               "        \"calendarName\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Name des Kalenders, dem die Termine zugeordnet werden sollen (optional)\",\n" +
               "            \"default\": null\n" +
               "        }\n" +
               "    },\n" +
               "    \"required\": [\"icalContent\"]\n" +
               "}";
    }

    @Override
    public AIToolSafety getSafety() {
        return AIToolSafety.USER_DATA_WRITE;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input) {
        try {
            String icalContent = (String) input.get("icalContent");
            String calendarName = (String) input.get("calendarName");

            if (icalContent == null || icalContent.trim().isEmpty()) {
                return AgentToolResult.error("Der ICS-Inhalt darf nicht leer sein");
            }

            String calendarId;
            if (calendarName != null && !calendarName.trim().isEmpty()) {
                calendarId = calendarService.getCalendarByName(calendarName)
                        .map(c -> c.getId())
                        .orElseGet(() -> calendarService.createCalendar(calendarName).getId());
            } else {
                calendarId = calendarService.getCalendarByName("Privat")
                        .map(c -> c.getId())
                        .orElseGet(() -> calendarService.createCalendar("Privat").getId());
            }

            List<Event> events = ICalParser.parseICal(icalContent, calendarId);

            if (events.isEmpty()) {
                return AgentToolResult.error("Keine Termine in der ICS-Datei gefunden");
            }

            int conflictCount = 0;
            for (Event event : events) {
                List<Event> conflicts = eventService.checkForConflicts(event);
                if (!conflicts.isEmpty()) {
                    conflictCount++;
                }
            }

            String message = "Es wurden " + events.size() + " Termine aus der ICS-Datei extrahiert. " +
                    "Zielkalender: " + calendarService.getCalendarById(calendarId).map(c -> c.getName()).orElse("Unbekannt") + ". " +
                    (conflictCount > 0 ? "Es gibt " + conflictCount + " Termine mit potenziellen Konflikten. " : "") +
                    "Bitte bestätigen Sie den Import.";

            return AgentToolResult.confirmationRequired(
                    message,
                    Map.of("events", events, "calendarId", calendarId, "conflictCount", conflictCount)
            );
        } catch (Exception e) {
            return AgentToolResult.error("Fehler beim Import der ICS-Datei: " + e.getMessage());
        }
    }
}
