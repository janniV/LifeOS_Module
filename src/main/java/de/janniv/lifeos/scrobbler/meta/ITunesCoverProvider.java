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
 * Cover-art lookup against Apple's iTunes Search API. Free, no key, sane
 * coverage for mainstream and a surprising amount of niche music. The 100×100
 * thumbnail URL it returns is rewritten to 600×600 before download.
 */
public final class ITunesCoverProvider implements CoverProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrivacyHttp http;

    public ITunesCoverProvider(PrivacyHttp http) {
        this.http = http;
    }

    @Override public String name() { return "itunes"; }

    @Override
    public Result lookup(String artist, String title, String album) {
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) return null;
        try {
            String term = URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8);
            URI uri = new URI("https://itunes.apple.com/search?term=" + term
                + "&media=music&entity=song&limit=5");
            PrivacyHttp.Response resp = http.get(uri, Map.of("Accept", "application/json"));
            if (resp.statusCode < 200 || resp.statusCode >= 300) return null;

            JsonNode root = JSON.readTree(resp.body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return null;

            // Prefer matches whose artist + track name agree (case-insensitive) with
            // what we asked for. Falls back to the first result if nothing matches.
            JsonNode best = results.get(0);
            for (JsonNode r : results) {
                if (matches(r.path("artistName").asText(""), artist)
                    && matches(r.path("trackName").asText(""), title)) {
                    best = r;
                    break;
                }
            }

            String art = best.path("artworkUrl100").asText("");
            if (art.isEmpty()) return null;
            String big = art.replace("100x100bb", "600x600bb")
                            .replace("100x100", "600x600");

            byte[] data = http.getBytes(URI.create(big), Map.of());
            if (data == null || data.length < 200) return null;
            return new Result(hash(artist + "|" + title), data);
        } catch (Exception e) {
            ScrobblerLog.warn("iTunes cover lookup failed", e);
            return null;
        }
    }

    private static boolean matches(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
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
