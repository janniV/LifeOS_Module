package de.janniv.lifeos.scrobbler;

/**
 * Heuristic classifier deciding whether a {@link NowPlaying} item should be
 * treated as a podcast / long-form video and routed to the local podcast log
 * instead of being scrobbled to Maloja.
 *
 * <p>Three signals contribute, in order of confidence:
 * <ol>
 *   <li>The source app is on the configured podcast-source list.</li>
 *   <li>The track length exceeds the configured podcast cutoff (default 15 min).</li>
 *   <li>The title contains a podcast keyword like "Episode", "Folge" or "Podcast".</li>
 * </ol>
 *
 * <p>Any single signal is enough to flag the item.
 */
public final class PodcastDetector {

    private PodcastDetector() {}

    public static boolean isPodcast(NowPlaying np, ScrobblerSettings s) {
        if (np == null || np.isEmpty() || s == null) return false;
        if (!s.podcastFilteringEnabled) return false;

        String src = np.sourceApp() == null ? "" : np.sourceApp().toLowerCase();
        for (String marker : s.podcastSourceMarkers) {
            if (marker == null) continue;
            String m = marker.trim().toLowerCase();
            if (!m.isEmpty() && src.contains(m)) return true;
        }

        if (s.podcastLengthCutoffSeconds > 0
            && np.durationMs() > 0
            && np.durationMs() / 1000L >= s.podcastLengthCutoffSeconds) {
            return true;
        }

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
