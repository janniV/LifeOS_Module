package de.janniv.lifeos.scrobbler;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PodcastDetectorTest {

    private static NowPlaying np(String artist, String title, String src, long durMs) {
        return new NowPlaying(artist, title, "", src, durMs, 0, NowPlaying.State.PLAYING, Instant.now());
    }

    @Test
    void flagsByLengthCutoff() {
        ScrobblerSettings s = new ScrobblerSettings();
        // 25 minutes — over the default 15 minute cutoff.
        assertTrue(PodcastDetector.isPodcast(np("Some Artist", "Episode 12", "music", 25 * 60_000L), s));
    }

    @Test
    void doesNotFlagShortTracks() {
        ScrobblerSettings s = new ScrobblerSettings();
        assertFalse(PodcastDetector.isPodcast(np("Daft Punk", "Around the World", "spotify", 4 * 60_000L), s));
    }

    @Test
    void flagsByKeyword() {
        ScrobblerSettings s = new ScrobblerSettings();
        assertTrue(PodcastDetector.isPodcast(
            np("Lage der Nation", "Folge 412", "browser", 60_000L), s));
    }

    @Test
    void flagsBySourceMarker() {
        ScrobblerSettings s = new ScrobblerSettings();
        assertTrue(PodcastDetector.isPodcast(
            np("Some Show", "Some Title", "podcastapp.client", 5 * 60_000L), s));
    }

    @Test
    void respectsDisabledFlag() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.podcastFilteringEnabled = false;
        assertFalse(PodcastDetector.isPodcast(
            np("Lange Sendung", "Episode 12", "podcast", 60 * 60_000L), s));
    }
}
