package lifesuite;

import core.CoreServices;
import core.LifeModule;
import core.ModuleAIContext;
import javafx.scene.Node;

import java.nio.file.Path;
import java.util.List;

public class LifeSuiteModule implements LifeModule {
    private CoreServices core;
    private EditorView editorView;

    @Override
    public String getModuleName() {
        return "LifeSuite";
    }

    @Override
    public String getModuleId() {
        return "com.lifeos.lifesuite";
    }

    @Override
    public String getModuleIconPath() {
        return null;
    }

    @Override
    public String getFontIconName() {
        return "fas-edit";
    }

    @Override
    public void onInitialize(CoreServices core) {
        this.core = core;
        Path storagePath = core.getModuleStoragePath(getModuleId());
        editorView = new EditorView(storagePath);
        // LifeOS handles theming and calls applyTheme via onThemeChanged
    }

    @Override
    public Node getMainView() {
        return editorView;
    }

    @Override
    public void onShutdown() {
        if (editorView != null) {
            editorView.shutdown();
        }
    }

    @Override
    public ModuleAIContext getAIContext() {
        return new ModuleAIContext(
            "Du hilfst im LifeSuite-Modul beim Bearbeiten von Texten. Nutze Tools nur, wenn sie zur Aufgabe passen.",
            null,
            List.of()
        );
    }

    // We can add a hook for when the theme changes, if LifeOS invokes it.
    // However, LifeOS runtime will probably call it on the module if it implements it
    // Wait, the prompt says:
    // "JavaFX-Module erben das Theme über die LifeOS-Scene und können zusätzlich onThemeChanged(...) überschreiben."

    public void onThemeChanged(boolean isModern, boolean isDark, double fontScale) {
        if (editorView != null) {
            editorView.applyTheme(isModern, isDark, fontScale);
        }
    }
}
