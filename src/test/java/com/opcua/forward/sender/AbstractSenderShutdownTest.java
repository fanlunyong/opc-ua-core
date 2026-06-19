package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderShutdownTest {

    static class SlowSender extends AbstractSender {
        final AtomicInteger sent = new AtomicInteger();
        final long sleepMillis;
        SlowSender(long sleepMillis) {
            super("slow-1", 100, 1);
            this.sleepMillis = sleepMillis;
            start();
        }
        @Override protected void doSend(OpcUaDeviceData data) {
            try { Thread.sleep(sleepMillis); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            sent.incrementAndGet();
        }
        @Override protected void doClose() { }
    }

    @Test
    void shutdown_drainsBeforeTimeout() {
        SlowSender s = new SlowSender(10);
        for (int i = 0; i < 5; i++) s.enqueue(makeData("d" + i));
        s.shutdown(Duration.ofSeconds(2));
        assertEquals(5, s.sent.get());
    }

    @Test
    void shutdown_timeoutLeavesUnsentMessages() {
        SlowSender s = new SlowSender(500);
        for (int i = 0; i < 10; i++) s.enqueue(makeData("d" + i));
        s.shutdown(Duration.ofMillis(200));
        assertTrue(s.sent.get() < 10, "should not finish all under timeout");
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
