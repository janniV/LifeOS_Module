package de.janniv.lifeos.scrobbler.ui;

import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.PodcastLog;
import de.janniv.lifeos.scrobbler.ScrobbleEngine;
import de.janniv.lifeos.scrobbler.ScrobbleEvent;
import de.janniv.lifeos.scrobbler.ScrobblerSettings;
import de.janniv.lifeos.scrobbler.source.MediaSourceFactory;
import de.janniv.lifeos.scrobbler.target.MalojaClient;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Hauptansicht des Scrobbler-Moduls: aktuell laufender Track mit Cover,
 * History-Tabelle und Einstellungen. Geschäftslogik liegt komplett in der
 * Engine — die View hört nur per Listener mit und schreibt Settings über
 * den übergebenen Consumer zurück.
 */
public final class ScrobblerView extends BorderPane implements ScrobbleEngine.Listener {

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public interface Controller {
        void onSettingsChanged(ScrobblerSettings updated);
        void onManualScrobble(NowPlaying track);
        void onDeleteScrobble(ScrobbleEvent event);
        void onEditScrobble(ScrobbleEvent original, NowPlaying corrected);
        /** Same as {@link #onEditScrobble} but also pins the correction as a
         *  persistent rule for all future scrobbles of the matched (artist,
         *  title) pair, and optionally rewrites prior Maloja history. */
        default void onEditScrobbleAndRemember(ScrobbleEvent original, NowPlaying corrected,
                                               boolean alsoRewritePast) {
            onEditScrobble(original, corrected);
        }
        java.util.List<PodcastLog.Entry> podcastSnapshot();
        void onDeletePodcast(int index);
        void onEditPodcast(int index, PodcastLog.Entry updated);
        /** Asks the controller for the latest history from Maloja. The reply
         *  is delivered asynchronously so the network call doesn't block the
         *  FX thread. May be called repeatedly via the refresh button. */
        void loadHistoryFromServer(Consumer<java.util.List<ScrobbleEvent>> reply);
    }

    private final ScrobblerSettings settings;
    private final Controller controller;
    private final ObservableList<PodcastLog.Entry> podcasts = FXCollections.observableArrayList();
    private final TableView<PodcastLog.Entry> podcastTable = new TableView<>(podcasts);
    private final CoverCache coverCache = new CoverCache();

    private final ImageView cover = new ImageView();
    private final Label nowTitle = new Label("—");
    private final Label nowArtist = new Label("Nichts läuft");
    private final Label nowAlbum = new Label("");
    private final Label nowSource = new Label("");
    private final Label enhancedHint = new Label("");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label statusLabel = new Label("Bereit");

    private final ObservableList<ScrobbleEvent> history = FXCollections.observableArrayList();
    private final TableView<ScrobbleEvent> historyTable = new TableView<>(history);

    private final TextField malojaUrl = new TextField();
    private final PasswordField malojaKey = new PasswordField();
    private final PasswordField malojaAdminPassword = new PasswordField();
    private final ChoiceBox<String> proxyMode = new ChoiceBox<>(FXCollections.observableArrayList("none", "socks", "http"));
    private final TextField proxyHost = new TextField();
    private final TextField proxyPort = new TextField();
    private final ChoiceBox<String> playerPicker = new ChoiceBox<>();
    private final CheckBox useMb = new CheckBox("MusicBrainz zur Verifizierung benutzen");
    private final CheckBox smartParsing = new CheckBox("Smarte Künstler:innen-Erkennung für Browser-/YouTube-Titel");
    private final CheckBox enabled = new CheckBox("Scrobbeln aktiv");
    private final CheckBox notifyOnScrobble = new CheckBox("Desktop-Benachrichtigung bei jedem Scrobble");
    private final Spinner<Integer> webhookPort = new Spinner<>(0, 65535, 0, 1);
    private final CheckBox podcastFilter = new CheckBox("Podcasts/Videos lokal loggen statt scrobbeln");
    private final Spinner<Integer> podcastCutoff = new Spinner<>(0, 10800, 900, 60);
    private final TextArea podcastKeywords = new TextArea();
    private final TextArea podcastSourceMarkers = new TextArea();
    private final Spinner<Integer> pollInterval = new Spinner<>(500, 10000, 1500, 250);
    private final Spinner<Integer> minLength = new Spinner<>(0, 600, 30, 5);
    private final Spinner<Integer> absoluteSec = new Spinner<>(30, 1800, 240, 10);
    private final Spinner<Integer> skipThreshold = new Spinner<>(0, 60, 5, 1);
    private final Slider fractionSlider = new Slider(0.1, 0.95, 0.5);
    private final TextArea ignored = new TextArea();
    private final TextArea uploaderBlacklist = new TextArea();
    private final TextArea artistPatterns = new TextArea();
    private final TextArea titleCleanupPatterns = new TextArea();
    private final Label connTest = new Label("");
    /** Tracks the source-app name of whatever is currently playing so the
     *  "Diese Quelle ignorieren"-Button knows what to add to the ignore list. */
    private volatile String currentSourceApp = "";
    private final Button ignoreSourceBtn = new Button("Diese Quelle ignorieren");

