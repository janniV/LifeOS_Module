package de.janniv.lifeos.scrobbler.ui;

import de.janniv.lifeos.scrobbler.util.PrivacyHttp;
import de.janniv.lifeos.scrobbler.util.ScrobblerLog;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * In-memory cover-art loader and cache shared by the history and podcast tables.
 *
 * <p>JavaFX's built-in {@link Image} loader for HTTP URLs is finicky: some
 * Maloja responses (placeholders, redirects, mime-type quirks) make it fail
 * silently and the {@code TableCell} just shows nothing. To avoid that, we
 * download bytes ourselves via {@link PrivacyHttp} — which is the same path
 * the rest of the module uses — and feed them into {@link Image} from a
 * {@link ByteArrayInputStream}. Failures are logged so the user can see what
 * the server actually returned.
 *
 * <p>Loads are de-duplicated by URL and dispatched on a small thread pool so
 * scrolling a long history table doesn't open hundreds of sockets.
 */
public final class CoverCache {

    private static final int CACHE_SIZE = 512;
    private static final int MIN_BYTES = 200; // anything smaller is almost certainly an error page
    private static final int IMG_SIZE = 80;   // request 2× display size for crisp HiDPI rendering

    private final ExecutorService pool =
        Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "scrobbler-cover-loader");
            t.setDaemon(true);
            return t;
        });

    // LRU cache of decoded Image objects.
    private final Map<String, Image> cache = java.util.Collections.synchronizedMap(
        new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    // URLs we've recently observed to fail — don't keep retrying.
    private final java.util.Set<String> failed = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final PrivacyHttp http;

    public CoverCache() {
        this.http = new PrivacyHttp("", 0, "none");
    }

    /**
     * Asynchronously fetches the image at {@code url} and delivers it via
     * {@code onLoaded} on the JavaFX thread. {@code onLoaded} is not called when
     * the load fails — in that case the caller just shows nothing.
     */
    public void load(String url, Consumer<Image> onLoaded) {
        if (url == null || url.isBlank() || onLoaded == null) return;
        Image cached = cache.get(url);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        if (failed.contains(url)) return;
        pool.execute(() -> {
            try {
                byte[] bytes = fetchBytes(url);
                if (bytes == null || bytes.length < MIN_BYTES || !looksLikeImage(bytes)) {
                    failed.add(url);
                    return;
                }
                Image img = new Image(new ByteArrayInputStream(bytes),
                    IMG_SIZE, IMG_SIZE, true, true);
                if (img.isError()) {
                    failed.add(url);
                    ScrobblerLog.warn("Cover decode failed for " + url + ": "
                        + (img.getException() == null ? "?" : img.getException().getMessage()), null);
                    return;
                }
                cache.put(url, img);
                Platform.runLater(() -> onLoaded.accept(img));
            } catch (Exception e) {
                failed.add(url);
                ScrobblerLog.warn("Cover load failed for " + url + ": " + e.getMessage(), null);
            }
        });
    }

    private byte[] fetchBytes(String url) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("file".equals(scheme)) {
            try { return Files.readAllBytes(Paths.get(uri)); }
            catch (Exception e) { return null; }
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return http.getBytes(uri, Map.of("Accept", "image/*"));
        }
        return null;
    }

    /** Sanity check on the first few bytes — JPEG / PNG / GIF / WebP / BMP. */
    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 4) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        // GIF: GIF8
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
        // BMP: BM
        if (b[0] == 'B' && b[1] == 'M') return true;
        // WebP: starts with RIFF .... WEBP
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        return false;
    }
}
