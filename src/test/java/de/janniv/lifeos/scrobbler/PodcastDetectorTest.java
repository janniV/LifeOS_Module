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

    @Test
    void musicOverrideBeatsLongDuration() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.artistClassificationRules = new java.util.ArrayList<>();
        s.artistClassificationRules.add(new ArtistClassificationRule("Long Song Artist", ArtistClassificationRule.Type.MUSIC));
        assertFalse(PodcastDetector.isPodcast(
            np("Long Song Artist", "Epic Ballad", "music", 20 * 60_000L), s));
    }

    @Test
    void podcastArtistRuleCatchesShortEpisodes() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.artistClassificationRules = new java.util.ArrayList<>();
        s.artistClassificationRules.add(new ArtistClassificationRule("Lage der Nation", ArtistClassificationRule.Type.PODCAST));
        // 5 minutes — below the 15-minute duration cutoff, but rule forces podcast
        assertTrue(PodcastDetector.isPodcast(
            np("Lage der Nation", "Schnellfolge 5", "music", 5 * 60_000L), s));
    }

    @Test
    void isLongFormByDuration_trueAboveCutoff() {
        ScrobblerSettings s = new ScrobblerSettings();
        assertTrue(PodcastDetector.isLongFormByDuration(np("A", "B", "src", 16 * 60_000L), s));
    }

    @Test
    void isLongFormByDuration_falseAtCutoffEdge() {
        ScrobblerSettings s = new ScrobblerSettings();
        // Default cutoff is 900 s; 14 minutes is below that.
        assertFalse(PodcastDetector.isLongFormByDuration(np("A", "B", "src", 14 * 60_000L), s));
    }

    @Test
    void isLongFormPodcast_trueForLongTrack() {
        ScrobblerSettings s = new ScrobblerSettings();
        assertTrue(PodcastDetector.isLongFormPodcast(np("A", "B", "src", 20 * 60_000L), s));
    }

    @Test
    void isLongFormPodcast_falseWhenMusicOverride() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.artistClassificationRules = new java.util.ArrayList<>();
        s.artistClassificationRules.add(new ArtistClassificationRule("A", ArtistClassificationRule.Type.MUSIC));
        assertFalse(PodcastDetector.isLongFormPodcast(np("A", "B", "src", 20 * 60_000L), s));
    }

    @Test
    void musicOverrideMatchesSubstring() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.artistClassificationRules = new java.util.ArrayList<>();
        s.artistClassificationRules.add(new ArtistClassificationRule("Pink Floyd", ArtistClassificationRule.Type.MUSIC));
        assertTrue(PodcastDetector.isMusicOverride(np("Pink Floyd", "Echoes", "music", 30 * 60_000L), s));
    }
}
