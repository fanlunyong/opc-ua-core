package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardingEngineShutdownIntegrationTest {

    /** 模拟慢 Sender — 每条 doSend 睡 200ms */
    static class SlowFactory implements SenderFactory {
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            AbstractSender s = new AbstractSender("slow", t.getQueueCapacity(), 1) {
                {
                    start();
                }
                @Override protected void doSend(OpcUaDeviceData data) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                @Override protected void doClose() { }
            };
            return s;
        }
    }

    @Test
    void shutdown_drainsBeforeTimeout() {
        ForwardTarget t = kafkaTarget(10);
        ForwardingEngine engine = engineFor(t, Duration.ofSeconds(3));

        // 入队 5 条，每条 200ms = 1s 总耗时（passAll 让 Good 数据全过）
        for (int i = 0; i < 5; i++) engine.onDataReceived(makeData(Quality.Good));

        long start = System.nanoTime();
        engine.shutdown();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 应该在 3s 之内完成排空（约 1s）
        assertTrue(elapsed < 2500, "shutdown took " + elapsed + "ms");
    }

    @Test
    void shutdown_timeoutDoesNotHang() {
        ForwardTarget t = kafkaTarget(50);
        ForwardingEngine engine = engineFor(t, Duration.ofMillis(300));

        // 入队 50 条，每条 200ms = 10s — 远超 300ms timeout
        for (int i = 0; i < 50; i++) engine.onDataReceived(makeData(Quality.Good));

        long start = System.nanoTime();
        engine.shutdown();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 必须在 1s 内强制返回（不阻塞 JVM）
        assertTrue(elapsed < 1000, "shutdown took " + elapsed + "ms");
    }

    private ForwardingEngine engineFor(ForwardTarget t, Duration timeout) {
        ForwardRule rule = new ForwardRule();
        rule.setName("r");
        rule.setTargets(List.of(t));

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(timeout);
        props.setRules(new ArrayList<>(List.of(rule)));

        SenderRegistry registry = new SenderRegistry(new SlowFactory());
        return new ForwardingEngine(mock(OpcUaService.class), registry, props);
    }

    private static ForwardTarget kafkaTarget(int capacity) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers("k:9092");
        t.setTopic("topic");
        t.setQueueCapacity(capacity);
        t.setWorkerThreads(1);
        return t;
    }

    private static OpcUaDeviceData makeData(Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(p));
    }
}