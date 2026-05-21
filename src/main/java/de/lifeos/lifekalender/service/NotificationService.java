package de.lifeos.lifekalender.service;

import de.lifeos.lifekalender.model.Event;
import core.CoreServices;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Verwaltet Erinnerungen für Termine und sendet Benachrichtigungen über LifeOS.
 */
public class NotificationService {
    private final CoreServices core;
    private final EventService eventService;
    private Timer notificationTimer;
    private final List<String> sentNotifications = new CopyOnWriteArrayList<>();

    public NotificationService(CoreServices core, EventService eventService) {
        this.core = core;
        this.eventService = eventService;
        this.notificationTimer = new Timer("LifeKalender-Notification-Timer", true);
    }

    /**
     * Startet den Benachrichtigungsdienst.
     * Prüft alle 60 Sekunden auf anstehende Erinnerungen.
     */
    public void start() {
        if (notificationTimer != null) {
            notificationTimer.cancel();
        }
        
        notificationTimer = new Timer("LifeKalender-Notification-Timer", true);
        
        // Sofortige Prüfung
        checkReminders();
        
        // Regelmäßige Prüfung alle 60 Sekunden
        notificationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkReminders();
            }
        }, 60000, 60000);
    }

    /**
     * Stoppt den Benachrichtigungsdienst.
     */
    public void stop() {
        if (notificationTimer != null) {
            notificationTimer.cancel();
            notificationTimer = null;
        }
    }

    /**
     * Prüft auf anstehende Erinnerungen und sendet Benachrichtigungen.
     */
    private void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lookAhead = now.plusMinutes(1); // 1 Minute Vorlauf
        
        List<Event> upcomingEvents = eventService.getUpcomingReminders();
        
        for (Event event : upcomingEvents) {
            LocalDateTime reminderTime = event.getReminder();
            
            // Prüfen, ob die Erinnerung in den nächsten 60 Sekunden fällig ist
            if (reminderTime != null && 
                !reminderTime.isBefore(now) && 
                reminderTime.isBefore(lookAhead) &&
                !sentNotifications.contains(event.getId())) {
                
                sendNotification(event);
                sentNotifications.add(event.getId());
            }
        }
        
        // Alte Benachrichtigungen bereinigen (älter als 1 Stunde)
        sentNotifications.removeIf(id -> {
            Event event = eventService.getEventById(id).orElse(null);
            if (event == null) return true;
            LocalDateTime reminderTime = event.getReminder();
            return reminderTime == null || 
                   ChronoUnit.HOURS.between(reminderTime, now) > 1;
        });
    }

    /**
     * Sendet eine Benachrichtigung für einen Termin.
     */
    private void sendNotification(Event event) {
        String title = event.getTitle();
        String message;
        
        if (event.isAllDay()) {
            message = String.format("Ganztägiger Termin: %s\nOrt: %s\nDetails: %s",
                    title,
                    event.getLocation() != null ? event.getLocation() : "Kein Ort",
                    event.getDetails() != null ? event.getDetails() : "Keine Details");
        } else {
            message = String.format("Termin: %s\nZeit: %s - %s\nOrt: %s\nDetails: %s",
                    title,
                    event.getStart().toLocalTime().toString(),
                    event.getEnd().toLocalTime().toString(),
                    event.getLocation() != null ? event.getLocation() : "Kein Ort",
                    event.getDetails() != null ? event.getDetails() : "Keine Details");
        }
        
        // Benachrichtigung über LifeOS senden
        if (core != null) {
            core.sendNotification("LifeKalender: " + title + " - Erinnerung");
            System.out.println("Benachrichtigung gesendet: " + message);
        } else {
            System.out.println("LifeKalender-Erinnerung: " + message);
        }
    }

    /**
     * Fügt eine manuelle Erinnerung für einen Termin hinzu.
     */
    public void addReminder(Event event, long minutesBefore) {
        LocalDateTime reminderTime = event.getStart().minusMinutes(minutesBefore);
        event.setReminder(reminderTime);
        eventService.updateEvent(event);
    }

    /**
     * Entfernt die Erinnerung von einem Termin.
     */
    public void removeReminder(Event event) {
        event.setReminder(null);
        eventService.updateEvent(event);
    }

    /**
     * Setzt die Erinnerungszeit für einen Termin.
     */
    public void setReminder(Event event, LocalDateTime reminderTime) {
        event.setReminder(reminderTime);
        eventService.updateEvent(event);
    }
}
