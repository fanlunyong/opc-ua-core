package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.config.QualityFilter;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardingEngineEndToEndTest {

    /** 记录所有 enqueue 的 sender — 模拟 4 类 sender */
    static class RecordingSender implements Sender {
        final String id;
        final ConcurrentLinkedQueue<OpcUaDeviceData> received = new ConcurrentLinkedQueue<>();
        RecordingSender(String id) { this.id = id; }
        @Override public String getSenderId() { return id; }
        @Override public void enqueue(OpcUaDeviceData data) { received.add(data); }
        @Override public void stopAccepting() { }
        @Override public int awaitDrain(Duration timeout) { return 0; }
        @Override public void shutdown(Duration timeout) { }
    }

    static class RecordingFactory implements SenderFactory {
        final AtomicLong c = new AtomicLong();
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            return new RecordingSender(t.getType() + "-" + c.incrementAndGet());
        }
    }

    @Test
    void endToEnd_goodData_routesOnlyToMainTargets() {
        ForwardTarget mainT = kafka("k:9092", "data-{productId}");
        ForwardTarget alertT = kafka("k:9092", "alert-{productId}");

        ForwardRule rule = new ForwardRule();
        rule.setName("e2e");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofMillis(200));
        props.setRules(new ArrayList<>(List.of(rule)));

        RecordingFactory factory = new RecordingFactory();
        SenderRegistry registry = new SenderRegistry(factory);

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData good = makeData("line-A", "d1", Quality.Good);
        engine.onDataReceived(good);

        // 主 sender 收到 1 条；alert sender 不应被触发（Good 数据无告警）
        Sender mainSender = registry.getOrCreate(mainT);
        assertEquals(1, ((RecordingSender) mainSender).received.size());

        registry.shutdown(Duration.ofMillis(100));
    }

    @Test
    void endToEnd_badData_routesToAlertChannel_mainSkippedDueToFilter() {
        // 默认 dropBadOnly：全 Bad 数据 → 主通道过滤为 null → 跳过；alert 通道仍发原始
        ForwardTarget mainT = kafka("k:9092", "data-{productId}");
        ForwardTarget alertT = kafka("k:9092", "alert-{productId}");

        ForwardRule rule = new ForwardRule();
        rule.setName("e2e");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofMillis(200));
        props.setRules(new ArrayList<>(List.of(rule)));

        SenderRegistry registry = new SenderRegistry(new RecordingFactory());
        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(makeData("line-A", "d1", Quality.Bad));

        // alert sender 收到原始（含 Bad）
        RecordingSender alertSender = (RecordingSender) registry.getOrCreate(alertT);
        assertEquals(1, alertSender.received.size());

        registry.shutdown(Duration.ofMillis(100));
    }

    @Test
    void endToEnd_passAllFilter_badData_routesToBothChannels() {
        // passAll filter：Bad 数据不被过滤 → 主通道也收到
        ForwardTarget mainT = kafka("k:9092", "data-{productId}");
        ForwardTarget alertT = kafka("k:9092", "alert-{productId}");

        ForwardRule rule = new ForwardRule();
        rule.setName("e2e");
        rule.setTargets(List.of(mainT));
        rule.setQualityFilter(QualityFilter.passAll());
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofMillis(200));
        props.setRules(new ArrayList<>(List.of(rule)));

        SenderRegistry registry = new SenderRegistry(new RecordingFactory());
        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(makeData("line-A", "d1", Quality.Bad));

        RecordingSender mainSender = (RecordingSender) registry.getOrCreate(mainT);
        RecordingSender alertSender = (RecordingSender) registry.getOrCreate(alertT);
        assertEquals(1, mainSender.received.size());
        assertEquals(1, alertSender.received.size());

        registry.shutdown(Duration.ofMillis(100));
    }

    static ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setSecurityProtocol("PLAINTEXT");
        t.setTopic(topic);
        return t;
    }

    static OpcUaDeviceData makeData(String pid, String did, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "opc.tcp://x"),
                List.of(p));
    }
}