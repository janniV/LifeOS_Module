package core;

import javafx.scene.Node;
import java.util.List;

public interface LifeModule {
    String getModuleName();
    String getModuleId();
    default String getModuleIconPath() { return null; }
    String getFontIconName();
    Node getMainView();
    void onInitialize(CoreServices core);
    void onShutdown();
    ModuleAIContext getAIContext();
    default Node getDashboardWidget() { return null; }
    default List<LifeModule> getSubModules() { return List.of(); }
    default void onUninstall() {}
    default List<String> getRequestedPermissions() { return List.of(); }
}
