package de.janniv.lifeos.scrobbler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RetryStoreTest {

    @Test
    void persistsAndReloadsAcrossInstances(@TempDir Path tmp) {
        Path file = tmp.resolve("queue.json");
        RetryStore store = new RetryStore(file);
        store.enqueue(new RetryStore.PendingScrobble(Instant.parse("2025-01-01T00:00:00Z"),
            "Daft Punk", "Around the World", "Discovery", 425000, "503"));
        assertEquals(1, store.size());

        RetryStore reloaded = new RetryStore(file);
        assertEquals(1, reloaded.size());
        RetryStore.PendingScrobble p = reloaded.snapshot().get(0);
        assertEquals("Daft Punk", p.artist);
        assertEquals("Around the World", p.title);
        assertEquals(425000, p.durationMs);
    }

    @Test
    void backoffPreventsImmediatePeek(@TempDir Path tmp) {
        RetryStore store = new RetryStore(tmp.resolve("q.json"));
        store.enqueue(new RetryStore.PendingScrobble(Instant.now(), "X", "Y", "", 0, "fail"));
        store.onFailure();
        // After a failure, peekDue must return null because backoff is active.
        assertNull(store.peekDue());
    }

    @Test
    void onSuccessRemovesHead(@TempDir Path tmp) {
        RetryStore store = new RetryStore(tmp.resolve("q.json"));
        store.enqueue(new RetryStore.PendingScrobble(Instant.now(), "A", "B", "", 0, ""));
        store.enqueue(new RetryStore.PendingScrobble(Instant.now(), "C", "D", "", 0, ""));
        assertEquals(2, store.size());
        store.onSuccess();
        assertEquals(1, store.size());
        assertEquals("C", store.snapshot().get(0).artist);
    }
}
