package de.janniv.lifeos.scrobbler.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.util.SourceAppName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;

/**
 * Bridges the Windows System Media Transport Controls into Java by spawning a
 * short-lived PowerShell process that queries the WinRT session manager.
 *
 * <p>The script lives as a classpath resource and is extracted to the module
 * data directory on first use. PowerShell is available on every supported
 * Windows release without admin rights. Each invocation also writes the
 * current thumbnail (if any) to a fixed temp file so the UI can pick it up
 * without hitting the network.
 */
public final class WindowsSmtcSource implements MediaSource {

    private static final String SCRIPT_RESOURCE = "/scripts/smtc_query.ps1";

    private final Path scriptPath;
    private final Path thumbnailPath;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> ignoredApps;

    public WindowsSmtcSource(Path moduleStorageDir, Set<String> ignoredApps) throws IOException {
        this.ignoredApps = ignoredApps == null ? Set.of() : ignoredApps;
        Files.createDirectories(moduleStorageDir);
        this.scriptPath = moduleStorageDir.resolve("smtc_query.ps1");
        this.thumbnailPath = moduleStorageDir.resolve("smtc_thumbnail.png");
        try (InputStream in = WindowsSmtcSource.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) throw new IOException("Bundled SMTC script not found on classpath: " + SCRIPT_RESOURCE);
            Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String describe() {
        return "SMTC (PowerShell+WinRT)";
    }

    @Override
    public NowPlaying poll() {
        ProcessBuilder pb = new ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", scriptPath.toAbsolutePath().toString(),
            "-ThumbnailOut", thumbnailPath.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String body = out.toString().trim();
            if (body.isEmpty() || body.equals("{}")) return null;

            JsonNode n = json.readTree(body);
            String rawApp = textOrEmpty(n, "appId");
            if (isIgnored(rawApp)) return null;
            String app = SourceAppName.simplify(rawApp);

            String status = textOrEmpty(n, "status");
            NowPlaying.State state = switch (status.toLowerCase()) {
                case "playing" -> NowPlaying.State.PLAYING;
                case "paused"  -> NowPlaying.State.PAUSED;
                case "stopped", "closed" -> NowPlaying.State.STOPPED;
                default -> NowPlaying.State.UNKNOWN;
            };

            long durationMs = n.path("durationMs").asLong(0);
            long positionMs = n.path("positionMs").asLong(0);
            String coverPath = textOrEmpty(n, "coverPath");
            String coverUrl = coverPath.isEmpty() ? "" : Path.of(coverPath).toUri().toString();

            return new NowPlaying(
                textOrEmpty(n, "artist"),
                textOrEmpty(n, "title"),
                textOrEmpty(n, "album"),
                app,
                durationMs,
                positionMs,
                state,
                Instant.now(),
                coverUrl
            );
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isIgnored(String app) {
        if (app == null || app.isEmpty()) return false;
        String a = app.toLowerCase();
        for (String pat : ignoredApps) {
            if (pat == null) continue;
            String p = pat.trim().toLowerCase();
            if (!p.isEmpty() && a.contains(p)) return true;
        }
        return false;
    }

    private static String textOrEmpty(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }
}
