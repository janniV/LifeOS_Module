package de.janniv.lifeos.scrobbler.target;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.ScrobbleEvent;
import de.janniv.lifeos.scrobbler.util.ArtistNormalizer;
import de.janniv.lifeos.scrobbler.util.ArtistSplitter;
import de.janniv.lifeos.scrobbler.util.PrivacyHttp;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny client for the Maloja {@code mlj_1} REST API.
 *
 * <p>Auth is split between two completely different mechanisms in Maloja:
 * <ul>
 *   <li><b>Scrobble writes</b> ({@code newscrobble}) accept an API key —
 *       sent both as {@code Authorization: Token ...} header and as a
 *       {@code key} form field for cross-version compatibility.</li>
 *   <li><b>Mutations of existing data</b> ({@code delete_scrobble},
 *       {@code edit_*}) are gated behind a logged-in admin session;
 *       Maloja explicitly ignores API keys for these endpoints. The client
 *       therefore logs in to {@code /auth/authenticate} with the admin
 *       password on demand and stashes the resulting session cookie in
 *       memory only.</li>
 * </ul>
 *
 * <p>Maloja typically runs on plain HTTP inside the user's LAN — the client
 * does not enforce HTTPS. It also never routes through the privacy proxy;
 * scrobbles are private network traffic, not third-party metadata.
 */
public final class MalojaClient {

    private final String baseUrl;
    private final String apiKey;
    private final String adminPassword;
    private final PrivacyHttp http;
    private volatile String sessionCookie; // e.g. "maloja_sessiontoken=abc123"

