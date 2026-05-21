package de.janniv.lifeos.scrobbler.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.janniv.lifeos.scrobbler.util.PrivacyHttp;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Cover-art lookup against Deezer's public search API. No key required.
 * Strong coverage for European and electronic music — a useful complement to
 * iTunes for the long tail.
 */
public final class DeezerCoverProvider implements CoverProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrivacyHttp http;

    public DeezerCoverProvider(PrivacyHttp http) {
        this.http = http;
    }

    @Override public String name() { return "deezer"; }

    @Override
    public Result lookup(String artist, String title, String album) {
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) return null;
        try {
            String q = "artist:\"" + artist + "\" track:\"" + title + "\"";
            URI uri = new URI("https://api.deezer.com/search?q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&limit=5");
            PrivacyHttp.Response resp = http.get(uri, Map.of("Accept", "application/json"));
            if (resp.statusCode < 200 || resp.statusCode >= 300) return null;

            JsonNode root = JSON.readTree(resp.body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) return null;

            JsonNode best = data.get(0);
            String coverUrl = best.path("album").path("cover_xl").asText("");
            if (coverUrl.isEmpty()) coverUrl = best.path("album").path("cover_big").asText("");
            if (coverUrl.isEmpty()) return null;

            byte[] bytes = http.getBytes(URI.create(coverUrl), Map.of());
            if (bytes == null || bytes.length < 200) return null;
            return new Result(hash(artist + "|" + title), bytes);
        } catch (Exception e) {
            ScrobblerLog.warn("Deezer cover lookup failed", e);
            return null;
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
