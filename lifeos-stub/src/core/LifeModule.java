package core;

import javafx.scene.Node;
import java.util.List;

/**
 * Schnittstelle, die jedes LifeOS-Modul implementieren muss, um vom Host
 * geladen zu werden.
 *
 * Compile-Stub: Der LifeOS-Host stellt die massgebliche Definition zur Laufzeit
 * bereit.
 */
public interface LifeModule {

    String getModuleName();

    String getModuleId();

    default String getModuleIconPath() {
        return null;
    }

    String getFontIconName();

    Node getMainView();

    void onInitialize(CoreServices core);

    void onShutdown();

    ModuleAIContext getAIContext();

    default Node getDashboardWidget() {
        return null;
    }

    default List<LifeModule> getSubModules() {
        return List.of();
    }

    default void onUninstall() {
    }
}
