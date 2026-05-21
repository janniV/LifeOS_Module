package de.lifeos.lifekalender.ui;

import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.model.RecurrenceRule;
import de.lifeos.lifekalender.model.Calendar;
import de.lifeos.lifekalender.service.CalendarService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Dialog zum Erstellen oder Bearbeiten von Terminen.
 */
public class EventDialog {
    private final CalendarService calendarService;
    private final Event event;
    private final boolean isNewEvent;
    
    private GridPane grid;
    private TextField titleField;
    private ComboBox<String> calendarComboBox;
    private DatePicker datePicker;
    private CheckBox allDayCheckBox;
    private TextField startTimeField;
    private TextField endTimeField;
    private TextField locationField;
    private TextArea detailsArea;
    private CheckBox reminderCheckBox;
    private Spinner<Integer> reminderMinutesSpinner;
    private ComboBox<String> recurrenceComboBox;

    public EventDialog(CalendarService calendarService, LocalDate initialDate) {
        this(calendarService, new Event(
                calendarService.getAllCalendars().isEmpty() ? "" : calendarService.getAllCalendars().get(0).getId(),
                "",
                initialDate.atStartOfDay(),
                initialDate.atStartOfDay().plusHours(1)
        ));
    }

    public EventDialog(CalendarService calendarService, Event event) {
        this.calendarService = calendarService;
        this.event = event;
        this.isNewEvent = event.getId() == null || event.getId().isEmpty();
        
        initializeUI();
    }

