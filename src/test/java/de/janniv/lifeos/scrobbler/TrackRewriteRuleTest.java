package de.janniv.lifeos.scrobbler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackRewriteRuleTest {

    @Test
    void matchesCaseInsensitive() {
        TrackRewriteRule r = new TrackRewriteRule("KNEECAP", "Fine Art", "Kneecap", "Fine Art", "");
        assertTrue(r.matches("kneecap", "fine art"));
        assertTrue(r.matches("Kneecap", "Fine Art"));
        assertTrue(r.matches("  KneeCap  ", " Fine Art "));
    }

    @Test
    void doesNotMatchOnDifferentTitle() {
        TrackRewriteRule r = new TrackRewriteRule("Artist", "Song A", "Other Artist", "Song A", "");
        assertFalse(r.matches("Artist", "Song B"));
    }

    @Test
    void matchesAcrossNfcNormalisation() {
        // The artist field carries "ä" as a single codepoint in one rule and
        // as "a + combining diaeresis" in the observation. NFC normalisation
        // inside the matcher should collapse both forms to the same key.
        String composed = "Mädchen";                 // 'Mädchen' with U+00E4
        String decomposed = "Mädchen";              // 'Mädchen' decomposed
        TrackRewriteRule r = new TrackRewriteRule(composed, "Song", "X", "Y", "");
        assertTrue(r.matches(decomposed, "Song"));
    }

    @Test
    void matchKeyIsStable() {
        assertEquals(
            TrackRewriteRule.matchKey("Artist", "Title"),
            TrackRewriteRule.matchKey("ARTIST ", " title "));
    }
}
