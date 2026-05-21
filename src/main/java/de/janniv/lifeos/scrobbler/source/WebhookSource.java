package de.janniv.lifeos.scrobbler.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local HTTP webhook endpoint that accepts "now-playing" pushes from any
 * external source — browser bookmarklet, curl from the NAS, a phone
 * automation app, etc.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   POST /now-playing   — set current track (JSON or form body)
 *   DELETE /now-playing — explicitly stop
 *   GET /now-playing    — read current state (healthcheck / bookmarklet)
 *   GET /health         — 204 No Content always
 * </pre>
 *
 * <h2>JSON body for POST</h2>
 * <pre>
 * {
 *   "artist": "Radiohead",
 *   "title":  "Creep",
 *   "album":  "Pablo Honey",          // optional
 *   "duration_ms": 238000,             // optional, 0 = unknown
 *   "position_ms": 12000,             // optional
 *   "state": "PLAYING"                // optional, "PLAYING" or "PAUSED"
 * }
 * </pre>
 *
 * <h2>Browser Bookmarklet (UGREEN Web-UI)</h2>
 * Paste the following as a bookmark URL. Click it while music is playing
 * in the UGREEN Music tab to push the current track to LifeOS:
 * <pre>
 * javascript:(function(){
 *   var a=document.querySelector('.song-name,.track-title,.music-title,h1,h2')?.innerText||'';
 *   var b=document.querySelector('.artist-name,.track-artist')?.innerText||'';
 *   fetch('http://localhost:8089/now-playing',{method:'POST',
 *     headers:{'Content-Type':'application/json'},
 *     body:JSON.stringify({title:a,artist:b,state:'PLAYING'})});
 * })();
 * </pre>
 *
 * <h2>TTL</h2>
 * If no POST is received within 90 seconds the track is considered stopped
 * so stale data doesn't block normal sources from taking over.
 */
public final class WebhookSource implements MediaSource {

    public static final int DEFAULT_PORT = 8089;
    private static final long TTL_MS = 90_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private volatile HttpServer server;

    public WebhookSource(int port) {
        this.port = port > 0 ? port : DEFAULT_PORT;
    }

    /** Starts the HTTP listener. Called by the factory after construction. */
    public WebhookSource start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/now-playing", this::handleNowPlaying);
            server.createContext("/health", ex -> { ex.sendResponseHeaders(204, -1); ex.close(); });
            server.setExecutor(null);
            server.start();
            ScrobblerLog.info("Webhook source listening on http://127.0.0.1:" + port + "/now-playing");
        } catch (IOException e) {
            ScrobblerLog.warn("Could not start webhook source on port " + port, e);
        }
        return this;
    }

    @Override
    public void close() {
        if (server != null) { server.stop(0); server = null; }
    }

    @Override
    public NowPlaying poll() {
        Snapshot s = current.get();
        if (s == null) return null;
        if (System.currentTimeMillis() - s.receivedAt > TTL_MS) {
            current.compareAndSet(s, null);
            return null;
        }
        return s.track;
    }

    @Override
    public String describe() { return "Webhook (HTTP localhost:" + port + ")"; }

    private void handleNowPlaying(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        switch (method) {
            case "GET" -> respondJson(ex, 200, currentJson());
            case "DELETE" -> { current.set(null); respond(ex, 200, "stopped"); }
            case "POST", "PUT" -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                String raw = new String(body, StandardCharsets.UTF_8).trim();
                NowPlaying np = parse(raw, ex.getRequestHeaders().getFirst("Content-Type"));
                if (np != null && !np.isEmpty()) {
                    current.set(new Snapshot(np, System.currentTimeMillis()));
                    ScrobblerLog.info("Webhook received: " + np.artist() + " — " + np.title());
                    respondJson(ex, 200, "{\"status\":\"ok\",\"artist\":" + q(np.artist())
                        + ",\"title\":" + q(np.title()) + "}");
                } else {
                    respond(ex, 400, "could not parse body: " + raw);
                }
            }
            default -> respond(ex, 405, "method not allowed");
        }
    }

    private NowPlaying parse(String body, String contentType) {
        try {
            if (body.startsWith("{")) {
                JsonNode n = JSON.readTree(body);
                return build(
                    n.path("artist").asText(""),
                    n.path("title").asText(""),
                    n.path("album").asText(""),
                    n.path("duration_ms").asLong(0),
                    n.path("position_ms").asLong(0),
                    "PAUSED".equalsIgnoreCase(n.path("state").asText("")) ? NowPlaying.State.PAUSED : NowPlaying.State.PLAYING);
            } else {
                // application/x-www-form-urlencoded fallback
                java.util.Map<String, String> params = parseForm(body);
                return build(
                    params.getOrDefault("artist", ""),
                    params.getOrDefault("title", ""),
                    params.getOrDefault("album", ""),
                    Long.parseLong(params.getOrDefault("duration_ms", "0")),
                    Long.parseLong(params.getOrDefault("position_ms", "0")),
                    "PAUSED".equalsIgnoreCase(params.getOrDefault("state", "")) ? NowPlaying.State.PAUSED : NowPlaying.State.PLAYING);
            }
        } catch (Exception e) {
            ScrobblerLog.warn("Webhook body parse failed", e);
            return null;
        }
    }

    private static NowPlaying build(String artist, String title, String album,
                                    long durationMs, long posMs, NowPlaying.State state) {
        if (artist.isBlank() && title.isBlank()) return null;
        return new NowPlaying(artist.trim(), title.trim(), album.trim(), "webhook",
            durationMs, posMs, state, Instant.now());
    }

    private static java.util.Map<String, String> parseForm(String body) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            m.put(java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                  java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private String currentJson() {
        Snapshot s = current.get();
        if (s == null || System.currentTimeMillis() - s.receivedAt > TTL_MS)
            return "{\"state\":\"STOPPED\"}";
        NowPlaying t = s.track;
        return "{\"artist\":" + q(t.artist()) + ",\"title\":" + q(t.title())
            + ",\"album\":" + q(t.album()) + ",\"duration_ms\":" + t.durationMs()
            + ",\"state\":\"" + t.state() + "\"}";
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\\\"")) + "\"";
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    private static void respondJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    private record Snapshot(NowPlaying track, long receivedAt) {}
}
