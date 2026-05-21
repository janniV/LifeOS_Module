package de.janniv.lifeos.scrobbler;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class NowPlayingTest {

    @Test
    void emptyWhenArtistAndTitleAreBlank() {
        NowPlaying np = new NowPlaying("", "", "Album", "src", 0, 0, NowPlaying.State.UNKNOWN, Instant.now());
        assertTrue(np.isEmpty());
    }

    @Test
    void trackKeyIsCaseInsensitive() {
        NowPlaying a = new NowPlaying("Daft Punk", "Around the World", "Discovery",
            "src", 0, 0, NowPlaying.State.PLAYING, Instant.now());
        NowPlaying b = new NowPlaying("DAFT PUNK", "around the world", "discovery",
            "src", 0, 0, NowPlaying.State.PLAYING, Instant.now());
        assertEquals(a.trackKey(), b.trackKey());
    }

    @Test
    void withCoverPreservesEverythingElse() {
        NowPlaying a = new NowPlaying("Daft Punk", "One More Time", "", "src", 320000, 100,
            NowPlaying.State.PLAYING, Instant.now());
        NowPlaying b = a.withCover("file:///tmp/x.jpg");
        assertEquals("Daft Punk", b.artist());
        assertEquals("file:///tmp/x.jpg", b.coverUrl());
    }

    @Test
    void withCoverIgnoresEmpty() {
        NowPlaying a = new NowPlaying("X", "Y", "", "", 0, 0, NowPlaying.State.PLAYING, Instant.now())
            .withCover("file:///tmp/z.jpg");
        NowPlaying b = a.withCover("");
        assertEquals("file:///tmp/z.jpg", b.coverUrl());
    }
}
