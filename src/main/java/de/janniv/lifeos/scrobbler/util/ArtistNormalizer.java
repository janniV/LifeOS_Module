package de.janniv.lifeos.scrobbler.util;

import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.ScrobblerSettings;

import java.util.regex.PatternSyntaxException;

/**
 * Last-mile normaliser applied to artist + title before a scrobble is sent.
 *
 * <p>Two universally-applicable steps, in order:
 *
 * <ol>
 *   <li><b>Title-case both fields</b>: every word's first letter is upper-cased,
 *       all other letters lower-cased. This is the strict simple rule the user
 *       asked for: "ABC DEF", "abc def", and "Abc Def" all become "Abc Def" —
 *       no acronym exceptions, no platform-specific quirks.</li>
 *   <li><b>Title cleanup</b>: configurable regex list strips producer credits
 *       ("(PROD. BY YAYA)", "[prod. von honey]") so the same track doesn't end
 *       up on Maloja under several distinct titles.</li>
 * </ol>
 */
public final class ArtistNormalizer {

    private ArtistNormalizer() {}

    public static NowPlaying normalize(NowPlaying in, ScrobblerSettings s) {
        if (in == null) return null;
        String artist = in.artist() == null ? "" : in.artist();
        String title = in.title() == null ? "" : in.title();

        // 1. Title case so "KNEECAP", "kneecap", and "Kneecap" — and the same
        //    variants of song titles — all collapse to a single spelling.
        artist = smartCase(artist);
        title = smartCase(title);

        // 2. Title cleanup (producer credits etc.)
        if (s.titleCleanupPatterns != null) {
            for (String pat : s.titleCleanupPatterns) {
                if (pat == null || pat.isBlank()) continue;
                try {
                    title = title.replaceAll(pat, " ");
                } catch (PatternSyntaxException ignored) {
                    // bad user regex — skip silently
                }
            }
            title = title.replaceAll("\\s{2,}", " ").trim();
        }

        if (artist.equals(in.artist()) && title.equals(in.title())) return in;
        return in.withEnhanced(artist, title, in.album());
    }

    /**
     * Word-by-word title-case normalisation. Every word that contains letters
     * gets its first letter upper-cased and the rest lower-cased. Mixed-case
     * spellings ("DaBaby", "SbF") are explicitly preserved — those are
     * intentional capitalisation by the artist, not a platform inconsistency.
     * Pure digit tokens ("42", "182") and single-letter connectors ("x", "a")
     * are left alone.
     *
     * <p>Public so callers like {@code MalojaClient} can apply it when
     * hydrating historical entries — those come straight from Maloja and may
     * have been stored before this normaliser existed.
     */
    public static String smartCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < s.length() && Character.isLetterOrDigit(s.charAt(i))) i++;
                out.append(normalizeWord(s.substring(start, i)));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String normalizeWord(String w) {
        if (w.isEmpty()) return w;
        long letters = w.chars().filter(Character::isLetter).count();
        if (letters == 0) return w; // pure digit token like "42" or "182"
        if (letters <= 1) return w; // single-letter connector — "x", "a"

        boolean allUpper = w.chars().filter(Character::isLetter).allMatch(c -> Character.isUpperCase((char) c));
        boolean allLower = w.chars().filter(Character::isLetter).allMatch(c -> Character.isLowerCase((char) c));

        if (allUpper || allLower) {
            // Title-case: first char upper, rest lower. Strict rule, no
            // acronym exceptions — same fix the user asked for explicitly.
            return Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase();
        }
        return w; // mixed case → user intent, preserve
    }
}
