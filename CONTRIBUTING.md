# LifeSuite - Entwicklungsrichtlinien

LifeSuite ist eine eigenstaendige Office-Alternative. Dieses Dokument enthaelt die
architektonischen Vorgaben, den Entwicklungsstand sowie die Codierungs- und
Kommentierungsrichtlinien. Jede KI und jeder Entwickler, die an diesem Code arbeiten,
muessen diese Vorgaben ausnahmslos befolgen.

## 1. Architektur-Uebersicht

LifeSuite ist eine reine JavaFX-Anwendung. Der Code liegt in zwei Paketen:

* **`lifesuite`:** Der Markdown-Text-Editor (`EditorView`, `MarkdownRenderer`,
  `EditorDocument`, `EditorBridge`), die Startklasse `LifeSuiteStandalone` sowie die
  JAR-Startklasse `Launcher`.
* **`lifesuite.pdf`:** Der PDF-Reader (`PdfReaderView`, `PdfDocumentLoader`, `PdfPage`,
  `PdfPageData`, `TextAtom`, `PdfStroke`, `PdfTool`).

Die Anwendung wird ueber `LifeSuiteStandalone` gestartet. Ohne Startparameter oeffnet
der Text-Editor; mit dem Parameter `--pdf` oeffnet der PDF-Reader. Die Parameter
`--dark` und `--classic` steuern die Darstellung.

## Bauen und Ausfuehren

Der Befehl `mvn package` erzeugt unter `target/lifesuite-<version>.jar` eine
ausfuehrbare JAR-Datei, in die alle Abhaengigkeiten gebuendelt sind. Die JAR wird mit
`java -jar target/lifesuite-<version>.jar` gestartet und kann optional die
Startparameter `--pdf`, `--dark` und `--classic` erhalten. Die JAR enthaelt die
JavaFX-Laufzeit des Betriebssystems, auf dem `mvn package` ausgefuehrt wurde; fuer ein
anderes Betriebssystem wird `mvn package` dort erneut ausgefuehrt.

## 2. Verzeichnisstruktur

Die Struktur folgt den Java-Maven-Standards. Package-Namen werden kleingeschrieben.

LifeSuite/
├── pom.xml
├── CONTRIBUTING.md
└── src/
    └── main/
        ├── java/
        │   └── lifesuite/        (Editor-Code; Unterpaket pdf fuer den PDF-Reader)
        └── resources/
            └── lifesuite/        (Stylesheet der Editor-Oberflaeche)

## 3. Strikte Codierungs- und Kommentierungsregeln (CRITICAL)

Die folgenden Regeln sind beim Generieren von Code unumstoesslich:

1. **Vollstaendiger Code:** Es wird der vollstaendige Code der geaenderten Klasse
   ausgegeben. Es wird niemals Code mit Platzhaltern wie `// hier bestehende Klasse`
   generiert.
2. **Passive Kommentierung:** Aktive Kommentare (z. B. "Wir initialisieren die Liste")
   sind verboten. Es werden ausschliesslich passive Formulierungen verwendet (z. B.
   "Die Liste wird initialisiert").
3. **Sachlichkeit:** Bewertungen in Kommentaren wie "wichtig" oder "kommt oft vor"
   sind verboten.
4. **Keine Aenderungsmarker:** Das Wort "NEU" oder aehnliche Marker zur Hervorhebung
   von Code-Aenderungen duerfen in Kommentaren nicht verwendet werden.
5. **Klarheit und Findbarkeit:** Funktionen und Klassen werden so kommentiert, dass
   die Logik leicht verstaendlich ist und alle Elemente beim Durchsuchen des Codes
   schnell wiedergefunden werden koennen.
6. **Keine Metaphern:** Es werden keine metaphorischen Bezeichnungen fuer
   Systemarchitekturen verwendet. Es wird exakte technische Terminologie genutzt.
7. **Changelog-Pflicht:** Jede Code-Aenderung MUSS im Changelog (Abschnitt 4)
   dokumentiert werden. Der Eintrag muss Versionsnummer, Branch-Name, Datum/Uhrzeit
   und eine praezise Beschreibung enthalten.
8. **Kommentierung (DEUTSCH und UMFASSEND):** Alle Klassen, Methoden und komplexen
   Logikbloecke muessen in deutscher Sprache kommentiert werden. Die Kommentare
   erklaeren die Intention, nicht nur den Ablauf.
9. **Eigenstaendigkeit:** LifeSuite muss als eigenstaendige Java-Anwendung lauffaehig
   bleiben (`LifeSuiteStandalone`).
10. **UI-Anforderungen:** Die Anwendung unterstuetzt das Classic-Design (CSS-basiert)
    und das Modern-Design (AtlantaFX-basiert), jeweils im Light- und im Dark-Modus.
11. **Ansprache des Nutzers:** Der Nutzer wird immer mit "Du" angesprochen.

## 4. Aktueller Entwicklungsstand (Changelog)

**Version:** 0.2.3 - LifeOS Native Modul
* **Branch:** 0.2.3
* **Datum/Zeit:** 2026-05-17 00:30
* **Status:**
  - `core.LifeModule` Interface und `core.CoreServices` hinzugefügt, um LifeSuite nativ in LifeOS zu integrieren.
  - `lifesuite.LifeSuiteModule` implementiert und via `META-INF/services/core.LifeModule` registriert.
  - Sämtliche "ae", "oe", "ue" Umschreibungen in `.java`-Dateien durch die korrekten deutschen Umlaute (ä, ö, ü) und "ß" ersetzt.
  - Standalone-Modus in `LifeSuiteStandalone.java` erkennt nun dynamisch per JavaFX-Reflection (`Platform.getPreferences()`) das System-Design (Light-/Dark-Mode) und wendet dieses als Standard an.
  - maven-shade-plugin Konfiguration in der `pom.xml` so angepasst, dass die `lifesuite.jar` direkt in das Verzeichnis `../../Modules/LifeSuite/` exportiert wird, damit LifeOS das Modul korrekt einbinden kann.

