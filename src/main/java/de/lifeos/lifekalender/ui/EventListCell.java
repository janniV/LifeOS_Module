package de.lifeos.lifekalender.ui;

import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.service.CalendarService;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Benutzerdefinierte ListCell für die Anzeige von Terminen in einer ListView.
 */
public class EventListCell extends ListCell<Event> {
    private final CalendarService calendarService;

    public EventListCell(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Override
    protected void updateItem(Event event, boolean empty) {
        super.updateItem(event, empty);

        if (empty || event == null) {
            setGraphic(null);
            setText(null);
        } else {
            // Farbiger Indikator für den Kalender
            Region colorIndicator = new Region();
            colorIndicator.setStyle("-fx-background-color: " + 
                    calendarService.getCalendarById(event.getCalendarId())
                            .map(c -> c.getColor())
                            .orElse("#999999") + "; " +
                    "-fx-pref-width: 12px; -fx-pref-height: 12px; -fx-background-radius: 6px;");

            // Titel
            Text titleText = new Text(event.getTitle());
            titleText.setStyle("-fx-font-weight: bold;");

            // Uhrzeit
            String timeText;
            if (event.isAllDay()) {
                timeText = "Ganztägig";
            } else {
                timeText = String.format("%02d:%02d - %02d:%02d",
                        event.getStart().getHour(),
                        event.getStart().getMinute(),
                        event.getEnd().getHour(),
                        event.getEnd().getMinute());
            }
            Text timeTextNode = new Text(" | " + timeText);

            // Ort
            Text locationText = new Text();
            if (event.getLocation() != null && !event.getLocation().isBlank()) {
                locationText.setText(" | " + event.getLocation());
            }

            // Details (nur erste Zeile anzeigen)
            Text detailsText = new Text();
            if (event.getDetails() != null && !event.getDetails().isBlank()) {
                String details = event.getDetails();
                if (details.length() > 50) {
                    details = details.substring(0, 47) + "...";
                }
                detailsText.setText(" | " + details);
            }

            TextFlow textFlow = new TextFlow(titleText, timeTextNode, locationText, detailsText);

            HBox cellContent = new HBox(10, colorIndicator, textFlow);
            cellContent.setStyle("-fx-padding: 5px;");

            setGraphic(cellContent);
            setText(null);
        }
    }
}
