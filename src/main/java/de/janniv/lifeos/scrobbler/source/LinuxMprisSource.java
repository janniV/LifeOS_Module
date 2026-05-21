package de.janniv.lifeos.scrobbler.source;

import de.janniv.lifeos.scrobbler.NowPlaying;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads the active MPRIS player on Linux via the {@code playerctl} CLI.
 *
 * <p>{@code playerctl} is a thin wrapper around the standard MPRIS D-Bus
 * interface. We deliberately call it as a subprocess rather than bundling a
 * D-Bus library: the binary is a single ~100 kB dependency that virtually
 * every desktop Linux distro packages, and shelling out keeps this module
 * free of native code.
 */
public final class LinuxMprisSource implements MediaSource {

    private final Set<String> ignoredPlayers;
    private final String preferredPlayer;

    public LinuxMprisSource(Set<String> ignoredPlayers, String preferredPlayer) {
        this.ignoredPlayers = ignoredPlayers == null ? Set.of() : ignoredPlayers;
        this.preferredPlayer = preferredPlayer == null ? "" : preferredPlayer.trim();
    }

    @Override
    public String describe() {
        return "MPRIS (playerctl)";
    }

    @Override
    public NowPlaying poll() {
        String player = pickActivePlayer();
        if (player == null) return null;

        String fmt = "{{xesam:artist}}{{xesam:title}}{{xesam:album}}"
                   + "{{status}}{{mpris:length}}{{position}}"
                   + "{{mpris:artUrl}}";
        String raw = run("playerctl", "-p", player, "metadata", "--format", fmt);
        if (raw == null || raw.isBlank()) return null;

        String[] parts = raw.split("", -1);
        if (parts.length < 6) return null;

        long durationMs = parseLongSafe(parts[4]) / 1000L;
        long positionMs = parseLongSafe(parts[5]) / 1000L;
        String coverUrl = parts.length >= 7 ? parts[6] : "";

        NowPlaying.State state = switch (parts[3].trim().toLowerCase()) {
            case "playing" -> NowPlaying.State.PLAYING;
            case "paused"  -> NowPlaying.State.PAUSED;
            case "stopped" -> NowPlaying.State.STOPPED;
            default -> NowPlaying.State.UNKNOWN;
        };

        return new NowPlaying(parts[0], parts[1], parts[2], player,
            durationMs, positionMs, state, Instant.now(), coverUrl);
    }

    /** Lists all currently registered MPRIS players (regardless of status). */
    public List<String> listPlayers() {
        List<String> result = new ArrayList<>();
        String list = run("playerctl", "-l");
        if (list == null) return result;
        for (String line : list.split("\\R")) {
            String n = line.trim();
            if (!n.isEmpty()) result.add(n);
        }
        return result;
    }

    private String pickActivePlayer() {
        if (!preferredPlayer.isEmpty()) {
            // Honour an explicit pick even if the player is paused/stopped right now.
            return preferredPlayer;
        }
        List<String> all = listPlayers();
        for (String n : all) {
            if (isIgnored(n)) continue;
            String status = run("playerctl", "-p", n, "status");
            if (status == null) continue;
            if (status.trim().equalsIgnoreCase("Playing")) return n;
        }
        for (String n : all) {
            if (!isIgnored(n)) return n;
        }
        return null;
    }

    private boolean isIgnored(String player) {
        for (String pat : ignoredPlayers) {
            if (pat == null) continue;
            String p = pat.trim().toLowerCase();
            if (p.isEmpty()) continue;
            if (player.toLowerCase().contains(p)) return true;
        }
        return false;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static String run(String... cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return out.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
