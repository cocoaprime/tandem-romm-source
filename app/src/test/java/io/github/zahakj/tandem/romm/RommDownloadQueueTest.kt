package io.github.zahakj.tandem.romm

import org.junit.Assert.*
import org.junit.Test

class RommDownloadQueueTest {
    @Test fun allowsTwoActiveGamesAndStartsTheNextInOrder() {
        val queue = RommDownloadQueue<Long, String>(parallelism = 2, waitingLimit = 3)
        assertTrue(queue.enqueue(1, "first"))
        assertTrue(queue.enqueue(2, "second"))
        assertEquals("first", queue.startNext())
        assertEquals("second", queue.startNext())
        assertTrue(queue.enqueue(3, "third"))
        assertTrue(queue.enqueue(4, "fourth"))
        assertNull(queue.startNext())
        assertEquals(2, queue.runningCount)
        assertEquals(2, queue.waitingCount)

        queue.finish(2)
        assertEquals("third", queue.startNext())
        assertEquals("fourth", queue.removeWaiting(4))
        queue.finish(1)
        queue.finish(3)
        assertFalse(queue.hasWork)
    }

    @Test fun duplicateAndOverflowRequestsDoNotReplaceExistingTasks() {
        val queue = RommDownloadQueue<Long, String>(parallelism = 2, waitingLimit = 1)
        assertTrue(queue.enqueue(1, "original"))
        assertFalse(queue.enqueue(1, "duplicate"))
        assertFalse(queue.enqueue(2, "too many"))
        assertEquals("original", queue.startNext())
        assertFalse(queue.enqueue(1, "duplicate running"))
        assertTrue(queue.enqueue(2, "queued"))
        assertEquals(listOf("queued"), queue.drainWaiting())
        assertEquals(listOf("original"), queue.active.toList())
    }

    @Test fun aSecondTransferCannotReserveTheSameGameOrFileUntilTheFirstFinishes() {
        val queue = RommDownloadQueue<Long, String>(parallelism = 2, waitingLimit = 3)
        assertTrue(queue.enqueue(1, "first"))
        assertTrue(queue.enqueue(2, "second"))
        assertEquals("first", queue.startNext())
        assertEquals("second", queue.startNext())
        assertTrue(queue.reserve(1, "game:ps2/fixture.iso", "path:/internal/fixture.iso"))
        assertFalse(queue.reserve(2, "game:ps2/fixture.iso", "path:/sd/fixture.iso"))
        assertFalse(queue.reserve(2, "game:custom/fixture.iso", "path:/internal/fixture.iso"))
        queue.finish(1)
        assertTrue(queue.reserve(2, "game:ps2/fixture.iso", "path:/internal/fixture.iso"))
    }
}
