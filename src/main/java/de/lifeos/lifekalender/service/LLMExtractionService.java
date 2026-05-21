package de.lifeos.lifekalender.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.lifeos.lifekalender.model.Calendar;
import de.lifeos.lifekalender.model.Event;
import core.CoreServices;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dienst zur Extraktion von Terminen aus Texten, PDFs und Bildern mithilfe des LifeOS-internen LLM.
 */
public class LLMExtractionService {
    private final CoreServices core;
    private final CalendarService calendarService;
    private final EventService eventService;
    private final ObjectMapper objectMapper;

    // Korrigierte Regex-Patterns mit Escape-Sequenzen
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})|(\\d{4}-\\d{2}-\\d{2})|(\\d{1,2}/\\d{1,2}/\\d{2,4})|(\\d{1,2}\\.\\s*[A-Za-z]{3,})|([A-Za-z]{3,}\\s*\\d{1,2},?\\s*\\d{4})"
    );
    
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d{1,2}:\\d{2})|(\\d{1,2}:\\d{2}:\\d{2})|(\\d{1,2}\\s*[ap]m)"
    );

    public LLMExtractionService(CoreServices core, CalendarService calendarService, EventService eventService) {
        this.core = Objects.requireNonNull(core, "CoreServices darf nicht null sein");
        this.calendarService = Objects.requireNonNull(calendarService, "CalendarService darf nicht null sein");
        this.eventService = Objects.requireNonNull(eventService, "EventService darf nicht null sein");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extrahiere Termine aus einem Text mithilfe des LLM.
     * @param text Der zu analysierende Text
     * @return Liste der extrahierten Termine
     */
    public List<Event> extractEventsFromText(String text) {
        List<Event> events = new ArrayList<>();
        
        if (text == null || text.trim().isEmpty()) {
            return events;
        }

        try {
            // Prompt für das LLM
            String prompt = buildExtractionPrompt(text);
            
            // LLM-Aufruf
            String llmResponse = core.completeWithInternalLLM(prompt);
            
            // Parsen der LLM-Antwort
            events = parseLLMResponse(llmResponse);
        } catch (Exception e) {
            System.err.println("Fehler bei der Terminextraktion: " + e.getMessage());
        }
        
        return events;
    }

    /**
     * Extrahiere Termine aus einem PDF-Dokument.
     * @param pdfContent Der Inhalt des PDFs als Text
     * @return Liste der extrahierten Termine
     */
    public List<Event> extractEventsFromPDF(String pdfContent) {
        return extractEventsFromText(pdfContent);
    }

    /**
     * Extrahiere Termine aus einem Bild (OCR-Text).
     * @param imageText Der extrahierte Text aus dem Bild
     * @return Liste der extrahierten Termine
     */
    public List<Event> extractEventsFromImage(String imageText) {
        return extractEventsFromText(imageText);
    }

    /**
     * Erstellt einen Prompt für die Terminextraktion.
     */
    private String buildExtractionPrompt(String text) {
        return "Analysiere den folgenden Text und extrahiere alle Termine im JSON-Format. " +
                "Jeder Termin sollte folgende Felder enthalten: " +
                "title (Titel des Termins, Pflichtfeld), " +
                "date (Datum im Format YYYY-MM-DD, Pflichtfeld), " +
                "startTime (Startzeit im Format HH:MM, optional), " +
                "endTime (Endzeit im Format HH:MM, optional), " +
                "location (Ort, optional), " +
                "details (Details/Beschreibung, optional), " +
                "allDay (boolean, ob ganztägig, optional, Standard: false). " +
                "Beispiel: [{\"title\": \"Meeting\", \"date\": \"2024-10-15\", \"startTime\": \"14:00\", \"endTime\": \"15:00\"}] " +
                "Text zur Analyse: " + text + " " +
                "Wichtig: Gib NUR das JSON-Array zurück, ohne zusätzliche Erklärungen. " +
                "Wenn keine Termine gefunden werden, gib ein leeres Array [] zurück. " +
                "Achte darauf, dass alle Daten gültig sind. " +
                "Versuche, den Kalender zu erkennen und füge ein Feld \"suggestedCalendar\" hinzu, falls möglich.";
    }

    /**
     * Parsed die LLM-Antwort und erstellt Event-Objekte.
     */
    private List<Event> parseLLMResponse(String llmResponse) {
        List<Event> events = new ArrayList<>();
        
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            return events;
        }

        try {
            // Versuche, das JSON-Array zu parsen
            JsonNode rootNode = objectMapper.readTree(llmResponse);
            
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    Event event = parseEventNode(node);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Parsen der LLM-Antwort: " + e.getMessage());
            // Fallback: Versuche, Termine mit Regex zu extrahieren
            events = extractEventsWithRegex(llmResponse);
        }
        
        return events;
    }

    /**
     * Parsed einen JSON-Knoten in ein Event-Objekt.
     */
    private Event parseEventNode(JsonNode node) {
        try {
            String title = node.path("title").asText();
            if (title.isBlank()) {
                return null;
            }

            String dateStr = node.path("date").asText();
            LocalDate date = parseDate(dateStr);
            if (date == null) {
                return null;
            }

            boolean allDay = node.path("allDay").asBoolean(false);
            LocalTime startTime = null;
            LocalTime endTime = null;

            if (!allDay) {
                String startTimeStr = node.path("startTime").asText();
                String endTimeStr = node.path("endTime").asText();
                
                startTime = parseTime(startTimeStr);
                endTime = parseTime(endTimeStr);
                
                // Falls keine Endzeit angegeben ist, 1 Stunde annehmen
                if (endTime == null && startTime != null) {
                    endTime = startTime.plusHours(1);
                }
            }

            LocalDateTime start = allDay ? 
                date.atStartOfDay() : 
                date.atTime(startTime != null ? startTime : LocalTime.of(9, 0));
            
            LocalDateTime end = allDay ? 
                date.atTime(LocalTime.MAX) : 
                date.atTime(endTime != null ? endTime : LocalTime.of(10, 0));

            // Falls Endzeit vor Startzeit liegt, korrigieren
            if (end.isBefore(start)) {
                end = start.plusHours(1);
            }

            String location = node.path("location").asText(null);
            String details = node.path("details").asText(null);

            // Standard-Kalender (wird später durch den Nutzer bestätigt)
            String calendarId = "privat";
            
            // Versuche, den vorgeschlagenen Kalender zu verwenden
            String suggestedCalendar = node.path("suggestedCalendar").asText();
            if (!suggestedCalendar.isBlank()) {
                Optional<Calendar> calendar = calendarService.getCalendarByName(suggestedCalendar);
                if (calendar.isPresent()) {
                    calendarId = calendar.get().getId();
                }
            }

            Event event = new Event(calendarId, title, start, end);
            event.setLocation(location);
            event.setDetails(details);
            event.setAllDay(allDay);
            
            return event;
        } catch (Exception e) {
            System.err.println("Fehler beim Parsen eines Termins: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parsed ein Datum aus einem String.
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        // Versuche verschiedene Formate
        String[] formats = {
            "yyyy-MM-dd", "dd.MM.yyyy", "MM/dd/yyyy", "dd/MM/yyyy",
            "d.M.yyyy", "M/d/yyyy", "yyyy/MM/dd", "dd-MMM-yyyy"
        };

        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                return LocalDate.parse(dateStr, formatter);
            } catch (DateTimeParseException e) {
                // Ignorieren und nächstes Format versuchen
            }
        }

        // Versuche, das Jahr zu ergänzen (falls nur Tag und Monat)
        if (dateStr.matches("\\d{1,2}\\.\\d{1,2}")) {
            int year = LocalDate.now().getYear();
            return LocalDate.parse(dateStr + "." + year, DateTimeFormatter.ofPattern("d.M.yyyy"));
        }

        return null;
    }

    /**
     * Parsed eine Uhrzeit aus einem String.
     */
    private LocalTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return null;
        }

        // Versuche verschiedene Formate
        String[] formats = {"HH:mm", "HH:mm:ss"};

        for (String format : formats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                return LocalTime.parse(timeStr, formatter);
            } catch (DateTimeParseException e) {
                // Ignorieren und nächstes Format versuchen
            }
        }

        // Versuche, AM/PM zu parsen
        if (timeStr.toLowerCase().contains("am") || timeStr.toLowerCase().contains("pm")) {
            String cleanTime = timeStr.replaceAll("(?i)(am|pm)", "").trim();
            String[] parts = cleanTime.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            
            if (timeStr.toLowerCase().contains("pm") && hour < 12) {
                hour += 12;
            } else if (timeStr.toLowerCase().contains("am") && hour == 12) {
                hour = 0;
            }
            
            return LocalTime.of(hour, minute);
        }

        return null;
    }

    /**
     * Extrahiere Termine mit Regex als Fallback.
     */
    private List<Event> extractEventsWithRegex(String text) {
        List<Event> events = new ArrayList<>();
        
        // Suche nach Datumsangaben
        Matcher dateMatcher = DATE_PATTERN.matcher(text);
        Matcher timeMatcher = TIME_PATTERN.matcher(text);

        // Einfache Heuristik: Suche nach Zeilen, die Datum und Uhrzeit enthalten
        String[] lines = text.split("\n");
        for (String line : lines) {
            dateMatcher.reset(line);
            timeMatcher.reset(line);
            if (dateMatcher.find() && timeMatcher.find()) {
                // Versuche, einen Termin zu extrahieren
                String dateStr = dateMatcher.group();
                String timeStr = timeMatcher.group();
                
                LocalDate date = parseDate(dateStr);
                LocalTime time = parseTime(timeStr);
                
                if (date != null && time != null) {
                    // Erstelle einen Termin mit Standardwerten
                    Event event = new Event(
                            "privat",
                            "Termin am " + date,
                            date.atTime(time),
                            date.atTime(time.plusHours(1))
                    );
                    events.add(event);
                }
            }
        }
        
        return events;
    }

    /**
     * Vorschlag für die Zuordnung eines Termins zu einem Kalender.
     * @param event Der Termin
     * @return Vorschlag für den Kalendernamen
     */
    public String suggestCalendarForEvent(Event event) {
        String title = event.getTitle().toLowerCase();
        String details = event.getDetails() != null ? event.getDetails().toLowerCase() : "";
        String location = event.getLocation() != null ? event.getLocation().toLowerCase() : "";
        
        // Keywords für verschiedene Kalender
        String[] uniKeywords = {"uni", "vorlesung", "seminar", "prüfung", "studium", "hochschule"};
        String[] gardenKeywords = {"garten", "pflanzen", "gärtnern", "beet", "rasen"};
        String[] workKeywords = {"arbeit", "büro", "meeting", "besprechung", "projekt"};
        String[] privateKeywords = {"privat", "familie", "freunde", "geburtstag", "urlaub"};
        
        // Prüfe Keywords
        for (String keyword : uniKeywords) {
            if (title.contains(keyword) || details.contains(keyword) || location.contains(keyword)) {
                return "Uni";
            }
        }
        
        for (String keyword : gardenKeywords) {
            if (title.contains(keyword) || details.contains(keyword) || location.contains(keyword)) {
                return "Garten";
            }
        }
        
        for (String keyword : workKeywords) {
            if (title.contains(keyword) || details.contains(keyword) || location.contains(keyword)) {
                return "Arbeit";
            }
        }
        
        // Standard: Privat
        return "Privat";
    }

    /**
     * Schlage einen Kalender für eine Liste von Terminen vor.
     */
    public String suggestCalendarForEvents(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return "Privat";
        }

        // Zähle die Häufigkeit der vorgeschlagenen Kalender
        java.util.Map<String, Integer> calendarCounts = new java.util.HashMap<>();
        for (Event event : events) {
            String suggested = suggestCalendarForEvent(event);
            calendarCounts.put(suggested, calendarCounts.getOrDefault(suggested, 0) + 1);
        }

        // Gib den häufigsten Kalender zurück
        return calendarCounts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse("Privat");
    }
}
