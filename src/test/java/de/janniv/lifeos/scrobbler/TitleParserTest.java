package de.janniv.lifeos.scrobbler;

import de.janniv.lifeos.scrobbler.meta.TitleParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for the title-parser. The interesting cases are the ones
 * where the OS reports a YouTube channel as artist, and the title encodes the
 * actual artist as a {@code "Artist - Title"} prefix.
 */
class TitleParserTest {

    private static NowPlaying observed(String artist, String title) {
        return new NowPlaying(artist, title, "", "yt", 0, 0, NowPlaying.State.PLAYING, Instant.now());
    }

    @Test
    void rewritesArtistFromTitle_whenChannelLooksLikeUploader() {
        ScrobblerSettings s = new ScrobblerSettings();
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("Daft Punk - Topic", "Around the World"), s);
        // Title doesn't contain a dash here so we shouldn't rewrite — guard against
        // false positives on tracks that come pre-decomposed.
        assertTrue(rewritten.isEmpty());
    }

    @Test
    void rewritesWhenTitleEncodesArtistDash() {
        ScrobblerSettings s = new ScrobblerSettings();
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("MusicChannelXYZ", "Daft Punk - Around the World"), s);
        assertTrue(rewritten.isPresent());
        assertEquals("Daft Punk", rewritten.get().artist());
        assertEquals("Around the World", rewritten.get().title());
    }

    @Test
    void stripsOfficialVideoNoise() {
        ScrobblerSettings s = new ScrobblerSettings();
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("VEVO", "Tame Impala - The Less I Know The Better (Official Video)"), s);
        assertTrue(rewritten.isPresent());
        assertEquals("Tame Impala", rewritten.get().artist());
        assertEquals("The Less I Know The Better", rewritten.get().title());
    }

    @Test
    void leavesTrustedArtistAlone_whenTitleAgreesAfterStrip() {
        ScrobblerSettings s = new ScrobblerSettings();
        // The reported artist already matches the parsed one — title should still get cleaned.
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("Tame Impala", "Tame Impala - The Less I Know The Better"), s);
        assertTrue(rewritten.isPresent());
        assertEquals("Tame Impala", rewritten.get().artist());
        assertEquals("The Less I Know The Better", rewritten.get().title());
    }

    @Test
    void doesNothingWhenSmartParsingDisabled() {
        ScrobblerSettings s = new ScrobblerSettings();
        s.smartArtistParsing = false;
        assertTrue(TitleParser.rewrite(
            observed("MusicChannelXYZ", "Daft Punk - Around the World"), s).isEmpty());
    }

    @Test
    void handlesBracketPrefix() {
        ScrobblerSettings s = new ScrobblerSettings();
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("RandomChannel", "[Aphex Twin] Avril 14th"), s);
        assertTrue(rewritten.isPresent());
        assertEquals("Aphex Twin", rewritten.get().artist());
        assertEquals("Avril 14th", rewritten.get().title());
    }

    @Test
    void doesNotMisparseACDCSlash() {
        // A song where the artist genuinely contains a slash but no dash —
        // we should not invent a dash split.
        ScrobblerSettings s = new ScrobblerSettings();
        Optional<NowPlaying> rewritten = TitleParser.rewrite(
            observed("AC/DC", "Highway to Hell"), s);
        assertTrue(rewritten.isEmpty());
    }
}
