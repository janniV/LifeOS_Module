package de.janniv.lifeos.scrobbler.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a single "artist" string into the list of collaborating artists.
 * Maloja stores each contributor separately, so "DAHABFLEX x ERZIN" should
 * become two entries — but only when the separator is a real collab marker,
 * not a slash inside a band name like AC/DC.
 *
 * <p>Also extracts parenthetical feature credits that appear inside the artist
 * field, e.g. {@code "Artist (feat. Guest)"} → {@code ["Artist", "Guest"]}.
 * The same pattern is used by {@link #extractFeatFromTitle} to pull feature
 * credits out of song titles so they can be forwarded as separate artists.
 */
public final class ArtistSplitter {

    private ArtistSplitter() {}

    // Collab separators: " X ", " x ", " & ", ", ", " feat. ", " ft. ", " vs. ", " + ", " with ".
    // Word-boundary anchors make sure "Mxtreme" / "Boyz N Da Hood" don't get split mid-word.
    private static final Pattern SPLIT = Pattern.compile(
        "\\s+(?:[xX]|&|vs\\.?|feat(?:uring)?\\.?|ft\\.?|with|\\+)\\s+|,\\s+",
        Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);

    // Parenthetical feature credits anywhere in the string:
    //   (feat. X)  [feat. X]  (ft X)  (Featuring X)  (with X)
    static final Pattern FEAT_PAREN = Pattern.compile(
        "[\\(\\[]\\s*(?:feat(?:uring)?\\.?|ft\\.?|with)\\s+([^\\)\\]]+)[\\)\\]]",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Inline feature credits without brackets, anchored to a leading space so we
    // don't slice "Surfeat" or "Drift." mid-word. Group 1 captures the artist
    // name(s); group 2 captures any trailing bracketed annotation that should
    // be re-attached after stripping ("(Remix)", "[Edit]").
    // "with" is intentionally NOT included here — too many legitimate titles
    // contain "with" as a normal preposition.
    static final Pattern FEAT_INLINE = Pattern.compile(
        "\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+([^\\(\\[]+?)(\\s*[\\(\\[].*)?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return out;

        // Preserve known unsplittable composites as a deny-list. AC/DC and similar contain
        // a slash; we already don't split on slash, but a name like "Above & Beyond" really
        // *is* a collab pattern even though they're a single act. Keep it whitelisted.
        if (UNSPLITTABLE.contains(trimmed.toLowerCase())) {
            out.add(trimmed);
            return out;
        }

        // --- Step 1: strip (feat. X) / [ft. X] parentheticals, collect inner names -
        // These are not matched by the inline SPLIT pattern because the opening
        // bracket prevents the required leading \s+. Strip them first so the
        // remainder can be processed cleanly; remember the inner artists to append
        // after the primary ones so order stays "main artist first".
        List<String> featArtists = new ArrayList<>();
        Matcher featMatcher = FEAT_PAREN.matcher(trimmed);
        StringBuffer cleaned = new StringBuffer();
        while (featMatcher.find()) {
            String inner = featMatcher.group(1).trim();
            // The inner part can itself contain multiple artists ("feat. A & B").
            featArtists.addAll(split(inner));
            featMatcher.appendReplacement(cleaned, "");
        }
        featMatcher.appendTail(cleaned);
        String remainder = cleaned.toString().trim();

        // --- Step 2: split the remainder by inline collab markers ------------------
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        if (!remainder.isEmpty()) {
            String[] parts = SPLIT.split(remainder);
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) uniq.add(s);
            }
        }

        // Append feat artists after the primary ones so "Artist (feat. Guest)"
        // yields ["Artist", "Guest"], not ["Guest", "Artist"].
        uniq.addAll(featArtists);

        if (uniq.isEmpty()) uniq.add(trimmed);
        out.addAll(uniq);
        return out;
    }

    /**
     * Extracts parenthetical feature credits from a song title and returns the
     * feature artists as a list. Returns an empty list when none are found.
     * The caller is responsible for stripping the matched section from the title.
     *
     * <p>Example: {@code "Song (feat. Guest)"} → {@code ["Guest"]}
     */
    public static List<String> extractFeatFromTitle(String title) {
        if (title == null || title.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        Matcher m = FEAT_PAREN.matcher(title);
        while (m.find()) {
            result.addAll(split(m.group(1).trim()));
        }
        // Strip the bracketed matches first so inline-search doesn't re-grab
        // them. Then look for inline "feat X" / "ft. X" anywhere in the title.
        String inlineSource = FEAT_PAREN.matcher(title).replaceAll("");
        Matcher mi = FEAT_INLINE.matcher(inlineSource);
        if (mi.find()) {
            result.addAll(split(mi.group(1).trim()));
        }
        return result;
    }

    /**
     * Strips both parenthetical and inline feature credits from a title string,
     * leaving the rest clean.
     * <ul>
     *   <li>{@code "Song (feat. Guest) [Remix]"} → {@code "Song [Remix]"}</li>
     *   <li>{@code "Song feat. Guest"} → {@code "Song"}</li>
     *   <li>{@code "Song feat. Guest (Remix)"} → {@code "Song (Remix)"}</li>
     * </ul>
     */
    public static String stripFeatFromTitle(String title) {
        if (title == null) return "";
        String r = FEAT_PAREN.matcher(title).replaceAll("");
        // Inline pattern: capture the bracketed suffix (group 2) separately and
        // re-attach it after the strip so "Song feat. X (Remix)" → "Song (Remix)".
        r = FEAT_INLINE.matcher(r).replaceFirst(matchResult -> {
            String tail = matchResult.group(2);
            return tail == null || tail.isBlank() ? "" : " " + tail.trim();
        });
        return r.replaceAll("\\s{2,}", " ").trim();
    }

    /** Single-act names that contain a collab-marker as part of the band name. */
    private static final java.util.Set<String> UNSPLITTABLE = java.util.Set.of(
        "above & beyond",
        "earth, wind & fire",
        "hall & oates",
        "simon & garfunkel",
        "of monsters and men",
        "florence + the machine",
        "tyler, the creator",
        "crosby, stills & nash",
        "crosby, stills, nash & young",
        "iron & wine",
        "angus & julia stone"
    );
}
