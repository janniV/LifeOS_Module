package de.janniv.lifeos.scrobbler.ui;

import de.janniv.lifeos.scrobbler.NowPlaying;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Custom in-house scrobble notification — replaces {@link java.awt.TrayIcon},
 * which on Windows shows up as an "OpenJDK Platform binary" toast and looks
 * out of place next to LifeOS itself.
 *
 * <p>Renders an undecorated, transparent JavaFX stage at the bottom-right of
 * the primary screen with the cover thumbnail, title, and artist. Slides up
 * 200 ms, holds 3.5 s, fades out 400 ms, then disposes.
 *
 * <p>Concurrent scrobbles replace any visible toast in place (its content is
 * rebuilt) so a fast burst of scrobbles doesn't stack windows on screen.
 */
public final class ScrobbleToast {

    private static final double WIDTH = 360;
    private static final double HEIGHT = 84;
    private static final double MARGIN = 24;
    private static final Duration HOLD = Duration.seconds(3.5);

    private static volatile Stage active;

    private ScrobbleToast() {}

    public static void show(NowPlaying track) {
        if (track == null) return;
        Platform.runLater(() -> render(track));
    }

    private static void render(NowPlaying track) {
        if (active != null) {
            // Rebuild content of the already-visible toast instead of stacking.
            try { active.setScene(buildScene(track, active)); active.sizeToScene(); return; }
            catch (Exception ignored) {}
        }

        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setScene(buildScene(track, stage));

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMaxX() - WIDTH - MARGIN);
        stage.setY(bounds.getMaxY() - HEIGHT - MARGIN);
        stage.show();
        active = stage;

        StackPane root = (StackPane) stage.getScene().getRoot();
        root.setOpacity(0);
        root.setTranslateY(20);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), root);
        fadeIn.setFromValue(0); fadeIn.setToValue(1);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(220), root);
        slideIn.setFromY(20); slideIn.setToY(0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(400), root);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        PauseTransition hold = new PauseTransition(HOLD);

        SequentialTransition seq = new SequentialTransition(fadeIn, hold, fadeOut);
        slideIn.play();
        seq.setOnFinished(e -> {
            if (active == stage) active = null;
            stage.close();
        });
        seq.play();
    }

    private static Scene buildScene(NowPlaying track, Stage owner) {
        // Rounded background with subtle border + drop shadow. Hex values mirror
        // the dark/translucent panels used elsewhere in LifeOS.
        Rectangle bg = new Rectangle(WIDTH, HEIGHT);
        bg.setArcWidth(14);
        bg.setArcHeight(14);
        bg.setFill(Color.web("#1f1f23", 0.96));
        bg.setStroke(Color.web("#3a3a40"));
        bg.setStrokeWidth(1);
        bg.setEffect(new DropShadow(18, Color.color(0, 0, 0, 0.55)));

        ImageView cover = new ImageView();
        cover.setFitWidth(56);
        cover.setFitHeight(56);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        if (track.coverUrl() != null && !track.coverUrl().isBlank()) {
            try { cover.setImage(new Image(track.coverUrl(), 56, 56, true, true, true)); }
            catch (Exception ignored) {}
        }
        StackPane coverFrame = new StackPane(cover);
        coverFrame.setMinSize(56, 56);
        coverFrame.setMaxSize(56, 56);
        coverFrame.setStyle("-fx-background-color: #2a2a30; -fx-background-radius: 8;");

        Label badge = new Label("Scrobbled");
        badge.setStyle("-fx-text-fill: #7dd3fc; -fx-font-size: 10px; -fx-font-weight: bold;");
        Label title = new Label(track.title() == null || track.title().isBlank() ? "—" : track.title());
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        title.setStyle("-fx-text-fill: #f5f5f7;");
        title.setMaxWidth(WIDTH - 56 - 36);
        title.setWrapText(false);
        Label artist = new Label(track.artist() == null || track.artist().isBlank() ? "Unbekannte:r Künstler:in" : track.artist());
        artist.setFont(Font.font("System", FontWeight.NORMAL, 11));
        artist.setStyle("-fx-text-fill: #c5c5cc;");
        artist.setMaxWidth(WIDTH - 56 - 36);

        VBox text = new VBox(2, badge, title, artist);
        text.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(12, coverFrame, text);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(12, 16, 12, 12));
        content.setMaxSize(WIDTH, HEIGHT);
        content.setMinSize(WIDTH, HEIGHT);

        StackPane root = new StackPane(bg, content);
        root.setOnMouseClicked(e -> { if (active == owner) active = null; owner.close(); });

        Scene scene = new Scene(root, WIDTH + 24, HEIGHT + 24);
        scene.setFill(Color.TRANSPARENT);
        return scene;
    }
}
