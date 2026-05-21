package de.janniv.lifeos.scrobbler.util;

import java.util.List;

/**
 * Cleans up the raw source-app identifier delivered by the operating system
 * media APIs so the UI shows a readable name.
 *
 * <p>Windows SMTC reports the Application User Model ID (AUMID), which is a
 * package family name like {@code "SpotifyAB.SpotifyMusic_zpdnekdrzrea0"}.
 * Linux MPRIS reports the D-Bus name like {@code "org.mpris.MediaPlayer2.spotify"}.
 * Both are noisy and useless for the user.
 */
public final class SourceAppName {

    private SourceAppName() {}

    /**
     * Returns a short, human-readable name extracted from the raw source id.
     * Falls back to the input when no simplification rule applies.
     */
    public static String simplify(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        // Short single-word identifiers (e.g. "Spotify", "Firefox") are kept as is.
        if (!raw.contains(".") && !raw.contains("_")) return raw;

        String s = raw;
        // 1. Strip Windows package hash suffix: "Foo.Bar_abc123" → "Foo.Bar"
        int underscore = s.lastIndexOf('_');
        if (underscore > 0 && underscore < s.length() - 1) s = s.substring(0, underscore);
        // 2. Take last dot-segment: "Foo.Bar.Baz" → "Baz"
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        // 3. Strip common noise suffixes that add nothing for the user.
        for (String suffix : List.of("Music", "Player", "App")) {
            if (s.length() > suffix.length() && s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s.isEmpty() ? raw : s;
    }
}
