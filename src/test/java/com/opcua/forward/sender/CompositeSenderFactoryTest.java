package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompositeSenderFactoryTest {

    @Test
    void dispatchesByType() {
        SenderFactory kafkaFactory = mock(SenderFactory.class);
        SenderFactory httpFactory = mock(SenderFactory.class);

        Map<String, SenderFactory> map = Map.of(
                "kafka", kafkaFactory,
                "http", httpFactory);
        CompositeSenderFactory comp = new CompositeSenderFactory(map);

        ForwardTarget kt = new ForwardTarget(); kt.setType("kafka"); kt.setBootstrapServers("k:9092");
        ForwardTarget ht = new ForwardTarget(); ht.setType("http"); ht.setUrl("http://a");

        comp.createConnection(ConnectionFingerprint.of(kt), kt);
        verify(kafkaFactory).createConnection(any(), eq(kt));
        verifyNoInteractions(httpFactory);

        comp.createConnection(ConnectionFingerprint.of(ht), ht);
        verify(httpFactory).createConnection(any(), eq(ht));
    }

    @Test
    void unknownType_throws() {
        CompositeSenderFactory comp = new CompositeSenderFactory(Map.of());
        ForwardTarget t = new ForwardTarget(); t.setType("kafka"); t.setBootstrapServers("k:9092");
        assertThrows(IllegalArgumentException.class,
                () -> comp.createConnection(ConnectionFingerprint.of(t), t));
    }
}