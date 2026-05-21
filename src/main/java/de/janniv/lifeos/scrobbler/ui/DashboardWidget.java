package de.janniv.lifeos.scrobbler.ui;

import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.ScrobbleEngine;
import de.janniv.lifeos.scrobbler.ScrobbleEvent;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Compact widget for the LifeOS dashboard. Shows the current track and the
 * most recent successful scrobble with a small album thumbnail.
 */
public final class DashboardWidget extends HBox implements ScrobbleEngine.Listener {

    private final ImageView cover = new ImageView();
    private final Label title = new Label("Scrobbler");
    private final Label subtitle = new Label("Noch nichts gehört");
    private final Label tail = new Label("");

    public DashboardWidget() {
        setSpacing(12);
        setPadding(new Insets(12));
        setStyle("-fx-background-color: -fx-control-inner-background; -fx-background-radius: 8;"
            + " -fx-border-color: -fx-box-border; -fx-border-radius: 8; -fx-border-width: 1;");
        cover.setFitWidth(56);
        cover.setFitHeight(56);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);

        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        subtitle.setStyle("-fx-opacity: 0.85;");
        tail.setStyle("-fx-opacity: 0.55; -fx-font-size: 11px;");

        VBox text = new VBox(2, title, subtitle, tail);
        HBox.setHgrow(text, Priority.ALWAYS);
        getChildren().addAll(cover, text);
    }

    @Override
    public void onNowPlaying(NowPlaying current, NowPlaying enhanced) {
        Platform.runLater(() -> {
            if (current == null || current.isEmpty()) return;
            NowPlaying d = enhanced != null ? enhanced : current;
            title.setText(d.title().isEmpty() ? "—" : d.title());
            subtitle.setText(d.artist().isEmpty() ? "Unbekannte:r Künstler:in" : d.artist());
            tail.setText(d.album());
            String url = d.coverUrl();
            if (url != null && !url.isBlank()) {
                try { cover.setImage(new Image(url, 56, 56, true, true, true)); }
                catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onScrobble(ScrobbleEvent event) {
        if (event.status != ScrobbleEvent.Status.OK) return;
        Platform.runLater(() -> tail.setText("Zuletzt scrobbled: " + event.artist + " — " + event.title));
    }

    @Override
    public void onStatus(String message) {}
}