**Version:** 0.2.1 - Ausfuehrbare JAR-Datei
* **Branch:** lifesuite
* **Datum/Zeit:** 2026-05-16
* **Status:** LifeSuite wird nun ueber `mvn package` zu einer einzelnen ausfuehrbaren
  JAR-Datei gebuendelt, die sich mit `java -jar` starten und als startbares Programm
  weitergeben laesst.
* **Durchgefuehrte Aenderungen:**
  * Neue Klasse `lifesuite/Launcher.java`: JAR-Startklasse, die nicht von Application
    erbt, damit der Start ueber `java -jar` ohne JavaFX-Modulpfad funktioniert.
  * Update `pom.xml`: `maven-shade-plugin` ergaenzt, der alle Abhaengigkeiten in eine
    ausfuehrbare JAR-Datei buendelt und `lifesuite.Launcher` als Main-Class setzt. Die
    Erzeugung der `dependency-reduced-pom.xml` wird unterdrueckt.

**Version:** 0.2.0 - PDF-Reader
* **Branch:** lifesuite
* **Datum/Zeit:** 2026-05-16
* **Status:** Zweite Ausbaustufe der Office-Alternative. LifeSuite enthaelt nun einen
  PDF-Reader auf Basis von Apache PDFBox.
* **Durchgefuehrte Aenderungen:**
  * Neue Klasse `lifesuite/pdf/PdfReaderView.java`: Hauptansicht des PDF-Readers mit
    Werkzeugleiste (Datei oeffnen, Hand, Textauswahl, Textmarker, Stift, Radierer,
    Farbe, Strichbreite, Notizzettel, Suche, Seiten abdunkeln) und scrollbarem
    Seitenbereich. Nimmt ueber `onExternalStrokeReceived` Zeichendaten eines externen
    Geraets im JSON-Format entgegen.
  * Neue Klasse `lifesuite/pdf/PdfDocumentLoader.java`: Laedt ein PDF-Dokument ueber
    Apache PDFBox in einem Hintergrund-Thread, rendert die Seitenbilder und liest die
    Textbausteine samt Koordinaten aus.
  * Neue Klasse `lifesuite/pdf/PdfPage.java`: Stellt eine PDF-Seite als Stapel aus
    Bild-, Verdunkelungs-, Highlight-, Such-, Zeichen- und Event-Ebene dar. Die
    Highlight-Ebene nutzt den Multiplikations-Mischmodus, damit der Text unter der
    Markierung lesbar bleibt.
  * Neue Klassen `lifesuite/pdf/TextAtom.java`, `PdfPageData.java`, `PdfTool.java` und
    `PdfStroke.java`: Datenmodelle fuer Textbausteine, Seitendaten, Werkzeuge und Striche.
  * Update `lifesuite/LifeSuiteStandalone.java`: Der Startparameter `--pdf` oeffnet den
    PDF-Reader statt des Text-Editors.
  * Update `pom.xml`: Abhaengigkeit zu Apache PDFBox 2.0.30 ergaenzt.

**Version:** 0.1.0 - LifeSuite-Text-Editor
* **Branch:** lifesuite
* **Datum/Zeit:** 2026-05-16
* **Status:** Erste Ausbaustufe der Office-Alternative. LifeSuite stellt einen
  Markdown-Text-Editor mit Live-HTML-Vorschau bereit.
* **Durchgefuehrte Aenderungen:**
  * Neue Klasse `lifesuite/EditorView.java`: Hauptansicht aus scrollbarer
    Werkzeugleiste, geteiltem Arbeitsbereich (Markdown-Eingabe und WebView-Vorschau)
    und ein-/ausblendbarem Inhaltsverzeichnis. Unterstuetzt Code-, Split-, Vorschau-,
    Zen-, Buch- und Praesentationsmodus, Schrift- und Ausrichtungseinstellungen,
    Fett/Kursiv/Unterstrichen, Textfarbe, zuschaltbare Silbentrennung, das Einfuegen
    von Bildern, Tabellen, Code-Bloecken, Zitaten, Notizzetteln und Formeln,
    Papierformate samt Raendern, PDF-Export sowie automatische Speicherung alle
    10 Sekunden und eine Backup-Historie alle 10 Minuten mit bis zu 50 Sicherungen.
  * Neue Klasse `lifesuite/MarkdownRenderer.java`: Wandelt den Markdown-Text ueber
    Suchmuster in HTML um, erkennt Ueberschriften fuer das Inhaltsverzeichnis und
    Literaturverweise fuer das Wiki-Linking und baut das Vorschaudokument mit
    MathJax-Einbindung, Seitenlayout-CSS und Vorschau-Skript.
  * Neue Klasse `lifesuite/EditorDocument.java`: Datenmodell des Dokuments mit
    Layout-Eigenschaften sowie Speichern, Laden und Verwaltung der Backup-Historie.
  * Neue Klasse `lifesuite/EditorBridge.java`: Java-Web-Verbindung, ueber die das
    Vorschau-Skript die benoetigte Inhaltshoehe fuer den Buch-Modus zurueckmeldet.
  * Neue Klasse `lifesuite/LifeSuiteStandalone.java`: Startklasse der Anwendung mit
    Unterstuetzung fuer Classic-/Modern-Design und Light-/Dark-Modus.
  * Neue Ressource `lifesuite/lifesuite.css`: Stylesheet der Editor-Oberflaeche.
