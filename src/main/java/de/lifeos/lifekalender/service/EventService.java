package de.lifeos.lifekalender.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.model.RecurrenceRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Verwaltet die Termine im LifeKalender-Modul.
 * Speichert Termine als JSON-Datei im Modul-Speicher.
 */
public class EventService {
    private final Path storagePath;
    private final ObjectMapper objectMapper;
    private List<Event> events;
    private final CalendarService calendarService;

    public EventService(Path storagePath, CalendarService calendarService) {
        this.storagePath = Objects.requireNonNull(storagePath, "Speicherpfad darf nicht null sein");
        this.calendarService = Objects.requireNonNull(calendarService, "CalendarService darf nicht null sein");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.events = new ArrayList<>();
        loadEvents();
    }

    /**
     * Lädt die Termine aus der JSON-Datei.
     */
    private void loadEvents() {
        Path eventsFile = storagePath.resolve("events.json");
        if (Files.exists(eventsFile)) {
            try {
                events = objectMapper.readValue(
                        Files.readString(eventsFile),
                        new TypeReference<List<Event>>() {}
                );
            } catch (IOException e) {
                System.err.println("Fehler beim Laden der Termine: " + e.getMessage());
                events = new ArrayList<>();
            }
        } else {
            events = new ArrayList<>();
        }
    }

    /**
     * Speichert die Termine in der JSON-Datei.
     */
    private void saveEvents() {
        Path eventsFile = storagePath.resolve("events.json");
        try {
            Files.createDirectories(storagePath);
            objectMapper.writeValue(eventsFile.toFile(), events);
        } catch (IOException e) {
            System.err.println("Fehler beim Speichern der Termine: " + e.getMessage());
        }
    }

    /**
     * Gibt alle Termine zurück.
     */
    public List<Event> getAllEvents() {
        return new ArrayList<>(events);
    }

    /**
     * Gibt einen Termin anhand seiner ID zurück.
     */
    public Optional<Event> getEventById(String id) {
        return events.stream()
                .filter(e -> e.getId().equals(id))
                .findFirst();
    }

    /**
     * Gibt alle Termine eines bestimmten Kalenders zurück.
     */
    public List<Event> getEventsByCalendar(String calendarId) {
        return events.stream()
                .filter(e -> e.getCalendarId().equals(calendarId))
                .toList();
    }

    /**
     * Erstellt einen neuen Termin.
     */
    public Event createEvent(Event event) {
        Objects.requireNonNull(event, "Termin darf nicht null sein");
        
        // Prüfen, ob der Kalender existiert
        if (!calendarService.getCalendarById(event.getCalendarId()).isPresent()) {
            throw new IllegalArgumentException("Kalender mit ID " + event.getCalendarId() + " existiert nicht");
        }
        
        events.add(event);
        saveEvents();
        return event;
    }

    /**
     * Aktualisiert einen bestehenden Termin.
     */
    public boolean updateEvent(Event event) {
        Objects.requireNonNull(event, "Termin darf nicht null sein");
        
        int index = events.indexOf(event);
        if (index >= 0) {
            // Prüfen, ob der Kalender existiert
            if (!calendarService.getCalendarById(event.getCalendarId()).isPresent()) {
                throw new IllegalArgumentException("Kalender mit ID " + event.getCalendarId() + " existiert nicht");
            }
            events.set(index, event);
            saveEvents();
            return true;
        }
        return false;
    }

    /**
     * Löscht einen Termin anhand seiner ID.
     */
    public boolean deleteEvent(String id) {
        boolean removed = events.removeIf(e -> e.getId().equals(id));
        if (removed) {
            saveEvents();
        }
        return removed;
    }

    /**
     * Löscht alle Termine eines bestimmten Kalenders.
     */
    public int deleteEventsByCalendar(String calendarId) {
        int count = (int) events.stream()
                .filter(e -> e.getCalendarId().equals(calendarId))
                .count();
        events.removeIf(e -> e.getCalendarId().equals(calendarId));
        saveEvents();
        return count;
    }

    /**
     * Gibt alle Termine an einem bestimmten Tag zurück.
     */
    public List<Event> getEventsByDate(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        
        return events.stream()
                .filter(e -> {
                    if (e.isAllDay()) {
                        // Ganztägige Termine: Prüfen, ob der Tag im Bereich liegt
                        return !e.getStart().toLocalDate().isAfter(date) && 
                               !e.getEnd().toLocalDate().isBefore(date);
                    } else {
                        // Normale Termine: Prüfen, ob sie sich mit dem Tag überschneiden
                        return !e.getEnd().isBefore(startOfDay) && 
                               !e.getStart().isAfter(endOfDay);
                    }
                })
                .toList();
    }

