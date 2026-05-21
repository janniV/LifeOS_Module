package de.janniv.lifeos.scrobbler;

import core.AITool;
import core.CoreServices;
import core.LifeModule;
import core.ModuleAIContext;
import de.janniv.lifeos.scrobbler.source.MediaSource;
import de.janniv.lifeos.scrobbler.source.MediaSourceFactory;
import de.janniv.lifeos.scrobbler.target.MalojaClient;
import de.janniv.lifeos.scrobbler.ui.DashboardWidget;
import de.janniv.lifeos.scrobbler.ui.ScrobbleToast;
import de.janniv.lifeos.scrobbler.ui.ScrobblerView;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import javafx.scene.Node;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point for the LifeOSnext scrobbler module. Owns the engine, the
 * settings store, and the UI views, and acts as the bridge between the
 * engine's listeners and {@link CoreServices}.
 */
public final class ScrobblerModule implements LifeModule {

    public static final String MODULE_ID = "de.janniv.lifeos.scrobbler";
    public static final String VERSION = "1.8.3";
    private static final int RECENT_HISTORY = 50;

    private CoreServices core;
    private ScrobblerSettings settings;
    private Path settingsFile;
    private Path storage;
    private ScrobbleEngine engine;
    private MediaSource source;
    private MalojaClient maloja;
    private ScrobblerView view;
    private DashboardWidget dashboard;
    private final List<ScrobbleEvent> recent = new CopyOnWriteArrayList<>();

    @Override public String getModuleId() { return MODULE_ID; }
    @Override public String getModuleName() { return "Scrobbler"; }
    @Override public String getModuleIconPath() { return "/Icons/scrobbler_icon.png"; }
    @Override public String getFontIconName() { return "fas-headphones"; }

    @Override
    public void onInitialize(CoreServices core) {
        this.core = core;
        this.storage = resolveStorage(core);
        ScrobblerLog.initialize(storage);
        ScrobblerLog.info("Module initialising at " + storage);

        this.settingsFile = storage.resolve("settings.json");
        this.settings = ScrobblerSettings.load(settingsFile);
        this.source = MediaSourceFactory.create(storage, settings);
        this.maloja = new MalojaClient(settings.malojaUrl, settings.malojaApiKey, settings.malojaAdminPassword);
        // Pin podcast persistence to ~/.lifeos/${MODULE_ID}/podcasts.json so the
        // log survives across run contexts (Eclipse vs. packaged app). The
        // module-storage path the host hands us can change between IDE runs and
        // production launches, which used to drop the podcast log on restart.
        Path podcastFile = stablePodcastFile();
        migratePodcastFileIfNeeded(storage.resolve("podcasts.json"), podcastFile);
        ScrobblerLog.info("Podcast log persists at " + podcastFile);
        this.engine = new ScrobbleEngine(settings, source, maloja, storage,
            podcastFile, this::sendNotification);
        engine.addListener(historyTap());
        engine.start();
    }

    /** Returns the stable path used to persist the podcast log, regardless of
     *  the host-provided module storage path. */
    static Path stablePodcastFile() {
        return Paths.get(System.getProperty("user.home", "."), ".lifeos", MODULE_ID, "podcasts.json");
    }

    /** Moves an existing podcasts.json from the host's module-storage dir to
     *  the stable user-home location, so users who upgraded from 1.8.2 don't
     *  lose their podcast history on first launch. */
    private static void migratePodcastFileIfNeeded(Path legacy, Path target) {
        try {
            if (legacy == null || target == null) return;
            if (java.nio.file.Files.exists(target)) return;          // already migrated
            if (!java.nio.file.Files.exists(legacy)) return;         // nothing to migrate
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(legacy, target);
            ScrobblerLog.info("Migrated podcast log from " + legacy + " → " + target);
        } catch (Exception e) {
            ScrobblerLog.warn("Podcast log migration failed: " + e.getMessage(), null);
        }
    }

