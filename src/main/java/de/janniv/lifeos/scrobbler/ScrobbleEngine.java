package de.janniv.lifeos.scrobbler;

import de.janniv.lifeos.scrobbler.meta.MetadataEnhancer;
import de.janniv.lifeos.scrobbler.source.MediaSource;
import de.janniv.lifeos.scrobbler.target.MalojaClient;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls the active media source on a fixed cadence, decides when a track has
 * been listened to "enough" to count as a scrobble, enriches its metadata,
 * and forwards it to Maloja.
 *
 * <p>Decisions follow Last.fm's classic rule: a track is scrobbleable once at
 * least half its length has elapsed, OR the user has heard {@code N} absolute
 * seconds — whichever comes first. Played time is accumulated on the engine
 * thread, so seeks and pauses can't double-count. Failed deliveries are pushed
 * onto a {@link RetryStore} that survives process restarts.
 */
public final class ScrobbleEngine {

    public interface Listener {
        void onNowPlaying(NowPlaying current, NowPlaying enhanced);
        void onScrobble(ScrobbleEvent event);
        void onStatus(String message);
    }

    private volatile ScrobblerSettings settings;
    private volatile MediaSource source;
    private volatile MalojaClient maloja;
    private volatile MetadataEnhancer enhancer;
    private final RetryStore retry;
    private final PodcastLog podcastLog;
    private final Consumer<NowPlaying> notifier;
    private final ScheduledExecutorService poller =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scrobbler-poll");
            t.setDaemon(true);
            return t;
        });
    private final ScheduledExecutorService dispatcher =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scrobbler-dispatch");
            t.setDaemon(true);
            return t;
        });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Path moduleStorageDir;

    private volatile ScheduledFuture<?> pollHandle;
    private NowPlaying lastObserved;
    private NowPlaying enhancedCurrent;
    private String currentTrackKey;
    private long playedMsAccumulated;
    private long lastTickTs;
    private boolean currentScrobbled;
    private Instant currentStartedAt;
    // Position from the previous tick — used to detect a song restarting on repeat
    // (position jumps from near-end back to near-start while trackKey stays the same).
    private long lastObservedPositionMs;
    // Track key of the most recently successfully scrobbled item, used to suppress
    // false duplicate scrobbles when the same song "restarts" after an interruption
    // during a pause (e.g. an ad briefly appears and resets the engine state).
    private volatile String lastScrobbledKey;
    private volatile long lastScrobbledAt;     // wall-clock ms, updated by dispatcher thread
    private volatile long lastScrobbledDurationMs;
    // Separate resume-detection state for podcasts (podcasts are never sent to Maloja,
    // so lastScrobbledKey is never set for them; we track them independently so that
    // music interludes don't trigger a double-log of the same podcast episode).
    private volatile String lastPodcastKey;
    private volatile long lastPodcastAt;
    private volatile long lastPodcastDurationMs;
    // Set at track-start for long-form content so we can route it to the podcast log
    // as soon as the minimum listen threshold passes — without waiting for 50%/240s.
    private boolean currentTrackIsLongPodcast;

    public ScrobbleEngine(ScrobblerSettings settings, MediaSource source, MalojaClient maloja,
                          Path moduleStorageDir, Consumer<NowPlaying> notifier) {
        this(settings, source, maloja, moduleStorageDir,
             moduleStorageDir.resolve("podcasts.json"), notifier);
    }

    public ScrobbleEngine(ScrobblerSettings settings, MediaSource source, MalojaClient maloja,
                          Path moduleStorageDir, Path podcastFile,
                          Consumer<NowPlaying> notifier) {
        this.settings = settings;
        this.source = source;
        this.maloja = maloja;
        this.moduleStorageDir = moduleStorageDir;
        this.enhancer = new MetadataEnhancer(settings, moduleStorageDir.resolve("covers"));
        this.retry = new RetryStore(moduleStorageDir.resolve("retry-queue.json"));
        this.podcastLog = new PodcastLog(podcastFile);
        this.notifier = notifier == null ? np -> {} : notifier;
        // Restore podcast session state so resume detection survives app restarts.
        PodcastLog.SessionState sess = podcastLog.loadSession();
        if (sess != null && sess.lastKey != null) {
            this.lastPodcastKey = sess.lastKey;
            this.lastPodcastAt = sess.lastAt;
            this.lastPodcastDurationMs = sess.lastDurationMs;
        }
    }

    public PodcastLog podcastLog() { return podcastLog; }

    public void addListener(Listener l) { listeners.add(l); }

    public synchronized void start() {
        if (pollHandle != null) return;
        long interval = Math.max(500, settings.pollIntervalMs);
        pollHandle = poller.scheduleAtFixedRate(this::tickSafely, 0, interval, TimeUnit.MILLISECONDS);
        emitStatus("Engine gestartet — überwacht " + source.describe() + ", Intervall " + interval + " ms");
        ScrobblerLog.info("Engine started, source=" + source.describe() + " interval=" + interval + "ms");
    }

    public synchronized void stop() {
        if (pollHandle != null) {
            pollHandle.cancel(false);
            pollHandle = null;
            emitStatus("Engine angehalten");
            ScrobblerLog.info("Engine stopped");
        }
    }

    public void shutdown() {
        stop();
        poller.shutdownNow();
        dispatcher.shutdownNow();
        try { source.close(); } catch (Exception ignored) {}
    }

    /**
     * Applies a new settings/source/maloja triple without losing the in-flight
     * track state. Used after the user saves the settings panel — much cheaper
     * and less disruptive than a full engine restart.
     */
    public synchronized void reconfigure(ScrobblerSettings newSettings, MediaSource newSource, MalojaClient newMaloja) {
        try { this.source.close(); } catch (Exception ignored) {}
        this.settings = newSettings;
        this.source = newSource;
        this.maloja = newMaloja;
        this.enhancer = new MetadataEnhancer(newSettings, moduleStorageDir.resolve("covers"));
        // Restart only the timer so the new poll interval takes effect.
        if (pollHandle != null) {
            pollHandle.cancel(false);
            pollHandle = null;
            long interval = Math.max(500, newSettings.pollIntervalMs);
            pollHandle = poller.scheduleAtFixedRate(this::tickSafely, 0, interval, TimeUnit.MILLISECONDS);
            emitStatus("Engine neu konfiguriert (Intervall " + interval + " ms)");
        }
        ScrobblerLog.info("Engine reconfigured");
    }

    public RetryStore retryStore() { return retry; }

    private void tickSafely() {
        try { tick(); } catch (Throwable t) {
            emitStatus("Tick fehlgeschlagen: " + t.getMessage());
            ScrobblerLog.warn("Tick failed", t);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long elapsed = lastTickTs == 0 ? 0 : now - lastTickTs;
        lastTickTs = now;

        NowPlaying observed = source.poll();
        if (observed == null || observed.isEmpty()) {
            for (Listener l : listeners) l.onNowPlaying(null, null);
            flushRetries();
            return;
        }

        boolean trackChanged = currentTrackKey == null || !currentTrackKey.equals(observed.trackKey());
        if (trackChanged) {
            // If the previous track changed before scrobbling and was barely played,
            // record it as a skip so the user can see it in the history.
            if (lastObserved != null && !currentScrobbled
                && playedMsAccumulated < settings.skipThresholdSeconds * 1000L
                && playedMsAccumulated > 0) {
                NowPlaying prev = enhancedCurrent != null ? enhancedCurrent : lastObserved;
                recordEvent(new ScrobbleEvent(Instant.now(), prev.artist(), prev.title(), prev.album(),
                    prev.durationMs(), ScrobbleEvent.Status.SKIPPED,
                    "Track gewechselt nach " + (playedMsAccumulated / 1000) + " s"));
            }
            beginNewTrack(observed);
        } else if (currentScrobbled && isSongRepeatStart(observed.positionMs(), lastObservedPositionMs)) {
            // Same track key but position jumped from near the end back to the beginning —
            // the song is playing on repeat. Treat this as a genuinely new listen.
            beginNewTrack(observed);
        } else if (observed.state() == NowPlaying.State.PLAYING) {
            playedMsAccumulated += Math.min(elapsed, settings.pollIntervalMs * 3L);
        }

        lastObservedPositionMs = observed.positionMs();
        lastObserved = observed;
        for (Listener l : listeners) l.onNowPlaying(observed, enhancedCurrent);

        // Early path: if the track was already identified as long-form at start, route it
        // to the podcast log as soon as the minimum listen threshold passes — without
        // waiting for the normal 50%/240s scrobble threshold.
        long earlyPodcastThresholdMs = Math.max((long) settings.minTrackLengthSeconds * 1000L, 10_000L);
        if (!currentScrobbled && currentTrackIsLongPodcast
                && playedMsAccumulated >= earlyPodcastThresholdMs) {
            currentScrobbled = true;
            logPodcast(observed, currentTrackKey);
        } else if (!currentScrobbled && shouldScrobble(observed)) {
            currentScrobbled = true;
            NowPlaying toRecord = enhancedCurrent != null ? enhancedCurrent : observed;
            // Capture the raw source key now (before the dispatcher thread might see
            // a different currentTrackKey) so resume detection stays accurate.
            String capturedRawKey = currentTrackKey;
            if (PodcastDetector.isPodcast(observed, settings)
                || PodcastDetector.isPodcast(toRecord, settings)) {
                logPodcast(observed, capturedRawKey);
            } else {
                scrobble(toRecord, capturedRawKey);
            }
        }

        flushRetries();
    }

    /**
     * Routes a podcast item to the local log. Uses the raw observed NowPlaying
     * (not the enhanced version) so episode numbers and show-specific titles
     * are preserved as the media source reported them.
     */
    private void logPodcast(NowPlaying observed, String capturedRawKey) {
        NowPlaying podcastData = observed;
        podcastLog.record(podcastData);
        lastPodcastKey = capturedRawKey;
        lastPodcastAt = System.currentTimeMillis();
        lastPodcastDurationMs = podcastData.durationMs();
        podcastLog.saveSession(capturedRawKey, lastPodcastAt, lastPodcastDurationMs);
        // Map podcast metadata to scrobble fields, matching the PodcastLog
        // convention so the Verlauf and the Podcasts tab show the same data:
        //   artist column = show (podcast name, from album field)
        //   title column  = episode (artist+title combined if artist holds
        //                   the episode number, else title alone)
        String pArtist = podcastData.artist();
        String pTitle = podcastData.title();
        String pAlbum = podcastData.album();
        String showName = !pAlbum.isBlank() ? pAlbum : pArtist;
        String baseEpisode = !pTitle.isBlank() ? pTitle : pArtist;
        String episode = (!pArtist.isBlank() && !pArtist.equals(showName) && !pArtist.equals(baseEpisode))
            ? pArtist + " — " + baseEpisode
            : baseEpisode;
        String podcastImgUrl = maloja.buildCoverUrl(showName, "");
        recordEvent(new ScrobbleEvent(Instant.now(), showName, episode,
            "", podcastData.durationMs(), ScrobbleEvent.Status.LOCAL,
            "Lokal gescrobbelt (geht nicht an Maloja)", podcastImgUrl));
        emitStatus("Podcast erkannt, lokal gescrobbelt: " + episode);
        ScrobblerLog.info("Podcast logged: " + showName + " — " + episode);
    }

    private void beginNewTrack(NowPlaying observed) {
        currentTrackKey = observed.trackKey();
        playedMsAccumulated = 0;
        currentStartedAt = Instant.now();
        enhancedCurrent = observed;
        currentTrackIsLongPodcast = PodcastDetector.isLongFormPodcast(observed, settings);

        // Suppress re-scrobbling when the same song shows up again after a pause
        // was interrupted by something else (e.g. an ad briefly taking over SMTC/MPRIS).
        // We consider it a "resume" when the track was scrobbled recently AND the
        // player is reporting a mid-song position (not a fresh restart from 0).
        boolean isResume = lastScrobbledKey != null
            && lastScrobbledKey.equals(observed.trackKey())
            && observed.positionMs() > 15_000
            && System.currentTimeMillis() - lastScrobbledAt
               < resumeGraceWindowMs(lastScrobbledDurationMs);
        // Same check for podcasts: they never go to Maloja so lastScrobbledKey is
        // never set for them. If the user listened to part of a podcast, switched to
        // music, and then resumed the podcast — don't log it a second time.
        // positionMs() == 0 means the source doesn't report position; in that case we
        // fall back to the grace-window check alone so we're still protected.
        boolean isPodcastResume = lastPodcastKey != null
            && lastPodcastKey.equals(observed.trackKey())
            && (observed.positionMs() == 0 || observed.positionMs() > 15_000)
            && System.currentTimeMillis() - lastPodcastAt
               < resumeGraceWindowMs(lastPodcastDurationMs);
        currentScrobbled = isResume || isPodcastResume;

        final NowPlaying baseline = observed;
        dispatcher.execute(() -> {
            NowPlaying enriched = enhancer.enhance(baseline);
            if (currentTrackKey != null && currentTrackKey.equals(baseline.trackKey())) {
                enhancedCurrent = enriched;
                for (Listener l : listeners) l.onNowPlaying(baseline, enriched);
            }
        });
    }

    /**
     * True when the song on repeat has just restarted: position was well into
     * the track on the previous tick but is now near the beginning. Both values
     * must be non-zero — a zero position typically means the source doesn't
     * report position, so we can't distinguish repeat from start.
     */
    private static boolean isSongRepeatStart(long newPos, long oldPos) {
        return oldPos > 30_000 && newPos > 0 && newPos < 10_000;
    }

    /**
     * How long after a successful scrobble the same track key is considered
     * "still the same listen" for resume detection. Tracks that re-appear within
     * this window (with a mid-song position) are treated as resumed, not replayed.
     */
    private static long resumeGraceWindowMs(long durationMs) {
        return Math.max(durationMs + 5 * 60_000L, 20 * 60_000L);
    }

    private boolean shouldScrobble(NowPlaying observed) {
        if (!settings.enabled) return false;
        if (looksLikeGarbage(observed)) return false;
        long durationMs = observed.durationMs();
        long minLengthMs = (long) settings.minTrackLengthSeconds * 1000L;
        if (durationMs > 0 && durationMs < minLengthMs) return false;

        long thresholdMs;
        if (durationMs > 0) {
            long byFraction = (long) (durationMs * settings.scrobbleAtFraction);
            long byAbsolute = settings.scrobbleAfterSeconds * 1000L;
            thresholdMs = Math.min(byFraction, byAbsolute);
        } else {
            thresholdMs = settings.scrobbleAfterSeconds * 1000L;
        }
        return playedMsAccumulated >= Math.max(thresholdMs, minLengthMs);
    }

    /**
     * Catches scrobbles where the source filled artist + title with the same
     * generic string (e.g. "UGREEN", "Spotify"). These are signs that the
     * media bridge couldn't read real metadata, and scrobbling them just
     * pollutes Maloja. Belt-and-suspenders alongside the {@code ignoredSources}
     * filter, which catches things by source-app name.
     */
    private static boolean looksLikeGarbage(NowPlaying np) {
        String a = np.artist() == null ? "" : np.artist().trim().toLowerCase();
        String t = np.title() == null ? "" : np.title().trim().toLowerCase();
        if (a.isEmpty() && t.isEmpty()) return true;
        if (!a.isEmpty() && a.equals(t)) return true;
        return false;
    }

    private void scrobble(NowPlaying track) {
        scrobble(track, currentTrackKey);
    }

    private void scrobble(NowPlaying track, String rawKey) {
        if (track == null || track.title().isBlank()) {
            recordEvent(new ScrobbleEvent(Instant.now(), track == null ? "" : track.artist(),
                track == null ? "" : track.title(), "", 0, ScrobbleEvent.Status.SKIPPED, "Leerer Titel"));
            return;
        }
        if (!maloja.isConfigured()) {
            recordEvent(new ScrobbleEvent(Instant.now(), track.artist(), track.title(),
                track.album(), track.durationMs(), ScrobbleEvent.Status.SKIPPED, "Maloja nicht konfiguriert"));
            return;
        }

        Instant playedAt = currentStartedAt != null ? currentStartedAt : Instant.now();
        emitStatus("Sende Scrobble: " + track.artist() + " — " + track.title());
        dispatcher.execute(() -> {
            try {
                String detail = maloja.scrobble(track, playedAt);
                // Remember this scrobble so we can detect resumes vs. genuine replays.
                lastScrobbledKey = rawKey;
                lastScrobbledAt = System.currentTimeMillis();
                lastScrobbledDurationMs = track.durationMs();
                // Prefer the local file:// cover for in-session display — CoverCache
                // handles it reliably. Maloja's /image URL may return an SVG placeholder
                // that passes the HTTP check but fails the magic-bytes guard in CoverCache.
                // Once the cover is uploaded and the history is reloaded from Maloja the
                // server-side URL will be used for cross-device display.
                String eventCoverUrl = !track.coverUrl().isBlank() ? track.coverUrl()
                    : maloja.buildCoverUrl(track.artist(), track.title());
                recordEvent(new ScrobbleEvent(playedAt, track.artist(), track.title(),
                    track.album(), track.durationMs(), ScrobbleEvent.Status.OK, detail, eventCoverUrl));
                emitStatus("Scrobble OK: " + track.artist() + " — " + track.title() + " (" + detail + ")");
                if (settings.notifyOnScrobble) {
                    notifier.accept(track);
                }
                ScrobblerLog.info("Scrobbled: " + track.artist() + " — " + track.title());
                // Best-effort cover upload — sends our locally fetched cover to Maloja so
                // it serves better art than its own metadata sources. Never blocks or retries.
                uploadCoverToMaloja(track);
            } catch (Exception e) {
                retry.enqueue(new RetryStore.PendingScrobble(playedAt, track.artist(), track.title(),
                    track.album(), track.durationMs(), e.getMessage()));
                recordEvent(new ScrobbleEvent(Instant.now(), track.artist(), track.title(),
                    track.album(), track.durationMs(), ScrobbleEvent.Status.RETRY, e.getMessage()));
                emitStatus("Scrobble fehlgeschlagen, in Retry-Queue: " + e.getMessage());
                ScrobblerLog.warn("Scrobble failed (queued for retry): " + e.getMessage());
            }
        });
    }

    private void uploadCoverToMaloja(NowPlaying track) {
        if (track == null || track.coverUrl().isBlank()) return;
        try {
            java.net.URI fileUri = java.net.URI.create(track.coverUrl());
            if (!"file".equals(fileUri.getScheme())) return;
            byte[] coverBytes = Files.readAllBytes(Paths.get(fileUri));
            if (coverBytes.length > 0) {
                maloja.uploadCover(track.artist(), track.title(), coverBytes);
            }
        } catch (Exception e) {
            ScrobblerLog.warn("Cover upload skipped: " + e.getMessage(), null);
        }
    }

    /**
     * Submits one queued retry per call (FIFO). Backoff is owned by
     * {@link RetryStore}, so concurrent failures do not hammer Maloja.
     */
    private void flushRetries() {
        if (retry.isEmpty() || !maloja.isConfigured()) return;
        RetryStore.PendingScrobble due = retry.peekDue();
        if (due == null) return;
        dispatcher.execute(() -> {
            try {
                NowPlaying replay = new NowPlaying(due.artist, due.title, due.album, "",
                    due.durationMs, 0, NowPlaying.State.PLAYING, due.playedAt);
                String detail = maloja.scrobble(replay, due.playedAt);
                retry.onSuccess();
                recordEvent(new ScrobbleEvent(Instant.now(), due.artist, due.title,
                    due.album, due.durationMs, ScrobbleEvent.Status.OK, "Nachgeholt: " + detail));
                ScrobblerLog.info("Replayed queued scrobble: " + due.artist + " — " + due.title);
            } catch (Exception e) {
                retry.onFailure();
                ScrobblerLog.warn("Replay failed (still queued): " + e.getMessage());
            }
        });
    }

    /** Submits a scrobble manually (for the UI's "add" dialog). */
    public void submitManual(NowPlaying track, Instant playedAt) {
        scrobble(track);
    }

    /**
     * Removes a previously-sent scrobble from Maloja by its played-at timestamp.
     * Used by the history-edit context menu. Returns a status string suitable
     * for showing in the UI.
     */
    public void deleteOnMaloja(Instant playedAt, java.util.function.Consumer<String> reply) {
        if (!maloja.isConfigured()) { reply.accept("Maloja nicht konfiguriert"); return; }
        dispatcher.execute(() -> {
            try {
                String r = maloja.deleteScrobble(playedAt.getEpochSecond());
                emitStatus("Scrobble gelöscht: " + r);
                reply.accept(r);
            } catch (Exception e) {
                emitStatus("Löschen fehlgeschlagen: " + e.getMessage());
                reply.accept("Fehler: " + e.getMessage());
            }
        });
    }

    /**
     * Edits a scrobble: deletes the old entry on Maloja and immediately submits
     * a corrected one with the same played-at timestamp.
     */
    public void editOnMaloja(Instant playedAt, NowPlaying corrected, java.util.function.Consumer<String> reply) {
        if (!maloja.isConfigured()) { reply.accept("Maloja nicht konfiguriert"); return; }
        dispatcher.execute(() -> {
            try {
                maloja.deleteScrobble(playedAt.getEpochSecond());
                String detail = maloja.scrobble(corrected, playedAt);
                recordEvent(new ScrobbleEvent(Instant.now(), corrected.artist(), corrected.title(),
                    corrected.album(), corrected.durationMs(), ScrobbleEvent.Status.OK,
                    "Bearbeitet → " + detail));
                emitStatus("Scrobble bearbeitet: " + corrected.artist() + " — " + corrected.title());
                reply.accept("Bearbeitet");
            } catch (Exception e) {
                emitStatus("Bearbeiten fehlgeschlagen: " + e.getMessage());
                reply.accept("Fehler: " + e.getMessage());
            }
        });
    }

    public int retryQueueSize() { return retry.size(); }

    private void recordEvent(ScrobbleEvent event) {
        for (Listener l : listeners) l.onScrobble(event);
    }

    private void emitStatus(String msg) {
        for (Listener l : listeners) l.onStatus(msg);
    }
}