    /**
     * Gibt alle Termine in einem bestimmten Zeitraum zurück.
     */
    public List<Event> getEventsInRange(LocalDateTime start, LocalDateTime end) {
        return events.stream()
                .filter(e -> {
                    if (e.isAllDay()) {
                        // Ganztägige Termine: Prüfen, ob sie sich mit dem Zeitraum überschneiden
                        return !e.getEnd().toLocalDate().isBefore(start.toLocalDate()) &&
                               !e.getStart().toLocalDate().isAfter(end.toLocalDate());
                    } else {
                        // Normale Termine
                        return !e.getEnd().isBefore(start) && 
                               !e.getStart().isAfter(end);
                    }
                })
                .toList();
    }

    /**
     * Prüft, ob ein Termin mit anderen Terminen im selben Kalender kollidiert.
     */
    public List<Event> checkForConflicts(Event event) {
        return events.stream()
                .filter(e -> e.getCalendarId().equals(event.getCalendarId()))
                .filter(e -> !e.getId().equals(event.getId()))
                .filter(e -> e.hasConflict(event))
                .toList();
    }

    /**
     * Erstellt wiederkehrende Termine basierend auf einer Wiederholungsregel.
     * Erstellt Termine bis zum Enddatum oder bis zur maximalen Anzahl von Wiederholungen.
     */
    public List<Event> createRecurringEvents(Event baseEvent, RecurrenceRule rule) {
        List<Event> recurringEvents = new ArrayList<>();
        
        if (rule == null || baseEvent.getRecurrenceRule() != null) {
            return recurringEvents; // Keine Wiederholung oder bereits wiederkehrend
        }
        
        LocalDateTime currentStart = baseEvent.getStart();
        LocalDateTime currentEnd = baseEvent.getEnd();
        int occurrences = 0;
        
        while (true) {
            // Überspringe das erste Vorkommen (das ist der Basis-Termin)
            if (occurrences > 0) {
                LocalDate nextDate = rule.getNextOccurrence(currentStart.toLocalDate());
                if (nextDate == null) break;
                
                // Berechne die neue Start- und Endzeit
                LocalTime startTime = currentStart.toLocalTime();
                LocalTime endTime = currentEnd.toLocalTime();
                currentStart = nextDate.atTime(startTime);
                currentEnd = nextDate.atTime(endTime);
                
                // Prüfen, ob das Enddatum überschritten wurde
                if (rule.getEndDate() != null && currentStart.toLocalDate().isAfter(rule.getEndDate())) {
                    break;
                }
                
                // Prüfen, ob die maximale Anzahl von Wiederholungen erreicht wurde
                if (rule.getMaxOccurrences() > 0 && occurrences >= rule.getMaxOccurrences()) {
                    break;
                }
            }
            
            // Erstelle einen neuen Termin
            Event newEvent = new Event(
                    baseEvent.getCalendarId(),
                    baseEvent.getTitle(),
                    currentStart,
                    currentEnd
            );
            newEvent.setLocation(baseEvent.getLocation());
            newEvent.setDetails(baseEvent.getDetails());
            newEvent.setAllDay(baseEvent.isAllDay());
            newEvent.setRecurrenceRule(rule);
            newEvent.setReminder(baseEvent.getReminder());
            
            recurringEvents.add(newEvent);
            occurrences++;
            
            // Beende die Schleife, wenn keine weiteren Vorkommen möglich sind
            if (rule.getNextOccurrence(currentStart.toLocalDate()) == null) {
                break;
            }
        }
        
        return recurringEvents;
    }

    /**
     * Gibt alle Termine zurück, die eine Erinnerung haben und in der Zukunft liegen.
     */
    public List<Event> getUpcomingReminders() {
        LocalDateTime now = LocalDateTime.now();
        return events.stream()
                .filter(e -> e.getReminder() != null)
                .filter(e -> e.getReminder().isAfter(now))
                .sorted((e1, e2) -> e1.getReminder().compareTo(e2.getReminder()))
                .toList();
    }

    /**
     * Gibt alle Termine zurück, die heute stattfinden.
     */
    public List<Event> getTodaysEvents() {
        return getEventsByDate(LocalDate.now());
    }

    /**
     * Durchsucht Termine nach einem Suchbegriff (Titel, Ort, Details).
     */
    public List<Event> searchEvents(String query) {
        String searchTerm = query.toLowerCase();
        return events.stream()
                .filter(e -> 
                    (e.getTitle() != null && e.getTitle().toLowerCase().contains(searchTerm)) ||
                    (e.getLocation() != null && e.getLocation().toLowerCase().contains(searchTerm)) ||
                    (e.getDetails() != null && e.getDetails().toLowerCase().contains(searchTerm))
                )
                .toList();
    }
}
