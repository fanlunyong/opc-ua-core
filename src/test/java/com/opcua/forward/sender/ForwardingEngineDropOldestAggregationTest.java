package com.opcua.forward.sender;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ForwardingEngineDropOldestAggregationTest {

    /** 容量 1 + 不消费 → 所有 enqueue 在容量已满时触发 drop-oldest */
    static class StallFactory implements SenderFactory {
        AbstractSender lastCreated;
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> conn) {
            AbstractSender s = new AbstractSender("stall-" + t.getTopic(), 1, 1) {
                @Override protected void doSend(OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
            // 不调用 start() — worker 不消费
            this.lastCreated = s;
            return s;
        }
    }

    @Test
    void aggregatesByDeviceId_acrossSenders() {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka"); t.setBootstrapServers("k:9092"); t.setTopic("only");
        t.setQueueCapacity(1);

        ForwardRule rule = new ForwardRule();
        rule.setName("r"); rule.setTargets(List.of(t));
        // passAll filter so Good data passes through
        rule.setQualityFilter(com.opcua.forward.config.QualityFilter.passAll());

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        StallFactory factory = new StallFactory();
        SenderRegistry registry = new SenderRegistry(factory);
        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        for (int i = 0; i < 30; i++) engine.onDataReceived(makeData("d1", Quality.Good));
        for (int i = 0; i < 20; i++) engine.onDataReceived(makeData("d2", Quality.Good));

        AbstractSender sender = factory.lastCreated;
        // 容量 1：首次 d1 占用 → 后 29 次 d1 触发 drop-oldest（29 drops）
        // 然后 d2 enqueue：占用容量 → 后 19 次 d2 触发 drop-oldest（19 drops）
        // 实际：取决于 d1 第一条入队后是否被 d2 第一条踢出
        // 我们仅检查总和正确：30 + 20 - 1（d1 第一条无 drop） = 49 总 drops
        long d1Drops = sender.getDropCount("d1");
        long d2Drops = sender.getDropCount("d2");
        long total = d1Drops + d2Drops;
        assertEquals(49, total, "total drops d1=" + d1Drops + " d2=" + d2Drops);

        registry.shutdown(Duration.ofMillis(50));
    }

    private static OpcUaDeviceData makeData(String deviceId, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "x"),
                List.of(p));
    }
}