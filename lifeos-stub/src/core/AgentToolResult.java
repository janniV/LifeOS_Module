package core;

public class AgentToolResult {
    public static String error(String message) {
        return "{\"success\":false,\"message\":" + quote(message) + "}";
    }

    public static String success(String message) {
        return "{\"success\":true,\"message\":" + quote(message) + "}";
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
