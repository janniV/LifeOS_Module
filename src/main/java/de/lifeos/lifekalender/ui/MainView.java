package de.lifeos.lifekalender.ui;

import core.CoreServices;
import de.lifeos.lifekalender.model.Calendar;
import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.service.CalendarService;
import de.lifeos.lifekalender.service.EventService;
import de.lifeos.lifekalender.service.LLMExtractionService;
import de.lifeos.lifekalender.service.NotificationService;
import de.lifeos.lifekalender.util.ICalParser;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Hauptansicht des LifeKalender-Moduls.
 * Zeigt eine Monatsansicht mit Kalenderliste und Terminliste.
 */
public class MainView {
    private final CoreServices core;
    private final CalendarService calendarService;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final LLMExtractionService extractionService;
    
    private BorderPane root;
    private VBox calendarList;
    private GridPane calendarGrid;
    private ListView<Event> eventListView;
    private ObservableList<Event> eventsForSelectedDay;
    
    private LocalDate currentDate;
    private YearMonth currentYearMonth;

    public MainView(CoreServices core, CalendarService calendarService, 
                    EventService eventService, NotificationService notificationService,
                    LLMExtractionService extractionService) {
        this.core = core;
        this.calendarService = calendarService;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.extractionService = extractionService;
        
        this.currentDate = LocalDate.now();
        this.currentYearMonth = YearMonth.from(currentDate);
        this.eventsForSelectedDay = FXCollections.observableArrayList();
        
        initializeUI();
    }

    private void initializeUI() {
        root = new BorderPane();
        root.setStyle("-fx-background-color: -lifeos-background-color;");
        
        // Header mit Monatsnavigation
        HBox header = createHeader();
        root.setTop(header);
        
        // Hauptbereich mit Kalender und Terminliste
        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.25);
        
        // Linke Seite: Kalenderliste
        calendarList = createCalendarList();
        mainSplit.getItems().add(calendarList);
        
        // Mitte: Kalender-Grid
        calendarGrid = createCalendarGrid();
        mainSplit.getItems().add(calendarGrid);
        
        // Rechte Seite: Terminliste
        VBox eventView = createEventView();
        mainSplit.getItems().add(eventView);
        
        root.setCenter(mainSplit);
        
