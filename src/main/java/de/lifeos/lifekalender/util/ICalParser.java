package de.lifeos.lifekalender.util;

import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.model.RecurrenceRule;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Date;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Dur;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Hilfsklasse zum Parsen und Generieren von iCal (ICS)-Dateien.
 * Nutzt die iCal4j-Bibliothek.
 */
public class ICalParser {

    /**
     * Parsed eine ICS-Datei und gibt eine Liste von Terminen zurück.
     * @param icsContent Der Inhalt der ICS-Datei als String
     * @return Liste der geparsten Termine
     */
    public static List<Event> parseICal(String icsContent, String defaultCalendarId) {
        List<Event> events = new ArrayList<>();
        
        try {
            InputStream inputStream = new ByteArrayInputStream(icsContent.getBytes(StandardCharsets.UTF_8));
            CalendarBuilder builder = new CalendarBuilder();
            Calendar calendar = builder.build(inputStream);
            
            for (Object component : calendar.getComponents()) {
                if (component instanceof VEvent) {
                    VEvent vEvent = (VEvent) component;
                    Event event = parseVEvent(vEvent, defaultCalendarId);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        } catch (ParserException | IOException e) {
            System.err.println("Fehler beim Parsen der ICS-Datei: " + e.getMessage());
        }
        
        return events;
    }

    /**
     * Parsed ein einzelnes VEvent-Objekt in ein Event-Objekt.
     */
    private static Event parseVEvent(VEvent vEvent, String defaultCalendarId) {
        try {
            // Titel
            String title = vEvent.getSummary() != null ? 
                vEvent.getSummary().getValue() : "Unbenannter Termin";
            
            // Beschreibung
            String details = vEvent.getDescription() != null ? 
                vEvent.getDescription().getValue() : null;
            
            // Ort
            String location = vEvent.getLocation() != null ? 
                vEvent.getLocation().getValue() : null;
            
            // Startdatum/zeit
            Property startProperty = vEvent.getStartDate();
            LocalDateTime start = parseDateProperty(startProperty);
            
            // Enddatum/zeit
            Property endProperty = vEvent.getEndDate();
            LocalDateTime end = null;
            if (endProperty != null) {
                end = parseDateProperty(endProperty);
            } else {
                // Falls keine Endzeit angegeben ist, Dauer verwenden
                Property durationProperty = vEvent.getDuration();
                if (durationProperty != null) {
                    Dur duration = (Dur) durationProperty;
                    end = start.plus(duration.getPeriod());
                } else {
                    // Standard: 1 Stunde
                    end = start.plusHours(1);
                }
            }
            
            // Ganztägiger Termin?
            boolean allDay = startProperty instanceof Date;
            
            // Erstelle das Event
            Event event = new Event(defaultCalendarId, title, start, end);
            event.setLocation(location);
            event.setDetails(details);
            event.setAllDay(allDay);
            
            // Wiederholungsregel (RRULE)
            RRule rrule = vEvent.getProperty(RRule.RRULE);
            if (rrule != null) {
                RecurrenceRule recurrenceRule = parseRRule(rrule);
                event.setRecurrenceRule(recurrenceRule);
            }
            
            // Erinnerung (VALARM)
            VAlarm valarm = (VAlarm) vEvent.getProperty(VAlarm.VALARM);
            if (valarm != null) {
                // Einfache Implementierung: 10 Minuten vor Start
                // (Könnte erweitert werden für komplexere Erinnerungen)
                event.setReminder(start.minusMinutes(10));
            }
            
            return event;
        } catch (Exception e) {
            System.err.println("Fehler beim Parsen des VEvent: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parsed ein Date/DateTime-Property in ein LocalDateTime.
     */
    private static LocalDateTime parseDateProperty(Property property) {
        if (property instanceof DateTime) {
            DateTime dateTime = (DateTime) property;
            return dateTime.toString().contains("T") ?
                LocalDateTime.parse(dateTime.toString().replace("T", " ").split("[+Z]")[0]) :
                LocalDate.parse(dateTime.toString()).atStartOfDay();
        } else if (property instanceof Date) {
            Date date = (Date) property;
            return date.toString().contains("T") ?
                LocalDateTime.parse(date.toString().replace("T", " ").split("[+Z]")[0]) :
                LocalDate.parse(date.toString()).atStartOfDay();
        }
        return LocalDateTime.now();
    }

    /**
     * Parsed eine RRULE in eine RecurrenceRule.
     */
    private static RecurrenceRule parseRRule(RRule rrule) {
        try {
            RecurrenceRule.Frequency frequency;
            switch (rrule.getRecur().getFrequency()) {
                case DAILY:
                    frequency = RecurrenceRule.Frequency.DAILY;
                    break;
                case WEEKLY:
                    frequency = RecurrenceRule.Frequency.WEEKLY;
                    break;
                case MONTHLY:
                    frequency = RecurrenceRule.Frequency.MONTHLY;
                    break;
                case YEARLY:
                    frequency = RecurrenceRule.Frequency.YEARLY;
                    break;
                default:
                    frequency = RecurrenceRule.Frequency.DAILY;
            }
            
            int interval = rrule.getRecur().getInterval();
            
            // Für wöchentliche Wiederholung: Tage der Woche
            java.util.List<java.time.DayOfWeek> daysOfWeek = new ArrayList<>();
            if (frequency == RecurrenceRule.Frequency.WEEKLY && rrule.getRecur().getDayList() != null) {
                for (net.fortuna.ical4j.model.WeekDay day : rrule.getRecur().getDayList()) {
                    daysOfWeek.add(java.time.DayOfWeek.valueOf(day.name()));
                }
            }
            
            // Für monatliche/jährliche Wiederholung: Tag des Monats
            int dayOfMonth = 0;
            if (frequency == RecurrenceRule.Frequency.MONTHLY || frequency == RecurrenceRule.Frequency.YEARLY) {
                dayOfMonth = rrule.getRecur().getDayOfMonth();
            }
            
            // Für jährliche Wiederholung: Monat
            int monthOfYear = 0;
            if (frequency == RecurrenceRule.Frequency.YEARLY) {
                monthOfYear = rrule.getRecur().getMonth();
            }
            
            // Enddatum
            LocalDate endDate = null;
            if (rrule.getRecur().getUntil() != null) {
                endDate = LocalDate.parse(rrule.getRecur().getUntil().toString().split("T")[0]);
            }
            
            // Maximale Wiederholungen
            int maxOccurrences = rrule.getRecur().getCount();
            
            return new RecurrenceRule(
                    frequency,
                    interval,
                    daysOfWeek.toArray(new java.time.DayOfWeek[0]),
                    dayOfMonth,
                    monthOfYear,
                    endDate,
                    maxOccurrences
            );
        } catch (Exception e) {
            System.err.println("Fehler beim Parsen der RRULE: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generiert eine ICS-Datei aus einer Liste von Terminen.
     * @param events Liste der Termine
     * @param calendarName Name des Kalenders
     * @return ICS-Content als String
     */
    public static String generateICal(List<Event> events, String calendarName) {
        try {
            Calendar calendar = new Calendar();
            calendar.getProperties().add(new ProdId("-//LifeOS LifeKalender//DE"));
            calendar.getProperties().add(Version.VERSION_2_0);
            calendar.getProperties().add(new Calscale("GREGORIAN"));
            calendar.getProperties().add(new Method(Method.PUBLISH));
            
            for (Event event : events) {
                VEvent vEvent = createVEvent(event);
                calendar.getComponents().add(vEvent);
            }
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CalendarOutputter outputter = new CalendarOutputter();
            outputter.output(calendar, outputStream);
            
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            System.err.println("Fehler beim Generieren der ICS-Datei: " + e.getMessage());
            return null;
        }
    }

    /**
     * Erstellt ein VEvent-Objekt aus einem Event-Objekt.
     */
    private static VEvent createVEvent(Event event) {
        VEvent vEvent = new VEvent();
        
        // UID (eindeutige ID)
        vEvent.getProperties().add(new Uid(event.getId()));
        
        // Titel
        vEvent.getProperties().add(new Summary(event.getTitle()));
        
        // Beschreibung
        if (event.getDetails() != null && !event.getDetails().isBlank()) {
            vEvent.getProperties().add(new Description(event.getDetails()));
        }
        
        // Ort
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            vEvent.getProperties().add(new Location(event.getLocation()));
        }
        
        // Startdatum/zeit
        if (event.isAllDay()) {
            Date startDate = new Date(event.getStart().toLocalDate());
            vEvent.getProperties().add(startDate);
        } else {
            DateTime startDateTime = new DateTime(event.getStart());
            vEvent.getProperties().add(startDateTime);
        }
        
        // Enddatum/zeit
        if (event.isAllDay()) {
            Date endDate = new Date(event.getEnd().toLocalDate().plusDays(1)); // Ganztägig: Ende ist nächster Tag
            vEvent.getProperties().add(new EndDate(endDate));
        } else {
            DateTime endDateTime = new DateTime(event.getEnd());
            vEvent.getProperties().add(new EndDate(endDateTime));
        }
        
        // Erinnerung (VALARM)
        if (event.getReminder() != null) {
            VAlarm valarm = new VAlarm();
            valarm.getProperties().add(new Action(Action.DISPLAY));
            valarm.getProperties().add(new Description("Erinnerung: " + event.getTitle()));
            
            // Trigger: 10 Minuten vor Start (könnte dynamisch berechnet werden)
            Dur trigger = new Dur(-10, 0, 0, 0); // -PT10M
            valarm.getProperties().add(new Trigger(trigger));
            
            vEvent.getProperties().add(valarm);
        }
        
        // Wiederholungsregel (RRULE)
        if (event.getRecurrenceRule() != null) {
            RecurrenceRule rule = event.getRecurrenceRule();
            RRule rrule = createRRule(rule, event.getStart());
            if (rrule != null) {
                vEvent.getProperties().add(rrule);
            }
        }
        
        return vEvent;
    }

    /**
     * Erstellt eine RRULE aus einer RecurrenceRule.
     */
    private static RRule createRRule(RecurrenceRule rule, LocalDateTime startDate) {
        try {
            net.fortuna.ical4j.model.Recur recur = new net.fortuna.ical4j.model.Recur(
                    net.fortuna.ical4j.model.Recur.Frequency.valueOf(rule.getFrequency().name()),
                    rule.getInterval()
            );
            
            // Für wöchentliche Wiederholung: Tage der Woche
            if (rule.getFrequency() == RecurrenceRule.Frequency.WEEKLY && rule.getDaysOfWeek().length > 0) {
                net.fortuna.ical4j.model.WeekDayList dayList = new net.fortuna.ical4j.model.WeekDayList();
                for (java.time.DayOfWeek day : rule.getDaysOfWeek()) {
                    dayList.add(net.fortuna.ical4j.model.WeekDay.valueOf(day.name()));
                }
                recur.setDayList(dayList);
            }
            
            // Für monatliche Wiederholung: Tag des Monats
            if (rule.getFrequency() == RecurrenceRule.Frequency.MONTHLY && rule.getDayOfMonth() != 0) {
                recur.setDayOfMonth(rule.getDayOfMonth());
            }
            
            // Für jährliche Wiederholung: Monat und Tag
            if (rule.getFrequency() == RecurrenceRule.Frequency.YEARLY) {
                recur.setMonth(rule.getMonthOfYear());
                recur.setDayOfMonth(rule.getDayOfMonth());
            }
            
            // Enddatum
            if (rule.getEndDate() != null) {
                recur.setUntil(new Date(rule.getEndDate()));
            }
            
            // Maximale Wiederholungen
            if (rule.getMaxOccurrences() > 0) {
                recur.setCount(rule.getMaxOccurrences());
            }
            
            return new RRule(recur);
        } catch (Exception e) {
            System.err.println("Fehler beim Erstellen der RRULE: " + e.getMessage());
            return null;
        }
    }
}
