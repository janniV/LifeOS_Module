package core;

import java.io.IOException;
import java.nio.file.Path;

public interface LifeOSFileService {
    Path getDataRoot();
    Path getWorkspacesRoot();
    Path getExportsRoot();
    Path getModuleDataRoot();
    Path getModelsRoot();
    String readTextFile(Path path) throws IOException;
    void writeTextFile(Path path, String content) throws IOException;
    Path createDirectories(Path path) throws IOException;
    void moveToTrash(Path path, String displayName) throws IOException;
    boolean isInsideLifeOS(Path path);
}
