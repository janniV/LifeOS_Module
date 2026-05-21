# LifeKalender

Ein Kalender-Modul für LifeOS, das mehrere Kalender mit farbiger Darstellung, Terminverwaltung, iCal-Import/Export und LLM-basierte Terminextraktion aus Texten, PDFs und Bildern unterstützt.

## Funktionen

### Kalenderverwaltung
- Erstellen, Bearbeiten, Löschen und Ausblenden von Kalendern
- Automatische Zuweisung von Farben aus einer Palette mit 20 Farben
- Standardkalender: Privat, Uni, Garten, Arbeit

### Terminverwaltung
- Termine mit **Datum, Uhrzeit, Titel, Ort und Details**
- Ganztägige Termine
- Wiederkehrende Termine (täglich, wöchentlich, monatlich, jährlich)
- Erinnerungen mit Benachrichtigungen
- Konfliktprüfung

### Import/Export
- **iCal (ICS)-Dateien** importieren und exportieren
- Auswahl des Zielkalenders beim Import
- Export aller Termine oder ausgewählter Termine
- Export in die Zwischenablage

### LLM-Integration
- **Terminextraktion aus Texten, PDFs und Bildern** (über LifeOS-internes LLM)
- Automatische Zuordnung zu Kalendern basierend auf Keywords
- Manuelle Bestätigung vor dem Speichern

### Benachrichtigungen
- Erinnerungen werden 10 Minuten vor dem Termin angezeigt
- Benachrichtigungen über LifeOS (`core.sendNotification`)

## Technische Details

### Architektur
- **Java-Modul** als JAR mit `LifeModule`-Implementierung
- **Datenhaltung**: JSON-Dateien im Modul-Speicher (`core.getModuleStoragePath`)
- **UI**: JavaFX mit Monats-, Wochen- und Tagesansicht
- **LLM**: Nutzung von `core.completeWithInternalLLM` für Terminextraktion

### Abhängigkeiten
- **JavaFX 17**: Für die UI
- **Jackson**: JSON-Serialisierung
- **iCal4j**: Parsen und Generieren von ICS-Dateien
- **PDFBox**: PDF-Text-Extraktion (Fallback)

### Dateistruktur
```
LifeKalender/
├── src/
│   ├── main/
│   │   ├── java/de/lifeos/lifekalender/
│   │   │   ├── LifeKalenderModule.java       # Hauptmodul
│   │   │   ├── model/                         # Modellklassen
│   │   │   │   ├── Calendar.java
│   │   │   │   ├── Event.java
│   │   │   │   └── RecurrenceRule.java
│   │   │   ├── service/                       # Dienste
│   │   │   │   ├── CalendarService.java
│   │   │   │   ├── EventService.java
│   │   │   │   ├── NotificationService.java
│   │   │   │   └── LLMExtractionService.java
│   │   │   ├── ui/                            # UI-Komponenten
│   │   │   │   ├── MainView.java
│   │   │   │   ├── EventDialog.java
│   │   │   │   └── EventListCell.java
│   │   │   ├── tools/                        # AI-Tools
│   │   │   │   ├── ExtractTermsTool.java
│   │   │   │   ├── ImportICalTool.java
│   │   │   │   ├── CreateCalendarTool.java
│   │   │   │   └── CreateEventTool.java
│   │   │   └── util/                          # Hilfsklassen
│   │   │       └── ICalParser.java
│   │   └── resources/
│   │       └── META-INF/services/
│   │           └── core.LifeModule
└── pom.xml
```

### AI-Tools
| Tool | Beschreibung | Safety | Bestätigung |
|------|--------------|--------|-------------|
| `lifekalender_extract_terms` | Extrahiere Termine aus Text/PDF/Bild | `USER_DATA_WRITE` | Ja |
| `lifekalender_import_ical` | Importiere ICS-Datei | `USER_DATA_WRITE` | Ja |
| `lifekalender_create_calendar` | Erstelle neuen Kalender | `USER_DATA_WRITE` | Nein |
| `lifekalender_create_event` | Erstelle neuen Termin | `USER_DATA_WRITE` | Ja |

### Modul-ID
- **ID**: `com.lifeos.lifekalender`
- **Name**: `LifeKalender`
- **Icon**: `fas-calendar-alt` (FontAwesome 5)

## Installation

1. Projekt mit Maven bauen:
   ```bash
   cd LifeKalender
   mvn clean package
   ```

2. Die generierte JAR-Datei (`target/LifeKalender-1.0.0.jar`) in den LifeOS-Modulordner kopieren.

3. LifeOS neu starten. Das Modul sollte automatisch in der Sidebar erscheinen.

## Verwendung

### Kalender verwalten
1. Klicke auf "Neuer Kalender" in der Header-Leiste
2. Gib einen Namen ein und wähle eine Farbe
3. Der Kalender erscheint in der linken Leiste

### Termine erstellen
1. Klicke auf "Neuer Termin" in der Header-Leiste
2. Wähle Kalender, Datum, Uhrzeit, Titel, Ort und Details
3. Optional: Wiederholung und Erinnerung einstellen
4. Speichern

### Termine importieren
1. Klicke auf "Importieren" in der Header-Leiste
2. Füge den ICS-Inhalt ein
3. Wähle den Zielkalender
4. Bestätige den Import

### Termine exportieren
1. Klicke auf "Exportieren" in der Header-Leiste
2. Wähle Kalender und Zeitraum
3. Kopiere den ICS-Inhalt in die Zwischenablage

### LLM-Integration
- Frage Samira: "Extrahiere Termine aus diesem Text: [Text]"
- Samira wird die Termine extrahieren und zur Bestätigung anzeigen
- Nach Bestätigung werden die Termine im passenden Kalender gespeichert

## Datenformat

### Kalender (calendars.json)
```json
[
  {
    "id": "abc123",
    "name": "Privat",
    "color": "#FF5733",
    "visible": true
  }
]
```

### Termine (events.json)
```json
[
  {
    "id": "def456",
    "calendarId": "abc123",
    "title": "Meeting",
    "start": "2024-10-15T14:00:00",
    "end": "2024-10-15T15:00:00",
    "location": "Büro",
    "details": "Besprechung mit Team",
    "allDay": false,
    "reminder": "2024-10-15T13:50:00"
  }
]
```

## Theme-Unterstützung
Das Modul übernimmt automatisch den LifeOS-Light-/Darkmode durch die Verwendung von CSS-Variablen:
- `-lifeos-background-color`
- `-lifeos-surface-color`
- `-lifeos-text-primary`
- `-lifeos-text-secondary`
- `-lifeos-accent-color`
- `-lifeos-accent-light-color`
- `-lifeos-border-color`

## Lizenz
Dieses Modul ist Teil von LifeOS und unterliegt der gleichen Lizenz wie das Hauptprojekt.
