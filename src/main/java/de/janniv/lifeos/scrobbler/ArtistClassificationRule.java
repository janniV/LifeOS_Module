package de.janniv.lifeos.scrobbler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A persistent rule that forces a specific artist to always be classified as
 * either a podcast or music, overriding the automatic heuristics.
 *
 * <p>Matching is a case-insensitive substring check against the artist name
 * as reported by the media source, so a rule for "Lage der Nation" matches
 * any variant that contains those words.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ArtistClassificationRule {

    public enum Type { PODCAST, MUSIC }

    public String matchArtist = "";
    public Type type = Type.PODCAST;

    public ArtistClassificationRule() {}

    public ArtistClassificationRule(String matchArtist, Type type) {
        this.matchArtist = matchArtist == null ? "" : matchArtist.trim();
        this.type = type == null ? Type.PODCAST : type;
    }

    public boolean matchesArtist(String artist) {
        if (matchArtist == null || matchArtist.isBlank()) return false;
        if (artist == null) return false;
        return artist.toLowerCase().contains(matchArtist.toLowerCase().trim());
    }
}
