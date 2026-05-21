package de.janniv.lifeos.scrobbler.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.util.PrivacyHttp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Looks up recordings on MusicBrainz to verify or correct the locally parsed
 * artist/title, and resolves cover art via the Cover Art Archive.
 *
 * <p>MusicBrainz documents a hard limit of one request per second per IP; we
 * respect that here with a global mutex/sleep. The User-Agent identifies the
 * module, never the host machine. All traffic can be routed through Tor or an
 * HTTP proxy via {@link PrivacyHttp}.
 */
public final class MusicBrainzClient {

    private static final String UA = "LifeOSnext-Scrobbler/1.1 ( https://github.com/janniV/LifeOSnext )";
    private static final String MB_BASE = "https://musicbrainz.org/ws/2/recording";
    private static final String CAA_BASE = "https://coverartarchive.org";
    private static final long MIN_INTERVAL_MS = 1100;

    private final PrivacyHttp http;
    private final ObjectMapper json = new ObjectMapper();
    private final Object rateLock = new Object();
    private long nextEarliestRequest = 0L;

    public MusicBrainzClient(String host, int port, String mode) {
        this.http = new PrivacyHttp(host, port, mode);
    }

    public PrivacyHttp.Mode proxyMode() {
        return http.mode();
    }

    /**
     * Tries to find a canonical recording match. Returns a NowPlaying with
     * normalised artist/title (and possibly album) when a high-confidence
     * match is found. The returned object also exposes a release id via
     * {@link Enrichment} for downstream cover-art lookups.
     */
    public Optional<Enrichment> enrich(NowPlaying input) {
        if (input == null || input.title().isBlank()) return Optional.empty();
        String query = buildQuery(input.artist(), input.title());
        URI uri = URI.create(MB_BASE + "?fmt=json&limit=5&query="
            + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        try {
            rateLimit();
            PrivacyHttp.Response resp = http.get(uri, Map.of(
                "User-Agent", UA,
                "Accept", "application/json"
            ));
            if (resp.statusCode != 200) return Optional.empty();
            JsonNode root = json.readTree(resp.body);
            JsonNode recordings = root.path("recordings");
            if (!recordings.isArray() || recordings.isEmpty()) return Optional.empty();

            JsonNode best = recordings.get(0);
            int score = best.path("score").asInt(0);
            if (score < 90) return Optional.empty();

            String recTitle = best.path("title").asText("").trim();
            String recArtist = "";
            JsonNode credit = best.path("artist-credit");
            if (credit.isArray() && !credit.isEmpty()) {
                recArtist = credit.get(0).path("name").asText("").trim();
            }
            String recAlbum = "";
            String releaseId = "";
            JsonNode releases = best.path("releases");
            if (releases.isArray() && !releases.isEmpty()) {
                recAlbum = releases.get(0).path("title").asText("").trim();
                releaseId = releases.get(0).path("id").asText("").trim();
            }
            if (recArtist.isEmpty() || recTitle.isEmpty()) return Optional.empty();
            String album = input.album().isBlank() ? recAlbum : input.album();
            NowPlaying np = input.withEnhanced(recArtist, recTitle, album);
            return Optional.of(new Enrichment(np, releaseId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Returns the front-cover bytes for the given MB release id, or {@code null}. */
    public byte[] fetchCoverArt(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) return null;
        try {
            rateLimit();
            URI uri = URI.create(CAA_BASE + "/release/" + releaseId + "/front-250");
            return http.getBytes(uri, Map.of("User-Agent", UA, "Accept", "image/*"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Blocks until at least {@link #MIN_INTERVAL_MS} have passed since the last request. */
    private void rateLimit() throws InterruptedException {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            long wait = nextEarliestRequest - now;
            if (wait > 0) Thread.sleep(wait);
            nextEarliestRequest = System.currentTimeMillis() + MIN_INTERVAL_MS;
        }
    }

    private static String buildQuery(String artist, String title) {
        Map<String, String> parts = new LinkedHashMap<>();
        if (title != null && !title.isBlank()) parts.put("recording", title);
        if (artist != null && !artist.isBlank()) parts.put("artist", artist);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if (!first) sb.append(" AND ");
            sb.append(e.getKey()).append(":\"").append(e.getValue().replace("\"", "")).append("\"");
            first = false;
        }
        return sb.toString();
    }

    /** Result of an MB lookup: the corrected track plus a release id for cover art. */
    public static final class Enrichment {
        public final NowPlaying track;
        public final String releaseId;
        public Enrichment(NowPlaying track, String releaseId) {
            this.track = track;
            this.releaseId = releaseId == null ? "" : releaseId;
        }
    }
}
