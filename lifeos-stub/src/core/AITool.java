package core;

import java.util.function.Function;

public class AITool {
    private final String name;
    private final String description;
    private final String inputSchema;
    private final AIToolSafety safety;
    private final boolean requiresConfirmation;
    private final Function<String, String> handler;

    public AITool(String name, String description, String inputSchema,
                  AIToolSafety safety, Function<String, String> handler) {
        this(name, description, inputSchema, safety, false, handler);
    }

    public AITool(String name, String description, String inputSchema,
                  AIToolSafety safety, boolean requiresConfirmation, Function<String, String> handler) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.safety = safety;
        this.requiresConfirmation = requiresConfirmation;
        this.handler = handler;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
    public AIToolSafety getSafety() { return safety; }
    public boolean requiresConfirmation() { return requiresConfirmation; }
    public String execute(String input) { return handler.apply(input); }
}
