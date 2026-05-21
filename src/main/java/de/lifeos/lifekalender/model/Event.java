package de.lifeos.lifekalender.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Repräsentiert einen Termin im LifeKalender-Modul.
 * Ein Termin gehört zu einem Kalender und kann optional eine Wiederholungsregel haben.
 */
public class Event {
    private final String id;
    private String calendarId;
    private String title;
    private LocalDateTime start;
    private LocalDateTime end;
    private String location;
    private String details;
    private RecurrenceRule recurrenceRule;
    private boolean allDay;
    private LocalDateTime reminder; // Erinnerungszeitpunkt (null = keine Erinnerung)

    @JsonCreator
    public Event(
            @JsonProperty("id") String id,
            @JsonProperty("calendarId") String calendarId,
            @JsonProperty("title") String title,
            @JsonProperty("start") @JsonDeserialize(using = LocalDateTimeDeserializer.class) LocalDateTime start,
            @JsonProperty("end") @JsonDeserialize(using = LocalDateTimeDeserializer.class) LocalDateTime end,
            @JsonProperty("location") String location,
            @JsonProperty("details") String details,
            @JsonProperty("recurrenceRule") RecurrenceRule recurrenceRule,
            @JsonProperty("allDay") boolean allDay,
            @JsonProperty("reminder") @JsonDeserialize(using = LocalDateTimeDeserializer.class) LocalDateTime reminder) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
        this.calendarId = Objects.requireNonNull(calendarId, "calendarId darf nicht null sein");
        this.title = Objects.requireNonNull(title, "Titel darf nicht null sein");
        this.start = Objects.requireNonNull(start, "Startdatum darf nicht null sein");
        this.end = (end != null) ? end : start.plusHours(1); // Standard: 1 Stunde Dauer
        this.location = location;
        this.details = details;
        this.recurrenceRule = recurrenceRule;
        this.allDay = allDay;
        this.reminder = reminder;
    }

    public Event(String calendarId, String title, LocalDateTime start, LocalDateTime end) {
        this(UUID.randomUUID().toString(), calendarId, title, start, end, null, null, null, false, null);
    }

    // Getter und Setter
    public String getId() {
        return id;
    }

    public String getCalendarId() {
        return calendarId;
    }

    public void setCalendarId(String calendarId) {
        this.calendarId = Objects.requireNonNull(calendarId, "calendarId darf nicht null sein");
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title, "Titel darf nicht null sein");
    }

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    public LocalDateTime getStart() {
        return start;
    }

    public void setStart(LocalDateTime start) {
        this.start = Objects.requireNonNull(start, "Startdatum darf nicht null sein");
    }

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    public LocalDateTime getEnd() {
        return end;
    }

    public void setEnd(LocalDateTime end) {
        this.end = Objects.requireNonNull(end, "Enddatum darf nicht null sein");
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public RecurrenceRule getRecurrenceRule() {
        return recurrenceRule;
    }

    public void setRecurrenceRule(RecurrenceRule recurrenceRule) {
        this.recurrenceRule = recurrenceRule;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public void setAllDay(boolean allDay) {
        this.allDay = allDay;
    }

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    public LocalDateTime getReminder() {
        return reminder;
    }

    public void setReminder(LocalDateTime reminder) {
        this.reminder = reminder;
    }

    /**
     * Prüft, ob der Termin einen Konflikt mit einem anderen Termin hat.
     */
    public boolean hasConflict(Event other) {
        if (this == other) return false;
        if (other == null) return false;
        if (!this.calendarId.equals(other.calendarId)) return false;
        
        // Ganztägige Termine: Konflikt, wenn sich die Tage überschneiden
        if (this.allDay && other.allDay) {
            return !(this.end.toLocalDate().isBefore(other.start.toLocalDate()) || 
                    this.start.toLocalDate().isAfter(other.end.toLocalDate()));
        }
        
        // Normale Termine: Konflikt, wenn sich die Zeiten überschneiden
        return !(this.end.isBefore(other.start) || this.start.isAfter(other.end));
    }

    /**
     * Berechnet die Dauer des Termins in Minuten.
     */
    public long getDurationMinutes() {
        if (allDay) {
            return java.time.Duration.between(start, end).toMinutes();
        }
        return java.time.Duration.between(start, end).toMinutes();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Event event = (Event) o;
        return id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Event{" +
                "id='" + id + '\'' +
                ", calendarId='" + calendarId + '\'' +
                ", title='" + title + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", location='" + location + '\'' +
                ", details='" + details + '\'' +
                ", recurrenceRule=" + recurrenceRule +
                ", allDay=" + allDay +
                ", reminder=" + reminder +
                '}';
    }
}
