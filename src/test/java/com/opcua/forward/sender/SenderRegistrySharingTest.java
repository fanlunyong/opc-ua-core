package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SenderRegistrySharingTest {

    static class StubFactory implements SenderFactory {
        final AtomicInteger connectionCreations = new AtomicInteger();

        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            connectionCreations.incrementAndGet();
            return new SharedConnection<>(new Object(), () -> { });
        }

        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> conn) {
            return new AbstractSender("stub-" + t.getType() + "-" + t.getTopic(), 8, 1) {
                @Override protected void doSend(com.opcua.model.OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
        }
    }

    @Test
    void sameFingerprint_sharesConnection_butSenderIsPerTarget() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        ForwardTarget t1 = kafka("kafka:9092", "topic-A");
        ForwardTarget t2 = kafka("kafka:9092", "topic-B");

        Sender s1 = reg.getOrCreate(t1);
        Sender s2 = reg.getOrCreate(t2);

        assertNotSame(s1, s2);
        assertEquals(1, f.connectionCreations.get());

        reg.shutdown(Duration.ofMillis(100));
    }

    @Test
    void differentFingerprint_independentConnections() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        reg.getOrCreate(kafka("kafka1:9092", "topic-A"));
        reg.getOrCreate(kafka("kafka2:9092", "topic-A"));

        assertEquals(2, f.connectionCreations.get());
        reg.shutdown(Duration.ofMillis(100));
    }

    @Test
    void shutdown_preventsFurtherGetOrCreate() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        reg.getOrCreate(kafka("k:9092", "A"));
        reg.shutdown(Duration.ofMillis(100));

        assertThrows(IllegalStateException.class,
                () -> reg.getOrCreate(kafka("k:9092", "C")));
    }

    @Test
    void getOrCreate_sameTargetReturnsSameSender() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        ForwardTarget t = kafka("k:9092", "topic-A");
        Sender s1 = reg.getOrCreate(t);
        Sender s2 = reg.getOrCreate(kafka("k:9092", "topic-A"));

        assertSame(s1, s2);
        reg.shutdown(Duration.ofMillis(100));
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
