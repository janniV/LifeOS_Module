package de.lifeos.lifekalender.util;

import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.model.RecurrenceRule;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Date;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Month;
import net.fortuna.ical4j.model.MonthList;
import net.fortuna.ical4j.model.NumberList;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.WeekDay;
import net.fortuna.ical4j.model.WeekDayList;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Hilfsklasse zum Parsen und Generieren von iCal (ICS)-Dateien.
 * Nutzt die iCal4j-Bibliothek (3.2.x).
 */
public class ICalParser {

    /**
     * Parsed eine ICS-Datei und gibt eine Liste von Terminen zurueck.
     *
     * @param icsContent        Der Inhalt der ICS-Datei als String
     * @param defaultCalendarId Kalender-ID, der die geparsten Termine zugeordnet werden
     * @return Liste der geparsten Termine
     */
    public static List<Event> parseICal(String icsContent, String defaultCalendarId) {
        List<Event> events = new ArrayList<>();
        try {
            InputStream inputStream = new ByteArrayInputStream(icsContent.getBytes(StandardCharsets.UTF_8));
            Calendar calendar = new CalendarBuilder().build(inputStream);

            for (Component component : calendar.getComponents()) {
                if (component instanceof VEvent) {
                    Event event = parseVEvent((VEvent) component, defaultCalendarId);
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
            String title = vEvent.getSummary() != null
                    ? vEvent.getSummary().getValue() : "Unbenannter Termin";
            String details = vEvent.getDescription() != null
                    ? vEvent.getDescription().getValue() : null;
            String location = vEvent.getLocation() != null
                    ? vEvent.getLocation().getValue() : null;

            DtStart dtStart = vEvent.getStartDate();
            if (dtStart == null || dtStart.getDate() == null) {
                return null;
            }
            Date startDate = dtStart.getDate();
            boolean allDay = !(startDate instanceof DateTime);
            LocalDateTime start = toLocalDateTime(startDate);

            LocalDateTime end;
            DtEnd dtEnd = vEvent.getEndDate();
            if (dtEnd != null && dtEnd.getDate() != null) {
                end = toLocalDateTime(dtEnd.getDate());
            } else {
                // Ohne explizites Enddatum: Standarddauer von einer Stunde.
                end = start.plusHours(1);
            }

            Event event = new Event(defaultCalendarId, title, start, end);
            event.setLocation(location);
            event.setDetails(details);
            event.setAllDay(allDay);

            RRule rrule = vEvent.getProperty("RRULE");
            if (rrule != null) {
                RecurrenceRule recurrenceRule = parseRRule(rrule);
                if (recurrenceRule != null) {
                    event.setRecurrenceRule(recurrenceRule);
                }
            }

            // Liegt eine Erinnerung (VALARM) vor, wird sie vereinfacht auf
            // 10 Minuten vor Beginn gesetzt.
            if (!vEvent.getAlarms().isEmpty()) {
                event.setReminder(start.minusMinutes(10));
            }
            return event;
        } catch (Exception e) {
            System.err.println("Fehler beim Parsen des VEvent: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parsed eine RRULE in eine RecurrenceRule.
     */
    private static RecurrenceRule parseRRule(RRule rrule) {
        try {
            Recur recur = rrule.getRecur();

            RecurrenceRule.Frequency frequency;
            switch (recur.getFrequency()) {
                case WEEKLY:
                    frequency = RecurrenceRule.Frequency.WEEKLY;
                    break;
                case MONTHLY:
                    frequency = RecurrenceRule.Frequency.MONTHLY;
                    break;
                case YEARLY:
                    frequency = RecurrenceRule.Frequency.YEARLY;
                    break;
                case DAILY:
                default:
                    frequency = RecurrenceRule.Frequency.DAILY;
            }

            int interval = recur.getInterval() > 0 ? recur.getInterval() : 1;

            List<DayOfWeek> daysOfWeek = new ArrayList<>();
            if (frequency == RecurrenceRule.Frequency.WEEKLY && recur.getDayList() != null) {
                for (WeekDay day : recur.getDayList()) {
                    daysOfWeek.add(toDayOfWeek(day));
                }
            }

            int dayOfMonth = 0;
            if ((frequency == RecurrenceRule.Frequency.MONTHLY
                    || frequency == RecurrenceRule.Frequency.YEARLY)
                    && recur.getMonthDayList() != null && !recur.getMonthDayList().isEmpty()) {
                dayOfMonth = recur.getMonthDayList().get(0);
            }

            int monthOfYear = 0;
            if (frequency == RecurrenceRule.Frequency.YEARLY
                    && recur.getMonthList() != null && !recur.getMonthList().isEmpty()) {
                monthOfYear = recur.getMonthList().get(0).getMonthOfYear();
            }

            LocalDate endDate = null;
            if (recur.getUntil() != null) {
                endDate = toLocalDateTime(recur.getUntil()).toLocalDate();
            }

            int maxOccurrences = recur.getCount() > 0 ? recur.getCount() : 0;

            return new RecurrenceRule(
                    frequency,
                    interval,
                    daysOfWeek.toArray(new DayOfWeek[0]),
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
     *
     * @param events       Liste der Termine
     * @param calendarName Name des Kalenders
     * @return ICS-Content als String oder null im Fehlerfall
     */
    public static String generateICal(List<Event> events, String calendarName) {
        try {
            Calendar calendar = new Calendar();
            calendar.getProperties().add(new ProdId("-//LifeOS LifeKalender//DE"));
            calendar.getProperties().add(Version.VERSION_2_0);
            calendar.getProperties().add(CalScale.GREGORIAN);
            calendar.getProperties().add(Method.PUBLISH);

            for (Event event : events) {
                calendar.getComponents().add(createVEvent(event));
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            new CalendarOutputter(false).output(calendar, outputStream);
            return outputStream.toString(StandardCharsets.UTF_8);
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

        vEvent.getProperties().add(new Uid(event.getId()));
        vEvent.getProperties().add(new DtStamp());
        vEvent.getProperties().add(new Summary(event.getTitle()));

        if (event.getDetails() != null && !event.getDetails().isBlank()) {
            vEvent.getProperties().add(new Description(event.getDetails()));
        }
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            vEvent.getProperties().add(new Location(event.getLocation()));
        }

        if (event.isAllDay()) {
            vEvent.getProperties().add(new DtStart(toICalDate(event.getStart().toLocalDate())));
            // Ganztaegig: Ende ist exklusiv der naechste Tag.
            vEvent.getProperties().add(new DtEnd(toICalDate(event.getEnd().toLocalDate().plusDays(1))));
        } else {
            vEvent.getProperties().add(new DtStart(toICalDateTime(event.getStart())));
            vEvent.getProperties().add(new DtEnd(toICalDateTime(event.getEnd())));
        }

        if (event.getReminder() != null) {
            VAlarm valarm = new VAlarm();
            valarm.getProperties().add(Action.DISPLAY);
            valarm.getProperties().add(new Description("Erinnerung: " + event.getTitle()));
            valarm.getProperties().add(new Trigger(Duration.ofMinutes(-10)));
            vEvent.getComponents().add(valarm);
        }

        if (event.getRecurrenceRule() != null) {
            RRule rrule = createRRule(event.getRecurrenceRule());
            if (rrule != null) {
                vEvent.getProperties().add(rrule);
            }
        }
        return vEvent;
    }

    /**
     * Erstellt eine RRULE aus einer RecurrenceRule.
     */
    private static RRule createRRule(RecurrenceRule rule) {
        try {
            Recur.Builder builder = new Recur.Builder()
                    .frequency(Recur.Frequency.valueOf(rule.getFrequency().name()))
                    .interval(rule.getInterval());

            if (rule.getFrequency() == RecurrenceRule.Frequency.WEEKLY
                    && rule.getDaysOfWeek().length > 0) {
                WeekDayList dayList = new WeekDayList();
                for (DayOfWeek day : rule.getDaysOfWeek()) {
                    dayList.add(toWeekDay(day));
                }
                builder.dayList(dayList);
            }

            if (rule.getFrequency() == RecurrenceRule.Frequency.MONTHLY
                    && rule.getDayOfMonth() != 0) {
                NumberList monthDayList = new NumberList();
                monthDayList.add(rule.getDayOfMonth());
                builder.monthDayList(monthDayList);
            }

            if (rule.getFrequency() == RecurrenceRule.Frequency.YEARLY) {
                if (rule.getMonthOfYear() != 0) {
                    MonthList monthList = new MonthList();
                    monthList.add(new Month(rule.getMonthOfYear()));
                    builder.monthList(monthList);
                }
                if (rule.getDayOfMonth() != 0) {
                    NumberList monthDayList = new NumberList();
                    monthDayList.add(rule.getDayOfMonth());
                    builder.monthDayList(monthDayList);
                }
            }

            if (rule.getEndDate() != null) {
                builder.until(toICalDate(rule.getEndDate()));
            }
            if (rule.getMaxOccurrences() > 0) {
                builder.count(rule.getMaxOccurrences());
            }

            return new RRule(builder.build());
        } catch (Exception e) {
            System.err.println("Fehler beim Erstellen der RRULE: " + e.getMessage());
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
    }

    private static DateTime toICalDateTime(LocalDateTime dateTime) {
        return new DateTime(java.util.Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private static Date toICalDate(LocalDate date) {
        return new Date(java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    private static WeekDay toWeekDay(DayOfWeek day) {
        switch (day) {
            case TUESDAY:   return WeekDay.TU;
            case WEDNESDAY: return WeekDay.WE;
            case THURSDAY:  return WeekDay.TH;
            case FRIDAY:    return WeekDay.FR;
            case SATURDAY:  return WeekDay.SA;
            case SUNDAY:    return WeekDay.SU;
            case MONDAY:
            default:        return WeekDay.MO;
        }
    }

    private static DayOfWeek toDayOfWeek(WeekDay weekDay) {
        switch (weekDay.getDay()) {
            case TU: return DayOfWeek.TUESDAY;
            case WE: return DayOfWeek.WEDNESDAY;
            case TH: return DayOfWeek.THURSDAY;
            case FR: return DayOfWeek.FRIDAY;
            case SA: return DayOfWeek.SATURDAY;
            case SU: return DayOfWeek.SUNDAY;
            case MO:
            default: return DayOfWeek.MONDAY;
        }
    }
}
