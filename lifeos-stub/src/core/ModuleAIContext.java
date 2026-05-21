package core;

import java.util.List;

// Diese Klasse kapselt alle KI-spezifischen Anforderungen eines Moduls.
public class ModuleAIContext {
    
    private final String situationalPrompt;
    private final String requestedLoRAPath;
    private final List<AITool> registeredTools;

    public ModuleAIContext(String situationalPrompt, String requestedLoRAPath, List<AITool> registeredTools) {
        this.situationalPrompt = situationalPrompt;
        this.requestedLoRAPath = requestedLoRAPath;
        this.registeredTools = registeredTools;
    }

    // Der situative Prompt-Layer des Moduls wird zurückgegeben.
    public String getSituationalPrompt() {
        return situationalPrompt;
    }

    // Der Dateiname oder Pfad des spezifischen LoRA-Adapters wird zurückgegeben.
    // Es wird null zurückgegeben, falls kein spezifischer Adapter benötigt wird.
    public String getRequestedLoRAPath() {
        return requestedLoRAPath;
    }

    // Die Liste der registrierten Werkzeuge wird zurückgegeben.
    public List<AITool> getRegisteredTools() {
        return registeredTools;
    }
}