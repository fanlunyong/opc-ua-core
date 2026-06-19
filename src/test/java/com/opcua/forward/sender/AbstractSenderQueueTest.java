package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderQueueTest {

    static class CountingSender extends AbstractSender {
        final AtomicInteger sent = new AtomicInteger();
        final CountDownLatch latch;
        CountingSender(int expected) {
            super("counting-1", 16, 1);
            this.latch = new CountDownLatch(expected);
            start();
        }
        @Override protected void doSend(OpcUaDeviceData data) {
            sent.incrementAndGet();
            latch.countDown();
        }
        @Override protected void doClose() { /* noop */ }
    }

    @Test
    void enqueue_isDrainedByWorker() throws Exception {
        CountingSender sender = new CountingSender(3);
        try {
            sender.enqueue(makeData("d1"));
            sender.enqueue(makeData("d2"));
            sender.enqueue(makeData("d3"));
            assertTrue(sender.latch.await(2, TimeUnit.SECONDS));
            assertEquals(3, sender.sent.get());
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void enqueue_afterStopAccepting_isDropped() throws Exception {
        CountingSender sender = new CountingSender(0);
        sender.stopAccepting();
        sender.enqueue(makeData("d1"));
        Thread.sleep(50);
        assertEquals(0, sender.sent.get());
        sender.shutdown(Duration.ofSeconds(1));
    }

    private OpcUaDeviceData makeData(String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
