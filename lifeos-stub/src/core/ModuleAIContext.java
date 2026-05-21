package core;

import java.util.List;

/**
 * Kapselt die KI-spezifischen Anforderungen eines Moduls (Prompt-Layer, Tools,
 * LoRA-Adapter).
 *
 * Compile-Stub: Der LifeOS-Host stellt die massgebliche Definition zur Laufzeit
 * bereit.
 */
public class ModuleAIContext {

    private final String situationalPrompt;
    private final String requestedLoRAPath;
    private final List<AITool> registeredTools;

    public ModuleAIContext(String situationalPrompt, String requestedLoRAPath,
                           List<AITool> registeredTools) {
        this.situationalPrompt = situationalPrompt;
        this.requestedLoRAPath = requestedLoRAPath;
        this.registeredTools = registeredTools;
    }

    public String getSituationalPrompt() {
        return situationalPrompt;
    }

    public String getRequestedLoRAPath() {
        return requestedLoRAPath;
    }

    public List<AITool> getRegisteredTools() {
        return registeredTools;
    }
}
