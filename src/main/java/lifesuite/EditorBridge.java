package lifesuite;

import java.util.function.DoubleConsumer;

// Verbindungsobjekt zwischen dem JavaScript der WebView-Vorschau und dem Java-Code des Editors.
// Das in der Vorschau geladene Skript misst die tatsächlich benötigte Inhaltshöhe und meldet
// diese über dieses Objekt zurück, damit der Buch-Modus die Vorschaufläche nahtlos an die
// Inhaltshöhe anpassen kann.
public class EditorBridge {

    // Der Empfänger erhält die vom Skript gemeldete Inhaltshöhe in CSS-Pixeln.
    private final DoubleConsumer heightConsumer;

    public EditorBridge(DoubleConsumer heightConsumer) {
        this.heightConsumer = heightConsumer;
    }

    // Wird vom Vorschau-Skript aufgerufen, sobald die Inhaltshöhe feststeht oder sich ändert.
    // Der Aufruf erfolgt aus dem WebView-Thread und wird unverändert an den Empfänger gereicht;
    // der Empfänger sorgt selbst für die Ausführung im JavaFX-Anwendungsthread.
    public void reportHeight(double height) {
        if (heightConsumer != null && height > 0) {
            heightConsumer.accept(height);
        }
    }

    // Erlaubt dem Vorschau-Skript, Diagnosemeldungen in die Java-Konsole zu schreiben.
    public void log(String message) {
        System.out.println("[LifeSuite-Vorschau] " + message);
    }
}
