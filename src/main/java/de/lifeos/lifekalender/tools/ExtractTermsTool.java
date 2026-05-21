package de.lifeos.lifekalender.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.AITool;
import core.AIToolSafety;
import core.AgentToolResult;
import de.lifeos.lifekalender.model.Event;
import de.lifeos.lifekalender.service.LLMExtractionService;
import java.util.List;
import java.util.Map;

/**
 * AI-Tool zum Extrahieren von Terminen aus Texten, PDFs oder Bildern.
 */
public class ExtractTermsTool implements AITool {
    private final LLMExtractionService extractionService;
    private final ObjectMapper objectMapper;

    public ExtractTermsTool(LLMExtractionService extractionService) {
        this.extractionService = extractionService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "lifekalender_extract_terms";
    }

    @Override
    public String getDescription() {
        return "Extrahiere Termine aus einem Text, PDF oder Bild. " +
                "Das Tool gibt eine Liste von Terminen zurück, die dann einem Kalender zugeordnet werden können. " +
                "Der Nutzer muss die extrahierten Termine manuell bestätigen, bevor sie gespeichert werden.";
    }

    @Override
    public String getInputSchema() {
        return "{\n" +
               "    \"type\": \"object\",\n" +
               "    \"properties\": {\n" +
               "        \"content\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"description\": \"Der Text, PDF-Inhalt oder OCR-Text aus einem Bild, der analysiert werden soll\"\n" +
               "        },\n" +
               "        \"sourceType\": {\n" +
               "            \"type\": \"string\",\n" +
               "            \"enum\": [\"text\", \"pdf\", \"image\"],\n" +
               "            \"description\": \"Der Typ der Quelle (text, pdf oder image)\",\n" +
               "            \"default\": \"text\"\n" +
               "        }\n" +
               "    },\n" +
               "    \"required\": [\"content\"]\n" +
               "}";
    }

    @Override
    public AIToolSafety getSafety() {
        return AIToolSafety.USER_DATA_WRITE;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public AgentToolResult execute(Map<String, Object> input) {
        try {
            String content = (String) input.get("content");
            String sourceType = (String) input.getOrDefault("sourceType", "text");

            if (content == null || content.trim().isEmpty()) {
                return AgentToolResult.error("Der Inhalt darf nicht leer sein");
            }

            List<Event> events;
            switch (sourceType.toLowerCase()) {
                case "pdf":
                    events = extractionService.extractEventsFromPDF(content);
                    break;
                case "image":
                    events = extractionService.extractEventsFromImage(content);
                    break;
                case "text":
                default:
                    events = extractionService.extractEventsFromText(content);
                    break;
            }

            String jsonResponse = objectMapper.writeValueAsString(events);
            
            return AgentToolResult.confirmationRequired(
                    "Es wurden " + events.size() + " Termine extrahiert. Bitte überprüfen Sie die Termine und bestätigen Sie das Speichern.",
                    Map.of("events", events, "json", jsonResponse)
            );
        } catch (Exception e) {
            return AgentToolResult.error("Fehler bei der Terminextraktion: " + e.getMessage());
        }
    }
}
