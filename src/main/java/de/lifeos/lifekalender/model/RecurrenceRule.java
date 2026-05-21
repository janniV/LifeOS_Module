package de.lifeos.lifekalender.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Repräsentiert eine Wiederholungsregel für Termine.
 * Unterstützt tägliche, wöchentliche, monatliche und jährliche Wiederholungen.
 */
public class RecurrenceRule {
    public enum Frequency {
        DAILY, WEEKLY, MONTHLY, YEARLY
    }

    private final Frequency frequency;
    private final int interval; // z.B. alle 2 Wochen
    private final DayOfWeek[] daysOfWeek; // Für wöchentliche Wiederholung
    private final int dayOfMonth; // Für monatliche Wiederholung (1-31 oder -1 für "letzter Tag")
    private final int monthOfYear; // Für jährliche Wiederholung (1-12)
    private final LocalDate endDate; // Enddatum der Wiederholung (null = unbegrenzt)
    private final int maxOccurrences; // Maximale Anzahl von Wiederholungen (0 = unbegrenzt)

    @JsonCreator
    public RecurrenceRule(
            @JsonProperty("frequency") Frequency frequency,
            @JsonProperty("interval") int interval,
            @JsonProperty("daysOfWeek") DayOfWeek[] daysOfWeek,
            @JsonProperty("dayOfMonth") int dayOfMonth,
            @JsonProperty("monthOfYear") int monthOfYear,
            @JsonProperty("endDate") LocalDate endDate,
            @JsonProperty("maxOccurrences") int maxOccurrences) {
        this.frequency = Objects.requireNonNull(frequency, "Frequency darf nicht null sein");
        this.interval = interval > 0 ? interval : 1;
        this.daysOfWeek = daysOfWeek != null ? daysOfWeek : new DayOfWeek[0];
        this.dayOfMonth = dayOfMonth;
        this.monthOfYear = monthOfYear;
        this.endDate = endDate;
        this.maxOccurrences = maxOccurrences >= 0 ? maxOccurrences : 0;
    }

    // Builder-Methode für einfache Erstellung
    public static RecurrenceRule daily(int interval) {
        return new RecurrenceRule(Frequency.DAILY, interval, null, 0, 0, null, 0);
    }

    public static RecurrenceRule weekly(int interval, DayOfWeek... daysOfWeek) {
        return new RecurrenceRule(Frequency.WEEKLY, interval, daysOfWeek, 0, 0, null, 0);
    }

    public static RecurrenceRule monthly(int interval, int dayOfMonth) {
        return new RecurrenceRule(Frequency.MONTHLY, interval, null, dayOfMonth, 0, null, 0);
    }

    public static RecurrenceRule yearly(int interval, int monthOfYear, int dayOfMonth) {
        return new RecurrenceRule(Frequency.YEARLY, interval, null, dayOfMonth, monthOfYear, null, 0);
    }

    // Getter
    public Frequency getFrequency() {
        return frequency;
    }

    public int getInterval() {
        return interval;
    }

    public DayOfWeek[] getDaysOfWeek() {
        return daysOfWeek;
    }

    public int getDayOfMonth() {
        return dayOfMonth;
    }

    public int getMonthOfYear() {
        return monthOfYear;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getMaxOccurrences() {
        return maxOccurrences;
    }

    /**
     * Prüft, ob die Wiederholung unbegrenzt ist.
     */
    public boolean isInfinite() {
        return endDate == null && maxOccurrences == 0;
    }

    /**
     * Berechnet das nächste Vorkommen nach einem gegebenen Datum.
     */
    public LocalDate getNextOccurrence(LocalDate fromDate) {
        if (isInfinite() || (endDate != null && fromDate.isBefore(endDate)) || maxOccurrences > 0) {
            switch (frequency) {
                case DAILY:
                    return fromDate.plusDays(interval);
                case WEEKLY:
                    if (daysOfWeek.length == 0) {
                        return fromDate.plusWeeks(interval);
                    }
                    // Finde den nächsten Wochentag
                    for (int i = 1; i <= 7; i++) {
                        LocalDate nextDate = fromDate.plusDays(i);
                        for (DayOfWeek day : daysOfWeek) {
                            if (nextDate.getDayOfWeek() == day) {
                                return nextDate;
                            }
                        }
                    }
                    return fromDate.plusWeeks(interval);
                case MONTHLY:
                    if (dayOfMonth == -1) {
                        // Letzter Tag des Monats
                        return fromDate.plusMonths(interval).withDayOfMonth(
                                fromDate.plusMonths(interval).lengthOfMonth());
                    }
                    return fromDate.plusMonths(interval).withDayOfMonth(dayOfMonth);
                case YEARLY:
                    return fromDate.plusYears(interval).withMonth(monthOfYear).withDayOfMonth(dayOfMonth);
                default:
                    return fromDate;
            }
        }
        return null; // Keine weiteren Vorkommen
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecurrenceRule that = (RecurrenceRule) o;
        return interval == that.interval &&
                dayOfMonth == that.dayOfMonth &&
                monthOfYear == that.monthOfYear &&
                maxOccurrences == that.maxOccurrences &&
                frequency == that.frequency &&
                java.util.Arrays.equals(daysOfWeek, that.daysOfWeek) &&
                Objects.equals(endDate, that.endDate);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(frequency, interval, dayOfMonth, monthOfYear, endDate, maxOccurrences);
        result = 31 * result + java.util.Arrays.hashCode(daysOfWeek);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Alle ").append(interval).append(" ");
        switch (frequency) {
            case DAILY:
                sb.append("Tage");
                break;
            case WEEKLY:
                sb.append("Wochen");
                if (daysOfWeek.length > 0) {
                    sb.append(" (");
                    for (int i = 0; i < daysOfWeek.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(daysOfWeek[i].toString());
                    }
                    sb.append(")");
                }
                break;
            case MONTHLY:
                sb.append("Monate");
                if (dayOfMonth == -1) {
                    sb.append(" (letzter Tag)");
                } else {
                    sb.append(" am ").append(dayOfMonth).append(".");
                }
                break;
            case YEARLY:
                sb.append("Jahre am ").append(dayOfMonth).append(".").append(monthOfYear).append(".");
                break;
        }
        if (endDate != null) {
            sb.append(" bis ").append(endDate);
        }
        if (maxOccurrences > 0) {
            sb.append(" (max. ").append(maxOccurrences).append(" Wiederholungen)");
        }
        return sb.toString();
    }
}
