package com.yunx.app.data.download
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
class SharedDownloadSlotsTest {
    @Test fun mixedEnginesShareOneFifoBudgetAndCancelledWaitersDoNotBlock() {
        val slots = SharedDownloadSlots()
        assertTrue(slots.tryAcquire(1, 1)) // built-in
        assertFalse(slots.tryAcquire(2, 1)) // Gopeed
        assertFalse(slots.tryAcquire(3, 1))
        slots.release(2)
        slots.release(1)
        assertTrue(slots.tryAcquire(3, 1))
        assertEquals(1, slots.activeCount())
    }
    @Test fun changedLimitDoesNotKillActiveTasksOrStartTooMany() {
        val s = SharedDownloadSlots()
        assertTrue(s.tryAcquire(1,2)); assertTrue(s.tryAcquire(2,2))
        assertFalse(s.tryAcquire(3,1))
        s.release(1); assertFalse(s.tryAcquire(3,1))
        s.release(2); assertTrue(s.tryAcquire(3,1))
        assertTrue(s.tryAcquire(4,3)); assertTrue(s.tryAcquire(5,3))
        assertFalse(s.tryAcquire(6,3))
    }
    @Test fun concurrentAcquisitionIsAtomic() {
        val s = SharedDownloadSlots(); val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8); val go = CountDownLatch(1)
        try {
            val results = (1L..8L).map { id -> pool.submit<Boolean> { ready.countDown(); go.await(); s.tryAcquire(id,3) } }
            ready.await(); go.countDown()
            assertEquals(3, results.count { it.get() }); assertEquals(3,s.activeCount())
        } finally { pool.shutdownNow() }
    }
}
