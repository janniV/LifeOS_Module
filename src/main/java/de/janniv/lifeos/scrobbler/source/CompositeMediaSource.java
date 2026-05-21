package de.janniv.lifeos.scrobbler.source;

import de.janniv.lifeos.scrobbler.NowPlaying;

import java.util.List;

/**
 * Queries multiple sources in priority order and returns the first
 * non-null result. Used to combine the Webhook source (highest priority,
 * user-driven) with the OS bridge (SMTC/MPRIS) so that:
 *
 * <ul>
 *   <li>Normal playback (Spotify, local player) works via SMTC/MPRIS as before.</li>
 *   <li>When SMTC can't read metadata (e.g. broken NAS app), the user can click
 *       a bookmarklet that POSTs to the webhook — that result takes precedence.</li>
 *   <li>Once the webhook TTL expires (90 s without a new POST), fallback returns.</li>
 * </ul>
 */
public final class CompositeMediaSource implements MediaSource {

    private final List<MediaSource> sources;

    public CompositeMediaSource(List<MediaSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public NowPlaying poll() {
        for (MediaSource s : sources) {
            try {
                NowPlaying np = s.poll();
                if (np != null && !np.isEmpty()) return np;
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Composite[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sources.get(i).describe());
        }
        return sb.append("]").toString();
    }

    @Override
    public void close() {
        for (MediaSource s : sources) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
