package de.lifeos.lifekalender.tools;

import de.lifeos.lifekalender.model.Calendar;
import de.lifeos.lifekalender.service.CalendarService;
import core.AITool;
import core.AIToolSafety;
import core.AgentToolResult;
import java.util.Map;

/**
 * AI-Tool zum Erstellen eines neuen Kalenders.
 */
public class CreateCalendarTool implements AITool {
    private final CalendarService calendarService;

    public CreateCalendarTool(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Override
    public String getName() {
        return "lifekalender_create_calendar";
    }

    @Override
    public String getDescription() {
        return "Erstelle einen neuen Kalender mit einem bestimmten Namen. " +
                "Der Kalender erhält automatisch eine zufällige Farbe.";
    }

    @Override
    public String getInputSchema() {
        return "{\n" +
               "    \"type\": \"object\",\n" +
               "    \"properties\": {\n" +
               "        \"name\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Name des neuen Kalenders\",\n" +
               "            \"minLength\": 1,\n" +
               "            \"maxLength\": 50\n" +
               "        },\n" +
               "        \"color\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Die Farbe des Kalenders im Hex-Format (z.B. #FF5733), optional\",\n" +
               "            \"pattern\": \"^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$\"\n" +
               "        }\n" +
               "    },\n" +
               "    \"required\": [\"name\"]\n" +
               "}";
    }

    @Override
    public AIToolSafety getSafety() {
        return AIToolSafety.USER_DATA_WRITE;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input) {
        try {
            String name = (String) input.get("name");
            String color = (String) input.get("color");

            if (name == null || name.trim().isEmpty()) {
                return AgentToolResult.error("Der Kalendername darf nicht leer sein");
            }

            if (name.length() > 50) {
                return AgentToolResult.error("Der Kalendername darf nicht länger als 50 Zeichen sein");
            }

            if (calendarService.existsCalendarWithName(name)) {
                return AgentToolResult.error("Ein Kalender mit dem Namen '" + name + "' existiert bereits");
            }

            Calendar calendar;
            if (color != null && !color.isBlank()) {
                calendar = new Calendar(calendarService.generateUniqueId(), name, color, true);
                calendarService.getAllCalendars().add(calendar);
                calendarService.updateCalendar(calendar);
            } else {
                calendar = calendarService.createCalendar(name);
            }

            return AgentToolResult.success(
                    "Kalender '" + name + "' erfolgreich erstellt",
                    Map.of("calendarId", calendar.getId(), "name", calendar.getName(), "color", calendar.getColor())
            );
        } catch (Exception e) {
            return AgentToolResult.error("Fehler beim Erstellen des Kalenders: " + e.getMessage());
        }
    }
}
