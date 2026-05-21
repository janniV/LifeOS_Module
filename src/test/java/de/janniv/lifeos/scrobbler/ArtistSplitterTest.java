package de.janniv.lifeos.scrobbler;

import de.janniv.lifeos.scrobbler.util.ArtistSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtistSplitterTest {

    @Test
    void splitsOnLowercaseX() {
        List<String> r = ArtistSplitter.split("DAHABFLEX x ERZIN");
        assertEquals(List.of("DAHABFLEX", "ERZIN"), r);
    }

    @Test
    void splitsOnUppercaseX() {
        List<String> r = ArtistSplitter.split("MGMT X The Strokes");
        assertEquals(List.of("MGMT", "The Strokes"), r);
    }

    @Test
    void splitsOnAmpersand() {
        List<String> r = ArtistSplitter.split("Sonny & Cher");
        assertEquals(List.of("Sonny", "Cher"), r);
    }

    @Test
    void splitsOnFeat() {
        List<String> r = ArtistSplitter.split("Calvin Harris feat. Rihanna");
        assertEquals(List.of("Calvin Harris", "Rihanna"), r);
    }

    @Test
    void doesNotSplitSlash() {
        List<String> r = ArtistSplitter.split("AC/DC");
        assertEquals(List.of("AC/DC"), r);
    }

    @Test
    void respectsUnsplittableNames() {
        List<String> r = ArtistSplitter.split("Above & Beyond");
        assertEquals(List.of("Above & Beyond"), r);
    }

    @Test
    void singleArtistStaysSingle() {
        List<String> r = ArtistSplitter.split("Daft Punk");
        assertEquals(List.of("Daft Punk"), r);
    }

    @Test
    void doesNotSplitMidWord() {
        // "Mxtreme" must not become "M" + "treme"
        List<String> r = ArtistSplitter.split("Mxtreme");
        assertEquals(List.of("Mxtreme"), r);
    }

    @Test
    void blankInputReturnsEmpty() {
        assertTrue(ArtistSplitter.split("").isEmpty());
        assertTrue(ArtistSplitter.split(null).isEmpty());
    }

    // --- Parenthetical feat. in artist field ------------------------------------

    @Test
    void splitsParentheticalFeat() {
        List<String> r = ArtistSplitter.split("Artist (feat. Guest)");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    @Test
    void splitsParentheticalFeatWithBracket() {
        List<String> r = ArtistSplitter.split("Artist [feat. Guest]");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    @Test
    void splitsParentheticalFeatNoPeriod() {
        List<String> r = ArtistSplitter.split("Artist (feat Guest)");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    @Test
    void splitsParentheticalFeaturing() {
        List<String> r = ArtistSplitter.split("Artist (Featuring Guest)");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    @Test
    void splitsParentheticalFt() {
        List<String> r = ArtistSplitter.split("Artist (ft. Guest)");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    @Test
    void splitsMultipleFeatInParentheses() {
        List<String> r = ArtistSplitter.split("Artist (feat. A & B)");
        assertEquals(List.of("Artist", "A", "B"), r);
    }

    // --- extractFeatFromTitle / stripFeatFromTitle ------------------------------

    @Test
    void extractsFeatFromTitle() {
        List<String> r = ArtistSplitter.extractFeatFromTitle("Song (feat. Guest)");
        assertEquals(List.of("Guest"), r);
    }

    @Test
    void extractsFeatWithMultipleArtists() {
        List<String> r = ArtistSplitter.extractFeatFromTitle("Song (feat. A & B)");
        assertEquals(List.of("A", "B"), r);
    }

    @Test
    void stripsFeatFromTitle() {
        assertEquals("Song", ArtistSplitter.stripFeatFromTitle("Song (feat. Guest)"));
        assertEquals("Song", ArtistSplitter.stripFeatFromTitle("Song (ft. Guest)"));
        assertEquals("Song [Remix]", ArtistSplitter.stripFeatFromTitle("Song (feat. Guest) [Remix]"));
    }

    @Test
    void noFeatReturnsEmptyList() {
        assertTrue(ArtistSplitter.extractFeatFromTitle("Plain Song").isEmpty());
        assertTrue(ArtistSplitter.extractFeatFromTitle(null).isEmpty());
    }

    // --- Featuring as inline separator (without parentheses) -------------------

    @Test
    void splitsOnFeaturing() {
        List<String> r = ArtistSplitter.split("Artist featuring Guest");
        assertEquals(List.of("Artist", "Guest"), r);
    }

    // --- Inline feat. (no brackets) in song titles ------------------------------

    @Test
    void extractsInlineFeatFromTitle() {
        assertEquals(List.of("Guest"), ArtistSplitter.extractFeatFromTitle("Song feat. Guest"));
        assertEquals(List.of("Guest"), ArtistSplitter.extractFeatFromTitle("Song ft. Guest"));
        assertEquals(List.of("Guest"), ArtistSplitter.extractFeatFromTitle("Song featuring Guest"));
    }

    @Test
    void extractsInlineFeatBeforeBracketSuffix() {
        // "Song feat. Guest (Remix)" — the inline match must stop at "(Remix)".
        assertEquals(List.of("Guest"), ArtistSplitter.extractFeatFromTitle("Song feat. Guest (Remix)"));
    }

    @Test
    void extractsInlineFeatMultiArtist() {
        assertEquals(List.of("A", "B"),
            ArtistSplitter.extractFeatFromTitle("Song feat. A & B"));
    }

    @Test
    void stripsInlineFeatFromTitle() {
        assertEquals("Song", ArtistSplitter.stripFeatFromTitle("Song feat. Guest"));
        assertEquals("Song", ArtistSplitter.stripFeatFromTitle("Song ft. Guest"));
        assertEquals("Song (Remix)", ArtistSplitter.stripFeatFromTitle("Song feat. Guest (Remix)"));
    }

    @Test
    void doesNotSliceWordsContainingFeat() {
        // No leading space before "feat" inside the word — pattern must not match.
        assertEquals(List.of(), ArtistSplitter.extractFeatFromTitle("Defeat the System"));
        assertEquals("Defeat the System", ArtistSplitter.stripFeatFromTitle("Defeat the System"));
    }
}