    public MalojaClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, "");
    }

    public MalojaClient(String baseUrl, String apiKey, String adminPassword) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        this.http = new PrivacyHttp("", 0, "none");
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !apiKey.isEmpty();
    }

    public boolean hasAdminPassword() {
        return !adminPassword.isEmpty();
    }

    /**
     * Submits one scrobble. Returns a short status string; throws on transport
     * failures so the caller can decide whether to retry.
     */
    public String scrobble(NowPlaying track, Instant playedAt) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Maloja URL or API key not set");
        URI uri = endpoint("/apis/mlj_1/newscrobble");

        // Maloja supports multiple "artists" form fields per scrobble. Splitting on collab
        // separators here means "DAHABFLEX x ERZIN" lands as two separate artists in the
        // database, not as one squashed string.
        List<String> artists = ArtistSplitter.split(track.artist());
        StringBuilder body = new StringBuilder();
        appendField(body, "key", apiKey);
        for (String a : artists) appendField(body, "artist", a);
        appendField(body, "title", track.title());
        if (!track.album().isEmpty()) appendField(body, "album", track.album());
        if (track.durationMs() > 0) appendField(body, "length", String.valueOf(track.durationMs() / 1000L));
        appendField(body, "time", String.valueOf(playedAt.getEpochSecond()));
        appendField(body, "nofix", "no");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Token " + apiKey);

        PrivacyHttp.Response resp = http.postRaw(uri, "application/x-www-form-urlencoded", body.toString(), headers);
        int code = resp.statusCode;
        if (code >= 200 && code < 300) {
            return artists.size() > 1 ? "OK (" + code + ", " + artists.size() + " Artists)" : "OK (" + code + ")";
        }
        throw new RuntimeException("Maloja replied HTTP " + code + ": " + truncate(resp.body, 200));
    }

    /**
     * Deletes a single scrobble by its played-at epoch second. Maloja keys
     * scrobbles primarily by timestamp, so this is enough to identify one
     * uniquely under normal circumstances.
     *
     * <p>Requires the admin password — Maloja's {@code delete_scrobble}
     * endpoint does not accept API keys, only logged-in sessions.
     *
     * <p>Maloja's API has historically used both {@code timestamp} and
     * {@code time} as the key name across versions. We send both to cover
     * all Maloja releases without requiring the user to know their version.
     */
    public String deleteScrobble(long timestampSeconds) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Maloja URL or API key not set");
        if (adminPassword.isEmpty()) {
            throw new RuntimeException("Zum Löschen wird das Maloja-Admin-Passwort benötigt. "
                + "Trag es in den Scrobbler-Einstellungen ein — API-Keys reichen Maloja "
                + "nur fürs Scrobbeln, nicht fürs Löschen.");
        }
        return deleteWithRetry(timestampSeconds, true);
    }

    private String deleteWithRetry(long timestampSeconds, boolean canRetryLogin) throws Exception {
        ensureLoggedIn();

        URI uri = endpoint("/apis/mlj_1/delete_scrobble");
        // Send as JSON with an actual integer — form-urlencoded delivers everything
        // as strings, and Maloja's delete_scrobble() does an integer comparison
        // on the timestamp (e.g. `timestamp >= some_int`) which blows up with
        // TypeError when the value arrives as a string.
        String jsonBody = JSON.writeValueAsString(Map.of("timestamp", timestampSeconds));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Cookie", sessionCookie);

        PrivacyHttp.Response resp = http.postRaw(uri, "application/json", jsonBody, headers);
        int code = resp.statusCode;
        String body2 = resp.body == null ? "" : resp.body;
        boolean failure = body2.contains("\"status\": \"failure\"") || body2.contains("\"status\":\"failure\"");
        if (code >= 200 && code < 300 && !failure) {
            return "Gelöscht (" + code + ")";
        }
        if ((code == 401 || code == 403 || body2.contains("authentication_fail")) && canRetryLogin) {
            // Session probably expired — drop cached cookie and try once more with a fresh login.
            sessionCookie = null;
            return deleteWithRetry(timestampSeconds, false);
        }
        if (code == 401 || code == 403) {
            throw new RuntimeException("Maloja-Login abgelehnt (HTTP " + code + "). "
                + "Stimmt das Admin-Passwort? Das ist dasselbe Passwort, mit dem du dich "
                + "auf " + baseUrl + " im Browser einloggst.");
        }
        throw new RuntimeException("Maloja-Antwort HTTP " + code + ": " + truncate(body2, 200));
    }

    /**
     * Logs in to Maloja and caches the session cookie. No-op if a cookie is
     * already cached. Thread-safe via the synchronized monitor.
     *
     * <p>Maloja's login form (extracted from {@code login.html.jinja}) POSTs
     * a JSON body {@code {"user":"admin","password":"..."}} to
     * {@code /auth/authenticate}. Form-encoded bodies trigger a 500 inside
     * nimrodel's parameter binding, so this client mirrors the real form's
     * payload. The response JSON contains {@code token} and {@code cookie_name}.
     */
    private synchronized void ensureLoggedIn() throws Exception {
        if (sessionCookie != null) return;
        if (adminPassword.isEmpty()) {
            throw new RuntimeException("Maloja-Admin-Passwort fehlt.");
        }
        URI uri = endpoint("/auth/authenticate");
        String jsonBody = JSON.writeValueAsString(Map.of(
            "user", "admin",
            "password", adminPassword));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        PrivacyHttp.Response resp = http.postRaw(uri, "application/json",
            jsonBody, headers);
        int code = resp.statusCode;
        if (code == 401 || code == 403) {
            throw new RuntimeException("Maloja hat das Admin-Passwort abgelehnt (HTTP " + code
                + "). Es ist dasselbe Passwort, mit dem du dich auf " + baseUrl + " im Browser einloggst.");
        }
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Maloja-Login fehlgeschlagen: HTTP " + code
                + " — " + truncate(resp.body, 160));
        }
        String token;
        String cookieName = "maloja_sessiontoken";
        try {
            JsonNode root = JSON.readTree(resp.body);
            token = root.path("token").asText("");
            String cn = root.path("cookie_name").asText("");
            if (!cn.isEmpty()) cookieName = cn;
        } catch (Exception e) {
            throw new RuntimeException("Maloja-Login: unverständliche Antwort: "
                + truncate(resp.body, 160));
        }
        if (token.isEmpty()) {
            throw new RuntimeException("Maloja-Login: keinen Session-Token erhalten. "
                + "Stimmt das Admin-Passwort?");
        }
        this.sessionCookie = cookieName + "=" + token;
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        if (value == null) return;
        if (sb.length() > 0) sb.append('&');
        sb.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8))
          .append('=')
          .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Fetches the most recent scrobbles from Maloja so the LifeOS history
     * survives restarts without us having to keep a local copy. Maloja itself
     * is the source of truth; we just hydrate the in-memory view from it.
     *
     * <p>The {@code /apis/mlj_1/scrobbles} endpoint returns a {@code list}
     * array whose entries each carry a {@code track} object (with
     * {@code artists}, {@code title}, optional {@code album}, optional
     * {@code length}) and a unix-second {@code time} field.
     */
    public List<ScrobbleEvent> fetchRecent(int max) throws Exception {
        if (!isConfigured()) return List.of();
        URI uri = endpoint("/apis/mlj_1/scrobbles?max=" + Math.max(1, max));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Token " + apiKey);
        PrivacyHttp.Response resp = http.get(uri, headers);
        if (resp.statusCode < 200 || resp.statusCode >= 300) {
            throw new RuntimeException("Maloja replied HTTP " + resp.statusCode + ": " + truncate(resp.body, 200));
        }
        JsonNode root = JSON.readTree(resp.body);
        JsonNode list = root.path("list");
        if (!list.isArray()) list = root.path("scrobbles");
        List<ScrobbleEvent> out = new ArrayList<>();
        for (JsonNode n : list) {
            out.add(toEvent(n));  // instance call — uses baseUrl for cover URL
        }
        return out;
    }

    private ScrobbleEvent toEvent(JsonNode n) {
        JsonNode track = n.has("track") ? n.path("track") : n;
        StringBuilder artist = new StringBuilder();
        JsonNode artists = track.path("artists");
        if (artists.isArray()) {
            for (JsonNode a : artists) {
                if (artist.length() > 0) artist.append(", ");
                artist.append(a.asText(""));
            }
        } else {
            artist.append(track.path("artist").asText(""));
        }
        String title = track.path("title").asText("");
        String album = track.path("album").path("name").asText("");
        if (album.isEmpty()) album = track.path("album").asText("");
        long lengthSec = track.path("length").asLong(0);
        long timeSec = n.path("time").asLong(0);
        Instant ts = timeSec > 0 ? Instant.ofEpochSecond(timeSec) : Instant.now();
        // Apply title-case normalisation to both fields so entries scrobbled
        // before the normaliser existed (e.g. "KNEECAP" or "ABC DEF" stored
        // in all-caps) display the same way as new ones.
        String normalizedArtist = ArtistNormalizer.smartCase(artist.toString());
        String normalizedTitle = ArtistNormalizer.smartCase(title);
        // Attach Maloja's own image endpoint as the cover URL so the history view
        // can show artwork without a local cache — Maloja fetches and caches the
        // image from its configured sources on first request.
        String coverUrl = buildCoverUrl(normalizedArtist, normalizedTitle);
        return new ScrobbleEvent(ts, normalizedArtist, normalizedTitle, album,
            lengthSec * 1000L, ScrobbleEvent.Status.OK, "Aus Maloja", coverUrl);
    }

    /**
     * Uploads cover art bytes to Maloja's {@code /image} endpoint so that
     * Maloja (and every other device that uses it) can serve our locally-fetched
     * cover instead of whatever its own metadata sources find.
     *
     * <p>Uses the admin session (same credentials as delete/edit). Fails silently
     * when not configured, when Maloja doesn't support the PUT method, or when
     * the upload itself fails — the local cover is always still available as
     * fallback for the now-playing header.
     */
    public void uploadCover(String artist, String title, byte[] imageData) {
        if (!isConfigured() || imageData == null || imageData.length == 0) return;
        if (artist == null || artist.isBlank()) return;
        if (adminPassword.isEmpty()) return; // can't auth, silently skip
        try {
            ensureLoggedIn();
            String query = "?artist=" + java.net.URLEncoder.encode(artist, java.nio.charset.StandardCharsets.UTF_8);
            if (title != null && !title.isBlank()) {
                query += "&title=" + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
            }
            // Try PUT /image first (raw bytes, image/jpeg content type), then fall back
            // to a multipart POST which some Maloja builds expect from the admin UI.
            int code = tryPutImage(query, imageData);
            if (code < 200 || code >= 300) {
                code = tryMultipartPost(query, imageData);
            }
            if (code >= 200 && code < 300) {
                ScrobblerLog.info("Cover uploaded to Maloja: " + artist + " — " + title + " (HTTP " + code + ")");
            } else {
                ScrobblerLog.warn("Cover upload to Maloja failed: HTTP " + code + " for "
                    + artist + " — " + title, null);
            }
        } catch (Exception e) {
            ScrobblerLog.warn("Cover upload to Maloja failed: " + e.getMessage(), null);
        }
    }

    private int tryPutImage(String query, byte[] imageData) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Cookie", sessionCookie);
            headers.put("Content-Type", "image/jpeg");
            PrivacyHttp.Response resp = http.putBytes(new URI(baseUrl + "/image" + query), imageData, headers);
            return resp.statusCode;
        } catch (Exception e) { return 0; }
    }

    private int tryMultipartPost(String query, byte[] imageData) {
        try {
            String boundary = "----maloja-scrobbler-" + System.nanoTime();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"cover.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
            body.write(preamble.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(imageData);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Cookie", sessionCookie);
            headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
            PrivacyHttp.Response resp = http.postBytes(new URI(baseUrl + "/image" + query),
                body.toByteArray(), headers);
            return resp.statusCode;
        } catch (Exception e) { return 0; }
    }

    /**
     * Builds the URL for Maloja's {@code /image} endpoint for the given artist
     * and title. Maloja fetches and caches the cover art from external sources
     * (MusicBrainz, Last.fm, etc.) when this URL is first accessed, so no local
     * caching is required on this side.
     *
     * <p>Returns an empty string when the client is not configured.
     */
    public String buildCoverUrl(String artist, String title) {
        if (baseUrl.isEmpty() || artist == null || artist.isBlank()) return "";
        try {
            String url = baseUrl + "/image?artist="
                + java.net.URLEncoder.encode(artist, java.nio.charset.StandardCharsets.UTF_8);
            if (title != null && !title.isBlank()) {
                url += "&title=" + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
            }
            return url;
        } catch (Exception e) { return ""; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lightweight reachability test used by the settings panel. */
    public String testConnection() {
        if (baseUrl.isEmpty()) return "URL ist leer";
        try {
            URI uri = endpoint("/apis/mlj_1/serverinfo");
            PrivacyHttp.Response resp = http.get(uri, Map.of("Accept", "application/json"));
            int code = resp.statusCode;
            if (code >= 200 && code < 300) return "OK — Maloja erreichbar (HTTP " + code + ")";
            return "HTTP " + code + ": " + truncate(resp.body, 120);
        } catch (Exception e) {
            return "Fehler: " + e.getMessage();
        }
    }

    /**
     * Probes whether the configured admin password lets us log in. Maloja
     * does not authorise delete operations via API key — the only path is
     * {@code POST /auth/authenticate} with the admin password, which yields
     * a session cookie. This method tries that login and reports success
     * without actually deleting anything.
     */
    public String testDeleteAuth() {
        if (!isConfigured()) return "Keine URL/API-Key konfiguriert.";
        if (adminPassword.isEmpty()) {
            return "❌ Kein Admin-Passwort eingetragen.\n"
                + "Maloja akzeptiert API-Keys nur fürs Scrobbeln. Zum Löschen "
                + "braucht es das Admin-Passwort, mit dem du dich auf "
                + baseUrl + " im Browser einloggst. Trag es oben ein.";
        }
        try {
            // Drop any cached cookie so we actually exercise login.
            sessionCookie = null;
            ensureLoggedIn();
            return "✅ Admin-Login erfolgreich. Löschen sollte funktionieren.";
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    private URI endpoint(String path) throws URISyntaxException {
        return new URI(baseUrl + path);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
