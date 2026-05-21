package de.janniv.lifeos.scrobbler.meta;

import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.ScrobblerSettings;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises common "uploader vs. real artist" patterns that browsers and
 * media-keys players surface. The classic pain point is YouTube: SMTC reports
 * the channel name in the artist slot, even when the title obviously encodes
 * the real artist (e.g. {@code "Daft Punk - Around the World"}). We try the
 * configured regex patterns first, then apply some sanity checks.
 */
public final class TitleParser {

    private TitleParser() {}

    public static Optional<NowPlaying> rewrite(NowPlaying current, ScrobblerSettings settings) {
        if (current == null || current.isEmpty()) return Optional.empty();
        if (!settings.smartArtistParsing) return Optional.empty();

        String title = current.title();
        String artist = current.artist();

        // If the existing artist looks like a YouTube channel and the title contains a dash,
        // treat the title as authoritative. The blacklist catches "<artist> - Topic", "VEVO", etc.
        boolean uploaderLooksWrong = looksLikeUploader(artist, settings.uploaderBlacklist);

        for (String pat : settings.artistPatterns) {
            try {
                Pattern p = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(title);
                if (!m.find()) continue;

                String parsedArtist = safeGroup(m, "artist");
                String parsedTitle = safeGroup(m, "title");
                if (parsedArtist == null || parsedTitle == null) continue;

                parsedArtist = stripParenthetical(parsedArtist).trim();
                parsedTitle = stripParenthetical(parsedTitle).trim();
                if (parsedArtist.isEmpty() || parsedTitle.isEmpty()) continue;

                // Only override the artist if either it currently looks wrong, or it disagrees
                // strongly with what the title encodes. We never change the title alone.
                if (artist.isBlank() || uploaderLooksWrong || !artistsAgree(artist, parsedArtist)) {
                    return Optional.of(current.withEnhanced(parsedArtist, parsedTitle, current.album()));
                }
                // Artists agree but title contains the artist prefix — strip it for cleaner logs.
                if (!parsedTitle.equalsIgnoreCase(title)) {
                    return Optional.of(current.withEnhanced(artist, parsedTitle, current.album()));
                }
            } catch (Exception ignored) {
                // A user-supplied pattern can be malformed; we just skip it.
            }
        }

        // No artist pattern matched — but the title may still contain noise like "(Lyrics)",
        // "(Official Music Video)", "(Offizielles Musikvideo)". Strip it independently so we
        // don't scrobble polluted titles even when there's nothing to learn about the artist.
        String cleaned = stripParenthetical(title).trim();
        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(title)) {
            return Optional.of(current.withEnhanced(artist, cleaned, current.album()));
        }
        return Optional.empty();
    }

    private static String safeGroup(Matcher m, String name) {
        try { return m.group(name); } catch (Exception e) { return null; }
    }

    private static boolean looksLikeUploader(String artist, java.util.Set<String> blacklist) {
        if (artist == null || artist.isBlank()) return true;
        String a = artist.toLowerCase();
        for (String b : blacklist) {
            if (b == null) continue;
            String needle = b.trim().toLowerCase();
            if (!needle.isEmpty() && a.contains(needle)) return true;
        }
        return false;
    }

    private static boolean artistsAgree(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /**
     * Removes trailing parenthetical noise that browsers append to titles, e.g.
     * {@code "(Official Video)"} or {@code "[HD]"}. We keep nested feature artists
     * such as {@code "(feat. X)"} because Maloja can resolve them.
     */
    static String stripParenthetical(String s) {
        if (s == null) return "";
        // Token list of noise words we want to strip when they appear inside (...) / [...]
        // or as a trailing " - <word>" / " | <word>" suffix. Covers English + German variants.
        // Deliberately excludes "live", "remix", "original" — those are often legitimate
        // parts of a song title (e.g. "Wonderwall (Live at Wembley)" is its own track).
        String tokens = "(?:official(?:\\s+music)?(?:\\s+video|\\s+audio)?|offiziell(?:es)?(?:\\s+(?:musik|musikvideo|video))?"
            + "|music\\s*video|musikvideo|lyric[s]?(?:\\s*video)?|liedtext|audio(?:\\s*only)?"
            + "|hd|hq|4k|8k|remaster(?:ed)?|visualizer|visualiser|visualisierung"
            + "|extended\\s+(?:mix|version)|radio\\s*edit|with\\s+lyrics|mit\\s+text)";
        List<Pattern> noise = List.of(
            // Parenthetical / bracketed noise — token must be the first word inside.
            Pattern.compile("\\(\\s*" + tokens + "[^)]*\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[\\s*" + tokens + "[^\\]]*\\]", Pattern.CASE_INSENSITIVE),
            // Trailing "- Lyrics", "| Official Video", "– Music Video" etc. (incl. en/em dash)
            Pattern.compile("\\s+[-|\\u2013\\u2014]\\s+" + tokens + "\\s*$", Pattern.CASE_INSENSITIVE)
        );
        String r = s;
        for (Pattern p : noise) r = p.matcher(r).replaceAll("");
        return r.replaceAll("\\s{2,}", " ").trim();
    }
}
