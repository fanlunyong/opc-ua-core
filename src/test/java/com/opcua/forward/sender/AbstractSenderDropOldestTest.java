package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderDropOldestTest {

    /** 队列容量 1，且不启动 worker — 所有 enqueue 在容量已满时触发 drop-oldest */
    static class StallSender extends AbstractSender {
        StallSender() { super("stall-1", 1, 1); /* 不调用 start() */ }
        @Override protected void doSend(OpcUaDeviceData data) { }
        @Override protected void doClose() { }
    }

    @Test
    void dropOldest_countAggregatedByDevice() {
        StallSender s = new StallSender();
        try {
            for (int i = 0; i < 50; i++) s.enqueue(makeData("d1"));
            for (int i = 0; i < 30; i++) s.enqueue(makeData("d2"));
            // 队列容量 1：首次 enqueue 占用，后续每次 enqueue 触发一次 drop-oldest
            // d1: 49 次 drop（不计首次占用）
            // d2: 30 次 drop（首次 d2 enqueue 即满，触发 drop）
            assertEquals(49, s.getDropCount("d1"));
            assertEquals(30, s.getDropCount("d2"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
        }
    }

    @Test
    void flush_byCountThreshold_resetsCounters() {
        StallSender s = new StallSender();
        try {
            // 触发 100 次 drop（首次占用 + 100 次 drop = 101 次 enqueue）
            for (int i = 0; i < 101; i++) s.enqueue(makeData("d1"));
            // 第 100 次 drop 后，maybeFlush 命中 count threshold → flush 清零
            assertEquals(0, s.getDropCount("d1"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
        }
    }

    @Test
    void flush_byTimeInterval_resetsCounters() throws Exception {
        StallSender s = new StallSender();
        try {
            // 5 次 drop（< 100 阈值）
            for (int i = 0; i < 6; i++) s.enqueue(makeData("d1"));
            assertEquals(5, s.getDropCount("d1"));

            // 等待 1.05s 跨过 1s 间隔
            Thread.sleep(1050);
            // 下一次 enqueue 触发 time-based flush
            s.enqueue(makeData("d1"));
            assertEquals(0, s.getDropCount("d1"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
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
