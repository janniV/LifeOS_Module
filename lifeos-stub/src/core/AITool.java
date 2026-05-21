package core;

import java.util.function.Function;

// Definition eines Werkzeugs, das dem KI-Agenten im aktuellen Modul zur Verfügung gestellt wird.
public class AITool {

    private final String toolName;
    private final String description;
    private final Function<String, String> action;

    public AITool(String toolName, String description, Function<String, String> action) {
        this.toolName = toolName;
        this.description = description;
        this.action = action;
    }

    // Der Name des Werkzeugs wird zurückgegeben.
    public String getToolName() {
        return toolName;
    }

    // Die detaillierte Beschreibung des Werkzeugs für den KI-System-Prompt wird zurückgegeben.
    public String getDescription() {
        return description;
    }

    // Die hinterlegte Aktion des Werkzeugs wird ausgeführt. 
    // Eingabe- und Rückgabewerte werden als JSON-formatierte Strings übergeben, da Sprachmodelle so am besten kommunizieren.
    public String execute(String jsonInput) {
        return action.apply(jsonInput);
    }
}