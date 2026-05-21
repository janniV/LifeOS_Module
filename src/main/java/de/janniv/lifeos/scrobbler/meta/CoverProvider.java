package de.janniv.lifeos.scrobbler.meta;

/**
 * Pluggable cover-art lookup. Implementations query a single source
 * (MusicBrainz, iTunes, Deezer, …) and return raw image bytes plus a
 * stable id we can use for the on-disk cache filename.
 */
public interface CoverProvider {

    /** Human-readable name of this provider, used in cache file names and logs. */
    String name();

    /** Looks up cover art. Returns null when nothing was found. */
    Result lookup(String artist, String title, String album);

    final class Result {
        public final String cacheKey;
        public final byte[] data;
        public Result(String cacheKey, byte[] data) {
            this.cacheKey = cacheKey;
            this.data = data;
        }
    }
}
