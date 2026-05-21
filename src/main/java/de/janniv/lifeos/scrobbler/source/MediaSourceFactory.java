package de.janniv.lifeos.scrobbler.source;

import de.janniv.lifeos.scrobbler.ScrobblerSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Picks the right media source for the host OS, with a no-op fallback. */
public final class MediaSourceFactory {

    private MediaSourceFactory() {}

    public static MediaSource create(Path moduleStorageDir, ScrobblerSettings settings) {
        MediaSource os = createOsSource(moduleStorageDir, settings);

        if (settings.webhookPort > 0) {
            WebhookSource webhook = new WebhookSource(settings.webhookPort).start();
            // Webhook has priority: when it has data it overrides OS-detected metadata.
            return new CompositeMediaSource(List.of(webhook, os));
        }
        return os;
    }

    private static MediaSource createOsSource(Path moduleStorageDir, ScrobblerSettings settings) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return new WindowsSmtcSource(moduleStorageDir, settings.ignoredSources);
            }
            if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
                return new LinuxMprisSource(settings.ignoredSources, settings.preferredSource);
            }
        } catch (IOException e) {
            System.err.println("[Scrobbler] Failed to bootstrap media source: " + e.getMessage());
        }
        return new MediaSource() {
            @Override public String describe() { return "unsupported (" + os + ")"; }
            @Override public de.janniv.lifeos.scrobbler.NowPlaying poll() { return null; }
        };
    }

    /**
     * Returns the list of detectable players/sources on this OS so the UI
     * can offer a picker. May be empty if the platform doesn't expose names.
     */
    public static List<String> listAvailablePlayers() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
            return new LinuxMprisSource(java.util.Set.of(), "").listPlayers();
        }
        // SMTC's session manager doesn't enumerate sessions in our minimal script;
        // we fall back to "auto" only on Windows.
        return List.of();
    }
}
