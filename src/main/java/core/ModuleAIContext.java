package core;

import java.util.List;

public class ModuleAIContext {
    private String situationalPrompt;
    private String requestedLoRAPath;
    private List<Object> registeredTools;

    public ModuleAIContext(String situationalPrompt, String requestedLoRAPath, List<Object> registeredTools) {
        this.situationalPrompt = situationalPrompt;
        this.requestedLoRAPath = requestedLoRAPath;
        this.registeredTools = registeredTools;
    }
}
