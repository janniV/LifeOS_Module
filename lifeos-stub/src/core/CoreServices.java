package core;

import java.nio.file.Path;
import javafx.scene.Node;

public interface CoreServices {
    Path getModuleStoragePath(String moduleId);
    void sendNotification(String message);
    String completeWithInternalLLM(String prompt);
    LifeOSFileService getFileService();
    void openModule(String moduleName);
    void showDialog(String title, Node content);
}
