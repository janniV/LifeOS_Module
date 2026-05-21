package lifesuite;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import lifesuite.pdf.PdfReaderView;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LifeSuiteStandalone extends Application {

    private boolean isDarkMode(List<String> params) {
        if (params.contains("--dark") || params.contains("dark")) {
            return true;
        }
        if (params.contains("--light") || params.contains("light")) {
            return false;
        }
        // Fallback to system preferences (JavaFX 21+)
        try {
            return (Boolean) Platform.class.getMethod("getPreferences").invoke(null).getClass().getMethod("getColorScheme").invoke(Platform.class.getMethod("getPreferences").invoke(null)).toString().equals("DARK");
        } catch (Exception e) {
            // If API not available or error, default to false
            return false;
        }
    }

    @Override
    public void start(Stage primaryStage) {
        // Die gewünschte Darstellung und Ansicht werden aus den Startparametern gelesen.
        List<String> params = getParameters().getRaw();
        boolean dark = isDarkMode(params);
        boolean modern = !(params.contains("--classic") || params.contains("classic"));
        boolean pdfMode = params.contains("--pdf") || params.contains("pdf");

        // Das AtlantaFX-Basistheme wird passend zum Light-/Dark-Modus gesetzt.
        Application.setUserAgentStylesheet(dark
            ? new PrimerDark().getUserAgentStylesheet()
            : new PrimerLight().getUserAgentStylesheet());

        Parent root;
        String title;
        Runnable onClose;
        if (pdfMode) {
            PdfReaderView reader = new PdfReaderView();
            reader.applyTheme(modern, dark, 1.0);
            root = reader;
            title = "LifeSuite - PDF-Reader (Eigenständig)";
            onClose = () -> { };
        } else {
            Path storagePath = Paths.get(System.getProperty("user.dir", "."), "lifesuite-data");
            EditorView editor = new EditorView(storagePath);
            editor.applyTheme(modern, dark, 1.0);
            root = editor;
            title = "LifeSuite - Editor (Eigenständig)";
            onClose = editor::shutdown;
        }

        Scene scene = new Scene(root, 1200, 800);

        // Listen to system theme changes if not explicitly overridden by params
        if (!params.contains("--dark") && !params.contains("dark") &&
            !params.contains("--light") && !params.contains("light")) {
            try {
                Object preferences = Platform.class.getMethod("getPreferences").invoke(null);
                Object colorSchemeProperty = preferences.getClass().getMethod("colorSchemeProperty").invoke(preferences);
                colorSchemeProperty.getClass().getMethod("addListener", javafx.beans.value.ChangeListener.class).invoke(colorSchemeProperty, (javafx.beans.value.ChangeListener<Object>) (obs, oldVal, newVal) -> {
                    boolean newDark = newVal.toString().equals("DARK");
                    Application.setUserAgentStylesheet(newDark
                        ? new PrimerDark().getUserAgentStylesheet()
                        : new PrimerLight().getUserAgentStylesheet());
                    if (pdfMode) {
                        ((PdfReaderView) root).applyTheme(modern, newDark, 1.0);
                    } else {
                        ((EditorView) root).applyTheme(modern, newDark, 1.0);
                    }
                });
            } catch (Exception e) {
                // Ignore if not supported
            }
        }

        primaryStage.setTitle(title);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> onClose.run());
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
