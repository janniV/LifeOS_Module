package de.janniv.lifeos.scrobbler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only log of detected podcast/video plays, kept locally and never
 * forwarded to Maloja. Persists as JSON next to the module's other data so
 * the user can review and later turn it into a separate podcast module.
 *
 * <p>Also stores the last-active podcast session so the engine can detect
 * cross-session resumes (user pauses a podcast, closes the app, reopens it
 * and continues listening — should still count as one listen, not two).
 */
public final class PodcastLog {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        public Instant playedAt;
        public String show;
        public String episode;
        public String source;
        public long durationMs;
        /** Cover URL — not persisted, populated at display time from Maloja. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String coverUrl = "";

        public Entry() {}
        public Entry(Instant playedAt, String show, String episode, String source, long durationMs) {
            this.playedAt = playedAt;
            this.show = show == null ? "" : show;
            this.episode = episode == null ? "" : episode;
            this.source = source == null ? "" : source;
            this.durationMs = durationMs;
        }
    }

    /** Persisted breadcrumb for cross-session podcast resume detection. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SessionState {
        public String lastKey;     // trackKey() of the last logged podcast
        public long lastAt;        // wall-clock ms when it was logged
        public long lastDurationMs;
        public SessionState() {}
        public SessionState(String lastKey, long lastAt, long lastDurationMs) {
            this.lastKey = lastKey;
            this.lastAt = lastAt;
            this.lastDurationMs = lastDurationMs;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LogDoc {
        public List<Entry> entries = new ArrayList<>();
        public SessionState session;
        public LogDoc() {}
    }

    private static final int MAX_ENTRIES = 5000;

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private SessionState session;
    private final Object lock = new Object();

    public PodcastLog(Path file) {
        this.file = file;
        load();
    }

    public void record(NowPlaying np) {
        if (np == null || np.isEmpty()) return;
        // Podcast metadata convention observed in the wild:
        //   album  = podcast name (the show)
        //   title  = episode title (sometimes prefixed "651: …" / "Folge 651 - …")
        //   artist = often episode number ("Episode 651"), sometimes show or host.
        String artist = np.artist() == null ? "" : np.artist();
        String title = np.title() == null ? "" : np.title();
        String album = np.album() == null ? "" : np.album();
        String show = !album.isBlank() ? album : artist;
        String episodeTitle = !title.isBlank() ? title : artist;
        String episode = composeEpisode(artist, episodeTitle, show);
        Entry e = new Entry(Instant.now(), show, episode, np.sourceApp(), np.durationMs());
        synchronized (lock) {
            entries.add(0, e);
            while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
            persist();
        }
    }

    /**
     * Builds the "Episode N — Title" display string from whatever metadata the
     * source provided. Tries three strategies in order:
     * <ol>
     *   <li>Prefer the artist field when it carries the number ("Episode 651")
     *       and is distinct from both the show name and the episode title.</li>
     *   <li>Otherwise, parse the title for a leading number pattern: "651:",
     *       "#651", "Folge 651", "Ep. 12 - …". Strip the prefix from the title
     *       so it shows once at the start.</li>
     *   <li>Otherwise, return the title unchanged.</li>
     * </ol>
     */
    public static String composeEpisode(String artist, String episodeTitle, String show) {
        if (artist != null && !artist.isBlank() && !artist.equals(show) && !artist.equals(episodeTitle)) {
            return artist + " — " + episodeTitle;
        }
        return reformatEpisodeTitle(episodeTitle);
    }