    @Override
    public Node getMainView() {
        if (view == null) {
            view = new ScrobblerView(settings, new ScrobblerView.Controller() {
                @Override public void onSettingsChanged(ScrobblerSettings updated) { applyAndPersistSettings(updated); }
                @Override public void onManualScrobble(NowPlaying track) { manualScrobble(track); }
                @Override public void onDeleteScrobble(ScrobbleEvent event) {
                    engine.deleteOnMaloja(event.timestamp, msg -> ScrobblerLog.info("Delete: " + msg));
                }
                @Override public void onEditScrobble(ScrobbleEvent original, NowPlaying corrected) {
                    engine.editOnMaloja(original.timestamp, corrected, msg -> ScrobblerLog.info("Edit: " + msg));
                }
                @Override public void onEditScrobbleAndRemember(ScrobbleEvent original, NowPlaying corrected,
                                                                 boolean alsoRewritePast) {
                    // 1. Update or insert the persistent rule keyed by (matchArtist, matchTitle).
                    persistRewriteRule(original, corrected);
                    // 2. Edit the single scrobble the user clicked on, as before.
                    engine.editOnMaloja(original.timestamp, corrected, msg -> ScrobblerLog.info("Edit: " + msg));
                    // 3. Optionally walk recent Maloja history and edit every other
                    //    scrobble that matches the (original artist, original title).
                    if (alsoRewritePast) backfillExistingScrobbles(original, corrected);
                }
                @Override public java.util.List<PodcastLog.Entry> podcastSnapshot() {
                    java.util.List<PodcastLog.Entry> list = engine.podcastLog().snapshot();
                    // Normalise legacy entries for display: shorten the raw Windows
                    // package id to a readable name, and try to lift an episode
                    // number out of a title prefix ("651: …" → "Episode 651 — …").
                    for (PodcastLog.Entry e : list) {
                        e.source = de.janniv.lifeos.scrobbler.util.SourceAppName.simplify(e.source);
                        e.episode = PodcastLog.reformatEpisodeTitle(e.episode);
                        if (e.coverUrl == null || e.coverUrl.isBlank()) {
                            e.coverUrl = maloja.buildCoverUrl(e.show, "");
                        }
                    }
                    return list;
                }
                @Override public void onDeletePodcast(int index) {
                    engine.podcastLog().removeAt(index);
                }
                @Override public void onEditPodcast(int index, PodcastLog.Entry updated) {
                    engine.podcastLog().update(index, updated);
                }
                @Override public void loadHistoryFromServer(java.util.function.Consumer<java.util.List<ScrobbleEvent>> reply) {
                    fetchHistoryAsync(reply);
                }
            });
            engine.addListener(view);
        }
        return view;
    }

    @Override
    public Node getDashboardWidget() {
        if (dashboard == null) {
            dashboard = new DashboardWidget();
            engine.addListener(dashboard);
        }
        return dashboard;
    }

    @Override
    public void onShutdown() {
        if (settings != null && settingsFile != null) settings.save(settingsFile);
        if (engine != null) engine.shutdown();
        ScrobblerLog.info("Module shut down");
    }

    @Override
    public ModuleAIContext getAIContext() {
        return new ModuleAIContext(
            "Du bist ein Assistent für das LifeOS-Scrobbler-Modul. Du kannst die laufende Wiedergabe"
            + " beschreiben, kürzliche Scrobbles auflisten, das Scrobbeln ein- und ausschalten und"
            + " die Top-Künstler:innen der laufenden Sitzung melden.",
            null,
            List.of(
                new AITool("scrobbler_status",
                    "Liefert eine kurze Statusbeschreibung des Scrobblers.",
                    input -> describeStatus()),
                new AITool("scrobbler_toggle",
                    "Schaltet das Scrobbeln ein oder aus. Eingabe: 'on' oder 'off'.",
                    input -> {
                        boolean turnOn = input != null && input.toLowerCase().contains("on");
                        settings.enabled = turnOn;
                        applyAndPersistSettings(settings);
                        return "Scrobbeln " + (turnOn ? "aktiviert" : "deaktiviert");
                    }),
                new AITool("scrobbler_history",
                    "Listet die letzten N Scrobbles. Eingabe: optional eine Zahl (Standard 10).",
                    input -> formatHistory(parseLimit(input, 10))),
                new AITool("scrobbler_top_artists",
                    "Top-Künstler:innen der laufenden Sitzung. Eingabe: optional eine Zahl (Standard 5).",
                    input -> formatTopArtists(parseLimit(input, 5))),
                new AITool("scrobbler_retry_queue",
                    "Anzahl der noch nicht zugestellten Scrobbles in der Retry-Warteschlange.",
                    input -> "Retry-Queue: " + engine.retryQueueSize() + " Eintrag(e)")
            )
        );
    }

