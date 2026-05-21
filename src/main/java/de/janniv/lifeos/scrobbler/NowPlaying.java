package de.janniv.lifeos.scrobbler;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of what is currently playing in a system media session.
 * The fields are intentionally permissive — many sources only fill a subset.
 *
 * <p>{@link #coverUrl} is a hint for the UI: it can be a {@code file://} URL
 * (typical for MPRIS), an absolute path written by the SMTC bridge, an
 * {@code https://} CDN reference, or empty.
 */
public final class NowPlaying {

    public enum State { PLAYING, PAUSED, STOPPED, UNKNOWN }

    private final String artist;
    private final String title;
    private final String album;
    private final String sourceApp;
    private final long durationMs;
    private final long positionMs;
    private final State state;
    private final Instant observedAt;
    private final String coverUrl;

    public NowPlaying(String artist, String title, String album, String sourceApp,
                      long durationMs, long positionMs, State state, Instant observedAt) {
        this(artist, title, album, sourceApp, durationMs, positionMs, state, observedAt, "");
    }

    public NowPlaying(String artist, String title, String album, String sourceApp,
                      long durationMs, long positionMs, State state, Instant observedAt,
                      String coverUrl) {
        // NFC normalization makes identical-looking characters with different
        // Unicode representations (e.g. ä as U+00E4 vs a + combining diaeresis
        // U+0061 U+0308) compare equal, preventing phantom track-changes.
        this.artist = nfc(nullToEmpty(artist).trim());
        this.title = nfc(nullToEmpty(title).trim());
        this.album = nfc(nullToEmpty(album).trim());
        this.sourceApp = nullToEmpty(sourceApp).trim();
        this.durationMs = Math.max(0, durationMs);
        this.positionMs = Math.max(0, positionMs);
        this.state = state == null ? State.UNKNOWN : state;
        this.observedAt = observedAt == null ? Instant.now() : observedAt;
        this.coverUrl = nullToEmpty(coverUrl).trim();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String nfc(String s) { return s.isEmpty() ? s : Normalizer.normalize(s, Normalizer.Form.NFC); }

    public String artist() { return artist; }
    public String title() { return title; }
    public String album() { return album; }
    public String sourceApp() { return sourceApp; }
    public long durationMs() { return durationMs; }
    public long positionMs() { return positionMs; }
    public State state() { return state; }
    public Instant observedAt() { return observedAt; }
    public String coverUrl() { return coverUrl; }

    public boolean isEmpty() {
        return title.isEmpty() && artist.isEmpty();
    }

    /** Identity used to detect track changes — independent of playback position. */
    public String trackKey() {
        return (artist + "" + title + "" + album).toLowerCase();
    }

    public NowPlaying withEnhanced(String newArtist, String newTitle, String newAlbum) {
        return new NowPlaying(
            newArtist != null ? newArtist : artist,
            newTitle != null ? newTitle : title,
            newAlbum != null ? newAlbum : album,
            sourceApp, durationMs, positionMs, state, observedAt, coverUrl
        );
    }

    public NowPlaying withCover(String newCover) {
        if (newCover == null || newCover.isBlank()) return this;
        return new NowPlaying(artist, title, album, sourceApp, durationMs, positionMs, state, observedAt, newCover);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NowPlaying n)) return false;
        return durationMs == n.durationMs
            && positionMs == n.positionMs
            && state == n.state
            && Objects.equals(artist, n.artist)
            && Objects.equals(title, n.title)
            && Objects.equals(album, n.album)
            && Objects.equals(sourceApp, n.sourceApp)
            && Objects.equals(coverUrl, n.coverUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artist, title, album, sourceApp, durationMs, positionMs, state, coverUrl);
    }

    @Override
    public String toString() {
        return "NowPlaying[" + state + " " + artist + " - " + title
            + " (" + album + ") " + positionMs + "/" + durationMs + "ms via " + sourceApp + "]";
    }
}
