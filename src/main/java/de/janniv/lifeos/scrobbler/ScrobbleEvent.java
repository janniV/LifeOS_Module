package de.janniv.lifeos.scrobbler;

import java.time.Instant;

/** A single attempted or successful scrobble, kept in the in-memory history. */
public final class ScrobbleEvent {

    public enum Status { OK, RETRY, FAILED, SKIPPED, LOCAL }

    public final Instant timestamp;
    public final String artist;
    public final String title;
    public final String album;
    public final long durationMs;
    public final Status status;
    public final String detail;
    /** Cover image URL — may be a local {@code file://} path, an {@code https://}
     *  CDN link, or a Maloja {@code /image} endpoint URL. Empty when unavailable. */
    public final String coverUrl;

    public ScrobbleEvent(Instant timestamp, String artist, String title, String album,
                         long durationMs, Status status, String detail) {
        this(timestamp, artist, title, album, durationMs, status, detail, "");
    }

    public ScrobbleEvent(Instant timestamp, String artist, String title, String album,
                         long durationMs, Status status, String detail, String coverUrl) {
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.artist = artist == null ? "" : artist;
        this.title = title == null ? "" : title;
        this.album = album == null ? "" : album;
        this.durationMs = durationMs;
        this.status = status == null ? Status.OK : status;
        this.detail = detail == null ? "" : detail;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
    }

    public ScrobbleEvent withCoverUrl(String url) {
        if (url == null || url.isBlank()) return this;
        return new ScrobbleEvent(timestamp, artist, title, album, durationMs, status, detail, url);
    }
}
