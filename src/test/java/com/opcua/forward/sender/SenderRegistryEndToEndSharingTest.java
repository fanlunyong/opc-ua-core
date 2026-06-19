package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SenderRegistryEndToEndSharingTest {

    static class CountingDelegate implements SenderFactory {
        final AtomicInteger conns = new AtomicInteger();
        @Override public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            conns.incrementAndGet();
            // stub 出底层连接（避免真实 Kafka/MQTT 连接）
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            return new AbstractSender("stub-" + t.getType() + "-" + t.getTopic(), 1, 1) {
                @Override protected void doSend(com.opcua.model.OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
        }
    }

    @Test
    void multipleTargets_sameKafkaBroker_shareSingleConnection() {
        CountingDelegate kafkaCounter = new CountingDelegate();
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", kafkaCounter);
        SenderRegistry registry = new SenderRegistry(new CompositeSenderFactory(map));

        ForwardTarget t1 = kafka("k:9092", "topic-A");
        ForwardTarget t2 = kafka("k:9092", "topic-B");
        ForwardTarget t3 = kafka("k:9092", "topic-C");

        Sender s1 = registry.getOrCreate(t1);
        Sender s2 = registry.getOrCreate(t2);
        Sender s3 = registry.getOrCreate(t3);

        assertNotSame(s1, s2);
        assertNotSame(s2, s3);
        assertEquals(1, kafkaCounter.conns.get(), "single shared connection across 3 targets");

        registry.shutdown(Duration.ofMillis(50));
    }

    @Test
    void differentBrokers_independentConnections() {
        CountingDelegate kafkaCounter = new CountingDelegate();
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", kafkaCounter);
        SenderRegistry registry = new SenderRegistry(new CompositeSenderFactory(map));

        registry.getOrCreate(kafka("k1:9092", "A"));
        registry.getOrCreate(kafka("k2:9092", "A"));

        assertEquals(2, kafkaCounter.conns.get());
        registry.shutdown(Duration.ofMillis(50));
    }

    private ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setSecurityProtocol("PLAINTEXT");
        t.setTopic(topic);
        return t;
    }
}