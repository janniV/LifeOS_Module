package core;

import java.util.Map;

/**
 * Schnittstelle fuer ein KI-Werkzeug, das ein Modul dem LifeOS-Agenten bereitstellt.
 *
 * Compile-Stub: Die echte Definition liefert der LifeOS-Host zur Laufzeit. Diese
 * Datei enthaelt ausschliesslich die API-Signaturen, die das LifeKalender-Modul
 * verwendet, damit es eigenstaendig gebaut werden kann.
 */
public interface AITool {

    String getName();

    String getDescription();

    String getInputSchema();

    AIToolSafety getSafety();

    boolean requiresConfirmation();

    AgentToolResult execute(Map<String, Object> input);
}
