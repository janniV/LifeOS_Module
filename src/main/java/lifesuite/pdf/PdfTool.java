package lifesuite.pdf;

// Beschreibt das aktuell gewählte Werkzeug des PDF-Readers.
// Das Werkzeug bestimmt, wie die Event-Ebene einer Seite auf Mauseingaben reagiert.
public enum PdfTool {

    // Verschieben und Scrollen; die Event-Ebene fängt keine Eingaben ab.
    HAND,

    // Aufziehen eines Auswahlrechtecks zur Textauswahl.
    SELECT,

    // Auftragen einer halbtransparenten Textmarkierung auf der Highlight-Ebene.
    HIGHLIGHT,

    // Freihandzeichnen auf der Zeichen-Ebene.
    PEN,

    // Entfernen vorhandener Striche auf der Zeichen- und Highlight-Ebene.
    ERASER
}