        // Aktualisiere die Ansicht
        updateCalendarGrid();
        updateCalendarList();
    }

    private HBox createHeader() {
        HBox header = new HBox(10);
        header.setPadding(new Insets(10));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: -lifeos-surface-color;");
        
        // Monatsname
        Label monthLabel = new Label();
        monthLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        monthLabel.setStyle("-fx-text-fill: -lifeos-text-primary;");
        updateMonthLabel(monthLabel);
        
        // Navigation
        Button prevMonth = new Button("<");
        prevMonth.setOnAction(e -> {
            currentYearMonth = currentYearMonth.minusMonths(1);
            currentDate = currentYearMonth.atDay(1);
            updateCalendarGrid();
            updateMonthLabel(monthLabel);
        });
        
        Button nextMonth = new Button(">");
        nextMonth.setOnAction(e -> {
            currentYearMonth = currentYearMonth.plusMonths(1);
            currentDate = currentYearMonth.atDay(1);
            updateCalendarGrid();
            updateMonthLabel(monthLabel);
        });
        
        // Heute-Button
        Button todayButton = new Button("Heute");
        todayButton.setOnAction(e -> {
            currentDate = LocalDate.now();
            currentYearMonth = YearMonth.from(currentDate);
            updateCalendarGrid();
            updateMonthLabel(monthLabel);
        });
        
        // Aktionsbuttons
        Button newEventButton = new Button("Neuer Termin");
        newEventButton.setOnAction(e -> showNewEventDialog());
        
        Button newCalendarButton = new Button("Neuer Kalender");
        newCalendarButton.setOnAction(e -> showNewCalendarDialog());
        
        Button importButton = new Button("Importieren");
        importButton.setOnAction(e -> showImportDialog());
        
        Button exportButton = new Button("Exportieren");
        exportButton.setOnAction(e -> showExportDialog());
        
        HBox navBox = new HBox(10, prevMonth, monthLabel, nextMonth, todayButton);
        HBox actionBox = new HBox(10, newEventButton, newCalendarButton, importButton, exportButton);
        
        header.getChildren().addAll(navBox, actionBox);
        
        return header;
    }

    private VBox createCalendarList() {
        VBox calendarList = new VBox(5);
        calendarList.setPadding(new Insets(10));
        calendarList.setStyle("-fx-background-color: -lifeos-surface-color;");
        
        Label title = new Label("Kalender");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        title.setStyle("-fx-text-fill: -lifeos-text-primary;");
        
        calendarList.getChildren().add(title);
        
        return calendarList;
    }

    private GridPane createCalendarGrid() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(10));
        grid.setStyle("-fx-background-color: -lifeos-surface-color;");
        
        // Wochentage als Header
        String[] dayNames = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        for (int i = 0; i < 7; i++) {
            Label dayLabel = new Label(dayNames[i]);
            dayLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            dayLabel.setStyle("-fx-text-fill: -lifeos-text-secondary;");
            grid.add(dayLabel, i, 0);
        }
        
        // Klick-Handler für Zellen
        grid.setOnMouseClicked(e -> {
            Node source = e.getPickResult().getIntersectedNode();
            if (source instanceof StackPane) {
                Integer day = (Integer) source.getUserData();
                if (day != null) {
                    currentDate = currentYearMonth.atDay(day);
                    updateCalendarGrid();
                    updateEventList();
                }
            }
        });
        
        return grid;
    }

    private VBox createEventView() {
        VBox eventView = new VBox(5);
        eventView.setPadding(new Insets(10));
        eventView.setStyle("-fx-background-color: -lifeos-surface-color;");
        
        Label title = new Label("Termine");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        title.setStyle("-fx-text-fill: -lifeos-text-primary;");
        
        // Datum anzeigen
        Label dateLabel = new Label();
        dateLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        dateLabel.setStyle("-fx-text-fill: -lifeos-text-secondary;");
        updateDateLabel(dateLabel);
        
        // Terminliste
        eventListView = new ListView<>(eventsForSelectedDay);
        eventListView.setCellFactory(lv -> new EventListCell(calendarService));
        eventListView.setPrefHeight(400);
        
        // Kontextmenü für Termine
        ContextMenu eventContextMenu = new ContextMenu();
        MenuItem editItem = new MenuItem("Bearbeiten");
        editItem.setOnAction(e -> {
            Event selected = eventListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showEditEventDialog(selected);
            }
        });
        
        MenuItem deleteItem = new MenuItem("Löschen");
        deleteItem.setOnAction(e -> {
            Event selected = eventListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteEvent(selected);
            }
        });
        
        eventContextMenu.getItems().addAll(editItem, deleteItem);
        eventListView.setContextMenu(eventContextMenu);
        
        // Doppelklick zum Bearbeiten
        eventListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Event selected = eventListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showEditEventDialog(selected);
                }
            }
        });
        
        eventView.getChildren().addAll(title, dateLabel, eventListView);
        
        return eventView;
    }

    private void updateCalendarList() {
        calendarList.getChildren().clear();
        
        Label title = (Label) calendarList.getChildren().get(0);
        calendarList.getChildren().add(title);
        
        List<Calendar> calendars = calendarService.getAllCalendars();
        for (Calendar calendar : calendars) {
            CheckBox checkBox = new CheckBox(calendar.getName());
            checkBox.setSelected(calendar.isVisible());
            checkBox.setStyle("-fx-text-fill: " + calendar.getColor() + ";");
            
            // Farbiger Kreis als Indikator
            Region colorIndicator = new Region();
            colorIndicator.setStyle("-fx-background-color: " + calendar.getColor() + "; " +
                    "-fx-pref-width: 12px; -fx-pref-height: 12px; -fx-background-radius: 6px;");
            
            HBox calendarItem = new HBox(10, colorIndicator, checkBox);
            calendarItem.setPadding(new Insets(2, 0, 2, 0));
            
            // Sichtbarkeit ändern
            checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                calendar.setVisible(newVal);
                calendarService.updateCalendar(calendar);
                updateCalendarGrid();
            });
            
            // Kontextmenü für Kalender
            ContextMenu calendarContextMenu = new ContextMenu();
            MenuItem editCalendarItem = new MenuItem("Bearbeiten");
            editCalendarItem.setOnAction(e -> showEditCalendarDialog(calendar));
            
            MenuItem deleteCalendarItem = new MenuItem("Löschen");
            deleteCalendarItem.setOnAction(e -> deleteCalendar(calendar));
            
            calendarContextMenu.getItems().addAll(editCalendarItem, deleteCalendarItem);
            calendarItem.setOnContextMenuRequested(e ->
                    calendarContextMenu.show(calendarItem, e.getScreenX(), e.getScreenY()));
            
            calendarList.getChildren().add(calendarItem);
        }
    }

    private void updateCalendarGrid() {
        calendarGrid.getChildren().clear();
        
        // Wochentage als Header
        String[] dayNames = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};
        for (int i = 0; i < 7; i++) {
            Label dayLabel = new Label(dayNames[i]);
            dayLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            dayLabel.setStyle("-fx-text-fill: -lifeos-text-secondary;");
            calendarGrid.add(dayLabel, i, 0);
        }
        
        // Erste Woche: Leere Zellen für Tage vor dem 1. des Monats
        int firstDayOfMonth = currentYearMonth.atDay(1).getDayOfWeek().getValue() % 7;
        firstDayOfMonth = firstDayOfMonth == 7 ? 0 : firstDayOfMonth;
        
        for (int i = 0; i < firstDayOfMonth; i++) {
            StackPane emptyCell = new StackPane();
            emptyCell.setStyle("-fx-background-color: transparent;");
            calendarGrid.add(emptyCell, i, 1);
        }
        
        // Tage des Monats
        int daysInMonth = currentYearMonth.lengthOfMonth();
        int row = 1;
        int col = firstDayOfMonth;
        
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = currentYearMonth.atDay(day);
            StackPane dayCell = createDayCell(date, day);
            
            calendarGrid.add(dayCell, col, row);
            
            col++;
            if (col > 6) {
                col = 0;
                row++;
            }
        }
        
        // Letzte Woche: Leere Zellen für Tage nach dem letzten Tag
        while (col <= 6) {
            StackPane emptyCell = new StackPane();
            emptyCell.setStyle("-fx-background-color: transparent;");
            calendarGrid.add(emptyCell, col, row);
            col++;
        }
    }

    private StackPane createDayCell(LocalDate date, int day) {
        StackPane dayCell = new StackPane();
        dayCell.setUserData(day);
        dayCell.setStyle("-fx-background-color: -lifeos-surface-color; " +
                "-fx-border-color: -lifeos-border-color; " +
                "-fx-border-width: 0.5px; " +
                "-fx-min-width: 40px; -fx-min-height: 40px;");
        
        // Tag als Label
        Label dayLabel = new Label(String.valueOf(day));
        dayLabel.setStyle("-fx-text-fill: -lifeos-text-primary;");
        
        // Heute markieren
        if (date.equals(LocalDate.now())) {
            dayCell.setStyle(dayCell.getStyle() + " -fx-background-color: -lifeos-accent-color;");
            dayLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        }
        
        // Ausgewählter Tag markieren
        if (date.equals(currentDate)) {
            dayCell.setStyle(dayCell.getStyle() + " -fx-background-color: -lifeos-accent-light-color;");
        }
        
        // Termine als kleine Punkte anzeigen
        List<Event> events = eventService.getEventsByDate(date);
        VBox eventIndicators = new VBox(2);
        eventIndicators.setPadding(new Insets(2, 0, 0, 0));
        
        for (Event event : events) {
            Region indicator = new Region();
            indicator.setStyle("-fx-background-color: " + getCalendarColor(event.getCalendarId()) + "; " +
                    "-fx-pref-width: 6px; -fx-pref-height: 6px; -fx-background-radius: 3px;");
            eventIndicators.getChildren().add(indicator);
        }
        
        VBox content = new VBox(2, dayLabel, eventIndicators);
        content.setAlignment(Pos.TOP_CENTER);
        
        dayCell.getChildren().add(content);
        
        // Tooltip mit Terminanzahl
        if (!events.isEmpty()) {
            Tooltip tooltip = new Tooltip(events.size() + " Termin(e)");
            Tooltip.install(dayCell, tooltip);
        }
        
        return dayCell;
    }

    private String getCalendarColor(String calendarId) {
        return calendarService.getCalendarById(calendarId)
                .map(Calendar::getColor)
                .orElse("#999999");
    }

    private void updateEventList() {
        eventsForSelectedDay.clear();
        List<Event> events = eventService.getEventsByDate(currentDate);
        eventsForSelectedDay.addAll(events);
    }

    private void updateMonthLabel(Label label) {
        label.setText(currentYearMonth.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMAN) + 
                      " " + currentYearMonth.getYear());
    }

    private void updateDateLabel(Label label) {
        label.setText(currentDate.format(DateTimeFormatter.ofPattern("dd. MMMM yyyy", java.util.Locale.GERMAN)));
    }

    private void showNewEventDialog() {
        Dialog<Event> dialog = new Dialog<>();
        dialog.setTitle("Neuer Termin");
        dialog.setHeaderText("Erstellen Sie einen neuen Termin");
        
        EventDialog eventDialog = new EventDialog(calendarService, currentDate);
        dialog.getDialogPane().setContent(eventDialog.getView());
        
        ButtonType saveButton = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);
        
        Optional<Event> result = dialog.showAndWait();
        if (result.isPresent()) {
            eventService.createEvent(result.get());
            updateCalendarGrid();
            updateEventList();
        }
    }

    private void showEditEventDialog(Event event) {
        Dialog<Event> dialog = new Dialog<>();
        dialog.setTitle("Termin bearbeiten");
        dialog.setHeaderText("Bearbeiten Sie den Termin: " + event.getTitle());
        
        EventDialog eventDialog = new EventDialog(calendarService, event);
        dialog.getDialogPane().setContent(eventDialog.getView());
        
        ButtonType saveButton = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);
        
        Optional<Event> result = dialog.showAndWait();
        if (result.isPresent()) {
            eventService.updateEvent(result.get());
            updateCalendarGrid();
            updateEventList();
        }
    }

    private void deleteEvent(Event event) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Termin löschen");
        alert.setHeaderText("Termin löschen");
        alert.setContentText("Möchten Sie den Termin '" + event.getTitle() + "' wirklich löschen?");
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            eventService.deleteEvent(event.getId());
            updateCalendarGrid();
            updateEventList();
        }
    }

    private void showNewCalendarDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Neuer Kalender");
        dialog.setHeaderText("Erstellen Sie einen neuen Kalender");
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        
        TextField nameField = new TextField();
        nameField.setPromptText("Name");
        
        ColorPicker colorPicker = new ColorPicker(Color.web("#FF5733"));
        
        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Farbe:"), 0, 1);
        grid.add(colorPicker, 1, 1);
        
        dialog.getDialogPane().setContent(grid);
        
        ButtonType saveButton = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == saveButton && !nameField.getText().trim().isEmpty()) {
            String color = String.format("#%02X%02X%02X", 
                    (int) (colorPicker.getValue().getRed() * 255),
                    (int) (colorPicker.getValue().getGreen() * 255),
                    (int) (colorPicker.getValue().getBlue() * 255));
            
            Calendar calendar = calendarService.createCalendar(nameField.getText().trim());
            calendar.setColor(color);
            calendarService.updateCalendar(calendar);
            
            updateCalendarList();
            updateCalendarGrid();
        }
    }

    private void showEditCalendarDialog(Calendar calendar) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Kalender bearbeiten");
        dialog.setHeaderText("Bearbeiten Sie den Kalender: " + calendar.getName());
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        
        TextField nameField = new TextField(calendar.getName());
        ColorPicker colorPicker = new ColorPicker(Color.web(calendar.getColor()));
        CheckBox visibleCheckBox = new CheckBox("Sichtbar");
        visibleCheckBox.setSelected(calendar.isVisible());
        
        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Farbe:"), 0, 1);
        grid.add(colorPicker, 1, 1);
        grid.add(new Label("Sichtbar:"), 0, 2);
        grid.add(visibleCheckBox, 1, 2);
        
        dialog.getDialogPane().setContent(grid);
        
        ButtonType saveButton = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == saveButton) {
            String newName = nameField.getText().trim();
            if (!newName.isEmpty()) {
                calendar.setName(newName);
                calendar.setColor(String.format("#%02X%02X%02X", 
                        (int) (colorPicker.getValue().getRed() * 255),
                        (int) (colorPicker.getValue().getGreen() * 255),
                        (int) (colorPicker.getValue().getBlue() * 255)));
                calendar.setVisible(visibleCheckBox.isSelected());
                calendarService.updateCalendar(calendar);
                updateCalendarList();
                updateCalendarGrid();
            }
        }
    }

    private void deleteCalendar(Calendar calendar) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Kalender löschen");
        alert.setHeaderText("Kalender löschen");
        alert.setContentText("Möchten Sie den Kalender '" + calendar.getName() + "' und alle seine Termine wirklich löschen?");
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            eventService.deleteEventsByCalendar(calendar.getId());
            calendarService.deleteCalendar(calendar.getId());
            updateCalendarList();
            updateCalendarGrid();
            updateEventList();
        }
    }

    private void showImportDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Importieren");
        dialog.setHeaderText("Importieren Sie Termine aus einer iCal (ICS)-Datei");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        TextArea icsContentArea = new TextArea();
        icsContentArea.setPromptText("Fügen Sie hier den Inhalt der ICS-Datei ein...");
        icsContentArea.setPrefHeight(200);
        
        ComboBox<String> calendarComboBox = new ComboBox<>();
        calendarComboBox.setPromptText("Zielkalender auswählen");
        calendarComboBox.getItems().addAll(
                calendarService.getAllCalendars().stream()
                        .map(Calendar::getName)
                        .toList()
        );
        calendarComboBox.getSelectionModel().select(0);
        
        content.getChildren().addAll(
                new Label("ICS-Inhalt:"),
                icsContentArea,
                new Label("Zielkalender:"),
                calendarComboBox
        );
        
        dialog.getDialogPane().setContent(content);
        
        ButtonType importButton = new ButtonType("Importieren", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, cancelButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == importButton) {
            String icsContent = icsContentArea.getText();
            String calendarName = calendarComboBox.getValue();
            
            if (icsContent != null && !icsContent.trim().isEmpty() && calendarName != null) {
                String calendarId = calendarService.getCalendarByName(calendarName)
                        .map(Calendar::getId)
                        .orElseGet(() -> calendarService.createCalendar(calendarName).getId());
                
                List<Event> events = ICalParser.parseICal(icsContent, calendarId);
                
                if (!events.isEmpty()) {
                    showImportPreviewDialog(events, calendarId);
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Import");
                    alert.setHeaderText("Keine Termine gefunden");
                    alert.setContentText("In der ICS-Datei wurden keine Termine gefunden.");
                    alert.showAndWait();
                }
            }
        }
    }

    private void showImportPreviewDialog(List<Event> events, String calendarId) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Import-Vorschau");
        dialog.setHeaderText("Es wurden " + events.size() + " Termine gefunden. Wählen Sie aus, welche importiert werden sollen:");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        ListView<Event> eventListView = new ListView<>();
        eventListView.setItems(FXCollections.observableArrayList(events));
        eventListView.setCellFactory(lv -> new EventListCell(calendarService));
        eventListView.setPrefHeight(300);
        
        CheckBox selectAllCheckBox = new CheckBox("Alle auswählen");
        selectAllCheckBox.setSelected(true);
        
        eventListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        eventListView.getSelectionModel().selectAll();
        
        selectAllCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                eventListView.getSelectionModel().selectAll();
            } else {
                eventListView.getSelectionModel().clearSelection();
            }
        });
        
        content.getChildren().addAll(selectAllCheckBox, eventListView);
        
        dialog.getDialogPane().setContent(content);
        
        ButtonType importButton = new ButtonType("Importieren", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, cancelButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == importButton) {
            for (Event event : eventListView.getSelectionModel().getSelectedItems()) {
                event.setCalendarId(calendarId);
                eventService.createEvent(event);
            }
            
            updateCalendarGrid();
            updateEventList();
            
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Import erfolgreich");
            alert.setHeaderText("Termine importiert");
            alert.setContentText(eventListView.getSelectionModel().getSelectedItems().size() + " Termine wurden erfolgreich importiert.");
            alert.showAndWait();
        }
    }

    private void showExportDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Exportieren");
        dialog.setHeaderText("Exportieren Sie Termine als iCal (ICS)-Datei");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        ComboBox<String> calendarComboBox = new ComboBox<>();
        calendarComboBox.setPromptText("Kalender auswählen");
        calendarComboBox.getItems().addAll(
                calendarService.getAllCalendars().stream()
                        .map(Calendar::getName)
                        .toList()
        );
        calendarComboBox.getSelectionModel().select(0);
        
        RadioButton allEventsRadio = new RadioButton("Alle Termine");
        RadioButton selectedEventsRadio = new RadioButton("Ausgewählte Termine");
        ToggleGroup exportGroup = new ToggleGroup();
        allEventsRadio.setToggleGroup(exportGroup);
        selectedEventsRadio.setToggleGroup(exportGroup);
        allEventsRadio.setSelected(true);
        
        DatePicker startDatePicker = new DatePicker();
        DatePicker endDatePicker = new DatePicker();
        startDatePicker.setValue(LocalDate.now().minusMonths(1));
        endDatePicker.setValue(LocalDate.now().plusMonths(1));
        
        content.getChildren().addAll(
                new Label("Kalender:"),
                calendarComboBox,
                new Separator(),
                new Label("Zeitraum:"),
                new HBox(10, new Label("Von:"), startDatePicker, new Label("Bis:"), endDatePicker),
                new Separator(),
                new Label("Exportieren:"),
                new HBox(10, allEventsRadio, selectedEventsRadio)
        );
        
        dialog.getDialogPane().setContent(content);
        
        ButtonType exportButton = new ButtonType("Exportieren", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(exportButton, cancelButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == exportButton) {
            String calendarName = calendarComboBox.getValue();
            if (calendarName != null) {
                String calendarId = calendarService.getCalendarByName(calendarName)
                        .map(Calendar::getId)
                        .orElse(null);
                
                if (calendarId != null) {
                    LocalDate startDate = startDatePicker.getValue();
                    LocalDate endDate = endDatePicker.getValue();
                    
                    List<Event> eventsToExport;
                    if (allEventsRadio.isSelected()) {
                        eventsToExport = eventService.getEventsInRange(
                                startDate.atStartOfDay(),
                                endDate.atTime(java.time.LocalTime.MAX)
                        ).stream()
                                .filter(e -> e.getCalendarId().equals(calendarId))
                                .toList();
                    } else {
                        eventsToExport = eventService.getEventsByDate(currentDate).stream()
                                .filter(e -> e.getCalendarId().equals(calendarId))
                                .toList();
                    }
                    
                    if (!eventsToExport.isEmpty()) {
                        String icsContent = ICalParser.generateICal(eventsToExport, calendarName);
                        showExportResultDialog(icsContent);
                    } else {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Export");
                        alert.setHeaderText("Keine Termine zum Exportieren");
                        alert.setContentText("Es wurden keine Termine im ausgewählten Zeitraum gefunden.");
                        alert.showAndWait();
                    }
                }
            }
        }
    }

    private void showExportResultDialog(String icsContent) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Export-Ergebnis");
        dialog.setHeaderText("Die ICS-Datei wurde erfolgreich generiert:");
        
        TextArea contentArea = new TextArea(icsContent);
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.setPrefHeight(300);
        contentArea.setPrefWidth(500);
        
        dialog.getDialogPane().setContent(contentArea);
        
        ButtonType copyButton = new ButtonType("In Zwischenablage kopieren", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeButton = new ButtonType("Schließen", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(copyButton, closeButton);
        
        dialog.setResultConverter(buttonType -> buttonType);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == copyButton) {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(icsContent);
            clipboard.setContent(content);
            
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Kopieren erfolgreich");
            alert.setHeaderText("ICS-Inhalt kopiert");
            alert.setContentText("Der ICS-Inhalt wurde in die Zwischenablage kopiert.");
            alert.showAndWait();
        }
    }

    public Node getView() {
        return root;
    }

    public void cleanup() {
        if (calendarList != null) {
            calendarList.getChildren().clear();
        }
        if (calendarGrid != null) {
            calendarGrid.getChildren().clear();
        }
    }
}