    /** Inserts (or updates) a {@link TrackRewriteRule} for the (original artist,
     *  original title) pair so future scrobbles of the same song get the
     *  corrected metadata automatically. Only fields the user actually changed
     *  become rule overrides — untouched values are left blank so the source
     *  metadata still drives them on future plays. */
    private void persistRewriteRule(ScrobbleEvent original, NowPlaying corrected) {
        if (settings.trackRewrites == null) settings.trackRewrites = new ArrayList<>();
        String overrideArtist = corrected.artist().equalsIgnoreCase(original.artist) ? "" : corrected.artist();
        String overrideTitle  = corrected.title().equalsIgnoreCase(original.title)   ? "" : corrected.title();
        String overrideAlbum  = corrected.album().equalsIgnoreCase(original.album)   ? "" : corrected.album();
        TrackRewriteRule rule = new TrackRewriteRule(
            original.artist, original.title,
            overrideArtist, overrideTitle, overrideAlbum);

        // De-dupe: replace any existing rule keyed by the same (artist, title).
        String key = TrackRewriteRule.matchKey(original.artist, original.title);
        settings.trackRewrites.removeIf(r -> r != null
            && TrackRewriteRule.matchKey(r.matchArtist, r.matchTitle).equals(key));
        settings.trackRewrites.add(rule);
        settings.save(settingsFile);
        ScrobblerLog.info("Track-rewrite-Regel gespeichert: " + original.artist + " — "
            + original.title + " → " + corrected.artist() + " — " + corrected.title());
    }