    private void initializeUI() {
        grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        // Titel
        Label titleLabel = new Label("Titel:");
        titleField = new TextField(event.getTitle());
        grid.add(titleLabel, 0, 0);
        grid.add(titleField, 1, 0, 2, 1);

        // Kalender
        Label calendarLabel = new Label("Kalender:");
        calendarComboBox = new ComboBox<>();
        List<String> calendarNames = calendarService.getAllCalendars().stream()
                .map(c -> c.getName())
                .toList();
        calendarComboBox.setItems(FXCollections.observableArrayList(calendarNames));
        
        // Aktuellen Kalender auswählen
        String currentCalendarName = calendarService.getCalendarById(event.getCalendarId())
                .map(Calendar::getName)
                .orElse(calendarNames.isEmpty() ? "" : calendarNames.get(0));
        calendarComboBox.setValue(currentCalendarName);
        
        grid.add(calendarLabel, 0, 1);
        grid.add(calendarComboBox, 1, 1, 2, 1);

        // Datum
        Label dateLabel = new Label("Datum:");
        datePicker = new DatePicker(event.getStart().toLocalDate());
        grid.add(dateLabel, 0, 2);
        grid.add(datePicker, 1, 2);

        // Ganztägig
        allDayCheckBox = new CheckBox("Ganztägig");
        allDayCheckBox.setSelected(event.isAllDay());
        allDayCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            startTimeField.setDisable(newVal);
            endTimeField.setDisable(newVal);
        });
        grid.add(allDayCheckBox, 2, 2);

        // Startzeit
        Label startTimeLabel = new Label("Von:");
        startTimeField = new TextField(formatTime(event.getStart().toLocalTime()));
        startTimeField.setPromptText("HH:MM");
        startTimeField.setDisable(event.isAllDay());
        grid.add(startTimeLabel, 0, 3);
        grid.add(startTimeField, 1, 3);

        // Endzeit
        Label endTimeLabel = new Label("Bis:");
        endTimeField = new TextField(formatTime(event.getEnd().toLocalTime()));
        endTimeField.setPromptText("HH:MM");
        endTimeField.setDisable(event.isAllDay());
        grid.add(endTimeLabel, 2, 3);
        grid.add(endTimeField, 3, 3);

        // Ort
        Label locationLabel = new Label("Ort:");
        locationField = new TextField(event.getLocation() != null ? event.getLocation() : "");
        grid.add(locationLabel, 0, 4);
        grid.add(locationField, 1, 4, 2, 1);

        // Details
        Label detailsLabel = new Label("Details:");
        detailsArea = new TextArea(event.getDetails() != null ? event.getDetails() : "");
        detailsArea.setPrefHeight(80);
        grid.add(detailsLabel, 0, 5);
        grid.add(detailsArea, 1, 5, 2, 1);

        // Erinnerung
        reminderCheckBox = new CheckBox("Erinnerung");
        reminderCheckBox.setSelected(event.getReminder() != null);
        
        reminderMinutesSpinner = new Spinner<>(0, 1440, 10, 5);
        reminderMinutesSpinner.setDisable(event.getReminder() == null);
        reminderCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            reminderMinutesSpinner.setDisable(!newVal);
        });
        
        // Wenn Erinnerung existiert, Minuten berechnen
        if (event.getReminder() != null) {
            long minutes = java.time.Duration.between(event.getReminder(), event.getStart()).toMinutes();
            reminderMinutesSpinner.getValueFactory().setValue((int) minutes);
        }
        
        HBox reminderBox = new HBox(10, reminderCheckBox, new Label("Minuten vorher:"), reminderMinutesSpinner);
        grid.add(reminderBox, 0, 6, 3, 1);

        // Wiederholung
        Label recurrenceLabel = new Label("Wiederholung:");
        recurrenceComboBox = new ComboBox<>();
        recurrenceComboBox.setItems(FXCollections.observableArrayList(
                "Keine",
                "Täglich",
                "Wöchentlich",
                "Monatlich",
                "Jährlich"
        ));
        
        // Aktuelle Wiederholungsregel setzen
        if (event.getRecurrenceRule() != null) {
            switch (event.getRecurrenceRule().getFrequency()) {
                case DAILY:
                    recurrenceComboBox.setValue("Täglich");
                    break;
                case WEEKLY:
                    recurrenceComboBox.setValue("Wöchentlich");
                    break;
                case MONTHLY:
                    recurrenceComboBox.setValue("Monatlich");
                    break;
                case YEARLY:
                    recurrenceComboBox.setValue("Jährlich");
                    break;
            }
        } else {
            recurrenceComboBox.setValue("Keine");
        }
        
        grid.add(recurrenceLabel, 0, 7);
        grid.add(recurrenceComboBox, 1, 7, 2, 1);
    }

    private String formatTime(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private LocalTime parseTime(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            return LocalTime.of(9, 0); // Standard: 9:00
        }
    }

    public Node getView() {
        return grid;
    }

    public Event getEvent() {
        // Kalender-ID aus dem Namen holen
        String calendarId = calendarService.getCalendarByName(calendarComboBox.getValue())
                .map(Calendar::getId)
                .orElseGet(() -> {
                    // Falls Kalender nicht gefunden, ersten Kalender nehmen
                    return calendarService.getAllCalendars().isEmpty() ? "" : 
                            calendarService.getAllCalendars().get(0).getId();
                });

        // Datum und Uhrzeit
        LocalDate date = datePicker.getValue();
        LocalTime startTime = parseTime(startTimeField.getText());
        LocalTime endTime = parseTime(endTimeField.getText());

        LocalDateTime start = allDayCheckBox.isSelected() ?
                date.atStartOfDay() : date.atTime(startTime);
        LocalDateTime end = allDayCheckBox.isSelected() ?
                date.atTime(LocalTime.MAX) : date.atTime(endTime);

        // Falls Endzeit vor Startzeit liegt, korrigieren
        if (end.isBefore(start)) {
            end = start.plusHours(1);
        }

        // Erinnerung
        LocalDateTime reminder = null;
        if (reminderCheckBox.isSelected()) {
            reminder = start.minusMinutes(reminderMinutesSpinner.getValue());
        }

        // Wiederholungsregel
        RecurrenceRule recurrenceRule = null;
        String recurrence = recurrenceComboBox.getValue();
        if (recurrence != null && !recurrence.equals("Keine")) {
            switch (recurrence) {
                case "Täglich":
                    recurrenceRule = RecurrenceRule.daily(1);
                    break;
                case "Wöchentlich":
                    recurrenceRule = RecurrenceRule.weekly(1, date.getDayOfWeek());
                    break;
                case "Monatlich":
                    recurrenceRule = RecurrenceRule.monthly(1, date.getDayOfMonth());
                    break;
                case "Jährlich":
                    recurrenceRule = RecurrenceRule.yearly(1, date.getMonthValue(), date.getDayOfMonth());
                    break;
            }
        }

        // Event erstellen oder aktualisieren
        Event newEvent = new Event(
                isNewEvent ? "" : event.getId(),
                calendarId,
                titleField.getText(),
                start,
                end,
                locationField.getText().isBlank() ? null : locationField.getText(),
                detailsArea.getText().isBlank() ? null : detailsArea.getText(),
                recurrenceRule,
                allDayCheckBox.isSelected(),
                reminder
        );

        return newEvent;
    }
}
