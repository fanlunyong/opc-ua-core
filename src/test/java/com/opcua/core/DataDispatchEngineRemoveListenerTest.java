package com.opcua.core;

import com.opcua.api.OpcUaDataListener;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataDispatchEngineRemoveListenerTest {

    @Test
    void removeListener_stopsReceivingNewDispatches() throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstHit = new CountDownLatch(1);
        OpcUaDataListener listener = data -> {
            count.incrementAndGet();
            firstHit.countDown();
        };

        DataDispatchEngine engine = new DataDispatchEngine(2, 16, List.of(listener));
        try {
            engine.dispatch(makeData("d1"));
            assertTrue(firstHit.await(1, TimeUnit.SECONDS));
            assertEquals(1, count.get());

            assertTrue(engine.removeListener(listener));

            engine.dispatch(makeData("d1"));
            Thread.sleep(100);
            assertEquals(1, count.get(), "removed listener must not receive further dispatches");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void removeListener_returnsFalseWhenNotRegistered() {
        DataDispatchEngine engine = new DataDispatchEngine(1, 8, Collections.emptyList());
        try {
            assertFalse(engine.removeListener(data -> { }));
        } finally {
            engine.shutdown();
        }
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
