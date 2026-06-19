package com.opcua.forward.sender;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedConnectionTest {

    @Test
    void acquire_increments_release_decrements_lastReleaseClosesUnderlying() {
        AtomicInteger closed = new AtomicInteger();
        Object underlying = new Object();
        SharedConnection<Object> conn = new SharedConnection<>(underlying, () -> closed.incrementAndGet());

        conn.acquire();
        conn.acquire();
        assertEquals(2, conn.refCount());
        assertSame(underlying, conn.get());

        conn.release();
        assertEquals(1, conn.refCount());
        assertEquals(0, closed.get());

        conn.release();
        assertEquals(0, conn.refCount());
        assertEquals(1, closed.get());
    }

    @Test
    void release_belowZero_throws() {
        SharedConnection<String> conn = new SharedConnection<>("x", () -> { });
        assertThrows(IllegalStateException.class, conn::release);
    }

    @Test
    void closeException_doesNotMaskZeroRef() {
        AtomicInteger closed = new AtomicInteger();
        SharedConnection<String> conn = new SharedConnection<>("x", () -> {
            closed.incrementAndGet();
            throw new RuntimeException("close fail");
        });
        conn.acquire();
        assertDoesNotThrow(conn::release);
        assertEquals(0, conn.refCount());
        assertEquals(1, closed.get());
    }
}