    private static final java.util.concurrent.ExecutorService BACKFILL_POOL =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scrobbler-backfill");
            t.setDaemon(true);
            return t;
        });

    /** Walks recent Maloja history and re-submits every scrobble whose
     *  (artist, title) matches the original. Runs off the FX thread; errors
     *  are logged but never block the user. */
    private void backfillExistingScrobbles(ScrobbleEvent original, NowPlaying corrected) {
        BACKFILL_POOL.execute(() -> {
            try {
                java.util.List<ScrobbleEvent> hist = maloja.fetchRecent(settings.historyLimit);
                int hits = 0;
                for (ScrobbleEvent ev : hist) {
                    if (ev == null || ev.timestamp == null) continue;
                    if (ev.timestamp.equals(original.timestamp)) continue;     // already edited above
                    if (ev.status == ScrobbleEvent.Status.LOCAL) continue;     // not on Maloja
                    if (!equalsIgnoreCase(ev.artist, original.artist)) continue;
                    if (!equalsIgnoreCase(ev.title, original.title)) continue;
                    NowPlaying replacement = new NowPlaying(
                        corrected.artist(), corrected.title(), corrected.album(),
                        "edit-backfill", corrected.durationMs(), 0,
                        NowPlaying.State.PLAYING, ev.timestamp);
                    engine.editOnMaloja(ev.timestamp, replacement, m -> {});
                    hits++;
                }
                ScrobblerLog.info("Backfill für " + original.artist + " — " + original.title
                    + ": " + hits + " bestehende Scrobbles korrigiert");
            } catch (Exception e) {
                ScrobblerLog.warn("Backfill fehlgeschlagen: " + e.getMessage(), null);
            }
        });
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        return a.equalsIgnoreCase(b == null ? "" : b);
    }

    private void applyAndPersistSettings(ScrobblerSettings updated) {
        updated.save(settingsFile);
        // Rebuild the per-config clients without losing in-flight state.
        this.maloja = new MalojaClient(updated.malojaUrl, updated.malojaApiKey, updated.malojaAdminPassword);
        this.source = MediaSourceFactory.create(storage, updated);
        engine.reconfigure(updated, source, maloja);
    }

    private void manualScrobble(NowPlaying track) {
        engine.submitManual(track, java.time.Instant.now());
    }

    private static final java.util.concurrent.ExecutorService HISTORY_FETCH =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scrobbler-history-fetch");
            t.setDaemon(true);
            return t;
        });

    /**
     * Pulls recent scrobbles from Maloja off the FX thread. Maloja is the
     * source of truth for history, so we keep nothing locally and just
     * rehydrate the in-memory list whenever the view asks. Falls back to the
     * local in-session buffer on error so the UI is never blank.
     */
    private void fetchHistoryAsync(java.util.function.Consumer<java.util.List<ScrobbleEvent>> reply) {
        if (reply == null) return;
        HISTORY_FETCH.execute(() -> {
            java.util.List<ScrobbleEvent> events;
            try {
                events = new java.util.ArrayList<>(maloja.fetchRecent(settings.historyLimit));
                ScrobblerLog.info("Loaded " + events.size() + " scrobbles from Maloja");
            } catch (Exception e) {
                ScrobblerLog.warn("History fetch from Maloja failed", e);
                events = new java.util.ArrayList<>(recent);
            }
            // Merge locally-logged podcasts (LOCAL status) — Maloja never sees them,
            // so without this step they'd vanish from the Verlauf after a restart.
            for (PodcastLog.Entry pe : engine.podcastLog().snapshot()) {
                String podcastCover = maloja.buildCoverUrl(pe.show, "");
                events.add(new ScrobbleEvent(
                    pe.playedAt == null ? java.time.Instant.now() : pe.playedAt,
                    pe.show, PodcastLog.reformatEpisodeTitle(pe.episode), "", pe.durationMs,
                    ScrobbleEvent.Status.LOCAL, "Lokal gescrobbelt (geht nicht an Maloja)",
                    podcastCover));
            }
            events.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            reply.accept(events);
        });
    }

    private void sendNotification(NowPlaying track) {
        if (track == null) return;
        // Custom JavaFX toast instead of java.awt.TrayIcon — the AWT tray bubble
        // shows up on Windows with the OpenJDK process name and doesn't fit the
        // rest of the LifeOS UI. The toast renders with the track cover and
        // module styling.
        try { ScrobbleToast.show(track); }
        catch (Exception e) { ScrobblerLog.warn("Toast failed", e); }
        // Hand off to the host as well — some LifeOS frontends route this into
        // their own notification centre.
        if (core != null) {
            try { core.sendNotification("Scrobbled: " + track.artist() + " — " + track.title()); }
            catch (Exception e) { ScrobblerLog.warn("Host notification failed", e); }
        }
    }

    private ScrobbleEngine.Listener historyTap() {
        return new ScrobbleEngine.Listener() {
            @Override public void onNowPlaying(NowPlaying current, NowPlaying enhanced) {}
            @Override public void onScrobble(ScrobbleEvent event) {
                recent.add(0, event);
                while (recent.size() > RECENT_HISTORY) recent.remove(recent.size() - 1);
            }
            @Override public void onStatus(String message) {}
        };
    }

    private Path resolveStorage(CoreServices core) {
        if (core != null) {
            Path p = core.getModuleStoragePath(MODULE_ID);
            if (p != null) return p;
        }
        return Paths.get(System.getProperty("user.home", "."), ".lifeos", MODULE_ID);
    }

    private String describeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Aktiv: ").append(settings.enabled).append('\n');
        sb.append("Maloja: ").append(settings.malojaUrl.isEmpty() ? "(nicht konfiguriert)" : settings.malojaUrl).append('\n');
        sb.append("Quelle: ").append(source != null ? source.describe() : "(keine)").append('\n');
        sb.append("MusicBrainz: ").append(settings.useMusicBrainz).append('\n');
        sb.append("Proxy: ");
        if (settings.proxyMode == null || settings.proxyMode.equalsIgnoreCase("none")
            || settings.socksProxyHost.isBlank() || settings.socksProxyPort <= 0) {
            sb.append("aus");
        } else {
            sb.append(settings.proxyMode).append(" → ")
              .append(settings.socksProxyHost).append(':').append(settings.socksProxyPort);
        }
        sb.append('\n').append("Retry-Queue: ").append(engine.retryQueueSize());
        return sb.toString();
    }

    private String formatHistory(int n) {
        if (recent.isEmpty()) return "Noch keine Scrobbles in dieser Sitzung.";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ScrobbleEvent e : recent) {
            if (count++ >= n) break;
            sb.append(e.timestamp).append(" — ")
              .append(e.artist).append(" — ").append(e.title)
              .append(" [").append(e.status).append("]\n");
        }
        return sb.toString();
    }

    private String formatTopArtists(int n) {
        Map<String, Integer> counts = new HashMap<>();
        for (ScrobbleEvent e : recent) {
            if (e.status != ScrobbleEvent.Status.OK) continue;
            if (e.artist == null || e.artist.isBlank()) continue;
            counts.merge(e.artist, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "Noch keine erfolgreichen Scrobbles in dieser Sitzung.";
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Integer> e : ranked) {
            if (i++ >= n) break;
            sb.append(e.getValue()).append("x ").append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    private static int parseLimit(String input, int fallback) {
        if (input == null) return fallback;
        try {
            String trimmed = input.replaceAll("[^0-9]", "").trim();
            if (trimmed.isEmpty()) return fallback;
            return Math.max(1, Math.min(100, Integer.parseInt(trimmed)));
        } catch (Exception e) { return fallback; }
    }
}
