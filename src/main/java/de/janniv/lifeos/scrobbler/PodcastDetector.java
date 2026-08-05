package de.janniv.lifeos.scrobbler;

/**
 * Heuristic classifier deciding whether a {@link NowPlaying} item should be
 * treated as a podcast / long-form video and routed to the local podcast log
 * instead of being scrobbled to Maloja.
 *
 * <p>Signals are evaluated in priority order:
 * <ol>
 *   <li>Artist-level MUSIC override rules — exempt an artist unconditionally.</li>
 *   <li>Artist-level PODCAST rules — always classify this artist as podcast.</li>
 *   <li>The source app is on the configured podcast-source list.</li>
 *   <li>The track's total length exceeds the configured cutoff (default 15 min).
 *       Detection uses the reported total duration, not the amount already heard,
 *       so a long episode is classified from the moment it starts playing.</li>
 *   <li>The title or artist contains a podcast keyword like "Episode", "Folge".</li>
 * </ol>
 *
 * <p>Any single signal (except a MUSIC override) is enough to flag the item.
 */
public final class PodcastDetector {

    private PodcastDetector() {}

    /** True if a MUSIC override rule matches this track's artist. */
    public static boolean isMusicOverride(NowPlaying np, ScrobblerSettings s) {
        if (np == null || s == null || s.artistClassificationRules == null) return false;
        String artist = np.artist() == null ? "" : np.artist();
        for (ArtistClassificationRule rule : s.artistClassificationRules) {
            if (rule != null && rule.type == ArtistClassificationRule.Type.MUSIC
                    && rule.matchesArtist(artist)) {
                return true;
            }
        }
        return false;
    }

    /** True if a PODCAST artist rule forces this track to be classified as podcast. */
    public static boolean isPodcastByArtistRule(NowPlaying np, ScrobblerSettings s) {
        if (np == null || s == null || s.artistClassificationRules == null) return false;
        String artist = np.artist() == null ? "" : np.artist();
        for (ArtistClassificationRule rule : s.artistClassificationRules) {
            if (rule != null && rule.type == ArtistClassificationRule.Type.PODCAST
                    && rule.matchesArtist(artist)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the track's total reported duration meets or exceeds the configured
     * cutoff — evaluated against the full length, not the amount already heard.
     */
    public static boolean isLongFormByDuration(NowPlaying np, ScrobblerSettings s) {
        return s != null
            && s.podcastFilteringEnabled
            && s.podcastLengthCutoffSeconds > 0
            && np != null
            && np.durationMs() > 0
            && np.durationMs() / 1000L >= s.podcastLengthCutoffSeconds;
    }

    /**
     * True when the track should be treated as long-form content (podcast/video)
     * based on total duration or artist rule — ignoring keywords and source markers
     * (which are more ambiguous). Used for early detection at track start.
     */
    public static boolean isLongFormPodcast(NowPlaying np, ScrobblerSettings s) {
        if (np == null || s == null || !s.podcastFilteringEnabled) return false;
        if (isMusicOverride(np, s)) return false;
        return isLongFormByDuration(np, s) || isPodcastByArtistRule(np, s);
    }

    public static boolean isPodcast(NowPlaying np, ScrobblerSettings s) {
        if (np == null || np.isEmpty() || s == null) return false;
        if (!s.podcastFilteringEnabled) return false;

        // Artist-level MUSIC override wins over everything else.
        if (isMusicOverride(np, s)) return false;

        // Artist-level PODCAST rule classifies regardless of episode length.
        if (isPodcastByArtistRule(np, s)) return true;

        String src = np.sourceApp() == null ? "" : np.sourceApp().toLowerCase();
        for (String marker : s.podcastSourceMarkers) {
            if (marker == null) continue;
            String m = marker.trim().toLowerCase();
            if (!m.isEmpty() && src.contains(m)) return true;
        }

        // Duration check uses total track length, not the amount already heard.
        if (isLongFormByDuration(np, s)) return true;

        String title = (np.title() == null ? "" : np.title()).toLowerCase();
        String artist = (np.artist() == null ? "" : np.artist()).toLowerCase();
        for (String kw : s.podcastTitleKeywords) {
            if (kw == null) continue;
            String k = kw.trim().toLowerCase();
            if (k.isEmpty()) continue;
            if (title.contains(k) || artist.contains(k)) return true;
        }

        return false;
    }
}
