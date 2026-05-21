package de.janniv.lifeos.scrobbler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent FIFO of pending scrobbles that the engine could not deliver.
 *
 * <p>Every mutation flushes to {@code retry-queue.json} in the module data
 * directory, so a crash or LifeOS restart in the middle of a NAS outage
 * doesn't cost the user any plays. The store also tracks an exponential
 * backoff: after each failure we delay the next retry attempt, capped at
 * {@link #MAX_BACKOFF_MS}.
 */
public final class RetryStore {

    private static final long MIN_BACKOFF_MS = 5_000;
    private static final long MAX_BACKOFF_MS = 5 * 60_000;

    private final Path file;
    private final ObjectMapper mapper;
    private final List<PendingScrobble> queue = new ArrayList<>();
    private long nextAttemptAt = 0L;
    private long currentBackoff = MIN_BACKOFF_MS;

    public RetryStore(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        load();
    }

    public synchronized int size() { return queue.size(); }

    public synchronized boolean isEmpty() { return queue.isEmpty(); }

    public synchronized void enqueue(PendingScrobble p) {
        queue.add(p);
        flush();
    }

    /** Returns the oldest item if we are past the next-attempt window, else null. */
    public synchronized PendingScrobble peekDue() {
        if (queue.isEmpty()) return null;
        if (System.currentTimeMillis() < nextAttemptAt) return null;
        return queue.get(0);
    }

    public synchronized void onSuccess() {
        if (!queue.isEmpty()) queue.remove(0);
        currentBackoff = MIN_BACKOFF_MS;
        nextAttemptAt = 0L;
        flush();
    }

    public synchronized void onFailure() {
        currentBackoff = Math.min(MAX_BACKOFF_MS, Math.max(MIN_BACKOFF_MS, currentBackoff * 2));
        nextAttemptAt = System.currentTimeMillis() + currentBackoff;
        flush();
    }

    public synchronized List<PendingScrobble> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized void clear() {
        queue.clear();
        currentBackoff = MIN_BACKOFF_MS;
        nextAttemptAt = 0L;
        flush();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            QueueDoc doc = mapper.readValue(file.toFile(), QueueDoc.class);
            if (doc != null && doc.queue != null) queue.addAll(doc.queue);
        } catch (IOException e) {
            System.err.println("[Scrobbler] Could not load retry queue: " + e.getMessage());
        }
    }

    private void flush() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            QueueDoc doc = new QueueDoc();
            doc.queue = Collections.unmodifiableList(new ArrayList<>(queue));
            doc.savedAt = Instant.now();
            mapper.writeValue(file.toFile(), doc);
        } catch (IOException e) {
            System.err.println("[Scrobbler] Could not persist retry queue: " + e.getMessage());
        }
    }

    /** Single queued scrobble. Public fields keep Jackson happy without extra annotations. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PendingScrobble {
        public Instant playedAt = Instant.now();
        public String artist = "";
        public String title = "";
        public String album = "";
        public long durationMs = 0;
        public String lastError = "";
        public int attempts = 0;

        public PendingScrobble() {}

        public PendingScrobble(Instant playedAt, String artist, String title, String album,
                               long durationMs, String lastError) {
            this.playedAt = playedAt == null ? Instant.now() : playedAt;
            this.artist = artist == null ? "" : artist;
            this.title = title == null ? "" : title;
            this.album = album == null ? "" : album;
            this.durationMs = durationMs;
            this.lastError = lastError == null ? "" : lastError;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class QueueDoc {
        public Instant savedAt;
        public List<PendingScrobble> queue = new ArrayList<>();
    }
}