    public ScrobblerView(ScrobblerSettings settings, Controller controller) {
        this.settings = settings;
        this.controller = controller;
        // Direkt nach Konstruktion den Verlauf von Maloja ziehen, damit beim
        // App-Neustart nicht alles weg ist. Live-Events kommen weiterhin via
        // onScrobble; doppelte Einträge werden anhand des Zeitstempels
        // dedupliziert.
        controller.loadHistoryFromServer(this::seedHistoryFromServer);
        setPadding(new Insets(16));
        setTop(buildHeader());
        setCenter(buildCenter());
        setBottom(statusLabel);
        BorderPane.setMargin(statusLabel, new Insets(8, 0, 0, 0));
        statusLabel.setStyle("-fx-text-fill: -fx-text-base-color; -fx-opacity: 0.85; "
            + "-fx-padding: 6 10 6 10; -fx-background-color: -fx-control-inner-background; "
            + "-fx-background-radius: 4; -fx-font-size: 11px;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        loadSettingsIntoUi();
    }

    private Node buildHeader() {
        cover.setFitWidth(96);
        cover.setFitHeight(96);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        StackPane coverFrame = new StackPane(cover);
        coverFrame.setStyle("-fx-background-color: -fx-control-inner-background; -fx-background-radius: 6; -fx-min-width: 96;");
        coverFrame.setMinSize(96, 96);
        coverFrame.setMaxSize(96, 96);

        nowTitle.setFont(Font.font("System", FontWeight.BOLD, 22));
        nowArtist.setFont(Font.font("System", FontWeight.NORMAL, 16));
        nowArtist.setStyle("-fx-opacity: 0.85;");
        nowAlbum.setStyle("-fx-opacity: 0.65;");
        nowSource.setStyle("-fx-opacity: 0.5; -fx-font-size: 11px;");
        enhancedHint.setStyle("-fx-opacity: 0.6; -fx-font-style: italic;");
        progress.setMaxWidth(Double.MAX_VALUE);

        ignoreSourceBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2 8 2 8;");
        ignoreSourceBtn.setMinWidth(Region.USE_PREF_SIZE);
        ignoreSourceBtn.setDisable(true);
        ignoreSourceBtn.setOnAction(e -> ignoreCurrentSource());
        HBox sourceRow = new HBox(8, nowSource, ignoreSourceBtn);
        sourceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox text = new VBox(4, nowTitle, nowArtist, nowAlbum, enhancedHint, progress, sourceRow);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox box = new HBox(14, coverFrame, text);
        box.setPadding(new Insets(8, 12, 16, 12));
        box.setStyle("-fx-background-color: -fx-control-inner-background; "
            + "-fx-background-radius: 8; -fx-border-color: -fx-box-border; "
            + "-fx-border-radius: 8; -fx-border-width: 1;");
        return box;
    }

    private Node buildCenter() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildHistoryTab(), buildPodcastsTab(), buildManualTab(), buildSettingsTab());
        BorderPane.setMargin(tabs, new Insets(12, 0, 0, 0));
        return tabs;
    }

    private Tab buildPodcastsTab() {
        TableColumn<PodcastLog.Entry, String> imgPCol = new TableColumn<>("");
        imgPCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().coverUrl));
        imgPCol.setPrefWidth(50); imgPCol.setMinWidth(50); imgPCol.setMaxWidth(50); imgPCol.setResizable(false);
        imgPCol.setCellFactory(col -> coverCell());

        TableColumn<PodcastLog.Entry, String> tCol = new TableColumn<>("Zeit");
        tCol.setCellValueFactory(d -> new SimpleStringProperty(
            d.getValue().playedAt == null ? "" : TIME_FMT.format(d.getValue().playedAt)));
        tCol.setPrefWidth(72);
        TableColumn<PodcastLog.Entry, String> sCol = new TableColumn<>("Sendung");
        sCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().show));
        sCol.setPrefWidth(130);
        // Narrow episode-number column — shows just the number extracted from the episode field.
        TableColumn<PodcastLog.Entry, String> epNrCol = new TableColumn<>("Episode");
        epNrCol.setCellValueFactory(d -> new SimpleStringProperty(
            PodcastLog.extractEpisodeNumber(d.getValue().episode)));
        epNrCol.setPrefWidth(72); epNrCol.setMinWidth(50); epNrCol.setMaxWidth(100);
        // Wide title column — shows only the episode title (number already in the previous column).
        TableColumn<PodcastLog.Entry, String> eCol = new TableColumn<>("Folge");
        eCol.setCellValueFactory(d -> new SimpleStringProperty(
            PodcastLog.extractEpisodeTitleOnly(d.getValue().episode)));
        eCol.setPrefWidth(240);
        TableColumn<PodcastLog.Entry, String> srcCol = new TableColumn<>("Quelle");
        srcCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().source));
        srcCol.setPrefWidth(90);
        TableColumn<PodcastLog.Entry, String> dCol = new TableColumn<>("Länge");
        dCol.setCellValueFactory(d -> new SimpleStringProperty(formatLength(d.getValue().durationMs)));
        dCol.setPrefWidth(72);

        podcastTable.getColumns().setAll(tCol, imgPCol, sCol, epNrCol, eCol, srcCol, dCol);
        podcastTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        podcastTable.setPlaceholder(new Label("Noch keine Podcasts/Videos erkannt."));

        ContextMenu menu = new ContextMenu();
        MenuItem edit = new MenuItem("Bearbeiten…");
        edit.setOnAction(e -> {
            int idx = podcastTable.getSelectionModel().getSelectedIndex();
            PodcastLog.Entry sel = podcastTable.getSelectionModel().getSelectedItem();
            if (idx >= 0 && sel != null) showPodcastEditDialog(idx, sel);
        });
        MenuItem delete = new MenuItem("Eintrag löschen");
        delete.setOnAction(e -> {
            int idx = podcastTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                controller.onDeletePodcast(idx);
                refreshPodcasts();
            }
        });
        MenuItem refresh = new MenuItem("Liste aktualisieren");
        refresh.setOnAction(e -> refreshPodcasts());
        menu.getItems().addAll(edit, new SeparatorMenuItem(), delete, refresh);
        podcastTable.setContextMenu(menu);
        podcastTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                int idx = podcastTable.getSelectionModel().getSelectedIndex();
                PodcastLog.Entry sel = podcastTable.getSelectionModel().getSelectedItem();
                if (idx >= 0 && sel != null) showPodcastEditDialog(idx, sel);
            }
        });

        BorderPane pane = new BorderPane(podcastTable);
        Button refreshBtn = new Button("Aktualisieren");
        refreshBtn.setOnAction(e -> refreshPodcasts());
        Label hint = new Label("Diese Einträge werden nicht zu Maloja geschickt — nur lokal gespeichert.");
        hint.setStyle("-fx-opacity: 0.65; -fx-font-size: 11px;");
        HBox top = new HBox(10, hint, new Region(), refreshBtn);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setPadding(new Insets(6, 6, 8, 6));
        pane.setTop(top);
        refreshPodcasts();
        return new Tab("Podcasts", pane);
    }

    /** Cover-art cell that downloads bytes via {@link CoverCache} instead of
     *  relying on JavaFX's built-in HTTP loader (which fails silently on many
     *  Maloja placeholder responses). Each cell tracks the URL it's currently
     *  showing so cell-reuse during scrolling doesn't show stale art. */
    private <T> TableCell<T, String> coverCell() {
        return new TableCell<>() {
            private final ImageView iv = new ImageView();
            private String currentUrl = "";
            { iv.setFitWidth(40); iv.setFitHeight(40); iv.setPreserveRatio(true); iv.setSmooth(true); }
            @Override
            protected void updateItem(String url, boolean empty) {
                super.updateItem(url, empty);
                if (empty || url == null || url.isBlank()) {
                    setGraphic(null);
                    iv.setImage(null);
                    currentUrl = "";
                    return;
                }
                if (!url.equals(currentUrl)) {
                    currentUrl = url;
                    iv.setImage(null);
                    coverCache.load(url, img -> { if (url.equals(currentUrl)) iv.setImage(img); });
                }
                setGraphic(iv);
            }
        };
    }

    private void refreshPodcasts() {
        Platform.runLater(() -> podcasts.setAll(controller.podcastSnapshot()));
    }

    private void showPodcastEditDialog(int index, PodcastLog.Entry original) {
        if (original == null) return;
        Dialog<PodcastLog.Entry> d = new Dialog<>();
        d.setTitle("Podcast bearbeiten");
        d.setHeaderText("Lokaler Eintrag — wird nicht an Maloja geschickt.");
        if (getScene() != null) d.initOwner(getScene().getWindow());

        TextField show = new TextField(original.show == null ? "" : original.show);
        TextField episode = new TextField(original.episode == null ? "" : original.episode);
        TextField source = new TextField(original.source == null ? "" : original.source);
        Spinner<Integer> length = new Spinner<>(0, 36000, (int) Math.max(0, original.durationMs / 1000), 30);
        length.setEditable(true);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(new Label("Sendung"), 0, 0); g.add(show, 1, 0);
        g.add(new Label("Episode"), 0, 1); g.add(episode, 1, 1);
        g.add(new Label("Quelle"), 0, 2); g.add(source, 1, 2);
        g.add(new Label("Länge (Sek.)"), 0, 3); g.add(length, 1, 3);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS); c1.setMinWidth(260);
        g.getColumnConstraints().addAll(new ColumnConstraints(), c1);

        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            PodcastLog.Entry upd = new PodcastLog.Entry(
                original.playedAt, show.getText().trim(), episode.getText().trim(),
                source.getText().trim(), length.getValue() * 1000L);
            return upd;
        });
        d.showAndWait().ifPresent(updated -> {
            controller.onEditPodcast(index, updated);
            refreshPodcasts();
        });
    }

    private static String formatLength(long ms) {
        if (ms <= 0) return "";
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    @SuppressWarnings("unchecked")
    private Tab buildHistoryTab() {
        TableColumn<ScrobbleEvent, String> imgCol = new TableColumn<>("");
        imgCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().coverUrl));
        imgCol.setPrefWidth(50); imgCol.setMinWidth(50); imgCol.setMaxWidth(50); imgCol.setResizable(false);
        imgCol.setCellFactory(col -> coverCell());

        TableColumn<ScrobbleEvent, String> tCol = new TableColumn<>("Zeit");
        tCol.setCellValueFactory(d -> new SimpleStringProperty(TIME_FMT.format(d.getValue().timestamp)));
        tCol.setPrefWidth(72);
        TableColumn<ScrobbleEvent, String> aCol = new TableColumn<>("Künstler:in");
        aCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().artist));
        aCol.setPrefWidth(150);
        TableColumn<ScrobbleEvent, String> sCol = new TableColumn<>("Titel");
        sCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().title));
        sCol.setPrefWidth(200);
        TableColumn<ScrobbleEvent, String> alCol = new TableColumn<>("Album");
        alCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().album));
        alCol.setPrefWidth(140);
        TableColumn<ScrobbleEvent, String> stCol = new TableColumn<>("Status");
        stCol.setCellValueFactory(d -> new SimpleStringProperty(
            d.getValue().status == ScrobbleEvent.Status.LOCAL ? "LOKAL" : d.getValue().status.name()));
        stCol.setPrefWidth(70);
        TableColumn<ScrobbleEvent, String> dCol = new TableColumn<>("Detail");
        dCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().detail));
        dCol.setPrefWidth(240);

        historyTable.getColumns().setAll(tCol, imgCol, aCol, sCol, alCol, stCol, dCol);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setPlaceholder(new Label("Noch keine Scrobbles."));
        historyTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        historyTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<ScrobbleEvent> row = new javafx.scene.control.TableRow<>();
            ContextMenu rowMenu = new ContextMenu();

            MenuItem editItem = new MenuItem("Bearbeiten…");
            editItem.setOnAction(e -> {
                ScrobbleEvent ev = row.getItem();
                if (ev != null) showEditDialog(ev);
            });

            MenuItem deleteOneItem = new MenuItem("Aus Maloja löschen");
            deleteOneItem.setOnAction(e -> bulkDelete(
                historyTable.getSelectionModel().getSelectedItems().isEmpty()
                    ? List.of(row.getItem())
                    : new ArrayList<>(historyTable.getSelectionModel().getSelectedItems())));

            MenuItem deleteAllSelectedItem = new MenuItem("Alle markierten löschen");
            deleteAllSelectedItem.setOnAction(e -> bulkDelete(
                new ArrayList<>(historyTable.getSelectionModel().getSelectedItems())));

            rowMenu.getItems().addAll(editItem, new SeparatorMenuItem(), deleteOneItem, deleteAllSelectedItem);
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null).otherwise(rowMenu));
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) showEditDialog(row.getItem());
            });
            return row;
        });

        Button refresh = new Button("Aus Maloja laden");
        refresh.setOnAction(e -> {
            statusLabel.setText("Lade Verlauf …");
            controller.loadHistoryFromServer(events -> {
                seedHistoryFromServer(events);
                Platform.runLater(() -> statusLabel.setText("Verlauf: "
                    + (events == null ? 0 : events.size()) + " Einträge aus Maloja"));
            });
        });

        Button deleteSelected = new Button("Auswahl löschen");
        deleteSelected.setStyle("-fx-text-fill: -fx-error-color;");
        deleteSelected.setOnAction(e -> bulkDelete(
            new ArrayList<>(historyTable.getSelectionModel().getSelectedItems())));
        deleteSelected.disableProperty().bind(
            javafx.beans.binding.Bindings.isEmpty(historyTable.getSelectionModel().getSelectedItems()));

        Label hint = new Label("Strg+Klick für Mehrfachauswahl • Doppelklick = bearbeiten • Rechtsklick = Optionen");
        hint.setStyle("-fx-opacity: 0.65; -fx-font-size: 11px;");
        HBox top = new HBox(8, hint, new Region(), deleteSelected, refresh);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setPadding(new Insets(6, 6, 8, 6));
        BorderPane pane = new BorderPane(historyTable);
        pane.setTop(top);
        return new Tab("Verlauf", pane);
    }

    /**
     * Deletes all given events from Maloja and removes them from the local list.
     * Asks for confirmation when more than one entry is selected so the user
     * can't accidentally wipe large chunks of history.
     */
    private void bulkDelete(List<ScrobbleEvent> targets) {
        if (targets == null || targets.isEmpty()) return;
        targets = targets.stream()
            .filter(e -> e != null && e.status != ScrobbleEvent.Status.LOCAL)
            .collect(java.util.stream.Collectors.toList());
        if (targets.isEmpty()) {
            statusLabel.setText("Lokal gespeicherte Podcasts können nicht aus Maloja gelöscht werden.");
            return;
        }
        final List<ScrobbleEvent> toDelete = targets;

        String msg = toDelete.size() == 1
            ? "Aus Maloja löschen?\n" + toDelete.get(0).artist + " — " + toDelete.get(0).title
            : toDelete.size() + " Scrobbles aus Maloja löschen?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg);
        confirm.setHeaderText("Scrobble" + (toDelete.size() == 1 ? "" : "s") + " löschen");
        if (getScene() != null) confirm.initOwner(getScene().getWindow());
        confirm.showAndWait().ifPresent(b -> {
            if (b != ButtonType.OK) return;
            history.removeAll(toDelete);
            for (ScrobbleEvent ev : toDelete) {
                controller.onDeleteScrobble(ev);
            }
            statusLabel.setText(toDelete.size() + " Scrobble(s) zum Löschen gesendet …");
        });
    }

    private void showEditDialog(ScrobbleEvent original) {
        if (original == null) return;
        Dialog<EditResult> d = new Dialog<>();
        d.setTitle("Scrobble bearbeiten");
        d.setHeaderText("Korrigiert den Eintrag in Maloja (löscht alt, sendet neu).");
        if (getScene() != null) d.initOwner(getScene().getWindow());

        TextField artist = new TextField(original.artist);
        TextField title = new TextField(original.title);
        TextField album = new TextField(original.album);
        Spinner<Integer> length = new Spinner<>(0, 36000, (int) Math.max(0, original.durationMs / 1000), 5);
        length.setEditable(true);
        CheckBox remember = new CheckBox("Korrektur für dieses Lied merken (gilt für zukünftige Scrobbles)");
        CheckBox rewritePast = new CheckBox("Auch bestehende Maloja-Einträge dieses Songs nachträglich anpassen");
        rewritePast.setDisable(true);
        remember.selectedProperty().addListener((obs, was, isNow) -> {
            rewritePast.setDisable(!isNow);
            if (!isNow) rewritePast.setSelected(false);
        });

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(new Label("Künstler:in"), 0, 0); g.add(artist, 1, 0);
        g.add(new Label("Titel"), 0, 1); g.add(title, 1, 1);
        g.add(new Label("Album"), 0, 2); g.add(album, 1, 2);
        g.add(new Label("Länge (Sek.)"), 0, 3); g.add(length, 1, 3);
        g.add(remember, 0, 4, 2, 1);
        g.add(rewritePast, 0, 5, 2, 1);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS); c1.setMinWidth(220);
        g.getColumnConstraints().addAll(new ColumnConstraints(), c1);

        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            NowPlaying corrected = new NowPlaying(artist.getText().trim(), title.getText().trim(),
                album.getText().trim(), "edit", length.getValue() * 1000L, 0,
                NowPlaying.State.PLAYING, original.timestamp);
            return new EditResult(corrected, remember.isSelected(), rewritePast.isSelected());
        });
        d.showAndWait().ifPresent(result -> {
            if (result.remember) {
                controller.onEditScrobbleAndRemember(original, result.corrected, result.rewritePast);
            } else {
                controller.onEditScrobble(original, result.corrected);
            }
            // Optimistisch entfernen — die echte Quelle ist Maloja, beim
            // nächsten Sync taucht der korrigierte Eintrag wieder auf.
            history.remove(original);
        });
    }

    private record EditResult(NowPlaying corrected, boolean remember, boolean rewritePast) {}

    private Tab buildManualTab() {
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(14));

        TextField mArtist = new TextField();
        TextField mTitle = new TextField();
        TextField mAlbum = new TextField();
        Spinner<Integer> mLength = new Spinner<>(0, 36000, 0, 5);
        mLength.setEditable(true);

        g.add(new Label("Künstler:in"), 0, 0); g.add(mArtist, 1, 0);
        g.add(new Label("Titel"), 0, 1); g.add(mTitle, 1, 1);
        g.add(new Label("Album"), 0, 2); g.add(mAlbum, 1, 2);
        g.add(new Label("Länge (Sek.)"), 0, 3); g.add(mLength, 1, 3);

        Label hint = new Label("");
        hint.setStyle("-fx-opacity: 0.7;");

        Button submit = new Button("Manuell scrobbeln");
        submit.setOnAction(e -> {
            String a = mArtist.getText().trim();
            String t = mTitle.getText().trim();
            if (a.isEmpty() || t.isEmpty()) {
                hint.setText("Künstler:in und Titel sind Pflicht.");
                return;
            }
            long len = mLength.getValue() * 1000L;
            NowPlaying np = new NowPlaying(a, t, mAlbum.getText().trim(), "manual",
                len, 0, NowPlaying.State.PLAYING, Instant.now());
            controller.onManualScrobble(np);
            hint.setText("Gesendet: " + a + " — " + t);
            mArtist.clear(); mTitle.clear(); mAlbum.clear();
        });
        g.add(submit, 1, 4);
        g.add(hint, 1, 5);

        ColumnConstraints c0 = new ColumnConstraints(); c0.setMinWidth(120);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c0, c1);

        return new Tab("Manuell", g);
    }

    private Tab buildSettingsTab() {
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(12));
        int row = 0;

        g.add(new Label("Maloja-URL"), 0, row);
        malojaUrl.setPromptText("http://nas.local:42010");
        g.add(malojaUrl, 1, row++, 2, 1);

        g.add(new Label("Maloja-API-Key"), 0, row);
        malojaKey.setPromptText("Privater API-Key (fürs Scrobbeln)");
        g.add(malojaKey, 1, row++, 2, 1);

        g.add(new Label("Maloja-Admin-Passwort"), 0, row);
        malojaAdminPassword.setPromptText("Login-Passwort der Maloja-Weboberfläche");
        g.add(malojaAdminPassword, 1, row++, 2, 1);
        Label adminHint = new Label("→ Wird nur fürs Löschen/Bearbeiten gebraucht. "
            + "Maloja akzeptiert API-Keys nur fürs Scrobbeln; alles andere "
            + "läuft über die Login-Session. Lokal gespeichert, geht nicht ins Netz "
            + "außer zum Maloja-Server selbst.");
        adminHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        adminHint.setWrapText(true);
        g.add(adminHint, 1, row++, 2, 1);

        Button testBtn = new Button("Verbindung testen");
        testBtn.setOnAction(e -> {
            persistFromUi();
            MalojaClient probe = new MalojaClient(settings.malojaUrl, settings.malojaApiKey,
                settings.malojaAdminPassword);
            connTest.setText("Teste…");
            new Thread(() -> {
                String r = probe.testConnection();
                Platform.runLater(() -> connTest.setText(r));
            }, "scrobbler-connect-test").start();
        });
        Button authBtn = new Button("Lösch-Berechtigung prüfen");
        authBtn.setOnAction(e -> {
            persistFromUi();
            MalojaClient probe = new MalojaClient(settings.malojaUrl, settings.malojaApiKey,
                settings.malojaAdminPassword);
            connTest.setText("Prüfe Admin-Login…");
            new Thread(() -> {
                String r = probe.testDeleteAuth();
                Platform.runLater(() -> connTest.setText(r));
            }, "scrobbler-auth-test").start();
        });
        Button openMalojaBtn = new Button("Maloja-Webinterface öffnen");
        openMalojaBtn.setOnAction(e -> {
            String url = malojaUrl.getText().trim();
            if (url.isEmpty()) { connTest.setText("Bitte zuerst die Maloja-URL eintragen."); return; }
            // Open root URL — Maloja exposes API key management via the cog icon
            // in the top-right of the main page, not at a fixed admin URL.
            String rootUrl = url.replaceAll("/+$", "") + "/";
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(rootUrl));
            } catch (Exception ex) {
                connTest.setText("Konnte Browser nicht öffnen: " + ex.getMessage()
                    + "\nManuell aufrufen: " + rootUrl);
            }
        });
        Label openHint = new Label("→ API-Keys: oben rechts auf das Zahnrad-Icon klicken, "
            + "dann \"API Keys\". Admin-Passwort: dasselbe, mit dem du dich oben rechts "
            + "auf der Maloja-Seite einloggst.");
        openHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        openHint.setWrapText(true);
        // Pin buttons to their preferred width so JavaFX doesn't ellipsis-truncate
        // the label when the column is narrower than the sum of pref widths.
        for (Button b : List.of(testBtn, authBtn, openMalojaBtn)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox testRow = new HBox(8, testBtn, authBtn, openMalojaBtn);
        testRow.setMinWidth(Region.USE_PREF_SIZE);
        g.add(testRow, 1, row);
        VBox connArea = new VBox(4, connTest, openHint);
        connTest.setWrapText(true);
        connTest.setMaxWidth(420);
        g.add(connArea, 2, row++);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(new Label("Proxy-Modus"), 0, row);
        g.add(proxyMode, 1, row++);

        g.add(new Label("Proxy-Host"), 0, row);
        proxyHost.setPromptText("z. B. 127.0.0.1 (Tor)");
        g.add(proxyHost, 1, row++, 2, 1);

        g.add(new Label("Proxy-Port"), 0, row);
        proxyPort.setPromptText("z. B. 9050");
        g.add(proxyPort, 1, row++, 2, 1);

        // Pin checkbox text to preferred width so JavaFX doesn't add the "…"
        // ellipsis when the column is narrower than the label's natural size.
        for (CheckBox cb : List.of(useMb, smartParsing, notifyOnScrobble, enabled, podcastFilter)) {
            cb.setMinWidth(Region.USE_PREF_SIZE);
        }
        g.add(useMb, 0, row++, 3, 1);
        g.add(smartParsing, 0, row++, 3, 1);
        g.add(notifyOnScrobble, 0, row++, 3, 1);
        g.add(enabled, 0, row++, 3, 1);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(new Label("Webhook-Port (0 = aus)"), 0, row);
        webhookPort.setEditable(true);
        Label webhookHint = new Label("Aktiviert einen lokalen HTTP-Endpunkt: POST http://localhost:{port}/now-playing "
            + "mit JSON {artist,title,album,duration_ms,state}. "
            + "Damit kannst du Metadaten aus Quellen pushen, die SMTC/MPRIS nicht befüllen (z.B. NAS-Web-App). "
            + "Neustart nach Änderung erforderlich.");
        webhookHint.setWrapText(true);
        webhookHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        g.add(webhookPort, 1, row);
        g.add(webhookHint, 2, row++);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(podcastFilter, 0, row++, 3, 1);

        g.add(new Label("Podcast-Längen-Cutoff (s)"), 0, row);
        podcastCutoff.setEditable(true);
        Label cutHint = new Label("Tracks länger als dies werden als Podcast/Video behandelt. 0 = aus.");
        cutHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        g.add(podcastCutoff, 1, row);
        g.add(cutHint, 2, row++);

        g.add(new Label("Podcast-Schlüsselwörter (eines pro Zeile)"), 0, row);
        podcastKeywords.setPrefRowCount(3);
        g.add(podcastKeywords, 1, row++, 2, 1);

        g.add(new Label("Podcast-Quellen-Marker"), 0, row);
        podcastSourceMarkers.setPrefRowCount(2);
        g.add(podcastSourceMarkers, 1, row++, 2, 1);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(new Label("Bevorzugter Player"), 0, row);
        Button refreshPlayers = new Button("Aktualisieren");
        refreshPlayers.setOnAction(e -> reloadPlayerList());
        HBox pickerRow = new HBox(8, playerPicker, refreshPlayers);
        playerPicker.setPrefWidth(220);
        g.add(pickerRow, 1, row++, 2, 1);

        g.add(new Label("Polling-Intervall (ms)"), 0, row);
        pollInterval.setEditable(true);
        g.add(pollInterval, 1, row++);

        g.add(new Label("Minimale Tracklänge (s)"), 0, row);
        minLength.setEditable(true);
        g.add(minLength, 1, row++);

        g.add(new Label("Scrobbeln nach (s)"), 0, row);
        absoluteSec.setEditable(true);
        Label absHint = new Label("max. Wartezeit; Last.fm-Standard: 240. Niedriger = schneller scrobbeln.");
        absHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        g.add(absoluteSec, 1, row);
        g.add(absHint, 2, row++);

        g.add(new Label("Oder nach Anteil"), 0, row);
        fractionSlider.setShowTickLabels(true);
        fractionSlider.setShowTickMarks(true);
        fractionSlider.setMajorTickUnit(0.25);
        Label fracHint = new Label("Es gilt jeweils der niedrigere von beiden Werten.");
        fracHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        g.add(fractionSlider, 1, row);
        g.add(fracHint, 2, row++);

        g.add(new Label("Skip-Schwelle (s)"), 0, row);
        skipThreshold.setEditable(true);
        g.add(skipThreshold, 1, row++);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(new Label("Ignorierte Quellen (eine pro Zeile)"), 0, row);
        ignored.setPrefRowCount(3);
        g.add(ignored, 1, row++, 2, 1);

        g.add(new Label("Uploader-Blacklist"), 0, row);
        uploaderBlacklist.setPrefRowCount(3);
        g.add(uploaderBlacklist, 1, row++, 2, 1);

        g.add(new Label("Künstler:in-Regex-Muster"), 0, row);
        artistPatterns.setPrefRowCount(4);
        g.add(artistPatterns, 1, row++, 2, 1);

        g.add(new Separator(), 0, row++, 3, 1);

        g.add(new Label("Titel-Bereinigungs-Regex"), 0, row);
        titleCleanupPatterns.setPrefRowCount(3);
        Label cleanupHint = new Label("Diese Regex-Muster werden aus Titeln entfernt, "
            + "bevor sie zu Maloja gehen. Standard: \"(prod. by …)\" / \"[prod. von …]\".");
        cleanupHint.setWrapText(true);
        cleanupHint.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");
        g.add(titleCleanupPatterns, 1, row, 1, 1);
        g.add(cleanupHint, 2, row++);

        Button save = new Button("Einstellungen speichern");
        save.setOnAction(e -> persistFromUi());
        g.add(save, 1, row++);

        Label versionLabel = new Label("Scrobbler " + de.janniv.lifeos.scrobbler.ScrobblerModule.VERSION);
        versionLabel.setStyle("-fx-opacity: 0.4; -fx-font-size: 10px;");
        g.add(versionLabel, 1, row++);

        ColumnConstraints c0 = new ColumnConstraints(); c0.setMinWidth(180);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setHgrow(Priority.SOMETIMES);
        g.getColumnConstraints().addAll(c0, c1, c2);

        ScrollPane scroll = new ScrollPane(g);
        scroll.setFitToWidth(true);
        return new Tab("Einstellungen", scroll);
    }

    private void reloadPlayerList() {
        new Thread(() -> {
            List<String> players = new ArrayList<>();
            players.add("(automatisch)");
            try { players.addAll(MediaSourceFactory.listAvailablePlayers()); }
            catch (Exception ignored) {}
            Platform.runLater(() -> {
                playerPicker.getItems().setAll(players);
                String pref = settings.preferredSource;
                playerPicker.getSelectionModel().select(pref == null || pref.isBlank() ? 0
                    : Math.max(0, players.indexOf(pref)));
            });
        }, "scrobbler-player-refresh").start();
    }

    private void loadSettingsIntoUi() {
        malojaUrl.setText(settings.malojaUrl);
        malojaKey.setText(settings.malojaApiKey);
        malojaAdminPassword.setText(settings.malojaAdminPassword);
        proxyMode.getSelectionModel().select(settings.proxyMode == null ? "none" : settings.proxyMode);
        proxyHost.setText(settings.socksProxyHost);
        proxyPort.setText(settings.socksProxyPort > 0 ? String.valueOf(settings.socksProxyPort) : "");
        useMb.setSelected(settings.useMusicBrainz);
        smartParsing.setSelected(settings.smartArtistParsing);
        enabled.setSelected(settings.enabled);
        notifyOnScrobble.setSelected(settings.notifyOnScrobble);
        webhookPort.getValueFactory().setValue(settings.webhookPort);
        podcastFilter.setSelected(settings.podcastFilteringEnabled);
        podcastCutoff.getValueFactory().setValue(settings.podcastLengthCutoffSeconds);
        podcastKeywords.setText(String.join("\n", settings.podcastTitleKeywords));
        podcastSourceMarkers.setText(String.join("\n", settings.podcastSourceMarkers));
        pollInterval.getValueFactory().setValue(settings.pollIntervalMs);
        minLength.getValueFactory().setValue(settings.minTrackLengthSeconds);
        absoluteSec.getValueFactory().setValue(settings.scrobbleAfterSeconds);
        skipThreshold.getValueFactory().setValue(settings.skipThresholdSeconds);
        fractionSlider.setValue(settings.scrobbleAtFraction);
        ignored.setText(String.join("\n", settings.ignoredSources));
        uploaderBlacklist.setText(String.join("\n", settings.uploaderBlacklist));
        artistPatterns.setText(String.join("\n", settings.artistPatterns));
        titleCleanupPatterns.setText(settings.titleCleanupPatterns == null
            ? "" : String.join("\n", settings.titleCleanupPatterns));
        reloadPlayerList();
    }

    /**
     * Adds whatever source-app is currently observed to the ignore list and
     * persists it. The match in the source filter is substring-based (lowercased),
     * so adding "chrome" stops every Chrome session at once.
     */
    private void ignoreCurrentSource() {
        String src = currentSourceApp;
        if (src == null || src.isBlank()) return;
        settings.ignoredSources.add(src);
        ignored.setText(String.join("\n", settings.ignoredSources));
        persistFromUi();
        statusLabel.setText("Quelle ignoriert: " + src
            + " — wird ab sofort nicht mehr gescrobbelt.");
        ignoreSourceBtn.setDisable(true);
    }

    private void persistFromUi() {
        settings.malojaUrl = malojaUrl.getText().trim();
        settings.malojaApiKey = malojaKey.getText().trim();
        settings.malojaAdminPassword = malojaAdminPassword.getText();
        settings.proxyMode = proxyMode.getValue() == null ? "none" : proxyMode.getValue();
        settings.socksProxyHost = proxyHost.getText().trim();
        try { settings.socksProxyPort = Integer.parseInt(proxyPort.getText().trim()); }
        catch (Exception e) { settings.socksProxyPort = 0; }
        settings.useMusicBrainz = useMb.isSelected();
        settings.smartArtistParsing = smartParsing.isSelected();
        settings.enabled = enabled.isSelected();
        settings.notifyOnScrobble = notifyOnScrobble.isSelected();
        settings.webhookPort = webhookPort.getValue();
        settings.podcastFilteringEnabled = podcastFilter.isSelected();
        settings.podcastLengthCutoffSeconds = podcastCutoff.getValue();
        settings.podcastTitleKeywords = new ArrayList<>(parseLines(podcastKeywords.getText()));
        settings.podcastSourceMarkers = new LinkedHashSet<>(parseLines(podcastSourceMarkers.getText()));
        settings.pollIntervalMs = pollInterval.getValue();
        settings.minTrackLengthSeconds = minLength.getValue();
        settings.scrobbleAfterSeconds = absoluteSec.getValue();
        settings.skipThresholdSeconds = skipThreshold.getValue();
        settings.scrobbleAtFraction = fractionSlider.getValue();
        settings.ignoredSources = new LinkedHashSet<>(parseLines(ignored.getText()));
        settings.uploaderBlacklist = new LinkedHashSet<>(parseLines(uploaderBlacklist.getText()));
        settings.artistPatterns = new ArrayList<>(parseLines(artistPatterns.getText()));
        settings.titleCleanupPatterns = new ArrayList<>(parseLines(titleCleanupPatterns.getText()));
        String picked = playerPicker.getValue();
        settings.preferredSource = (picked == null || picked.startsWith("(")) ? "" : picked;
        controller.onSettingsChanged(settings);
    }

    private static LinkedHashSet<String> parseLines(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @Override
    public void onNowPlaying(NowPlaying current, NowPlaying enhanced) {
        Platform.runLater(() -> {
            if (current == null || current.isEmpty()) {
                nowTitle.setText("—");
                nowArtist.setText("Nichts läuft");
                nowAlbum.setText("");
                nowSource.setText("");
                enhancedHint.setText("");
                progress.setProgress(0);
                cover.setImage(null);
                currentSourceApp = "";
                ignoreSourceBtn.setDisable(true);
                return;
            }
            NowPlaying display = enhanced != null ? enhanced : current;
            nowTitle.setText(display.title().isEmpty() ? "—" : display.title());
            nowArtist.setText(display.artist().isEmpty() ? "Unbekannte:r Künstler:in" : display.artist());
            nowAlbum.setText(display.album());
            nowSource.setText("Quelle: " + (current.sourceApp().isEmpty() ? "—" : current.sourceApp())
                + "   Status: " + current.state());
            currentSourceApp = current.sourceApp() == null ? "" : current.sourceApp();
            ignoreSourceBtn.setDisable(currentSourceApp.isBlank());

            if (enhanced != null && !enhanced.equals(current)
                && (!enhanced.artist().equalsIgnoreCase(current.artist())
                || !enhanced.title().equalsIgnoreCase(current.title()))) {
                enhancedHint.setText("Erkannt als: " + enhanced.artist() + " — " + enhanced.title()
                    + " (war: " + current.artist() + " — " + current.title() + ")");
            } else {
                enhancedHint.setText("");
            }

            String url = display.coverUrl();
            if (url != null && !url.isBlank()) {
                // Route through CoverCache so HTTP loads (Maloja) and weird image
                // formats get the same error handling as the table cells. Local
                // file:// URLs are decoded synchronously inside the cache too.
                coverCache.load(url, cover::setImage);
            } else {
                cover.setImage(null);
            }

            if (current.durationMs() > 0) {
                progress.setProgress(Math.min(1.0, current.positionMs() / (double) current.durationMs()));
            } else {
                progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            }
        });
    }

    @Override
    public void onScrobble(ScrobbleEvent event) {
        Platform.runLater(() -> {
            // Dedupliziert anhand des Played-At-Zeitstempels — nach einem
            // Refresh aus Maloja kann derselbe Scrobble sonst doppelt landen.
            long ts = event.timestamp == null ? 0 : event.timestamp.getEpochSecond();
            history.removeIf(e -> e.timestamp != null && e.timestamp.getEpochSecond() == ts
                && e.artist.equals(event.artist) && e.title.equals(event.title));
            history.add(0, event);
            while (history.size() > Math.max(50, settings.historyLimit)) history.remove(history.size() - 1);
        });
    }

    /**
     * Replaces the in-memory history with whatever Maloja currently knows
     * about, sorted newest-first. Live scrobbles that happened during the
     * fetch are merged via {@link #onScrobble(ScrobbleEvent)} and the
     * deduplication there.
     */
    private void seedHistoryFromServer(java.util.List<ScrobbleEvent> events) {
        if (events == null) return;
        Platform.runLater(() -> {
            java.util.LinkedHashMap<Long, ScrobbleEvent> byTs = new java.util.LinkedHashMap<>();
            for (ScrobbleEvent ev : history) {
                if (ev.timestamp != null) byTs.putIfAbsent(ev.timestamp.getEpochSecond(), ev);
            }
            for (ScrobbleEvent ev : events) {
                if (ev.timestamp != null) byTs.putIfAbsent(ev.timestamp.getEpochSecond(), ev);
            }
            java.util.List<ScrobbleEvent> merged = new java.util.ArrayList<>(byTs.values());
            merged.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            int limit = Math.max(50, settings.historyLimit);
            if (merged.size() > limit) merged = merged.subList(0, limit);
            history.setAll(merged);
        });
    }

    @Override
    public void onStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }
}
