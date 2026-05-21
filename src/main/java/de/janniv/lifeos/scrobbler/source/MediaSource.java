package de.janniv.lifeos.scrobbler.source;

import de.janniv.lifeos.scrobbler.NowPlaying;

/** Abstraction over the operating-system specific media-session bridge. */
public interface MediaSource extends AutoCloseable {

    /** Human-friendly name for diagnostics, e.g. "MPRIS (playerctl)". */
    String describe();

    /**
     * Returns the current track or {@code null} if nothing is playing or the OS bridge
     * is unavailable. Callers must tolerate {@code null}.
     */
    NowPlaying poll();

    /** Releases any pooled resources held by the source. Safe to call repeatedly. */
    @Override
    default void close() {}
}
