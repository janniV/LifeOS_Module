package de.lifeos.lifekalender.tools;

import core.AITool;
import core.AIToolSafety;
import core.AgentToolResult;
import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.service.CalendarService;
import de.lifeos.lifekalender.service.EventService;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * AI-Tool zum Erstellen eines neuen Termins.
 */
public class CreateEventTool implements AITool {
    private final CalendarService calendarService;
    private final EventService eventService;

    public CreateEventTool(CalendarService calendarService, EventService eventService) {
        this.calendarService = calendarService;
        this.eventService = eventService;
    }

    @Override
    public String getName() {
        return "lifekalender_create_event";
    }

    @Override
    public String getDescription() {
        return "Erstelle einen neuen Termin in einem bestimmten Kalender. " +
                "Der Termin kann Datum, Uhrzeit, Titel, Ort und Details enthalten. " +
                "Der Nutzer muss den Termin manuell bestätigen, bevor er gespeichert wird.";
    }

    @Override
    public String getInputSchema() {
        return "{\n" +
               "    \"type\": \"object\",\n" +
               "    \"properties\": {\n" +
               "        \"calendarName\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Name des Kalenders, in dem der Termin erstellt werden soll\"\n" +
               "        },\n" +
               "        \"title\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Titel des Termins\",\n" +
               "            \"minLength\": 1\n" +
               "        },\n" +
               "        \"start\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Startdatum und -zeit im Format YYYY-MM-DD HH:MM oder YYYY-MM-DD\",\n" +
               "            \"pattern\": \"^\\\\d{4}-\\\\d{2}-\\\\d{2}(\\\\s+\\\\d{2}:\\\\d{2})?$\"\n" +
               "        },\n" +
               "        \"end\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Enddatum und -zeit im Format YYYY-MM-DD HH:MM oder YYYY-MM-DD (optional)\",\n" +
               "            \"pattern\": \"^\\\\d{4}-\\\\d{2}-\\\\d{2}(\\\\s+\\\\d{2}:\\\\d{2})?$\"\n" +
               "        },\n" +
               "        \"location\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Ort des Termins (optional)\"\n" +
               "        },\n" +
               "        \"details\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Details oder Beschreibung des Termins (optional)\"\n" +
               "        },\n" +
               "        \"allDay\": {\n" +
               "            \"type\": \"boolean\",\n" +
               "            \"description\": \"Ob es sich um einen ganztägigen Termin handelt (optional, Standard: false)\",\n" +
               "            \"default\": false\n" +
               "        },\n" +
               "        \"reminderMinutes\": {\n" +
               "            \"type\": \"integer\",\n" +
               "            \"description\": \"Erinnerung in Minuten vor dem Termin (optional)\",\n" +
               "            \"minimum\": 0\n" +
               "        }\n" +
               "    },\n" +
               "    \"required\": [\"calendarName\", \"title\", \"start\"]\n" +
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
            String calendarName = (String) input.get("calendarName");
            String title = (String) input.get("title");
            String startStr = (String) input.get("start");
            String endStr = (String) input.get("end");
            String location = (String) input.get("location");
            String details = (String) input.get("details");
            boolean allDay = (boolean) input.getOrDefault("allDay", false);
            Integer reminderMinutes = (Integer) input.get("reminderMinutes");

            // Validierung
            if (calendarName == null || calendarName.trim().isEmpty()) {
                return AgentToolResult.error("Der Kalendername darf nicht leer sein");
            }

            if (title == null || title.trim().isEmpty()) {
                return AgentToolResult.error("Der Titel darf nicht leer sein");
            }

            if (startStr == null || startStr.trim().isEmpty()) {
                return AgentToolResult.error("Das Startdatum darf nicht leer sein");
            }

            // Kalender finden oder erstellen
            String calendarId = calendarService.getCalendarByName(calendarName)
                    .map(c -> c.getId())
                    .orElseGet(() -> calendarService.createCalendar(calendarName).getId());

            // Datum und Uhrzeit parsen
            LocalDateTime start = parseDateTime(startStr, allDay);
            LocalDateTime end = null;

            if (endStr != null && !endStr.trim().isEmpty()) {
                end = parseDateTime(endStr, allDay);
            } else if (allDay) {
                end = start.plusDays(1);
            } else {
                end = start.plusHours(1);
            }

            // Prüfen, ob Enddatum nach Startdatum liegt
            if (end != null && end.isBefore(start)) {
                return AgentToolResult.error("Das Enddatum muss nach dem Startdatum liegen");
            }

            // Termin erstellen
            Event event = new Event(calendarId, title, start, end);
            event.setLocation(location);
            event.setDetails(details);
            event.setAllDay(allDay);

            // Erinnerung setzen
            if (reminderMinutes != null && reminderMinutes > 0) {
                event.setReminder(start.minusMinutes(reminderMinutes));
            }

            // Prüfen auf Konflikte
            List<Event> conflicts = eventService.checkForConflicts(event);
            
            String message = "Termin '" + title + "' im Kalender '" + calendarName + "' erstellen.";
            if (!conflicts.isEmpty()) {
                message += " Achtung: Es gibt " + conflicts.size() + " Termine mit potenziellen Konflikten!";
            }

            return AgentToolResult.confirmationRequired(
                    message,
                    Map.of("event", event, "conflicts", conflicts)
            );
        } catch (Exception e) {
            return AgentToolResult.error("Fehler beim Erstellen des Termins: " + e.getMessage());
        }
    }

    /**
     * Parsed ein Datum/Uhrzeit-String in ein LocalDateTime.
     */
    private LocalDateTime parseDateTime(String dateTimeStr, boolean allDay) {
        dateTimeStr = dateTimeStr.trim();
        
        try {
            if (allDay) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                return java.time.LocalDate.parse(dateTimeStr, formatter).atStartOfDay();
            } else {
                if (dateTimeStr.contains(" ")) {
                    String[] parts = dateTimeStr.split(" ");
                    String datePart = parts[0];
                    String timePart = parts[1];
                    
                    DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
                    
                    return java.time.LocalDate.parse(datePart, dateFormatter).atTime(
                            LocalTime.parse(timePart, timeFormatter)
                    );
                } else {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    return java.time.LocalDate.parse(dateTimeStr, formatter).atStartOfDay();
                }
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungültiges Datumsformat: " + dateTimeStr);
        }
    }
}
