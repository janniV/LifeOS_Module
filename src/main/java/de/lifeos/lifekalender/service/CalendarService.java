package de.lifeos.lifekalender.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.lifeos.lifekalender.model.Calendar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Verwaltet die Kalender im LifeKalender-Modul.
 * Speichert Kalender als JSON-Datei im Modul-Speicher.
 */
public class CalendarService {
    private final Path storagePath;
    private final ObjectMapper objectMapper;
    private List<Calendar> calendars;

    public CalendarService(Path storagePath) {
        this.storagePath = Objects.requireNonNull(storagePath, "Speicherpfad darf nicht null sein");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.calendars = new ArrayList<>();
        loadCalendars();
    }

    /**
     * Lädt die Kalender aus der JSON-Datei.
     */
    private void loadCalendars() {
        Path calendarsFile = storagePath.resolve("calendars.json");
        if (Files.exists(calendarsFile)) {
            try {
                calendars = objectMapper.readValue(
                        Files.readString(calendarsFile),
                        new TypeReference<List<Calendar>>() {}
                );
            } catch (IOException e) {
                System.err.println("Fehler beim Laden der Kalender: " + e.getMessage());
                calendars = new ArrayList<>();
            }
        } else {
            calendars = new ArrayList<>();
        }
    }

    /**
     * Speichert die Kalender in der JSON-Datei.
     */
    private void saveCalendars() {
        Path calendarsFile = storagePath.resolve("calendars.json");
        try {
            Files.createDirectories(storagePath);
            objectMapper.writeValue(calendarsFile.toFile(), calendars);
        } catch (IOException e) {
            System.err.println("Fehler beim Speichern der Kalender: " + e.getMessage());
        }
    }

    /**
     * Gibt alle Kalender zurück.
     */
    public List<Calendar> getAllCalendars() {
        return new ArrayList<>(calendars);
    }

    /**
     * Gibt einen Kalender anhand seiner ID zurück.
     */
    public Optional<Calendar> getCalendarById(String id) {
        return calendars.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }

    /**
     * Erstellt einen neuen Kalender.
     */
    public Calendar createCalendar(String name) {
        Calendar calendar = new Calendar(name);
        calendars.add(calendar);
        saveCalendars();
        return calendar;
    }

    /**
     * Aktualisiert einen bestehenden Kalender.
     */
    public boolean updateCalendar(Calendar calendar) {
        int index = calendars.indexOf(calendar);
        if (index >= 0) {
            calendars.set(index, calendar);
            saveCalendars();
            return true;
        }
        return false;
    }

    /**
     * Löscht einen Kalender anhand seiner ID.
     * ACHTUNG: Vor dem Löschen sollten alle zugehörigen Termine gelöscht oder umgeordnet werden!
     */
    public boolean deleteCalendar(String id) {
        boolean removed = calendars.removeIf(c -> c.getId().equals(id));
        if (removed) {
            saveCalendars();
        }
        return removed;
    }

    /**
     * Ändert die Sichtbarkeit eines Kalenders.
     */
    public boolean setCalendarVisibility(String id, boolean visible) {
        Optional<Calendar> calendarOpt = getCalendarById(id);
        if (calendarOpt.isPresent()) {
            Calendar calendar = calendarOpt.get();
            calendar.setVisible(visible);
            saveCalendars();
            return true;
        }
        return false;
    }

    /**
     * Gibt alle sichtbaren Kalender zurück.
     */
    public List<Calendar> getVisibleCalendars() {
        return calendars.stream()
                .filter(Calendar::isVisible)
                .toList();
    }

    /**
     * Gibt einen Kalender anhand seines Namens zurück (fallinsensitive).
     */
    public Optional<Calendar> getCalendarByName(String name) {
        return calendars.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Prüft, ob ein Kalender mit dem gegebenen Namen bereits existiert.
     */
    public boolean existsCalendarWithName(String name) {
        return calendars.stream()
                .anyMatch(c -> c.getName().equalsIgnoreCase(name));
    }

    /**
     * Generiert eine eindeutige ID für einen neuen Kalender.
     */
    public String generateUniqueId() {
        return java.util.UUID.randomUUID().toString();
    }
}