    /** Matches "651: Title", "#651: Title", "Folge 651 - Title", "Nr. 12 — Title" etc. */
    private static final java.util.regex.Pattern EPISODE_PREFIX =
        java.util.regex.Pattern.compile(
            "^\\s*(?:#|(?:episode|episodes?|ep\\.?|folge|nr\\.?|nummer|teil|part|chapter|kapitel)\\s*)?(\\d+)\\s*[:.\\-–—]\\s*(.+)$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Matches "#123 Title" — hash prefix with only a space separator (no colon/dash). */
    private static final java.util.regex.Pattern EPISODE_HASH_SPACE =
        java.util.regex.Pattern.compile("^\\s*#(\\d+)\\s+(.+)$");

    /** Reformats "651: Topic" / "#123 Title" → "Episode N — Topic". Unchanged when no number prefix. */
    public static String reformatEpisodeTitle(String title) {
        if (title == null || title.isBlank()) return title;
        java.util.regex.Matcher m = EPISODE_PREFIX.matcher(title);
        if (m.matches()) return "Episode " + m.group(1) + " — " + m.group(2).trim();
        m = EPISODE_HASH_SPACE.matcher(title);
        if (m.matches()) return "Episode " + m.group(1) + " — " + m.group(2).trim();
        return title;
    }

    /**
     * Extracts the episode number from a formatted episode string.
     * "Episode 651 — Title" → "651", "651 — Title" → "651", "Title" → "".
     */
    public static String extractEpisodeNumber(String episode) {
        if (episode == null || episode.isBlank()) return "";
        int sep = episode.indexOf(" — ");  // " — "
        if (sep > 0) {
            String left = episode.substring(0, sep).trim();
            if (left.matches("Episode \\d+")) return left.substring(8);
            if (left.matches("\\d+")) return left;
        }
        return "";
    }

    /**
     * Extracts the episode title portion from a formatted episode string.
     * "Episode 651 — Title" → "Title", "651 — Title" → "Title", "Title" → "Title".
     */
    public static String extractEpisodeTitleOnly(String episode) {
        if (episode == null || episode.isBlank()) return episode == null ? "" : episode;
        int sep = episode.indexOf(" — ");  // " — "
        if (sep > 0) {
            String left = episode.substring(0, sep).trim();
            if (left.matches("Episode \\d+") || left.matches("\\d+")) {
                return episode.substring(sep + 3).trim();
            }
        }
        return episode;
    }

    /** Replaces the entry at {@code index} with {@code updated}. Used by the
     *  edit dialog in the Podcasts tab. */
    public boolean update(int index, Entry updated) {
        if (updated == null) return false;
        synchronized (lock) {
            if (index < 0 || index >= entries.size()) return false;
            entries.set(index, updated);
            persist();
            return true;
        }
    }

    /** Persists the podcast session breadcrumb (called by the engine after logging a podcast). */
    public void saveSession(String trackKey, long wallClockAt, long durationMs) {
        synchronized (lock) {
            session = new SessionState(trackKey, wallClockAt, durationMs);
            persist();
        }
    }

    /** Returns the last persisted session state, or {@code null} if none exists. */
    public SessionState loadSession() {
        synchronized (lock) { return session; }
    }

    public List<Entry> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    public int size() {
        synchronized (lock) { return entries.size(); }
    }

    public boolean removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= entries.size()) return false;
            entries.remove(index);
            persist();
            return true;
        }
    }

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        // Explicitly register JavaTimeModule so Instant serialisation works even
        // when the module classloader can't discover it via ServiceLoader.
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    private void load() {
        try {
            if (file != null && Files.exists(file)) {
                LogDoc doc = mapper().readValue(file.toFile(), LogDoc.class);
                if (doc.entries != null) entries.addAll(doc.entries);
                session = doc.session;
            }
        } catch (IOException e) {
            ScrobblerLog.warn("Could not read podcast log", e);
        }
    }

    private void persist() {
        try {
            if (file == null) return;
            Files.createDirectories(file.getParent());
            LogDoc doc = new LogDoc();
            doc.entries = new ArrayList<>(entries);
            doc.session = session;
            mapper().writeValue(file.toFile(), doc);
        } catch (IOException e) {
            ScrobblerLog.warn("Could not save podcast log", e);
        }
    }
}
