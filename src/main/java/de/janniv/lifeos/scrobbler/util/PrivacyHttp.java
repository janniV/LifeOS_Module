package de.janniv.lifeos.scrobbler.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP helper that supports two proxy modes for outgoing metadata requests:
 *
 * <ul>
 *   <li>{@code none} — direct connection via the modern {@link HttpClient}.</li>
 *   <li>{@code SOCKS5} — connection routed through a local Tor or SSH-tunnel via
 *       the legacy {@link HttpURLConnection}, which is the only Java HTTP API
 *       that actually honours {@link Proxy.Type#SOCKS}. Works for HTTPS too,
 *       because the SSL handshake is layered on top of the proxied socket.</li>
 *   <li>{@code HTTP} — proxy with native {@link HttpClient} support.</li>
 * </ul>
 *
 * <p>The previous implementation tried to use {@code System.setProperty} for the
 * SOCKS proxy. That has no effect on {@link HttpClient}, so the requests went
 * out un-proxied. The current implementation explicitly picks the right code
 * path based on the configured mode.
 */
public final class PrivacyHttp {

    public enum Mode { NONE, SOCKS, HTTP }

    private final Mode mode;
    private final Proxy proxy;
    private final HttpClient direct;

    public PrivacyHttp(String host, int port, String modeRaw) {
        this.mode = parseMode(modeRaw, host, port);
        this.proxy = (this.mode == Mode.NONE)
            ? Proxy.NO_PROXY
            : new Proxy(this.mode == Mode.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                        new InetSocketAddress(host.trim(), port));

        HttpClient.Builder b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (this.mode == Mode.HTTP) {
            b = b.proxy(java.net.ProxySelector.of((InetSocketAddress) proxy.address()));
        }
        this.direct = b.build();
    }

    /** Convenience: pick mode automatically — empty host means {@code NONE}. */
    public PrivacyHttp(String host, int port) {
        this(host, port, host == null || host.isBlank() ? "none" : "socks");
    }

    private static Mode parseMode(String raw, String host, int port) {
        if (host == null || host.isBlank() || port <= 0) return Mode.NONE;
        String r = raw == null ? "" : raw.trim().toLowerCase();
        return switch (r) {
            case "http" -> Mode.HTTP;
            case "socks", "socks5", "tor" -> Mode.SOCKS;
            default -> Mode.NONE;
        };
    }

    public Mode mode() { return mode; }
    public Proxy proxy() { return proxy; }

    public Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        if (mode == Mode.SOCKS) return legacyGet(uri, headers);
        return modernSend(HttpRequest.newBuilder(uri).GET(), headers);
    }

    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        return postRaw(uri, "application/x-www-form-urlencoded", encodeForm(form), headers);
    }

    /**
     * POST with a pre-built body string. Used when the body is anything other
     * than a single-valued form (e.g. multi-valued fields or JSON).
     */
    public Response postRaw(URI uri, String contentType, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        if (mode == Mode.SOCKS) return legacyPostForm(uri, body, contentType, headers);
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) h.putAll(headers);
        h.put("Content-Type", contentType);
        return modernSend(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), h);
    }

    private Response modernSend(HttpRequest.Builder rb, Map<String, String> headers)
            throws IOException, InterruptedException {
        rb.timeout(Duration.ofSeconds(20));
        if (headers != null) headers.forEach(rb::header);
        HttpResponse<String> resp = direct.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(resp.statusCode(), resp.body());
    }

    /**
     * SOCKS implementation via {@link HttpURLConnection} — the only built-in
     * Java HTTP path that propagates the proxy to the underlying socket. Works
     * with both HTTP and HTTPS endpoints; the JVM layers TLS on top of the
     * proxied socket automatically.
     */
    private Response legacyGet(URI uri, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = openConn(uri.toURL(), "GET");
        applyHeaders(conn, headers);
        return readResponse(conn);
    }

    private Response legacyPostForm(URI uri, String body, String contentType, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = openConn(uri.toURL(), "POST");
        if (headers != null) headers.forEach(conn::setRequestProperty);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setDoOutput(true);
        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private HttpURLConnection openConn(URL url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static void applyHeaders(HttpURLConnection conn, Map<String, String> headers) {
        if (headers == null) return;
        headers.forEach(conn::setRequestProperty);
    }

    private static Response readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) buf.write(chunk, 0, n);
            stream.close();
        }
        return new Response(code, buf.toString(StandardCharsets.UTF_8));
    }

    public static String encodeForm(Map<String, String> form) {
        if (form == null || form.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /** Downloads raw bytes through the configured proxy. Used for cover art. */
    public byte[] getBytes(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        if (mode == Mode.SOCKS) {
            HttpURLConnection conn = openConn(uri.toURL(), "GET");
            applyHeaders(conn, headers);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(20));
        if (headers != null) headers.forEach(rb::header);
        HttpResponse<byte[]> resp = direct.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
        return resp.body();
    }

    /** Uploads raw bytes via PUT. Used to store cover art on the Maloja server. */
    public Response putBytes(URI uri, byte[] data, Map<String, String> headers)
            throws IOException, InterruptedException {
        return sendBytes("PUT", uri, data, headers);
    }

    /** Uploads raw bytes via POST. Used for multipart cover uploads where the
     *  body is a manually-built multipart form and must not be UTF-8 reencoded. */
    public Response postBytes(URI uri, byte[] data, Map<String, String> headers)
            throws IOException, InterruptedException {
        return sendBytes("POST", uri, data, headers);
    }

    private Response sendBytes(String method, URI uri, byte[] data, Map<String, String> headers)
            throws IOException, InterruptedException {
        if (mode == Mode.SOCKS) return legacySendBytes(method, uri, data, headers);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(data))
            .timeout(Duration.ofSeconds(30));
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) h.putAll(headers);
        return modernSend(rb, h);
    }

    private Response legacySendBytes(String method, URI uri, byte[] data, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = openConn(uri.toURL(), method);
        if (headers != null) headers.forEach(conn::setRequestProperty);
        conn.setDoOutput(true);
        try (java.io.OutputStream out = conn.getOutputStream()) { out.write(data); }
        return readResponse(conn);
    }

    /** Plain status+body container so callers don't have to know which transport was used. */
    public static final class Response {
        public final int statusCode;
        public final String body;
        public Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }
}
