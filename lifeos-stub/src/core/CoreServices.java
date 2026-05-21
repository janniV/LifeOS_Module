package core;

import java.nio.file.Path;
import javafx.scene.Node;
import java.util.List;

public interface CoreServices {
    Path getModuleStoragePath(String moduleId);
    // ... we can add more if needed
}
