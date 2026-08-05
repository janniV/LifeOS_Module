package de.janniv.lifeos.scrobbler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent settings for the scrobbler. Saved as JSON next to the module data folder.
 * Defaults are tuned for Maloja over plain HTTP on a private network.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScrobblerSettings {

    /** Where to deliver scrobbles, e.g. {@code http://nas.local:42010}. */
    public String malojaUrl = "";

    /** Maloja API key. Stored locally only; never sent to third parties. */
    public String malojaApiKey = "";

    /**
     * Maloja admin password. Required for delete and edit operations — Maloja's
     * API keys only authorise scrobble writes; everything that mutates existing
     * data is gated behind a session cookie. The client logs in via
     * {@code /auth/authenticate} on demand, caches the token in memory only,
     * and never writes it to disk.
     */
    public String malojaAdminPassword = "";

    /** How often the system media session is polled, in milliseconds. */
    public int pollIntervalMs = 1500;

    /** A track is scrobbled once it has played at least this fraction of its length. */
    public double scrobbleAtFraction = 0.5;

    /** Or alternatively, after this many seconds of absolute play time. */
    public int scrobbleAfterSeconds = 240;

    /** Tracks shorter than this are never scrobbled (Last.fm convention: 30 s). */
    public int minTrackLengthSeconds = 30;

    /** Whether to perform smart artist parsing on YouTube/browser-style titles. */
    public boolean smartArtistParsing = true;

    /** Whether to enrich metadata via MusicBrainz when the local guess is weak. */
    public boolean useMusicBrainz = true;

    /** Optional proxy used for all outgoing metadata requests (e.g. Tor: 127.0.0.1:9050). */
    public String socksProxyHost = "";
    public int socksProxyPort = 0;
    /** Proxy protocol: {@code none}, {@code socks} (Tor / SSH tunnel) or {@code http}. */
    public String proxyMode = "none";

    /** App identifiers (MPRIS player names or Windows AUMIDs) the scrobbler should ignore. */
    /**
     * Source/AUMID substrings that should never trigger a scrobble. Defaults
     * include "ugreen" because the UGREEN NAS desktop app exposes itself
     * via SMTC without track metadata after recent updates — anything
     * coming from there ends up as artist=title="UGREEN" garbage.
     */
    public Set<String> ignoredSources = new LinkedHashSet<>(List.of(
        "ugreen"
    ));

    /** Patterns whose match the scrobbler should treat as the artist when parsing titles. */
    public List<String> artistPatterns = new ArrayList<>(List.of(
        "^(?<artist>[^\\-\\u2013\\u2014]+?)\\s*[\\-\\u2013\\u2014]\\s*(?<title>.+)$",
        "^\\s*\\[(?<artist>[^\\]]+)\\]\\s*(?<title>.+)$",
        "^\\s*(?<artist>[^\\u2018\\u2019']+?):\\s+(?<title>.+)$"
    ));

    /** Channels/uploaders that should never be treated as the artist (used to override detection). */
    public Set<String> uploaderBlacklist = new LinkedHashSet<>(List.of(
        "topic", "vevo", "official", "records", "music", "channel"
    ));

    /** How many recent scrobble events to retain in the history view. */
    public int historyLimit = 200;

    public boolean enabled = true;

    /** Track changes within this many seconds count as a skip rather than a play. */
    public int skipThresholdSeconds = 5;

    /** When non-empty, force this MPRIS player / Windows AUMID. Empty = auto-pick. */
    public String preferredSource = "";

    /** Whether to fire a desktop notification on every successful scrobble. */
    public boolean notifyOnScrobble = false;

    /**
     * Port for the built-in HTTP webhook source. Set to 0 to disable.
     * When enabled, POST JSON to http://localhost:{port}/now-playing
     * to push metadata from any external tool (browser, NAS script, etc.).
     */
    public int webhookPort = 0;

    /** Whether the podcast detector is active. Detected items are logged locally instead of scrobbled. */
    public boolean podcastFilteringEnabled = true;

    /** Tracks longer than this are treated as podcasts/long-form content. 0 = no length cutoff. */
    public int podcastLengthCutoffSeconds = 900;

    /** Title or artist substrings that mark an item as a podcast. */
    public List<String> podcastTitleKeywords = new ArrayList<>(List.of(
        "podcast", "episode", "folge", "interview", "hörspiel", "horspiel", "audiobook", "hörbuch", "horbuch"
    ));

    /** Source-app substrings (lowercase MPRIS / AUMID) that mark a source as podcast-only. */
    public Set<String> podcastSourceMarkers = new LinkedHashSet<>(List.of(
        "podcast", "pocketcasts", "antennapod", "overcast", "spotify.podcast"
    ));

    /**
     * Regex patterns whose matches are stripped from the title before scrobbling.
     * Defaults strip producer credits in both German ("[prod. von X]") and
     * English ("(prod. by X)", "(PROD. BY X)") variants — so the same track
     * doesn't end up on Maloja under three different titles.
     */
    public List<String> titleCleanupPatterns = new ArrayList<>(List.of(
        "(?i)\\s*[\\[(]\\s*prod\\.?\\s*(?:by|von)\\s+[^\\])]+[\\])]",
        "(?i)\\s*[\\[(]\\s*produced\\s+by\\s+[^\\])]+[\\])]"
    ));

    /**
     * Artist-level classification rules. A PODCAST rule forces an artist to be
     * logged locally (never to Maloja) regardless of episode length. A MUSIC
     * rule exempts an artist from podcast detection even if their tracks are long
     * or match a keyword. Applied before all other heuristics.
     */
    public List<ArtistClassificationRule> artistClassificationRules = new ArrayList<>();

    /**
     * Per-track corrections the user pinned via "remember this edit". Each rule
     * matches an incoming (artist, title) pair (case- and whitespace-insensitive)
     * and overrides whichever fields the user changed in the dialog. Applied in
     * {@link de.janniv.lifeos.scrobbler.meta.MetadataEnhancer#enhance} before
     * MusicBrainz so the user's intent always wins.
     */
    public List<TrackRewriteRule> trackRewrites = new ArrayList<>();

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    public static ScrobblerSettings load(Path file) {
        try {
            if (Files.exists(file)) {
                return mapper().readValue(file.toFile(), ScrobblerSettings.class);
            }
        } catch (IOException e) {
            System.err.println("[Scrobbler] Could not read settings: " + e.getMessage());
        }
        return new ScrobblerSettings();
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            mapper().writeValue(file.toFile(), this);
        } catch (IOException e) {
            System.err.println("[Scrobbler] Could not save settings: " + e.getMessage());
        }
    }
}
