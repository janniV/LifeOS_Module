package core;

import java.util.List;

public class ModuleAIContext {
    private final String situationalPrompt;
    private final String requestedLoRAPath;
    private final List<AITool> registeredTools;

    public ModuleAIContext(String situationalPrompt, String requestedLoRAPath, List<AITool> registeredTools) {
        this.situationalPrompt = situationalPrompt;
        this.requestedLoRAPath = requestedLoRAPath;
        this.registeredTools = registeredTools != null ? registeredTools : List.of();
    }

    public String getSituationalPrompt() { return situationalPrompt; }
    public String getRequestedLoRAPath() { return requestedLoRAPath; }
    public List<AITool> getRegisteredTools() { return registeredTools; }
}
