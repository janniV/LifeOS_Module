package core;

import java.util.Map;

/**
 * Ergebnis der Ausfuehrung eines KI-Werkzeugs.
 *
 * Compile-Stub: Die echte Implementierung liefert der LifeOS-Host zur Laufzeit.
 * Hier sind nur die statischen Fabrikmethoden abgebildet, die das LifeKalender-
 * Modul verwendet.
 */
public class AgentToolResult {

    private final boolean success;
    private final boolean confirmationRequired;
    private final String message;
    private final Map<String, Object> data;

    private AgentToolResult(boolean success, boolean confirmationRequired,
                            String message, Map<String, Object> data) {
        this.success = success;
        this.confirmationRequired = confirmationRequired;
        this.message = message;
        this.data = data;
    }

    public static AgentToolResult error(String message) {
        return new AgentToolResult(false, false, message, Map.of());
    }

    public static AgentToolResult success(String message, Map<String, Object> data) {
        return new AgentToolResult(true, false, message, data);
    }

    public static AgentToolResult confirmationRequired(String message, Map<String, Object> data) {
        return new AgentToolResult(false, true, message, data);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isConfirmationRequired() {
        return confirmationRequired;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
